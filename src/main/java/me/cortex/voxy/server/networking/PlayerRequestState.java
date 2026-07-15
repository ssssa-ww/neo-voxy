package me.cortex.voxy.server.networking;

import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import me.cortex.voxy.server.util.PositionUtil;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public class PlayerRequestState {
    private ServerPlayer player;
    private final UUID uuid;
    private int capabilities;
    private boolean handshakeComplete;
    private long desiredBandwidth;
    private long bytesSentInLastTick;
    private int currentDimension;

    private final List<IncomingRequest> incomingRequests = new ArrayList<>();
    private final Set<Integer> cancelledRequests = new HashSet<>();
    private final PriorityQueue<QueuedPayload> sendQueue = new PriorityQueue<>(Comparator.comparingInt(QueuedPayload::priority));
    
    private final Set<Long> sentColumns = new HashSet<>();
    private final List<IncomingRequest> pendingRequests = new ArrayList<>();
    private int streamingRadius = 0;
    private int streamingX;
    private int streamingZ;

    public PlayerRequestState(ServerPlayer player, int syncRate, int syncConc, int genRate, int genConc) {
        this.player = player;
        this.uuid = player.getUUID();
        this.currentDimension = player.level().dimension().hashCode();
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public void updatePlayer(ServerPlayer player) {
        this.player = player;
    }

    public void setCapabilities(int capabilities) {
        this.capabilities = capabilities;
    }

    public void markHandshakeComplete() {
        this.handshakeComplete = true;
    }

    public boolean hasCompletedHandshake() {
        return handshakeComplete;
    }

    public void setDesiredBandwidth(long desiredRate) {
        this.desiredBandwidth = desiredRate;
    }

    public long getDesiredBandwidth() {
        return desiredBandwidth;
    }

    public void addRequest(int requestId, long packedPosition, long clientTimestamp) {
        synchronized (incomingRequests) {
            incomingRequests.add(new IncomingRequest(
                    PositionUtil.unpackX(packedPosition),
                    PositionUtil.unpackZ(packedPosition),
                    requestId,
                    clientTimestamp
            ));
        }
    }

    public void addCancel(int requestId) {
        synchronized (cancelledRequests) {
            cancelledRequests.add(requestId);
        }
    }

    public List<IncomingRequest> getIncomingRequests() {
        synchronized (incomingRequests) {
            List<IncomingRequest> copy = new ArrayList<>(incomingRequests);
            incomingRequests.clear();
            return copy;
        }
    }

    public boolean checkDimensionChange() {
        int dim = player.level().dimension().hashCode();
        if (dim != currentDimension) {
            currentDimension = dim;
            return true;
        }
        return false;
    }

    public void onDimensionChange() {
        synchronized (incomingRequests) {
            incomingRequests.clear();
        }
        synchronized (sendQueue) {
            sendQueue.clear();
        }
        synchronized (sentColumns) {
            sentColumns.clear();
        }
        synchronized (pendingRequests) {
            pendingRequests.clear();
        }
        streamingRadius = 0;
    }

    public void enqueuePayload(CustomPacketPayload payload, int estimatedBytes, int priority) {
        synchronized (sendQueue) {
            sendQueue.add(new QueuedPayload(payload, estimatedBytes, priority));
        }
    }

    public PriorityQueue<QueuedPayload> getSendQueue() {
        return sendQueue;
    }

    public int getSendQueueSize() {
        synchronized (sendQueue) {
            return sendQueue.size();
        }
    }

    public boolean canSend(long tickAllocation) {
        return bytesSentInLastTick < (tickAllocation / 20); // Simple rate limiting per tick
    }

    public void recordSend(int bytes) {
        bytesSentInLastTick += bytes;
    }

    public void resetTick() {
        bytesSentInLastTick = 0;
    }

    public Set<Long> getSentColumns() {
        return sentColumns;
    }

    public List<IncomingRequest> getPendingRequests() {
        return pendingRequests;
    }

    public void setStreamingCenter(int x, int z) {
        this.streamingX = x;
        this.streamingZ = z;
    }

    public int getStreamingX() { return streamingX; }
    public int getStreamingZ() { return streamingZ; }
    public int getStreamingRadius() { return streamingRadius; }
    public void setStreamingRadius(int radius) { this.streamingRadius = radius; }

    public record IncomingRequest(int cx, int cz, int requestId, long clientTimestamp) {
        public long packedPos() {
            return PositionUtil.packPosition(cx, cz);
        }
    }

    public record QueuedPayload(CustomPacketPayload payload, int estimatedBytes, int priority) {}

    public static class RateLimiterSet {
        public RateLimiter syncOnLoad() { return new RateLimiter(); }
        public RateLimiter generation() { return new RateLimiter(); }
    }

    public static class RateLimiter {
        public int getCurrentConcurrency() { return 0; }
        public int getMaxConcurrency() { return 1; }
    }
}
