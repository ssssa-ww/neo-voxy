package me.cortex.voxy.server.networking;

import dev.xantha.vss.networking.payloads.*;
import dev.xantha.vss.networking.VSSNetworking;
import me.cortex.voxy.server.util.VSSLogger;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class VSSServerNetworking {
    private static RequestProcessingService requestService;

    public static void setRequestService(RequestProcessingService service) {
        requestService = service;
    }

    public static void handleHandshake(HandshakeC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                handleHandshake(payload, player);
            }
        });
    }

    public static void handleBatchRequest(BatchChunkRequestC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                handleBatchRequest(payload, player);
            }
        });
    }

    public static void handleCancel(CancelRequestC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                handleCancel(payload, player);
            }
        });
    }

    public static void handleBandwidthUpdate(BandwidthUpdateC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                handleBandwidthUpdate(payload, player);
            }
        });
    }

    public static void handleRegionPresence(RegionPresenceC2SPayload payload, IPayloadContext context) {
        // Not used by this lightweight server, but we must define it to avoid registration error
    }

    private static void handleHandshake(HandshakeC2SPayload payload, ServerPlayer player) {
        // VSSLogger.info("VSS handshake received from " + player.getName().getString() + " (v" + payload.protocolVersion() + ")");
        
        if (requestService != null) {
            requestService.registerPlayer(player, payload.capabilities());
            
            // Send session config back
            java.util.UUID worldUUID = dev.xantha.vss.networking.server.VSSServerNetworking.getOrCreateWorldUUID();
            sendToPlayer(player, new SessionConfigS2CPayload(
                    15, // Protocol version
                    true, // Enabled
                    256, // lodDistanceChunks
                    1, // serverCapabilities
                    800, // nearSyncRateLimitPerTick
                    400, // midSyncRateLimitPerTick
                    200, // farSyncRateLimitPerTick
                    100, // distantSyncRateLimitPerTick
                    80, // generationRateLimitPerPlayer
                    16, // generationConcurrencyLimitPerPlayer
                    true, // generationEnabled
                    20971520L, // playerBandwidthLimit
                    1L, // configRevision
                    worldUUID // worldUUID
            ));
        }
    }

    private static void handleBatchRequest(BatchChunkRequestC2SPayload payload, ServerPlayer player) {
        if (requestService != null) {
            requestService.handleBatchRequest(player, payload);
        }
    }

    private static void handleCancel(CancelRequestC2SPayload payload, ServerPlayer player) {
        if (requestService != null) {
            requestService.handleCancel(player, payload);
        }
    }

    private static void handleBandwidthUpdate(BandwidthUpdateC2SPayload payload, ServerPlayer player) {
        if (requestService != null) {
            requestService.handleBandwidthUpdate(player, payload);
        }
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        VSSNetworking.sendToPlayer(player, payload);
    }
}
