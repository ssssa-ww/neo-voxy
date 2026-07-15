package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.SaveLoadSystem;
import com.github.luben.zstd.Zstd;

public class ZSTDCompressor implements StorageCompressor {
    private static final ThreadLocalMemoryBuffer SCRATCH = new ThreadLocalMemoryBuffer(SaveLoadSystem.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    private final int level;

    public ZSTDCompressor(int level) {
        this.level = level;
    }

    @Override
    public MemoryBuffer compress(MemoryBuffer saveData) {
        long bound = Zstd.compressBound(saveData.size);
        MemoryBuffer compressedData = new MemoryBuffer((int) bound);
        long compressedSize = Zstd.compressUnsafe(
            compressedData.address, compressedData.size, 
            saveData.address, saveData.size, 
            this.level
        );
        if (Zstd.isError(compressedSize)) {
            compressedData.free();
            throw new RuntimeException("Zstd compression failed: " + Zstd.getErrorName(compressedSize));
        }
        return compressedData.subSize(compressedSize);
    }

    @Override
    public MemoryBuffer decompress(MemoryBuffer saveData) {
        var decompressed = SCRATCH.get().createUntrackedUnfreeableReference();
        long size = Zstd.decompressUnsafe(
            decompressed.address, decompressed.size, 
            saveData.address, saveData.size
        );
        if (Zstd.isError(size)) {
            throw new RuntimeException("Zstd decompression failed: " + Zstd.getErrorName(size));
        }
        return decompressed.subSize(size);
    }

    @Override
    public void close() {

    }

    public static class Config extends CompressorConfig {
        public int compressionLevel;

        @Override
        public StorageCompressor build(ConfigBuildCtx ctx) {
            return new ZSTDCompressor(this.compressionLevel);
        }

        public static String getConfigTypeName() {
            return "ZSTD";
        }
    }
}
