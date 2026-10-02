package work.benwalker.golftour.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import work.benwalker.golftour.game.RoundManager;

public final class GolfNetworking {
	private GolfNetworking() {
	}

	/**
	 * Sends a golf payload to a player. Fake players (automation, tests) have no client, so they're skipped
	 * rather than handed a packet their placeholder connection can't route.
	 */
	public static void send(net.minecraft.server.level.ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
		if (!(player instanceof net.fabricmc.fabric.api.entity.FakePlayer)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(HitBallPayload.TYPE, HitBallPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(GolfActionPayload.TYPE, GolfActionPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RoundStatePayload.TYPE, RoundStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BallFlightPayload.TYPE, BallFlightPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(NoticePayload.TYPE, NoticePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MatchStatePayload.TYPE, MatchStatePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(HitBallPayload.TYPE, (payload, context) -> RoundManager.onHit(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(GolfActionPayload.TYPE, (payload, context) -> RoundManager.onAction(context.player(), payload));
	}
}
