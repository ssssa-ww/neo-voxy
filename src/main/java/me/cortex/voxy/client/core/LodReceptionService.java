package me.cortex.voxy.client.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.cortex.voxy.client.network.ClientCongestionControl;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.IdRemapper;
import me.cortex.voxy.common.network.VoxyNetworkHandler;
import me.cortex.voxy.common.network.VoxyPacketPayload;
import me.cortex.voxy.common.world.SectionSerializer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side service for receiving and processing streamed LOD data.
 * <p>
 * Handles:
 * <ul>
 * <li>Receiving and reassembling chunked section data</li>
 * <li>Deserializing section data</li>
 * <li>Remapping server IDs to client IDs</li>
 * <li>Injecting received sections into {@link WorldEngine}</li>
 * <li>Triggering render updates via {@code markDirty()}</li>
 * </ul>
 */
public class LodReceptionService implements AutoCloseable {

    private final WorldEngine worldEngine;
    private final Mapper clientMapper;
    private final IdRemapper idRemapper = new IdRemapper();
    private final ClientCongestionControl congestionControl;

    // Chunk reassembly buffers (sectionId -> partial data)
    private final ConcurrentHashMap<Integer, ChunkReassemblyBuffer> reassemblyBuffers = new ConcurrentHashMap<>();

    // Processing thread
    private final ExecutorService processingExecutor;
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    // Stats
    private final AtomicInteger sectionsReceived = new AtomicInteger(0);
    private final AtomicInteger sectionsApplied = new AtomicInteger(0);

    public LodReceptionService(WorldEngine worldEngine, Mapper clientMapper) {
        this.worldEngine = worldEngine;
        this.clientMapper = clientMapper;
        this.congestionControl = new ClientCongestionControl(this::onRateUpdate);

        this.processingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VoxyLodReception");
            t.setDaemon(true);
            return t;
        });

        // Register client message handler
        VoxyNetworkHandler.setClientMessageHandler(this::handleServerMessage);

        Logger.info("LodReceptionService initialized");
    }

    /**
     * Handle messages from the server.
     */
    private void handleServerMessage(VoxyPacketPayload payload) {
        switch (payload.messageType()) {
            case VoxyPacketPayload.MSG_MAPPER_SYNC -> handleMapperSync(payload);
            case VoxyPacketPayload.MSG_LOD_SECTION -> handleSection(payload);
            case VoxyPacketPayload.MSG_LOD_CHUNK -> handleChunk(payload);
            case VoxyPacketPayload.MSG_SYNC_COMPLETE -> handleSyncComplete(payload);
            case VoxyPacketPayload.MSG_CACHE_QUERY -> handleCacheQuery(payload);
        }

        // Update congestion control
        congestionControl.onChunkReceived(payload);
    }

    /**
     * Handle mapper sync from server.
     */
    private void handleMapperSync(VoxyPacketPayload payload) {
        Logger.info("Received mapper sync from server (" + payload.data().length + " bytes)");
        idRemapper.buildFromServerData(payload.data(), clientMapper);
    }

    /**
     * Handle complete section data.
     */
    private void handleSection(VoxyPacketPayload payload) {
        sectionsReceived.incrementAndGet();
        processingExecutor.submit(() -> processSection(payload.data()));
    }

    /**
     * Handle chunk of a large section.
     */
    private void handleChunk(VoxyPacketPayload payload) {
        byte[] data = payload.data();
        if (data.length < 9) {
            Logger.warn("Received chunk with insufficient header");
            return;
        }

        // Parse chunk header: [sectionId:4][offset:4][isLast:1][data:N]
        int sectionId = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) |
                ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int offset = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16) |
                ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        boolean isLast = data[8] != 0;

        // Get or create reassembly buffer
        ChunkReassemblyBuffer buffer = reassemblyBuffers.computeIfAbsent(
                sectionId,
                id -> new ChunkReassemblyBuffer());

        // Add chunk data
        buffer.addChunk(offset, data, 9, data.length - 9);

        if (isLast) {
            // Complete! Process the section
            reassemblyBuffers.remove(sectionId);
            sectionsReceived.incrementAndGet();

            byte[] completeData = buffer.assemble();
            if (completeData != null) {
                processingExecutor.submit(() -> processSection(completeData));
            }
        }
    }

    /**
     * Handle sync complete signal.
     */
    private void handleSyncComplete(VoxyPacketPayload payload) {
        Logger.info("LOD sync complete! Received: " + sectionsReceived.get() +
                ", Applied: " + sectionsApplied.get());
    }

    /**
     * Handle cache query from server.
     */
    private void handleCacheQuery(VoxyPacketPayload payload) {
        // TODO: Implement bloom filter cache response
    }

    /**
     * Process received section data.
     */
    private void processSection(byte[] data) {
        if (!isActive.get())
            return;

        try {
            SectionSerializer.SectionData sectionData = SectionSerializer.deserialize(data);
            if (sectionData == null) {
                Logger.warn("Failed to deserialize section data");
                return;
            }

            // Get or create section in world engine
            long key = sectionData.getKey();
            WorldSection section = worldEngine.acquire(key);

            if (section == null) {
                Logger.warn("Failed to acquire section for key: " + key);
                return;
            }

            try {
                // Apply data to section
                if (sectionData.hasData() && idRemapper.isReady()) {
                    applyVoxelData(section, sectionData.voxelData);
                }

                // Update non-empty children
                section._unsafeSetNonEmptyChildren(sectionData.nonEmptyChildren);

                // Mark dirty to trigger rendering - must use worldEngine.markDirty()
                // to trigger the dirty callback that notifies the render system
                worldEngine.markDirty(section);

                sectionsApplied.incrementAndGet();

            } finally {
                section.release();
            }

        } catch (Exception e) {
            Logger.error("Error processing section: " + e.getMessage());
            Logger.error(e);
        }
    }

    /**
     * Apply voxel data to a section, remapping IDs.
     */
    private void applyVoxelData(WorldSection section, long[] voxelData) {
        long[] dataArray = section._unsafeGetRawDataArray();
        if (dataArray == null) {
            Logger.warn("Section has no data array");
            return;
        }

        int count = Math.min(voxelData.length, dataArray.length);
        for (int i = 0; i < count; i++) {
            long serverVoxel = voxelData[i];
            long clientVoxel = idRemapper.remapVoxelId(serverVoxel);
            dataArray[i] = clientVoxel;
        }
    }

    /**
     * Called when congestion control adjusts rate.
     */
    private void onRateUpdate() {
        // Optionally send rate update to server
        // congestionControl.sendRateUpdate();
    }

    /**
     * Request LOD sync from server.
     */
    public void requestSync() {
        if (!VoxyNetworkHandler.shouldEnableStreaming()) {
            Logger.info("LOD streaming disabled in single-player");
            return;
        }

        Logger.info("Requesting LOD sync from server...");
        VoxyNetworkHandler.sendToServer(VoxyPacketPayload.syncRequest());
    }

    /**
     * Get reception stats.
     */
    public String getStats() {
        return String.format("Received: %d, Applied: %d, Pending reassembly: %d",
                sectionsReceived.get(), sectionsApplied.get(), reassemblyBuffers.size());
    }

    @Override
    public void close() {
        isActive.set(false);
        processingExecutor.shutdown();
        reassemblyBuffers.clear();
        idRemapper.reset();
        Logger.info("LodReceptionService closed");
    }

    /**
     * Buffer for reassembling chunked section data.
     */
    private static class ChunkReassemblyBuffer {
        private final ConcurrentHashMap<Integer, byte[]> chunks = new ConcurrentHashMap<>();
        private int totalSize = 0;

        void addChunk(int offset, byte[] data, int srcOffset, int length) {
            byte[] chunk = new byte[length];
            System.arraycopy(data, srcOffset, chunk, 0, length);
            chunks.put(offset, chunk);
            totalSize = Math.max(totalSize, offset + length);
        }

        byte[] assemble() {
            if (chunks.isEmpty())
                return null;

            byte[] result = new byte[totalSize];
            for (var entry : chunks.entrySet()) {
                System.arraycopy(entry.getValue(), 0, result, entry.getKey(), entry.getValue().length);
            }
            return result;
        }
    }
}
