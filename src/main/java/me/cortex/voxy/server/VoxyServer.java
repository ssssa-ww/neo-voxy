package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.networking.RequestProcessingService;
import me.cortex.voxy.server.networking.VSSServerNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public class VoxyServer {
    private static RequestProcessingService requestService;

    public static RequestProcessingService getRequestService() {
        return requestService;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        VoxyCommon.setInstanceFactory(() -> new VoxyServerInstance(server));
        VoxyCommon.createInstance();
        requestService = new RequestProcessingService(server);
        VSSServerNetworking.setRequestService(requestService);
        Logger.info("VSS Request Processing Service started.");
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        VoxyCommon.shutdownInstance();
        if (requestService != null) {
            requestService.shutdown();
            requestService = null;
            VSSServerNetworking.setRequestService(null);
            Logger.info("VSS Request Processing Service stopped.");
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (requestService != null) {
            requestService.tick();
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (requestService != null && event.getEntity() instanceof ServerPlayer player) {
            requestService.removePlayer(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel && event.getChunk() instanceof LevelChunk chunk) {
            var instance = VoxyCommon.getInstance();
            if (instance == null) return;

            try {
                var identifier = WorldIdentifier.of(serverLevel);
                if (identifier == null) return;
                var engine = instance.getNullable(identifier);
                if (engine == null) return;

                if (!instance.isIngestEnabled(identifier)) return;
                instance.getIngestService().enqueueIngest(engine, chunk);

                if (requestService != null) {
                    requestService.notifyChunkLoad(serverLevel, chunk);
                }
            } catch (Exception e) {
                Logger.error("Server chunk ingest failed for " + serverLevel, e);
            }
        }
    }

    public static void notifyColumnDirty(ServerLevel level, int cx, int cz) {
        if (requestService != null) {
            requestService.markColumnDirty(level, cx, cz);
        }
    }
}
