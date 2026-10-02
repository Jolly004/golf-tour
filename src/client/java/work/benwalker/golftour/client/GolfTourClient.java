package work.benwalker.golftour.client;

import java.util.List;

import net.minecraft.client.renderer.entity.ThrownItemRenderer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import work.benwalker.golftour.GolfTour;
import work.benwalker.golftour.net.BallFlightPayload;
import work.benwalker.golftour.net.NoticePayload;
import work.benwalker.golftour.net.RoundStatePayload;
import work.benwalker.golftour.registry.GolfEntities;

public final class GolfTourClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(GolfEntities.GOLF_BALL, ctx -> new ThrownItemRenderer<>(ctx, 0.45f, true));
		GolfKeys.init();
		HudElementRegistry.addLast(GolfTour.id("hud"), GolfHud::render);
		// Survival bars mean nothing on the course; hide them (and the crosshair during camera moves) while golfing.
		for (var id : List.of(VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.AIR_BAR,
			VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.MOB_EFFECTS, VanillaHudElements.HELD_ITEM_TOOLTIP)) {
			HudElementRegistry.replaceElement(id, original -> (g, delta) -> {
				if (!ClientGolf.active()) {
					original.extractRenderState(g, delta);
				}
			});
		}
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (g, delta) -> {
			if (!CinematicCamera.hideCrosshair()) {
				original.extractRenderState(g, delta);
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(RoundStatePayload.TYPE, (payload, context) -> ClientGolf.onState(payload));
		ClientPlayNetworking.registerGlobalReceiver(BallFlightPayload.TYPE, (payload, context) -> {
			if (payload.player().isEmpty()) {
				ClientGolf.onFlight(payload);
			} else {
				ClientMatch.onOtherFlight(payload);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(work.benwalker.golftour.net.MatchStatePayload.TYPE, (payload, context) -> ClientMatch.onState(payload));
		ClientPlayNetworking.registerGlobalReceiver(NoticePayload.TYPE, (payload, context) -> ClientGolf.notice(payload.text(), payload.color()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientGolf.onState(RoundStatePayload.inactive());
			ClientMatch.reset();
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientGolf.ticks++;
			GolfKeys.tick();
			ClientGolf.tickAddress();
			GolferPose.tick();
			SwingMeter.tick();
			ShotPlanner.tick();
			GolfWorldOverlay.tick();
		});
	}
}
