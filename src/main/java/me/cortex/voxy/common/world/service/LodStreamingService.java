package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.*;
import me.cortex.voxy.common.world.SectionSerializer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-side service that manages streaming LOD sections to connected players.
 * <p>
 * Orchestrates the streaming process:
 * <ul>
 * <li>Tracks connected players and their streaming state</li>
 * <li>Progressively streams sections in distance-based rings</li>
 * <li>Uses {@link ChunkedLodSender} for bandwidth-limited transfer</li>
 * <li>Sends mapper data on connect for ID remapping</li>
 * </ul>
 */
public class LodStreamingService implements AutoCloseable {

    private final WorldEngine worldEngine;
    private final SharedBandwidthLimit sharedBandwidthLimit;
    private final ConcurrentHashMap<UUID, PlayerStreamingState> playerStates = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    // Config
    private final int perPlayerLimitKBps;
    private final int maxStreamingRadius;

    /**
     * Create streaming service with default settings.
     */
    public LodStreamingService(WorldEngine worldEngine) {
        this(worldEngine, new SharedBandwidthLimit(),
                SharedBandwidthLimit.DEFAULT_PLAYER_LIMIT_KBPS, 32);
    }

    /**
     * Create streaming service with custom settings.
     */
    public LodStreamingService(WorldEngine worldEngine, SharedBandwidthLimit sharedBandwidthLimit,
            int perPlayerLimitKBps, int maxStreamingRadius) {
        this.worldEngine = worldEngine;
        this.sharedBandwidthLimit = sharedBandwidthLimit;
        this.perPlayerLimitKBps = perPlayerLimitKBps;
        this.maxStreamingRadius = maxStreamingRadius;

        // Keep the world engine alive while streaming service exists
        this.worldEngine.acquireRef();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VoxyLodStreaming");
            t.setDaemon(true);
            return t;
        });

        // Note: VoxyServer owns the message handler registration
        // and will call startSyncForPlayer() when it receives sync requests

        Logger.info("LodStreamingService initialized");
    }

    /**
     * Handle rate update from client.
     */
    public void handleRateUpdate(ServerPlayer player, VoxyPacketPayload payload) {
        int desiredRate = payload.parseRate();
        PlayerStreamingState state = playerStates.get(player.getUUID());
        if (state != null) {
            state.clientDesiredRate = desiredRate;
        }
    }

    /**
     * Start LOD sync for a player. Called by VoxyServer when it receives a sync
     * request.
     */
    public void startSyncForPlayer(ServerPlayer player) {
        Logger.info("Received sync request from " + player.getName().getString());

        PlayerStreamingState state = playerStates.computeIfAbsent(
                player.getUUID(),
                uuid -> new PlayerStreamingState(player, sharedBandwidthLimit, perPlayerLimitKBps));

        // Send mapper data first
        sendMapperSync(player);

        // Start streaming on scheduler thread
        scheduler.submit(() -> startStreaming(state));
    }

    /**
     * Send the mapper sync packet to a player.
     */
    private void sendMapperSync(ServerPlayer player) {
        byte[] mapperData = IdRemapper.serializeMapper(worldEngine.getMapper());
        VoxyNetworkHandler.sendToPlayer(player, VoxyPacketPayload.mapperSync(mapperData));
        Logger.info("Sent mapper sync to " + player.getName().getString() +
                " (" + mapperData.length + " bytes)");
    }

    /**
     * Start streaming sections to a player.
     */
    private void startStreaming(PlayerStreamingState state) {
        if (!isActive.get() || !state.player.isAlive()) {
            return;
        }

        // Keep the world engine marked as active during streaming
        worldEngine.markActive();

        int sectionsQueued = 0;
        int currentRing = state.currentRing;

        // Get player chunk position
        int playerChunkX = state.player.getBlockX() >> 5; // 32-block sections
        int playerChunkZ = state.player.getBlockZ() >> 5;

        // Stream sections in current ring
        for (int dx = -currentRing; dx <= currentRing; dx++) {
            for (int dz = -currentRing; dz <= currentRing; dz++) {
                // Only process ring boundary
                if (Math.abs(dx) != currentRing && Math.abs(dz) != currentRing) {
                    continue;
                }

                int sectionX = playerChunkX + dx;
                int sectionZ = playerChunkZ + dz;

                // Stream all Y levels and LOD levels
                for (int lvl = WorldEngine.MAX_LOD_LAYER; lvl >= 0; lvl--) {
                    for (int y = -4; y < 20; y++) { // Reasonable Y range
                        long key = WorldEngine.getWorldSectionId(lvl, sectionX, y, sectionZ);

                        if (state.sentSections.contains(key)) {
                            continue;
                        }

                        WorldSection section = worldEngine.acquire(key);
                        if (section != null) {
                            try {
                                if (section.getNonEmptyBlockCount() > 0 || section.getNonEmptyChildren() != 0) {
                                    byte[] data = SectionSerializer.serialize(section);
                                    state.sender.queueSection(data, (int) key);
                                    state.sentSections.add(key);
                                    sectionsQueued++;
                                }
                            } finally {
                                section.release();
                            }
                        }
                    }
                }
            }
        }

        // Advance to next ring if we had sections
        if (sectionsQueued > 0) {
            Logger.info("Queued " + sectionsQueued + " sections for " +
                    state.player.getName().getString() + " (ring " + currentRing + ")");
        }

        state.currentRing++;

        // Schedule next batch if within range
        if (state.currentRing <= maxStreamingRadius && isActive.get()) {
            scheduler.schedule(() -> startStreaming(state), 100, TimeUnit.MILLISECONDS);
        } else if (state.currentRing > maxStreamingRadius) {
            // Send completion signal
            VoxyNetworkHandler.sendToPlayer(state.player,
                    new VoxyPacketPayload(VoxyPacketPayload.MSG_SYNC_COMPLETE, new byte[0]));
            Logger.info("Completed streaming to " + state.player.getName().getString());
        }
    }

    /**
     * Handle cache response from client (bloom filter).
     */
    public void handleCacheResponse(ServerPlayer player, VoxyPacketPayload payload) {
        // TODO: Implement bloom filter cache checking
    }

    /**
     * Called when a player disconnects.
     */
    public void onPlayerDisconnect(UUID playerId) {
        PlayerStreamingState state = playerStates.remove(playerId);
        if (state != null) {
            state.close();
        }
        VoxyNetworkHandler.removePlayer(playerId);
    }

    /**
     * Get streaming stats for a player.
     */
    public String getPlayerStats(UUID playerId) {
        PlayerStreamingState state = playerStates.get(playerId);
        if (state == null) {
            return "No active streaming";
        }
        return state.sender.getStatsString();
    }

    @Override
    public void close() {
        isActive.set(false);
        scheduler.shutdown();

        for (PlayerStreamingState state : playerStates.values()) {
            state.close();
        }
        playerStates.clear();

        // Release the world engine reference
        try {
            worldEngine.releaseRef();
        } catch (Exception e) {
            Logger.error("Error releasing world engine ref", e);
        }

        Logger.info("LodStreamingService closed");
    }

    /**
     * Per-player streaming state.
     */
    private static class PlayerStreamingState {
        final ServerPlayer player;
        final ChunkedLodSender sender;
        final Set<Long> sentSections = ConcurrentHashMap.newKeySet();
        int currentRing = 0;
        int clientDesiredRate = SharedBandwidthLimit.DEFAULT_PLAYER_LIMIT_KBPS;

        PlayerStreamingState(ServerPlayer player, SharedBandwidthLimit sharedLimit, int limitKBps) {
            this.player = player;
            this.sender = new ChunkedLodSender(player, sharedLimit, limitKBps);
        }

        void close() {
            sender.close();
        }
    }
}
