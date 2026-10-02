package work.benwalker.golftour.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import work.benwalker.golftour.physics.Vec;

/** A broadcast-style chase camera that follows the ball after a full shot. */
public final class BallCam {
	private static Vec3 pos;
	private static long lastNanos;
	private static float yaw, pitch;

	private BallCam() {
	}

	/** True when following another player's shot rather than our own. */
	private static boolean watchingOther;

	/** Whether the camera should be following a ball this frame. */
	public static boolean active() {
		boolean own = ClientGolf.ballCam && ClientGolf.inFlight() && !ClientGolf.lastWasPutt
			&& ClientGolf.ticks - ClientGolf.flightStart >= 4 && ClientGolf.ballEntity() != null;
		boolean other = !own && ClientGolf.ballCam && ClientMatch.watching() && !ClientMatch.watchPutt
			&& ClientGolf.ticks - ClientMatch.watchStart >= 4 && ClientMatch.watchedBall() != null && spectating();
		watchingOther = other;
		if (!own && !other) {
			pos = null;
			return false;
		}
		return true;
	}

	/** Standing still on foot and not lining up our own shot: happy to watch a friend's. */
	private static boolean spectating() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.player.isPassenger() || ClientGolf.addressed()) {
			return false;
		}
		var keys = mc.player.input.keyPresses;
		return !(keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump());
	}

	public static Vec3 position() {
		return pos;
	}

	public static float yaw() {
		return yaw;
	}

	public static float pitch() {
		return pitch;
	}

	/** Updates the chase position for this frame. {@code from} is where the normal camera would be. */
	public static void update(Vec3 from, float partialTick) {
		Entity ball = watchingOther ? ClientMatch.watchedBall() : ClientGolf.ballEntity();
		Vec3 ballPos = ball.getPosition(partialTick);
		var path = watchingOther ? ClientMatch.watchPath : ClientGolf.flightPath;
		Vec start = path.get(0), end = path.get(path.size() - 1);
		Vec dir = end.sub(start).horizontal();
		if (dir.lengthSqr() < 1e-6) {
			dir = Vec.fromYaw(Minecraft.getInstance().player.getYRot());
		}
		dir = dir.normalize();
		Vec3 back = new Vec3(dir.x(), 0, dir.z()).scale(-7.5);
		Vec3 desired = ballPos.add(back).add(0, 2.6, 0);

		long now = System.nanoTime();
		double dt = pos == null ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
		lastNanos = now;
		if (pos == null) {
			pos = from;
		}
		double k = 1 - Math.exp(-dt * 3.5);
		pos = pos.add(desired.subtract(pos).scale(k));

		Vec3 look = ballPos.subtract(pos);
		yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
		pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
	}
}
