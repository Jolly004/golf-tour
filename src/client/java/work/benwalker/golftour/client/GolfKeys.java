package work.benwalker.golftour.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import work.benwalker.golftour.GolfTour;
import work.benwalker.golftour.physics.Club;

public final class GolfKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(GolfTour.id("golf"));
	public static final KeyMapping SHOT_TYPE = key("shot_type", InputConstants.KEY_G);
	public static final KeyMapping BALL_CAM = key("ball_cam", InputConstants.KEY_B);
	public static final KeyMapping GREEN_READ = key("green_read", InputConstants.KEY_V);
	public static final KeyMapping SCORECARD = key("scorecard", InputConstants.KEY_H);
	public static final KeyMapping CAMERA = key("camera", InputConstants.KEY_C);
	public static final KeyMapping RESET_STRIKE = key("reset_strike", InputConstants.KEY_Z);
	public static final KeyMapping SPIN_UP = key("spin_up", InputConstants.KEY_UP);
	public static final KeyMapping SPIN_DOWN = key("spin_down", InputConstants.KEY_DOWN);
	public static final KeyMapping SHAPE_LEFT = key("shape_left", InputConstants.KEY_LEFT);
	public static final KeyMapping SHAPE_RIGHT = key("shape_right", InputConstants.KEY_RIGHT);
	public static final KeyMapping GO_TO_BALL = key("go_to_ball", InputConstants.KEY_R);
	public static final KeyMapping CALL_BUGGY = key("call_buggy", InputConstants.KEY_J);

	private GolfKeys() {
	}

	private static KeyMapping key(String name, int keyCode) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.golftour." + name, keyCode, CATEGORY));
	}

	public static void init() {
	}

	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		boolean playing = ClientGolf.active();
		while (SHOT_TYPE.consumeClick()) {
			Club club = ClientGolf.heldClub();
			if (playing && club != null && ClientGolf.atAddress()) {
				ClientGolf.shotType = ClientGolf.shotTypeFor(club).next(club);
				ShotPlanner.invalidate();
				overlay(mc, "Shot: " + ClientGolf.shotType.displayName);
			}
		}
		while (BALL_CAM.consumeClick()) {
			if (playing) {
				ClientGolf.ballCam = !ClientGolf.ballCam;
				overlay(mc, "Ball camera " + (ClientGolf.ballCam ? "on" : "off"));
			}
		}
		while (GREEN_READ.consumeClick()) {
			if (playing) {
				ClientGolf.greenRead = !ClientGolf.greenRead;
				overlay(mc, "Green grid " + (ClientGolf.greenRead ? "on" : "off"));
			}
		}
		while (CAMERA.consumeClick()) {
			if (playing) {
				GolferPose.mode = GolferPose.mode == GolferPose.CameraMode.BROADCAST ? GolferPose.CameraMode.FIRST_PERSON : GolferPose.CameraMode.BROADCAST;
				overlay(mc, GolferPose.mode == GolferPose.CameraMode.BROADCAST ? "Broadcast camera" : "First-person camera");
			}
		}
		while (GO_TO_BALL.consumeClick()) {
			if (playing && ClientGolf.atAddress()) {
				ClientGolf.addressOnArrival();
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new work.benwalker.golftour.net.GolfActionPayload(work.benwalker.golftour.net.GolfActionPayload.GO_TO_BALL));
			}
		}
		while (CALL_BUGGY.consumeClick()) {
			if (playing) {
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new work.benwalker.golftour.net.GolfActionPayload(work.benwalker.golftour.net.GolfActionPayload.CALL_BUGGY));
			}
		}
		while (RESET_STRIKE.consumeClick()) {
			ClientGolf.shape = 0;
			ClientGolf.spin = 0;
		}
		if (playing && ClientGolf.atAddress() && SwingMeter.phase() == SwingMeter.Phase.IDLE) {
			ClientGolf.spin = step(ClientGolf.spin, SPIN_DOWN, SPIN_UP);
			ClientGolf.shape = step(ClientGolf.shape, SHAPE_RIGHT, SHAPE_LEFT);
		} else {
			SPIN_UP.consumeClick();
			SPIN_DOWN.consumeClick();
			SHAPE_LEFT.consumeClick();
			SHAPE_RIGHT.consumeClick();
		}
	}

	private static double step(double value, KeyMapping plus, KeyMapping minus) {
		while (plus.consumeClick()) {
			value = Math.min(1, value + 0.25);
		}
		while (minus.consumeClick()) {
			value = Math.max(-1, value - 0.25);
		}
		return value;
	}

	private static void overlay(Minecraft mc, String text) {
		ClientGolf.notice(text.toUpperCase(), 0xFFFFFF);
	}
}
