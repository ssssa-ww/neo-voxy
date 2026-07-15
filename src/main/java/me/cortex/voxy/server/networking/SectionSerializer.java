package me.cortex.voxy.server.networking;

import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;

public final class SectionSerializer {
    private record SectionInfo(int index, int sectionY, SectionPos sectionPos, DataLayer blLayer, boolean hasBlockLight) {}

    public static byte[] serializeColumn(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        int minSectionY = level.getMinSection();
        LevelChunkSection[] sections = chunk.getSections();
        LevelLightEngine lightEngine = level.getLightEngine();
        LayerLightEventListener blockLightListener = lightEngine.getLayerListener(LightLayer.BLOCK);

        ArrayList<SectionInfo> includedSections = new ArrayList<>(sections.length);
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section != null) {
                int sectionY = minSectionY + i;
                SectionPos sectionPos = SectionPos.of(cx, sectionY, cz);
                DataLayer blLayer = blockLightListener.getDataLayerData(sectionPos);
                boolean hasBlockLight = (blLayer != null && hasNonZeroData(blLayer));

                if (!section.hasOnlyAir() || hasBlockLight) {
                    includedSections.add(new SectionInfo(i, sectionY, sectionPos, blLayer, hasBlockLight));
                }
            }
        }

        if (includedSections.isEmpty()) {
            return null;
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(sections.length * 1024));
        try {
            buf.writeVarInt(includedSections.size());
            LayerLightEventListener skyLightListener = lightEngine.getLayerListener(LightLayer.SKY);

            for (SectionInfo info : includedSections) {
                LevelChunkSection section = sections[info.index];

                buf.writeByte(info.sectionY);
                // Call standard write method which writes non-empty block count, states, and biomes
                section.write(buf);

                buf.writeBoolean(info.hasBlockLight);
                if (info.hasBlockLight) {
                    buf.writeBytes(info.blLayer.getData());
                }

                DataLayer slLayer = skyLightListener.getDataLayerData(info.sectionPos);
                boolean hasSkyLight = (slLayer != null && hasNonZeroData(slLayer));
                buf.writeBoolean(hasSkyLight);
                if (hasSkyLight) {
                    buf.writeBytes(slLayer.getData());
                }
            }

            byte[] serialized = new byte[buf.readableBytes()];
            buf.readBytes(serialized);
            return serialized;
        } finally {
            buf.release();
        }
    }

    private static boolean hasNonZeroData(DataLayer layer) {
        byte[] data = layer.getData();
        for (byte b : data) {
            if (b != 0) return true;
        }
        return false;
    }
}
