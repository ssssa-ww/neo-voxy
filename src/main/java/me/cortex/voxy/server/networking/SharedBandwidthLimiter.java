package me.cortex.voxy.server.networking;

import java.util.concurrent.atomic.AtomicLong;

public class SharedBandwidthLimiter {
    private final long globalLimitBps;
    private final AtomicLong totalBytesSent = new AtomicLong(0);

    public SharedBandwidthLimiter(long globalLimitBps) {
        this.globalLimitBps = globalLimitBps;
    }

    public long getPerPlayerAllocation(int activePlayerCount) {
        if (activePlayerCount <= 0) {
            return globalLimitBps;
        }
        return globalLimitBps / activePlayerCount;
    }

    public void recordSend(int bytes) {
        totalBytesSent.addAndGet(bytes);
    }

    public long getTotalBytesSent() {
        return totalBytesSent.get();
    }
}
