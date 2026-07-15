package me.cortex.voxy.server.util;

import me.cortex.voxy.common.Logger;

public class VSSLogger {
    public static void info(String message) {
        Logger.info("[VSS] " + message);
    }

    public static void warn(String message) {
        Logger.warn("[VSS] " + message);
    }

    public static void error(String message) {
        Logger.error("[VSS] " + message);
    }

    public static void error(String message, Throwable t) {
        Logger.error("[VSS] " + message, t);
    }

    public static void debug(String message) {
        Logger.info("[VSS-DEBUG] " + message);
    }

    public static boolean isDebugEnabled() {
        return true; 
    }
}
