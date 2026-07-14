package me.cortex.voxy.common.mixin;

import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = VoxyIntegration.class, remap = false)
public class MixinVoxyIntegration {
    @Shadow
    private static boolean initialized;

    @Shadow
    private static boolean enabled;

    @Shadow
    private static java.lang.invoke.MethodHandle ingestMethod;

    @Shadow
    private static java.lang.invoke.MethodHandle rawIngestMethod;

    @Shadow
    private static java.lang.invoke.MethodHandle worldIdentifierOfMethod;

    @Shadow
    private static java.lang.invoke.MethodHandle voxyEnabledMethod;

    /**
     * @author Antigravity
     * @reason Catch Throwable instead of ClassNotFoundException to prevent server crash when client classes are missing.
     */
    @Overwrite
    private static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        try {
            Class<?> voxelIngestServiceClass = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
            Class<?> worldIdentifierClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");

            Object instance = null;
            try {
                var field = voxelIngestServiceClass.getDeclaredField("INSTANCE");
                instance = field.get(null);
            } catch (Exception e) {
                // Ignore
            }

            var lookup = java.lang.invoke.MethodHandles.lookup();

            String[] ingestMethodNames = {"ingestChunk", "tryAutoIngestChunk", "enqueueIngest", "ingest"};
            java.lang.reflect.Method ingestMethodRef = null;
            for (String name : ingestMethodNames) {
                try {
                    ingestMethodRef = voxelIngestServiceClass.getMethod(name, net.minecraft.world.level.chunk.LevelChunk.class);
                    if (ingestMethodRef != null) {
                        break;
                    }
                } catch (NoSuchMethodException e) {
                    // Try next
                }
            }

            if (ingestMethodRef != null) {
                java.lang.invoke.MethodHandle ingestMethodHandle = lookup.unreflect(ingestMethodRef);
                if (instance != null && !java.lang.reflect.Modifier.isStatic(ingestMethodRef.getModifiers())) {
                    ingestMethodHandle = ingestMethodHandle.bindTo(instance);
                }
                ingestMethod = ingestMethodHandle;
                enabled = true;
            }

            try {
                java.lang.reflect.Method rawIngestMethodRef = voxelIngestServiceClass.getMethod("rawIngest", 
                    worldIdentifierClass, 
                    net.minecraft.world.level.chunk.LevelChunkSection.class, 
                    int.class, int.class, int.class, 
                    net.minecraft.world.level.chunk.DataLayer.class, 
                    net.minecraft.world.level.chunk.DataLayer.class
                );
                rawIngestMethod = lookup.unreflect(rawIngestMethodRef);
            } catch (NoSuchMethodException e) {
                // Ignore
            }

            try {
                java.lang.reflect.Method worldIdentifierOfMethodRef = worldIdentifierClass.getMethod("of", net.minecraft.world.level.Level.class);
                worldIdentifierOfMethod = lookup.unreflect(worldIdentifierOfMethodRef);
            } catch (NoSuchMethodException e) {
                // Ignore
            }

            try {
                Class<?> voxyConfigClass = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
                try {
                    java.lang.reflect.Method isEnabledMethod = voxyConfigClass.getMethod("isEnabled");
                    voxyEnabledMethod = lookup.unreflect(isEnabledMethod);
                } catch (NoSuchMethodException e) {
                    var field = voxyConfigClass.getDeclaredField("enabled");
                    field.setAccessible(true);
                    voxyEnabledMethod = lookup.unreflectGetter(field);
                }
            } catch (Throwable e) {
                try {
                    Class<?> voxyClientClass = Class.forName("me.cortex.voxy.client.VoxyClient");
                    java.lang.reflect.Method isEnabledMethod = voxyClientClass.getMethod("isEnabled");
                    voxyEnabledMethod = lookup.unreflect(isEnabledMethod);
                } catch (Throwable ex) {
                    // Ignore
                }
            }

            com.ethan.voxyworldgenv2.VoxyWorldGenV2.LOGGER.info(
                "voxy integration initialized (enabled: {}, raw: {}, voxyEnabled: {})",
                enabled,
                rawIngestMethod != null,
                voxyEnabledMethod != null
            );

        } catch (Throwable t) {
            com.ethan.voxyworldgenv2.VoxyWorldGenV2.LOGGER.info("voxy not present, integration disabled");
            enabled = false;
        }
    }
}
