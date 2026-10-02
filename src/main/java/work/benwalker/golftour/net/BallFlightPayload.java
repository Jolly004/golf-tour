package work.benwalker.golftour.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import work.benwalker.golftour.GolfTour;
import work.benwalker.golftour.physics.Vec;

/**
 * Server to client: the recorded flight of a shot (ball contact point per tick), for the shot tracer and
 * ball camera, plus the shot's quality label and carry for the HUD. In a match, the other players get each shot
 * too, with {@code player} set to the name of whoever hit it (empty for the receiver's own shot).
 */
public record BallFlightPayload(int ballEntity, List<Vec> path, int landingTick, String quality, int qualityColor, float carryYards, boolean putt,
								String player) implements CustomPacketPayload {
	public static final Type<BallFlightPayload> TYPE = new Type<>(GolfTour.id("ball_flight"));
	public static final StreamCodec<FriendlyByteBuf, BallFlightPayload> CODEC = CustomPacketPayload.codec(BallFlightPayload::write, BallFlightPayload::read);

	private static BallFlightPayload read(FriendlyByteBuf buf) {
		int entity = buf.readVarInt();
		Vec origin = RoundStatePayload.readVec(buf);
		int n = buf.readVarInt();
		List<Vec> path = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			path.add(origin.add(buf.readFloat(), buf.readFloat(), buf.readFloat()));
		}
		return new BallFlightPayload(entity, path, buf.readVarInt(), buf.readUtf(32), buf.readInt(), buf.readFloat(), buf.readBoolean(), buf.readUtf(32));
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(ballEntity);
		Vec origin = path.isEmpty() ? Vec.ZERO : path.get(0);
		RoundStatePayload.writeVec(buf, origin);
		buf.writeVarInt(path.size());
		for (Vec p : path) {
			buf.writeFloat((float) (p.x() - origin.x()));
			buf.writeFloat((float) (p.y() - origin.y()));
			buf.writeFloat((float) (p.z() - origin.z()));
		}
		buf.writeVarInt(landingTick);
		buf.writeUtf(quality, 32);
		buf.writeInt(qualityColor);
		buf.writeFloat(carryYards);
		buf.writeBoolean(putt);
		buf.writeUtf(player, 32);
	}

	/** The same shot as seen by another player in the match. */
	public BallFlightPayload forOthers(String hitter) {
		return new BallFlightPayload(ballEntity, path, landingTick, quality, qualityColor, carryYards, putt, hitter);
	}

	@Override
	public Type<BallFlightPayload> type() {
		return TYPE;
	}
}
