package dev.xantha.vss.networking.server.storage;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.common.processing.EncodedColumnData;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class ColumnLodCache {
    private final VSSServerConfig config;
    private final Long2ObjectLinkedOpenHashMap<Entry> entries = new Long2ObjectLinkedOpenHashMap<>(1024);
    private final List<ResourceLocation> dimensionRegistry = new ArrayList<>();
    private long cachedBytes;
    private long hits;
    private long misses;
    private long puts;
    private long evictions;
    private long invalidations;

    private static final int OFFSET = 1 << 27;

    public ColumnLodCache(VSSServerConfig config) {
        this.config = config;
    }

    private int getDimensionId(ResourceLocation dimension) {
        int id = dimensionRegistry.indexOf(dimension);
        if (id == -1) {
            id = dimensionRegistry.size();
            dimensionRegistry.add(dimension);
        }
        return id;
    }

    private long getPackedKey(ResourceLocation dimension, int cx, int cz) {
        return packKey(getDimensionId(dimension), cx, cz);
    }

    public static long packKey(int dimensionId, int cx, int cz) {
        long x = cx + OFFSET;
        long z = cz + OFFSET;
        return ((long) (dimensionId & 0xFF) << 56) | (x << 28) | z;
    }

    public synchronized Entry get(ResourceKey<Level> dimension, int cx, int cz) {
        if (!config.enableColumnCache) {
            return null;
        }

        long packedKey = getPackedKey(dimension.location(), cx, cz);
        Entry entry = entries.get(packedKey);
        if (entry == null) {
            misses++;
        } else {
            entries.putAndMoveToLast(packedKey, entry);
            hits++;
        }
        return entry;
    }

    public synchronized void put(ResourceKey<Level> dimension, EncodedColumnData columnData) {
        if (!config.enableColumnCache || columnData == null || columnData.encodedBytes() == null || !columnData.completeColumn()) {
            return;
        }

        int sizeBytes = columnData.encodedBytes().length;
        if (sizeBytes <= 0 || sizeBytes > config.columnCacheMaxBytes) {
            return;
        }

        long packedKey = getPackedKey(dimension.location(), columnData.chunkX(), columnData.chunkZ());
        Entry previous = entries.remove(packedKey);
        if (previous != null) {
            if (previous.timestamp() > columnData.columnStamp()) {
                entries.putAndMoveToLast(packedKey, previous);
                return;
            }
            cachedBytes -= previous.sizeBytes();
        }

        byte[] cachedSections = Arrays.copyOf(columnData.encodedBytes(), columnData.encodedBytes().length);
        entries.putAndMoveToLast(packedKey, new Entry(
                columnData.chunkX(),
                columnData.chunkZ(),
                columnData.columnStamp(),
                columnData.compression(),
                columnData.rawSize(),
                cachedSections,
                sizeBytes,
                columnData.schemaVersion(),
                columnData.completeColumn()));
        this.cachedBytes += sizeBytes;
        puts++;
        evictOverflow();
    }

    public synchronized void invalidate(ResourceKey<Level> dimension, int cx, int cz) {
        long packedKey = getPackedKey(dimension.location(), cx, cz);
        Entry removed = entries.remove(packedKey);
        if (removed != null) {
            cachedBytes -= removed.sizeBytes();
            invalidations++;
        }
    }

    public synchronized void invalidateOlderThan(ResourceKey<Level> dimension, int cx, int cz, long minimumInvalidTimestamp) {
        long packedKey = getPackedKey(dimension.location(), cx, cz);
        Entry entry = entries.get(packedKey);
        if (entry == null || entry.timestamp() >= minimumInvalidTimestamp) {
            return;
        }
        entries.remove(packedKey);
        cachedBytes -= entry.sizeBytes();
        invalidations++;
    }

    public synchronized void clear() {
        entries.clear();
        cachedBytes = 0L;
    }

    public synchronized String diagnostics() {
        return String.format(
                "entries=%d, bytes=%.2f MiB, hits=%d, misses=%d, puts=%d, evictions=%d, invalidations=%d",
                entries.size(),
                cachedBytes / (double) VSSServerConfig.BYTES_PER_MIB,
                hits,
                misses,
                puts,
                evictions,
                invalidations);
    }

    private void evictOverflow() {
        while ((entries.size() > config.columnCacheMaxEntries || cachedBytes > config.columnCacheMaxBytes)
                && !entries.isEmpty()) {
            Entry eldestValue = entries.removeFirst();
            cachedBytes -= eldestValue.sizeBytes();
            evictions++;
        }
    }

    public record Entry(
            int chunkX,
            int chunkZ,
            long timestamp,
            int compression,
            int rawSize,
            byte[] encodedBytes,
            int sizeBytes,
            int schemaVersion,
            boolean completeColumn) {
        public EncodedColumnData columnData() {
            return new EncodedColumnData(
                    chunkX,
                    chunkZ,
                    compression,
                    rawSize,
                    encodedBytes,
                    timestamp,
                    schemaVersion,
                    completeColumn);
        }
    }
}
