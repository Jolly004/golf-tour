package work.benwalker.golftour.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import work.benwalker.golftour.GolfTour;
import work.benwalker.golftour.physics.Vec;

/**
 * Server to client: everything the HUD and the shot preview need about the player's round.
 *
 * @param state {@link work.benwalker.golftour.game.GolfRound.State} ordinal
 * @param scores strokes per hole, 0 if not played yet
 * @param ball the ball's contact point
 * @param lie {@link work.benwalker.golftour.physics.Surface} ordinal
 * @param windSpeed blocks per second
 * @param windYaw yaw the wind blows towards
 * @param cup cup centre on the green surface
 */
public record RoundStatePayload(boolean active, long courseSeed, int hole, int state, int strokes, int[] scores, int ballEntity, Vec ball,
								int lie, float windSpeed, float windYaw, Vec cup) implements CustomPacketPayload {
	public static final Type<RoundStatePayload> TYPE = new Type<>(GolfTour.id("round_state"));
	public static final StreamCodec<FriendlyByteBuf, RoundStatePayload> CODEC = CustomPacketPayload.codec(RoundStatePayload::write, RoundStatePayload::read);

	public static RoundStatePayload inactive() {
		return new RoundStatePayload(false, 0, 0, 0, 0, new int[18], -1, Vec.ZERO, 0, 0, 0, Vec.ZERO);
	}

	private static RoundStatePayload read(FriendlyByteBuf buf) {
		boolean active = buf.readBoolean();
		long seed = buf.readLong();
		int hole = buf.readVarInt();
		int state = buf.readVarInt();
		int strokes = buf.readVarInt();
		int[] scores = buf.readVarIntArray(18);
		int ballEntity = buf.readVarInt();
		Vec ball = readVec(buf);
		int lie = buf.readVarInt();
		float windSpeed = buf.readFloat();
		float windYaw = buf.readFloat();
		Vec cup = readVec(buf);
		return new RoundStatePayload(active, seed, hole, state, strokes, scores, ballEntity, ball, lie, windSpeed, windYaw, cup);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeBoolean(active);
		buf.writeLong(courseSeed);
		buf.writeVarInt(hole);
		buf.writeVarInt(state);
		buf.writeVarInt(strokes);
		buf.writeVarIntArray(scores);
		buf.writeVarInt(ballEntity);
		writeVec(buf, ball);
		buf.writeVarInt(lie);
		buf.writeFloat(windSpeed);
		buf.writeFloat(windYaw);
		writeVec(buf, cup);
	}

	static Vec readVec(FriendlyByteBuf buf) {
		return new Vec(buf.readDouble(), buf.readDouble(), buf.readDouble());
	}

	static void writeVec(FriendlyByteBuf buf, Vec v) {
		buf.writeDouble(v.x());
		buf.writeDouble(v.y());
		buf.writeDouble(v.z());
	}

	@Override
	public Type<RoundStatePayload> type() {
		return TYPE;
	}
}
