package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    @Shadow
    private net.minecraft.client.multiplayer.ClientLevel level;

    @Inject(method = "handleLogin", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;commonPlayerSpawnInfo()Lnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;"))
    private void voxy$init(ClientboundLoginPacket packet, CallbackInfo ci) {
        if (VoxyCommon.isAvailable() && !VoxyClientInstance.isInGame) {
            VoxyClientInstance.isInGame = true;
            if (VoxyConfig.CONFIG.enabled) {
                if (VoxyCommon.getInstance() != null) {
                    VoxyCommon.shutdownInstance();
                }
                VoxyCommon.createInstance();
            }
        }
    }

    @Inject(method = "applyLightData", at = @At("RETURN"))
    private void voxy$onApplyLightData(int x, int z, net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData data, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && this.level != null) {
            var chunk = this.level.getChunkSource().getChunk(x, z, false);
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }
}
