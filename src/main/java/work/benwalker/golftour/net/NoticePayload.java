package work.benwalker.golftour.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import work.benwalker.golftour.GolfTour;

/** Server to client: a short golf notice ("Water hazard", "Fairway · 158 yds") shown as a HUD banner. */
public record NoticePayload(String text, int color) implements CustomPacketPayload {
	public static final Type<NoticePayload> TYPE = new Type<>(GolfTour.id("notice"));
	public static final StreamCodec<FriendlyByteBuf, NoticePayload> CODEC = CustomPacketPayload.codec(
		(payload, buf) -> {
			buf.writeUtf(payload.text, 128);
			buf.writeInt(payload.color);
		},
		buf -> new NoticePayload(buf.readUtf(128), buf.readInt()));

	@Override
	public Type<NoticePayload> type() {
		return TYPE;
	}
}
