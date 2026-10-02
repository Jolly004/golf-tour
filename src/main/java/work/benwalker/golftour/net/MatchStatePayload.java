package work.benwalker.golftour.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import work.benwalker.golftour.GolfTour;
import work.benwalker.golftour.physics.Vec;

/**
 * Server to client: the match the player is in, for the leaderboard, turn banner, other players' ball markers and
 * the group scorecard.
 *
 * @param phase {@link work.benwalker.golftour.game.GolfMatch.Phase} ordinal
 * @param firstHole first hole of the match (0-based)
 * @param lastHole last hole of the match (0-based, inclusive)
 * @param hole hole being played (0-based)
 * @param turn whose shot it is, or null while waiting
 */
public record MatchStatePayload(boolean active, int phase, UUID host, int firstHole, int lastHole, int hole, UUID turn, List<Member> members)
	implements CustomPacketPayload {
	public static final Type<MatchStatePayload> TYPE = new Type<>(GolfTour.id("match_state"));
	public static final StreamCodec<FriendlyByteBuf, MatchStatePayload> CODEC = CustomPacketPayload.codec(MatchStatePayload::write, MatchStatePayload::read);
	private static final UUID NONE = new UUID(0, 0);

	/**
	 * @param state {@link work.benwalker.golftour.game.GolfRound.State} ordinal, or -1 before the match starts
	 * @param scores strokes per hole, 0 if not played yet
	 * @param strokes strokes on the current hole
	 * @param ball the player's ball (contact point)
	 * @param ballEntity entity id of the ball, or -1
	 */
	public record Member(UUID id, String name, int state, int[] scores, int strokes, Vec ball, int ballEntity) {
	}

	public static MatchStatePayload inactive() {
		return new MatchStatePayload(false, 0, NONE, 0, 0, 0, null, List.of());
	}

	private static MatchStatePayload read(FriendlyByteBuf buf) {
		boolean active = buf.readBoolean();
		int phase = buf.readVarInt();
		UUID host = buf.readUUID();
		int first = buf.readVarInt(), last = buf.readVarInt(), hole = buf.readVarInt();
		UUID turn = buf.readUUID();
		int n = buf.readVarInt();
		List<Member> members = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			members.add(new Member(buf.readUUID(), buf.readUtf(32), buf.readVarInt(), buf.readVarIntArray(18), buf.readVarInt(),
				RoundStatePayload.readVec(buf), buf.readVarInt()));
		}
		return new MatchStatePayload(active, phase, host, first, last, hole, turn.equals(NONE) ? null : turn, members);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeBoolean(active);
		buf.writeVarInt(phase);
		buf.writeUUID(host);
		buf.writeVarInt(firstHole);
		buf.writeVarInt(lastHole);
		buf.writeVarInt(hole);
		buf.writeUUID(turn == null ? NONE : turn);
		buf.writeVarInt(members.size());
		for (Member m : members) {
			buf.writeUUID(m.id());
			buf.writeUtf(m.name(), 32);
			buf.writeVarInt(m.state());
			buf.writeVarIntArray(m.scores());
			buf.writeVarInt(m.strokes());
			RoundStatePayload.writeVec(buf, m.ball());
			buf.writeVarInt(m.ballEntity());
		}
	}

	@Override
	public Type<MatchStatePayload> type() {
		return TYPE;
	}
}
