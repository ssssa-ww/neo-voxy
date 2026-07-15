package me.cortex.voxy.server;

import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public class VoxyServerInstance extends VoxyInstance {
    private final Config config;
    private final Path basePath;

    public VoxyServerInstance(MinecraftServer server) {
        super();
        this.basePath = server.getWorldPath(LevelResource.ROOT).resolve("voxy").toAbsolutePath();
        this.config = StorageConfigUtil.getCreateStorageConfig(Config.class, c -> c.version == 1 && c.sectionStorageConfig != null, () -> DEFAULT_STORAGE_CONFIG, this.basePath);
        this.updateDedicatedThreads();
    }

    @Override
    public void updateDedicatedThreads() {
        this.setNumThreads(4); // Default for server
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.config.sectionStorageConfig.build(ctx);
    }

    private static class Config {
        public int version = 1;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }
}
