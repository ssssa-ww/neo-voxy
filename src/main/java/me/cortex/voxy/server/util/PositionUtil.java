package me.cortex.voxy.server.util;

public class PositionUtil {
    public static long packPosition(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackZ(long packed) {
        return (int) packed;
    }

    public static int chebyshevDistance(int x1, int z1, int x2, int z2) {
        return Math.max(Math.abs(x1 - x2), Math.abs(z1 - z2));
    }

    public static boolean isOutOfRange(long packed, int centerX, int centerZ, int range) {
        return chebyshevDistance(unpackX(packed), unpackZ(packed), centerX, centerZ) > range;
    }
}
