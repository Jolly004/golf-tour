package work.benwalker.golftour.client;

import net.minecraft.client.Minecraft;

/** Decides which mouse clicks belong to the golf swing instead of vanilla use/attack. */
public final class SwingInput {
	private static final int LEFT = 0;
	private static final int RIGHT = 1;
	private static final int PRESS = 1;
	private static final int RELEASE = 0;

	private SwingInput() {
	}

	/** @return true if the click was consumed by the swing meter */
	public static boolean onMouseButton(long handle, int button, int action) {
		Minecraft mc = Minecraft.getInstance();
		if (handle != mc.getWindow().handle() || mc.gui.screen() != null || !mc.mouseHandler.isMouseGrabbed()) {
			return false;
		}
		if (!ClientGolf.active()) {
			return false;
		}
		if (CinematicCamera.flyoverActive()) {
			if (action == PRESS && (button == LEFT || button == RIGHT)) {
				CinematicCamera.skipFlyover();
			}
			return button == LEFT || button == RIGHT;
		}
		if (ClientGolf.heldClub() == null) {
			return false;
		}
		if (button == RIGHT) {
			if (action == RELEASE) {
				SwingMeter.release();
			} else if (action == PRESS) {
				if (ClientGolf.canSwing()) {
					SwingMeter.begin();
				} else if (ClientGolf.atBall() && !ClientMatch.myTurn()) {
					String who = ClientMatch.turnName();
					ClientGolf.notice(who.isEmpty() ? "WAIT FOR YOUR TURN" : "WAIT · " + who.toUpperCase() + " TO PLAY", 0xFFE066);
				} else if (!ClientGolf.tryAddress() && ClientGolf.atAddress() && !mc.player.isPassenger()) {
					ClientGolf.notice("WALK UP TO YOUR BALL  ·  [" + GolfKeys.GO_TO_BALL.getTranslatedKeyMessage().getString() + "] TO GO THERE", 0xFFE066);
				}
			}
			return !mc.player.isPassenger();
		}
		if (button == LEFT) {
			if (action == PRESS && SwingMeter.phase() != SwingMeter.Phase.IDLE) {
				SwingMeter.cancel();
			}
			return ClientGolf.addressed();
		}
		return false;
	}
}
