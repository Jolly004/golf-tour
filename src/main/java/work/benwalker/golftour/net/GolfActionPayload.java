package work.benwalker.golftour.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import work.benwalker.golftour.GolfTour;

/** Client to server: a simple golf action (skip the flyover, go to the ball, call the buggy). */
public record GolfActionPayload(int action) implements CustomPacketPayload {
	public static final int SKIP_FLYOVER = 0;
	/** Skip the walk: step up to the ball (or the next tee). */
	public static final int GO_TO_BALL = 1;
	/** Bring the golf buggy over. */
	public static final int CALL_BUGGY = 2;

	public static final Type<GolfActionPayload> TYPE = new Type<>(GolfTour.id("action"));
	public static final StreamCodec<FriendlyByteBuf, GolfActionPayload> CODEC = CustomPacketPayload.codec(
		(payload, buf) -> buf.writeVarInt(payload.action), buf -> new GolfActionPayload(buf.readVarInt()));

	@Override
	public Type<GolfActionPayload> type() {
		return TYPE;
	}
}
