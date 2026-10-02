package work.benwalker.golftour.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import work.benwalker.golftour.GolfTour;

/** Client to server: the player completed a swing. */
public record HitBallPayload(int club, int shotType, float power, float accuracy, float shape, float spin, float aimYaw, float puttTarget)
	implements CustomPacketPayload {
	public static final Type<HitBallPayload> TYPE = new Type<>(GolfTour.id("hit_ball"));
	public static final StreamCodec<FriendlyByteBuf, HitBallPayload> CODEC = CustomPacketPayload.codec(HitBallPayload::write, HitBallPayload::read);

	private static HitBallPayload read(FriendlyByteBuf buf) {
		return new HitBallPayload(buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
			buf.readFloat(), buf.readFloat());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(club);
		buf.writeVarInt(shotType);
		buf.writeFloat(power);
		buf.writeFloat(accuracy);
		buf.writeFloat(shape);
		buf.writeFloat(spin);
		buf.writeFloat(aimYaw);
		buf.writeFloat(puttTarget);
	}

	@Override
	public Type<HitBallPayload> type() {
		return TYPE;
	}
}
