package me.cortex.voxy.server.networking;

import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import me.cortex.voxy.server.util.PositionUtil;
import me.cortex.voxy.server.util.VSSLogger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RequestProcessingService {
    private final Map<UUID, PlayerRequestState> players = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final SharedBandwidthLimiter bandwidthLimiter;
    
    private final int lodDistanceChunks = 256;
    private final int bytesPerSecondLimitPerPlayer = 20971520;
    private final int bytesPerSecondLimitGlobal = 104857600;

    public RequestProcessingService(MinecraftServer server) {
        this.server = server;
        this.bandwidthLimiter = new SharedBandwidthLimiter(bytesPerSecondLimitGlobal);
    }

    public void registerPlayer(ServerPlayer player, int capabilities) {
        PlayerRequestState state = players.computeIfAbsent(player.getUUID(), uuid -> 
            new PlayerRequestState(player, 800, 200, 80, 16));
        state.setCapabilities(capabilities);
        state.markHandshakeComplete();
        // VSSLogger.info("Player " + player.getName().getString() + " registered for VSS");
    }

    public void removePlayer(UUID uuid) {
        players.remove(uuid);
    }

    public void handleBatchRequest(ServerPlayer player, BatchChunkRequestC2SPayload payload) {
        PlayerRequestState state = players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDist = lodDistanceChunks + 32;

        for (int i = 0; i < payload.count(); i++) {
            long packedPosition = payload.packedPositions()[i];
            int cx = PositionUtil.unpackX(packedPosition);
            int cz = PositionUtil.unpackZ(packedPosition);
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) <= maxDist) {
                state.addRequest(payload.requestIds()[i], packedPosition, payload.clientTimestamps()[i]);
            }
        }
    }

    public void handleCancel(ServerPlayer player, CancelRequestC2SPayload payload) {
        PlayerRequestState state = players.get(player.getUUID());
        if (state != null) {
            state.addCancel(payload.requestId());
        }
    }

    public void handleBandwidthUpdate(ServerPlayer player, BandwidthUpdateC2SPayload payload) {
        PlayerRequestState state = players.get(player.getUUID());
        if (state != null) {
            state.setDesiredBandwidth(payload.desiredRate());
        }
    }

    public void tick() {
        long perPlayerAllocation = bandwidthLimiter.getPerPlayerAllocation(players.size());
        long perPlayerCap = Math.min(perPlayerAllocation, bytesPerSecondLimitPerPlayer);

        for (PlayerRequestState state : players.values()) {
            if (!state.hasCompletedHandshake()) continue;
            
            state.resetTick();
            
            // Check for dimension change
            if (state.checkDimensionChange()) {
                state.onDimensionChange();
            }

            processRequests(state);
            tickProactiveStreaming(state);
            flushSendQueue(state, perPlayerCap);
        }
    }

    private void tickProactiveStreaming(PlayerRequestState state) {
        if (state.getSendQueueSize() > 100) return; // Don't overwhelm the queue

        ServerPlayer player = state.getPlayer();
        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        
        // Update streaming center if player moved significantly
        if (PositionUtil.chebyshevDistance(playerCx, playerCz, state.getStreamingX(), state.getStreamingZ()) > 32) {
            state.setStreamingCenter(playerCx, playerCz);
            state.setStreamingRadius(0);
        }

        int radius = state.getStreamingRadius();
        if (radius > lodDistanceChunks) return;

        // Try to send a few chunks this tick
        int sentThisTick = 0;
        int maxProactivePerTick = 20;

        while (sentThisTick < maxProactivePerTick && radius <= lodDistanceChunks) {
            // Very basic spiral/square expansion
            boolean foundAny = false;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) < radius && Math.abs(z) < radius) continue; // Already processed inner squares
                    
                    int cx = state.getStreamingX() + x;
                    int cz = state.getStreamingZ() + z;
                    long packed = PositionUtil.packPosition(cx, cz);
                    
                    if (!state.getSentColumns().contains(packed)) {
                        state.addRequest(-1, packed, System.currentTimeMillis());
                        state.getSentColumns().add(packed);
                        sentThisTick++;
                        foundAny = true;
                        if (sentThisTick >= maxProactivePerTick) break;
                    }
                }
                if (sentThisTick >= maxProactivePerTick) break;
            }
            if (!foundAny) {
                radius++;
                state.setStreamingRadius(radius);
            } else {
                break;
            }
        }
    }

    public void notifyChunkLoad(ServerLevel level, LevelChunk chunk) {
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;

        for (PlayerRequestState state : players.values()) {
            if (state.getPlayer().serverLevel() != level) continue;
            
            synchronized (state.getPendingRequests()) {
                Iterator<PlayerRequestState.IncomingRequest> it = state.getPendingRequests().iterator();
                while (it.hasNext()) {
                    PlayerRequestState.IncomingRequest req = it.next();
                    if (req.cx() == cx && req.cz() == cz) {
                        it.remove();
                        processSingleRequest(state, level, req);
                    }
                }
            }
        }
    }

    public void markColumnDirty(ServerLevel level, int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        for (PlayerRequestState state : players.values()) {
            if (state.getPlayer().serverLevel() != level) continue;
            if (state.getSentColumns().contains(packed)) {
                // If the client already has this column, notify them it's dirty
                state.enqueuePayload(
                    new DirtyColumnsS2CPayload(new long[]{packed}, new long[]{System.currentTimeMillis()}),
                    32,
                    1
                );
            }
        }
    }

    private void processRequests(PlayerRequestState state) {
        List<PlayerRequestState.IncomingRequest> requests = state.getIncomingRequests();
        ServerPlayer player = state.getPlayer();
        ServerLevel level = player.serverLevel();

        for (PlayerRequestState.IncomingRequest req : requests) {
            processSingleRequest(state, level, req);
        }
    }

    private void processSingleRequest(PlayerRequestState state, ServerLevel level, PlayerRequestState.IncomingRequest req) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(req.cx(), req.cz());
        if (chunk != null) {
            byte[] data = SectionSerializer.serializeColumn(level, chunk, req.cx(), req.cz());
            if (data != null) {
                state.enqueuePayload(
                    new VoxelColumnS2CPayload(
                        req.requestId(), req.cx(), req.cz(), level.dimension(), 
                        System.currentTimeMillis(), data
                    ),
                    data.length + 25,
                    0 
                );
            }
            state.getSentColumns().add(req.packedPos());
        } else {
            // Chunk not loaded, add to pending
            synchronized (state.getPendingRequests()) {
                state.getPendingRequests().add(req);
            }
        }
    }

    private void flushSendQueue(PlayerRequestState state, long allocationBytes) {
        PriorityQueue<PlayerRequestState.QueuedPayload> queue = state.getSendQueue();
        while (!queue.isEmpty()) {
            if (!state.canSend(allocationBytes)) break;
            
            PlayerRequestState.QueuedPayload queued = queue.poll();
            VSSServerNetworking.sendToPlayer(state.getPlayer(), queued.payload());
            state.recordSend(queued.estimatedBytes());
            bandwidthLimiter.recordSend(queued.estimatedBytes());
        }
    }

    public void shutdown() {
        players.clear();
    }
}
