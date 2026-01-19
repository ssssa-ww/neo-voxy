package me.cortex.voxy.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Custom packet payload for all Voxy LOD streaming network messages.
 * <p>
 * Message types:
 * <ul>
 * <li>{@link #MSG_MAPPER_SYNC} - Server sends block/biome mapping table on
 * connect</li>
 * <li>{@link #MSG_LOD_SECTION} - Complete section data (small sections)</li>
 * <li>{@link #MSG_LOD_CHUNK} - Chunk of large section (chunked transfer)</li>
 * <li>{@link #MSG_CACHE_QUERY} - Server asks what sections client has</li>
 * <li>{@link #MSG_CACHE_RESPONSE} - Client responds with bloom filter</li>
 * <li>{@link #MSG_RATE_UPDATE} - Client sends desired rate to server</li>
 * <li>{@link #MSG_SYNC_REQUEST} - Client requests LOD sync</li>
 * </ul>
 */
public record VoxyPacketPayload(byte messageType, byte[] data) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("voxy", "lod_sync");
    public static final Type<VoxyPacketPayload> TYPE = new Type<>(ID);

    // Message type constants
    public static final byte MSG_MAPPER_SYNC = 0; // Server→Client: block/biome ID mapping table
    public static final byte MSG_LOD_SECTION = 1; // Server→Client: complete section data
    public static final byte MSG_LOD_CHUNK = 2; // Server→Client: chunk of large section
    public static final byte MSG_CACHE_QUERY = 3; // Server→Client: asks what sections client has
    public static final byte MSG_CACHE_RESPONSE = 4; // Client→Server: bloom filter response
    public static final byte MSG_RATE_UPDATE = 5; // Client→Server: desired rate from congestion control
    public static final byte MSG_SYNC_REQUEST = 6; // Client→Server: request LOD sync
    public static final byte MSG_SYNC_COMPLETE = 7; // Server→Client: signals streaming complete

    @NotNull
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * StreamCodec for encoding/decoding VoxyPacketPayload to/from FriendlyByteBuf.
     */
    public static class Codec implements StreamCodec<FriendlyByteBuf, VoxyPacketPayload> {

        public static final Codec INSTANCE = new Codec();

        @NotNull
        @Override
        public VoxyPacketPayload decode(@NotNull FriendlyByteBuf buf) {
            byte messageType = buf.readByte();
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new VoxyPacketPayload(messageType, data);
        }

        @Override
        public void encode(@NotNull FriendlyByteBuf buf, VoxyPacketPayload payload) {
            buf.writeByte(payload.messageType);
            buf.writeVarInt(payload.data.length);
            buf.writeBytes(payload.data);
        }
    }

    /**
     * Helper to create a section payload.
     */
    public static VoxyPacketPayload section(byte[] sectionData) {
        return new VoxyPacketPayload(MSG_LOD_SECTION, sectionData);
    }

    /**
     * Helper to create a chunk payload for chunked transfer.
     */
    public static VoxyPacketPayload chunk(byte[] chunkData) {
        return new VoxyPacketPayload(MSG_LOD_CHUNK, chunkData);
    }

    /**
     * Helper to create a mapper sync payload.
     */
    public static VoxyPacketPayload mapperSync(byte[] mapperData) {
        return new VoxyPacketPayload(MSG_MAPPER_SYNC, mapperData);
    }

    /**
     * Helper to create a sync request payload.
     */
    public static VoxyPacketPayload syncRequest() {
        return new VoxyPacketPayload(MSG_SYNC_REQUEST, new byte[0]);
    }

    /**
     * Helper to create a rate update payload.
     */
    public static VoxyPacketPayload rateUpdate(int desiredRateKBps) {
        byte[] data = new byte[4];
        data[0] = (byte) (desiredRateKBps >> 24);
        data[1] = (byte) (desiredRateKBps >> 16);
        data[2] = (byte) (desiredRateKBps >> 8);
        data[3] = (byte) desiredRateKBps;
        return new VoxyPacketPayload(MSG_RATE_UPDATE, data);
    }

    /**
     * Parse rate from a rate update payload.
     */
    public int parseRate() {
        if (messageType != MSG_RATE_UPDATE || data.length < 4) {
            return 0;
        }
        return ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) |
                ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
    }
}
