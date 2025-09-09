package com.rehard.securityclient.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Сырой payload для обмена по каналу security:modcheck.
 */
public record RawPluginPayload(byte[] data) implements CustomPayload {
    public static final Id<RawPluginPayload> ID =
            new Id<>(Identifier.of("security", "modcheck"));

    // Правильно реализованный кодек
    public static final PacketCodec<RegistryByteBuf, RawPluginPayload> CODEC =
            new PacketCodec<>() {
                @Override
                public RawPluginPayload decode(RegistryByteBuf buf) {
                    int remaining = buf.readableBytes();
                    byte[] bytes = new byte[remaining];
                    buf.readBytes(bytes);
                    return new RawPluginPayload(bytes);
                }

                // encode принимает сначала буфер, потом значение
                @Override
                public void encode(RegistryByteBuf buf, RawPluginPayload value) {
                    buf.writeBytes(value.data());
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
