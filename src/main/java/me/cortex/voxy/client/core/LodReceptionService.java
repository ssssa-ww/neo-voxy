package me.cortex.voxy.client.core;

import me.cortex.voxy.client.network.ClientCongestionControl;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.BloomFilter;
import me.cortex.voxy.common.network.IdRemapper;
import me.cortex.voxy.common.network.VoxyNetworkHandler;
import me.cortex.voxy.common.network.VoxyPacketPayload;
import me.cortex.voxy.common.world.SectionSerializer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;

/**
 * Client-side service for receiving and processing streamed LOD data.
 * <p>
 * Implements server-driven streaming architecture with client hints:
 * <ul>
 * <li>Receives LOD sections pushed by the server</li>
 * <li>Responds to cache queries with bloom filter (to skip sections client
 * has)</li>
 * <li>Deserializes section data and remaps server IDs to client IDs</li>
 * <li>Injects received sections into {@link WorldEngine}</li>
 * <li>Triggers render updates via {@code markDirty()}</li>
 * </ul>
 */
public class LodReceptionService implements AutoCloseable {

    // ==================== Core Fields ==================== //

    private final WorldEngine worldEngine;
    private final Mapper clientMapper;
    private final IdRemapper idRemapper = new IdRemapper();
    private final ClientCongestionControl congestionControl;
    private final me.cortex.voxy.client.core.model.ModelBakerySubsystem modelBakery;

    // Chunk reassembly buffers (sectionId -> partial data)
    private final ConcurrentHashMap<Integer, ChunkReassemblyBuffer> reassemblyBuffers = new ConcurrentHashMap<>();

    // Processing thread
    private final ExecutorService processingExecutor;
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    // Stats
    private final AtomicInteger sectionsReceived = new AtomicInteger(0);
    private final AtomicInteger sectionsApplied = new AtomicInteger(0);

    // ==================== Tracking State ==================== //

    /** Sections that have been received from the server */
    private final Set<Long> receivedSections = ConcurrentHashMap.newKeySet();

    /** Sections pending processing because models aren't ready yet */
    private final ConcurrentHashMap<Long, byte[]> pendingSections = new ConcurrentHashMap<>();

    // Prepared section class for offloaded remapping and deserialization
    private static class PreparedSection {
        final long key;
        final long[] remappedVoxelData;
        final byte nonEmptyChildren;

        PreparedSection(long key, long[] remappedVoxelData, byte nonEmptyChildren) {
            this.key = key;
            this.remappedVoxelData = remappedVoxelData;
            this.nonEmptyChildren = nonEmptyChildren;
        }
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<PreparedSection> preparedSections = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final ExecutorService ingestionExecutor;
    private final java.util.concurrent.atomic.AtomicBoolean ingestionScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Whether the mapper has been synced (required for processing) */
    private volatile boolean mapperReady = false;

    /** Whether we've already requested sync */
    private volatile boolean syncRequested = false;

    /** Whether local cache preloading is completed */
    private final AtomicBoolean preloadingCompleted = new AtomicBoolean(false);

    // Progress bar tracking
    private volatile int totalSections = 0;
    private volatile int matchedSections = 0;
    private volatile int serverSentSections = 0;
    private volatile boolean syncInProgress = false;
    private int completeAnimationTicks = 0;
    private boolean hasShownSyncProgress = false;

    public LodReceptionService(WorldEngine worldEngine, Mapper clientMapper,
            me.cortex.voxy.client.core.model.ModelBakerySubsystem modelBakery) {
        this.worldEngine = worldEngine;
        this.clientMapper = clientMapper;
        this.modelBakery = modelBakery;
        this.congestionControl = new ClientCongestionControl(this::onRateUpdate);

        int coreCount = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.processingExecutor = Executors.newFixedThreadPool(coreCount, r -> {
            Thread t = new Thread(r, "VoxyLodReception");
            t.setDaemon(true);
            return t;
        });

        this.ingestionExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VoxyLodIngestion");
            t.setDaemon(true);
            return t;
        });

        // Pre-populate receivedSections from disk database asynchronously to prevent re-transfer on rejoin
        this.processingExecutor.submit(() -> {
            try {
                this.worldEngine.storage.iterateStoredSectionPositions(this.receivedSections::add);
                Logger.info("Pre-loaded " + this.receivedSections.size() + " cached section positions from local storage");
            } catch (Exception e) {
                Logger.error("Failed to load cached section positions: " + e.getMessage());
            } finally {
                this.preloadingCompleted.set(true);
            }
        });

        // Register client message handler
        VoxyNetworkHandler.setClientMessageHandler(this::handleServerMessage);

        Logger.info("LodReceptionService initialized for server-driven streaming");
    }

    /**
     * Called every client tick to ensure sync is requested.
     * Should be called from the client tick event.
     */
    public void tick() {
        if (!isActive.get()) {
            return;
        }

        if (completeAnimationTicks > 0) {
            completeAnimationTicks--;
        }

        if (!VoxyNetworkHandler.shouldEnableStreaming()) {
            return;
        }

        // Request sync from server if not done yet, but wait until local cache preloading is complete
        if (!syncRequested && preloadingCompleted.get()) {
            syncRequested = true;
            syncInProgress = true;
            hasShownSyncProgress = false;
            
            // Build bloom filter of sections we already have
            BloomFilter filter = BloomFilter.forExpectedElements(Math.max(100, receivedSections.size()));
            for (Long key : receivedSections) {
                filter.add(key);
            }
            
            Logger.info("Requesting LOD sync for server-driven streaming with " + receivedSections.size() + " cached sections");
            VoxyNetworkHandler.sendToServer(VoxyPacketPayload.syncRequest(filter));
        }

        // Process pending sections whose models are now available
        if (!pendingSections.isEmpty()) {
            processPendingSections();
        }
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
            case VoxyPacketPayload.MSG_SYNC_PROGRESS -> handleSyncProgress(payload);
            case VoxyPacketPayload.MSG_LOD_BATCH -> handleBatch(payload);
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
        mapperReady = true;
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

            byte[] completeData = buffer.assemble();
            if (completeData != null && completeData.length > 0) {
                byte type = completeData[0];
                byte[] actualData = new byte[completeData.length - 1];
                System.arraycopy(completeData, 1, actualData, 0, actualData.length);

                if (type == 1) {
                    processingExecutor.submit(() -> handleBatchData(actualData));
                } else {
                    sectionsReceived.incrementAndGet();
                    processingExecutor.submit(() -> processSection(actualData));
                }
            }
        }
    }

    /**
     * Handle batched sections directly.
     */
    private void handleBatch(VoxyPacketPayload payload) {
        processingExecutor.submit(() -> handleBatchData(payload.data()));
    }

    /**
     * Parse and process batched column sections data.
     */
    private void handleBatchData(byte[] batchData) {
        try {
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(batchData));
            int sectionCount = in.readByte() & 0xFF;

            // Increment the counter of received sections by the batch size
            sectionsReceived.addAndGet(sectionCount);

            for (int i = 0; i < sectionCount; i++) {
                long key = in.readLong(); // Read key
                int length = in.readInt();
                byte[] sectionData = new byte[length];
                in.readFully(sectionData);

                // Submit to multi-threaded executor
                processingExecutor.submit(() -> processSection(sectionData));
            }
        } catch (java.io.IOException e) {
            Logger.error("Failed to parse LOD batch data: " + e.getMessage());
        }
    }

    private void handleSyncComplete(VoxyPacketPayload payload) {
        Logger.info("LOD sync complete! Received: " + sectionsReceived.get() +
                ", Applied: " + sectionsApplied.get());
        syncInProgress = false;
        completeAnimationTicks = 60; // Show "Sync Complete" status for 3 seconds (60 ticks)
    }

    /**
     * Handle sync progress update from server.
     */
    private void handleSyncProgress(VoxyPacketPayload payload) {
        int[] stats = payload.parseSyncProgress();
        this.totalSections = stats[0];
        this.matchedSections = stats[1];
        this.serverSentSections = stats[2];
        this.syncInProgress = true;
    }

    /**
     * Handle cache query from server.
     */
    private void handleCacheQuery(VoxyPacketPayload payload) {
        // Build bloom filter of sections we have
        BloomFilter filter = BloomFilter.forExpectedElements(
                Math.max(100, receivedSections.size()));

        for (Long key : receivedSections) {
            filter.add(key);
        }

        // Send response
        VoxyNetworkHandler.sendToServer(VoxyPacketPayload.cacheResponse(filter));
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

            long key = sectionData.getKey();

            // Check if all required models for this section are available
            if (sectionData.hasData() && !areModelsAvailable(sectionData.voxelData)) {
                // Models not ready yet, queue for later processing
                pendingSections.put(key, data);
                return;
            }

            // Mark as received
            receivedSections.add(key);

            if (sectionData.hasData() && idRemapper.isReady()) {
                // Perform the heavy block ID remapping on the background thread!
                long[] remapped = new long[sectionData.voxelData.length];
                for (int i = 0; i < remapped.length; i++) {
                    remapped[i] = idRemapper.remapVoxelId(sectionData.voxelData[i]);
                }
                preparedSections.offer(new PreparedSection(key, remapped, sectionData.nonEmptyChildren));
            } else {
                preparedSections.offer(new PreparedSection(key, null, sectionData.nonEmptyChildren));
            }

            // Trigger single-threaded ingestion
            triggerIngestion();

        } catch (Exception e) {
            Logger.error("Error processing section: " + e.getMessage());
            Logger.error(e);
        }
    }

    private void triggerIngestion() {
        if (ingestionScheduled.compareAndSet(false, true)) {
            ingestionExecutor.submit(this::runIngestion);
        }
    }

    private void runIngestion() {
        try {
            PreparedSection prepared;
            while ((prepared = preparedSections.poll()) != null) {
                applyPreparedSection(prepared);
            }
        } finally {
            ingestionScheduled.set(false);
            if (!preparedSections.isEmpty()) {
                triggerIngestion();
            }
        }
    }

    private void applyPreparedSection(PreparedSection prepared) {
        try {
            WorldSection section = worldEngine.acquire(prepared.key);
            if (section == null) {
                return;
            }
            try {
                if (prepared.remappedVoxelData != null) {
                    long[] dataArray = section._unsafeGetRawDataArray();
                    if (dataArray != null) {
                        int count = Math.min(prepared.remappedVoxelData.length, dataArray.length);
                        System.arraycopy(prepared.remappedVoxelData, 0, dataArray, 0, count);
                    }
                }
                section._unsafeSetNonEmptyChildren(prepared.nonEmptyChildren);
                worldEngine.markDirty(section, WorldEngine.DEFAULT_UPDATE_FLAGS, 0x3F);
                sectionsApplied.incrementAndGet();
            } finally {
                section.release();
            }
        } catch (Exception e) {
            Logger.error("Error ingesting prepared section: " + e.getMessage());
        }
    }

    /**
     * Checks if all models referenced in the voxel data are available in the model
     * bakery.
     *
     * @param voxelData The voxel data array.
     * @return True if all models are available, false otherwise.
     */
    private boolean areModelsAvailable(long[] voxelData) {
        if (!idRemapper.isReady()) {
            return true; // Cannot check model availability without a remapper
        }
        // Sample voxel data to check if models are ready
        // Only check a small sample to avoid performance issues
        it.unimi.dsi.fastutil.ints.IntOpenHashSet checkedBlocks = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

        boolean allAvailable = true;

        // Sample every 64th voxel to keep it fast
        int step = Math.max(1, voxelData.length / 64);
        for (int i = 0; i < voxelData.length; i += step) {
            long serverVoxel = voxelData[i];
            long clientVoxel = idRemapper.remapVoxelId(serverVoxel);
            int clientBlockId = me.cortex.voxy.common.world.other.Mapper.getBlockId(clientVoxel);
            if (clientBlockId != 0 && checkedBlocks.add(clientBlockId)) {
                if (!modelBakery.factory.hasModelForBlockId(clientBlockId)) {
                    // Request the model to be baked
                    modelBakery.requestBlockBake(clientBlockId);
                    allAvailable = false;
                }
            }
            // Limit checking to first 16 unique blocks to keep it fast
            if (checkedBlocks.size() >= 16) {
                break;
            }
        }
        return allAvailable;
    }

    /**
     * Processes sections that were previously queued because their models were not
     * ready.
     */
    private void processPendingSections() {
        int checked = 0;
        int maxChecksPerTick;
        int speed = dev.xantha.vss.config.VSSClientConfig.CONFIG.lodPropagationSpeed;
        if (speed == 1) {
            maxChecksPerTick = 12;
        } else if (speed == 2) {
            maxChecksPerTick = 48;
        } else if (speed == 3) {
            maxChecksPerTick = 144;
        } else if (speed == 4) {
            maxChecksPerTick = 384;
        } else if (speed == 5) {
            maxChecksPerTick = 768;
        } else {
            maxChecksPerTick = 3072;
        }
        
        java.util.List<java.util.Map.Entry<Long, byte[]>> entries = new java.util.ArrayList<>(pendingSections.entrySet());
        var player = Minecraft.getInstance().player;
        final int playerChunkX = player != null ? player.getBlockX() >> 5 : 0;
        final int playerChunkZ = player != null ? player.getBlockZ() >> 5 : 0;
        
        entries.sort((a, b) -> {
            long keyA = a.getKey();
            long keyB = b.getKey();
            int lvlA = WorldEngine.getLevel(keyA);
            int lvlB = WorldEngine.getLevel(keyB);
            if (lvlA != lvlB) {
                return Integer.compare(lvlB, lvlA); // Higher level (coarser) first
            }
            // Same level, closer to player first
            int xA = WorldEngine.getX(keyA) << lvlA;
            int zA = WorldEngine.getZ(keyA) << lvlA;
            int xB = WorldEngine.getX(keyB) << lvlB;
            int zB = WorldEngine.getZ(keyB) << lvlB;
            
            double distA = Math.pow(xA - playerChunkX, 2) + Math.pow(zA - playerChunkZ, 2);
            double distB = Math.pow(xB - playerChunkX, 2) + Math.pow(zB - playerChunkZ, 2);
            return Double.compare(distA, distB);
        });

        for (var entry : entries) {
            if (checked >= maxChecksPerTick) {
                break;
            }
            long key = entry.getKey();
            byte[] data = entry.getValue();
            if (data != null) {
                checked++;
                try {
                    SectionSerializer.SectionData sectionData = SectionSerializer.deserialize(data);
                    if (sectionData != null && areModelsAvailable(sectionData.voxelData)) {
                        pendingSections.remove(key);
                        // Submit to processing executor to maintain consistent processing flow
                        processingExecutor.submit(() -> processSection(data));
                    }
                } catch (Exception e) {
                    Logger.error(
                            "Error re-processing pending section " + Long.toHexString(key) + ": " + e.getMessage());
                    Logger.error(e);
                    pendingSections.remove(key); // Remove to avoid infinite retries on error
                }
            }
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
        congestionControl.sendRateUpdate();
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
        BloomFilter filter = BloomFilter.forExpectedElements(Math.max(100, receivedSections.size()));
        for (Long key : receivedSections) {
            filter.add(key);
        }
        VoxyNetworkHandler.sendToServer(VoxyPacketPayload.syncRequest(filter));
    }

    /**
     * Get reception stats.
     */
    public String getStats() {
        return String.format("Received: %d, Applied: %d, Cached: %d",
                sectionsReceived.get(), sectionsApplied.get(),
                receivedSections.size());
    }

    @Override
    public void close() {
        isActive.set(false);
        processingExecutor.shutdown();
        ingestionExecutor.shutdown();
        reassemblyBuffers.clear();
        receivedSections.clear();
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
