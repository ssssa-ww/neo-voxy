package dev.xantha.vss.networking.client;

import dev.xantha.vss.api.VSSApi;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public final class VSSClientNetworking {
    private static volatile boolean serverEnabled;
    private static volatile int serverLodDistance;
    private static volatile boolean waitingForHandshake;
    private static volatile boolean handshakeSent;
    private static int handshakeRetryTicks;
    private static volatile LodRequestManager requestManager;
    private static final ClientColumnProcessor COLUMN_PROCESSOR = new ClientColumnProcessor();
    private static final AtomicLong columnsReceived = new AtomicLong();
    private static final AtomicLong bytesReceived = new AtomicLong();
    private static final int HANDSHAKE_RETRY_INTERVAL_TICKS = 5;
    private static final int HANDSHAKE_FAILED_RETRY_INTERVAL_TICKS = 5;
    private static final long COLUMN_RECEIVE_DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static volatile long lastColumnReceiveDiagnosticNanos;

    private VSSClientNetworking() {
    }

    public static boolean isServerEnabled() {
        return serverEnabled;
    }

    public static boolean isClientLodSessionActive() {
        return serverEnabled && COLUMN_PROCESSOR.isActive() && isClientWorldReady();
    }

    public static int getServerLodDistance() {
        return serverLodDistance;
    }

    static int getQueuedColumnCount() {
        return COLUMN_PROCESSOR.getQueuedCount();
    }

    public static long getColumnsReceived() {
        return columnsReceived.get();
    }

    public static long getBytesReceived() {
        return bytesReceived.get();
    }

    public static long getColumnsDropped() {
        return COLUMN_PROCESSOR.getColumnsDropped();
    }

    public static void handleSessionConfig(SessionConfigS2CPayload payload) {
        if (!isClientWorldReady()) {
            discardSession();
            return;
        }
        if (payload.protocolVersion() != VSSConstants.PROTOCOL_VERSION) {
            VSSLogger.warn("Server has incompatible VSS protocol " + payload.protocolVersion());
            discardSession();
            return;
        }
        if (payload.enabled() && (payload.serverCapabilities() & VSSConstants.CAPABILITY_VOXEL_COLUMNS) == 0) {
            VSSLogger.warn("VSS LOD session rejected: server does not advertise voxel column support");
            discardSession();
            return;
        }

        boolean wasEnabled = serverEnabled;
        waitingForHandshake = false;
        handshakeSent = true;
        handshakeRetryTicks = 0;
        serverEnabled = payload.enabled();
        serverLodDistance = payload.lodDistanceChunks();
        if (payload.enabled()) {
            verifyAndInvalidateCache(payload.worldUUID());
            LodRequestManager manager = requestManager;
            boolean newSession = manager == null || !wasEnabled;
            if (newSession) {
                COLUMN_PROCESSOR.beginSession();
                manager = new LodRequestManager(ClientLodPresenceCache.currentScope());
            }
            boolean requestStateReset = manager.onSessionConfig(payload);
            if (requestStateReset && !newSession) {
                COLUMN_PROCESSOR.beginSession();
            }
            requestManager = manager;
            sendBandwidthPreference();

            boolean hasConsumers = VSSApi.hasVoxelConsumers();
            if (!hasConsumers) {
                VSSLogger.warn("VSS LOD session started but no voxel consumers registered! LOD data will not be processed.");
                VSSLogger.warn("Make sure Voxy mod is loaded or register a custom consumer via VSSApi.registerColumnConsumer()");
            }

            VSSLogger.info("VSS LOD session ready: distance=" + payload.lodDistanceChunks()
                    + " chunks, generation=" + (payload.generationEnabled() ? "enabled" : "disabled")
                    + ", revision=" + payload.configRevision()
                    + ", reset=" + requestStateReset
                    + ", consumers=" + hasConsumers);
        } else {
            LodRequestManager manager = requestManager;
            requestManager = null;
            if (manager != null) {
                manager.disconnect();
            }
            FarPlayerClientRenderer.clear();
            COLUMN_PROCESSOR.shutdown();
        }
    }

    public static void handleBatchResponse(BatchResponseS2CPayload payload) {
        if (!serverEnabled || !isClientWorldReady()) {
            return;
        }
        LodRequestManager manager = requestManager;
        if (manager == null) {
            return;
        }
        for (int i = 0; i < payload.count(); i++) {
            int requestId = payload.requestIds()[i];
            switch (payload.responseTypes()[i]) {
                case VSSConstants.RESPONSE_RATE_LIMITED -> manager.onRateLimited(requestId);
                case VSSConstants.RESPONSE_BACKPRESSURE -> manager.onBackpressured(requestId);
                case VSSConstants.RESPONSE_UP_TO_DATE -> manager.onColumnUpToDate(requestId);
                case VSSConstants.RESPONSE_NOT_GENERATED -> manager.onColumnNotGenerated(requestId);
                default -> VSSLogger.warn("Unknown batch response type: " + payload.responseTypes()[i]);
            }
        }
    }

    public static void handleDirtyColumns(DirtyColumnsS2CPayload payload) {
        if (!serverEnabled || !isClientWorldReady()) {
            return;
        }
        LodRequestManager manager = requestManager;
        if (manager != null) {
            manager.onDirtyColumns(payload.dirtyPositions(), payload.dirtyTimestamps());
        }
    }

    public static void handleVoxelColumn(VoxelColumnS2CPayload payload) {
        if (!isClientLodSessionActive()) {
            return;
        }
        columnsReceived.incrementAndGet();
        bytesReceived.addAndGet(payload.estimatedBytes());
        logColumnReceive(payload);
        LodRequestManager manager = requestManager;
        LodRequestManager.ColumnReceiveResult receiveResult;
        if (payload.requestId() < 0) {
            long packed = PositionUtil.packPosition(payload.chunkX(), payload.chunkZ());
            receiveResult = new LodRequestManager.ColumnReceiveResult(true, true, false, packed);
        } else if (manager != null) {
            receiveResult = manager.onColumnReceived(payload.requestId(), payload.columnTimestamp());
        } else {
            receiveResult = new LodRequestManager.ColumnReceiveResult(false, false, false, Long.MIN_VALUE);
        }
        if (payload.requestId() >= 0 && !receiveResult.knownRequest()) {
            return;
        }
        boolean replaceMissingSections = receiveResult.knownRequest()
                && receiveResult.replaceExistingColumn()
                && payload.completeColumn();
        boolean queued = COLUMN_PROCESSOR.offer(
                payload,
                receiveResult.knownRequest(),
                receiveResult.priority(),
                replaceMissingSections);
        if (queued && payload.requestId() < 0 && manager != null) {
            manager.onPushedColumnReceived(payload.dimension(), payload.chunkX(), payload.chunkZ(), payload.columnTimestamp());
        }
        if (!queued && manager != null && receiveResult.packedPosition() != Long.MIN_VALUE) {
            manager.onColumnProcessingFailed(payload.dimension(), payload.chunkX(), payload.chunkZ());
        }
    }

    public static void onColumnProcessingFailed(ResourceKey<Level> dimension, int cx, int cz) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> onColumnProcessingFailed(dimension, cx, cz));
            return;
        }
        LodRequestManager manager = requestManager;
        if (manager != null) {
            manager.onColumnProcessingFailed(dimension, cx, cz);
        }
    }

    public static void onClientChunkDropped(ResourceKey<Level> dimension, int cx, int cz) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> onClientChunkDropped(dimension, cx, cz));
            return;
        }
        LodRequestManager manager = requestManager;
        if (manager != null) {
            manager.onClientChunkDropped(dimension, cx, cz);
        }
    }

    public static void forceLodResync(String reason) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> forceLodResync(reason));
            return;
        }
        LodRequestManager manager = requestManager;
        if (!serverEnabled || manager == null || !isClientWorldReady()) {
            return;
        }
        COLUMN_PROCESSOR.beginSession();
        manager.forceResync();
        VSSLogger.info("VSS LOD resync requested: " + reason);
    }

    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        serverEnabled = false;
        serverLodDistance = 0;
        waitingForHandshake = false;
        handshakeSent = false;
        handshakeRetryTicks = 0;
        requestManager = null;
        if (!VSSClientConfig.CONFIG.receiveServerLods) {
            return;
        }
        waitingForHandshake = true;
        handshakeRetryTicks = 0;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        stopClientSessionForWorldShutdown();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ensureHandshakePending();
        tryPendingHandshake();
        LodRequestManager manager = requestManager;
        if (manager != null && serverEnabled) {
            manager.tick();
        }
        COLUMN_PROCESSOR.scheduleProcessing(serverEnabled);
        ModCompat.clientTick();
    }

    public static void sendBandwidthPreference() {
        if (!serverEnabled) {
            return;
        }
        long desiredRate = VSSClientConfig.CONFIG.desiredBandwidthKbps > 0
                ? (long) VSSClientConfig.CONFIG.desiredBandwidthKbps * 1000L / 8L
                : 0L;
        sendBandwidthUpdate(new BandwidthUpdateC2SPayload(desiredRate));
    }

    public static void stopClientSessionForWorldShutdown() {
        stopClientSession(true);
    }

    static void sendBatchRequest(BatchChunkRequestC2SPayload payload) {
        VSSNetworking.sendToServer(payload);
    }

    static void sendCancelRequest(CancelRequestC2SPayload payload) {
        VSSNetworking.sendToServer(payload);
    }

    static void sendRegionPresence(RegionPresenceC2SPayload payload) {
        VSSNetworking.sendToServer(payload);
    }

    private static void sendBandwidthUpdate(BandwidthUpdateC2SPayload payload) {
        try {
            VSSNetworking.sendToServer(payload);
        } catch (Exception e) {
            VSSLogger.debug("Bandwidth preference send failed: " + e.getMessage());
        }
    }

    private static void tryPendingHandshake() {
        if (!waitingForHandshake || requestManager != null || !VSSClientConfig.CONFIG.receiveServerLods) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || !isClientWorldReady()) {
            return;
        }

        if (handshakeRetryTicks > 0) {
            handshakeRetryTicks--;
            return;
        }

        boolean sent = sendHandshake("Handshake send failed: ");
        if (sent) {
            if (!handshakeSent) {
                VSSLogger.debug("VSS handshake sent; waiting for session config");
            }
            handshakeSent = true;
            handshakeRetryTicks = HANDSHAKE_RETRY_INTERVAL_TICKS;
        } else {
            handshakeRetryTicks = HANDSHAKE_FAILED_RETRY_INTERVAL_TICKS;
        }
    }

    private static void ensureHandshakePending() {
        if (waitingForHandshake || handshakeSent || requestManager != null || serverEnabled || !VSSClientConfig.CONFIG.receiveServerLods) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null && isClientWorldReady()) {
            waitingForHandshake = true;
            handshakeRetryTicks = 0;
        }
    }

    private static boolean sendHandshake(String failurePrefix) {
        try {
            VSSNetworking.sendToServer(new HandshakeC2SPayload(VSSConstants.PROTOCOL_VERSION, clientCapabilities()));
            return true;
        } catch (Exception e) {
            VSSLogger.debug(failurePrefix + e.getMessage());
            return false;
        }
    }

    private static int clientCapabilities() {
        int clientCaps = VSSApi.hasVoxelConsumers() ? VSSConstants.CAPABILITY_VOXEL_COLUMNS : 0;
        if (LodByteCompression.isZstdAvailable()) {
            clientCaps |= VSSConstants.CAPABILITY_ZSTD_COLUMNS;
        }
        return clientCaps;
    }

    private static boolean isClientWorldReady() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        return level != null && player != null && !player.isRemoved();
    }

    private static void discardSession() {
        stopClientSession(false);
    }

    private static void stopClientSession(boolean resetStats) {
        LodRequestManager manager = requestManager;
        requestManager = null;
        serverEnabled = false;
        serverLodDistance = 0;
        waitingForHandshake = false;
        handshakeSent = false;
        handshakeRetryTicks = 0;
        if (manager != null) {
            manager.disconnect();
        }
        ClientLodPresenceCache.flush();
        FarPlayerClientRenderer.clear();
        COLUMN_PROCESSOR.shutdown();
        if (resetStats) {
            COLUMN_PROCESSOR.resetStats();
            columnsReceived.set(0);
            bytesReceived.set(0);
            lastColumnReceiveDiagnosticNanos = 0L;
        }
    }

    private static void logColumnReceive(VoxelColumnS2CPayload payload) {
        if (!VSSLogger.isDebugEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastColumnReceiveDiagnosticNanos < COLUMN_RECEIVE_DIAGNOSTIC_INTERVAL_NANOS) {
            return;
        }
        lastColumnReceiveDiagnosticNanos = now;
        VSSLogger.debug("LOD columns received: total=" + columnsReceived.get()
                + ", bytes=" + bytesReceived.get()
                + ", queued=" + COLUMN_PROCESSOR.getQueuedCount()
                + ", last=" + payload.chunkX() + "," + payload.chunkZ()
                + ", sectionsBytes=" + payload.decompressedSections().length);
    }

    private static void verifyAndInvalidateCache(java.util.UUID serverWorldUUID) {
        if (serverWorldUUID == null || serverWorldUUID.equals(new java.util.UUID(0L, 0L))) {
            return;
        }
        if (me.cortex.voxy.commonImpl.VoxyCommon.getInstance() instanceof me.cortex.voxy.client.VoxyClientInstance clientInstance) {
            java.nio.file.Path basePath = clientInstance.getStorageBasePath();
            if (basePath == null) {
                return;
            }
            java.nio.file.Path uuidFile = basePath.resolve("vss_world_uuid.txt");
            boolean mismatch = false;
            try {
                if (java.nio.file.Files.exists(uuidFile)) {
                    String cachedUuidStr = java.nio.file.Files.readString(uuidFile).trim();
                    java.util.UUID cachedUuid = java.util.UUID.fromString(cachedUuidStr);
                    if (!serverWorldUUID.equals(cachedUuid)) {
                        mismatch = true;
                        VSSLogger.info("VSS: Server world UUID mismatch! Client: " + cachedUuid + ", Server: " + serverWorldUUID);
                    }
                } else {
                    mismatch = true;
                    VSSLogger.info("VSS: No cached server world UUID found, initializing with: " + serverWorldUUID);
                }
            } catch (Exception e) {
                mismatch = true;
                VSSLogger.warn("VSS: Failed to read cached world UUID, invalidating: " + e.getMessage());
            }

            if (mismatch) {
                VSSLogger.info("VSS: Invalidating client cache due to database/world mismatch...");
                boolean voxyWasRunning = me.cortex.voxy.commonImpl.VoxyCommon.getInstance() != null;
                if (voxyWasRunning) {
                    me.cortex.voxy.commonImpl.VoxyCommon.shutdownInstance();
                }
                try {
                    deleteDirectoryContents(basePath);
                } catch (Exception e) {
                    VSSLogger.error("VSS: Failed to clear local cache directory", e);
                }
                try {
                    java.nio.file.Files.createDirectories(basePath);
                    java.nio.file.Files.writeString(uuidFile, serverWorldUUID.toString());
                } catch (Exception e) {
                    VSSLogger.error("VSS: Failed to save server world UUID", e);
                }
                if (voxyWasRunning) {
                    me.cortex.voxy.commonImpl.VoxyCommon.createInstance();
                }
            }
        }
    }

    private static void deleteDirectoryContents(java.nio.file.Path path) throws java.io.IOException {
        if (!java.nio.file.Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    if (!p.equals(path)) {
                        try {
                            java.nio.file.Files.delete(p);
                        } catch (java.io.IOException e) {
                            // ignore / log
                        }
                    }
                });
        }
    }

    public static int getInFlightCount() {
        LodRequestManager manager = requestManager;
        return manager != null ? manager.getInFlightCount() : 0;
    }

    public static int getClientPendingCount() {
        int count = 0;
        count += getInFlightCount();
        count += COLUMN_PROCESSOR.getQueuedCount();
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr instanceof me.cortex.voxy.client.core.IGetVoxyRenderSystem vrs) {
            var renderSystem = vrs.getVoxyRenderSystem();
            if (renderSystem != null) {
                var receptionService = renderSystem.getLodReceptionService();
                if (receptionService != null) {
                    count += receptionService.getPendingSectionsCount();
                }
            }
        }
        return count;
    }

    private static int maxPending = 0;

    @SubscribeEvent
    public static void onRenderGui(net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) {
        if (!VSSClientConfig.CONFIG.showPropagationProgress) {
            maxPending = 0;
            return;
        }

        net.minecraft.client.gui.GuiGraphics graphics = event.getGuiGraphics();
        int width = 182;
        int height = 5;
        int guiWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int x = (guiWidth - width) / 2;
        int y = 12;
        int spacing = 20;

        // Render client propagation progress
        if (isClientLodSessionActive()) {
            int currentPending = getClientPendingCount();
            if (currentPending > 0) {
                maxPending = Math.max(maxPending, currentPending);
                float pct = maxPending > 0 ? (float) (maxPending - currentPending) / maxPending : 1.0f;
                pct = Math.max(0.0f, Math.min(1.0f, pct));

                // Background
                graphics.fill(x, y, x + width, y + height, 0x80222222);
                // Fill (Voxy Cyan)
                int fillWidth = (int) (width * pct);
                graphics.fill(x, y, x + fillWidth, y + height, 0xFF00FFCC);
                // Text
                String text = net.minecraft.network.chat.Component.translatable("vss.hud.lod_propagation", (int) (pct * 100), currentPending).getString();
                graphics.drawCenteredString(Minecraft.getInstance().font, text, guiWidth / 2, y - 10, 0xFFFFFFFF);

                y += spacing;
            } else {
                maxPending = 0;
            }
        } else {
            maxPending = 0;
        }

        // Render server auto-ingestor progress (singleplayer only)
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            var ingestors = me.cortex.voxy.server.VoxyServer.getAutoIngestors();
            if (ingestors != null) {
                for (var entry : ingestors.entrySet()) {
                    var level = entry.getKey();
                    var ingestor = entry.getValue();
                    if (ingestor != null && ingestor.isRunning()) {
                        int processed = ingestor.getProcessedRegions();
                        int total = ingestor.getTotalRegions();
                        int queueSize = ingestor.getQueueSize();
                        if (total > 0) {
                            float pct = (float) processed / total;
                            pct = Math.max(0.0f, Math.min(1.0f, pct));

                            // Background
                            graphics.fill(x, y, x + width, y + height, 0x80222222);
                            // Fill (Lime Green / Emerald color for Server auto-ingest!)
                            int fillWidth = (int) (width * pct);
                            graphics.fill(x, y, x + fillWidth, y + height, 0xFF55FF55);

                            // Text
                            String dimName = level.dimension().location().getPath();
                            String text = net.minecraft.network.chat.Component.translatable(
                                    "vss.hud.server_auto_ingest",
                                    dimName,
                                    (int) (pct * 100),
                                    processed,
                                    total,
                                    queueSize
                            ).getString();
                            graphics.drawCenteredString(Minecraft.getInstance().font, text, guiWidth / 2, y - 10, 0xFFFFFFFF);

                            y += spacing;
                        }
                    }
                }
            }
        }
    }
}
