package work.benwalker.golftour.game;

import java.util.Random;
import java.util.UUID;

import work.benwalker.golftour.course.CourseLayout;
import work.benwalker.golftour.course.HoleLayout;
import work.benwalker.golftour.entity.GolfBallEntity;
import work.benwalker.golftour.physics.BallSimulator;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Vec;

/** One player's round in progress (server side). */
public final class GolfRound {
	public enum State {
		/** The pre-hole flyover camera is playing. */
		FLYOVER,
		/** Waiting for the player to swing. */
		ADDRESS,
		/** Ball is moving along its simulated path. */
		FLIGHT,
		/** Ball has stopped; player is about to be moved to it. */
		SETTLING,
		/** Ball is in the hole; next hole coming up. */
		HOLED,
		/** All 18 holes played. */
		FINISHED
	}

	final UUID player;
	final CourseLayout course;
	final Random random;
	final int[] scores = new int[18];
	int hole;
	int strokes;
	State state = State.ADDRESS;
	GolfBallEntity ball;
	Vec ballPos;
	Surface lie = Surface.TEE;
	Vec lastShotFrom;
	Surface lastLie = Surface.TEE;
	/** Blocks per second. */
	double windSpeed;
	/** Yaw the wind blows towards. */
	float windYaw;
	BallSimulator.Result flight;
	int flightTick;
	int timer;
	Runnable afterTimer;
	int flyTick;
	boolean flyovers = true;
	/** The next tee's flyover plays when the player walks (or drives) up to it. */
	boolean flyoverPending;
	/** The player's golf buggy (Automobility), if any. */
	UUID buggy;
	/** The match this round is part of, or null for a solo round. */
	GolfMatch match;
	/** Position in the match (which spot on the tee, where the buggy parks). */
	int teeSlot;

	GolfRound(UUID player, CourseLayout course) {
		this.player = player;
		this.course = course;
		this.random = new Random(player.getLeastSignificantBits() ^ System.nanoTime());
	}

	public State state() {
		return state;
	}

	public int strokes() {
		return strokes;
	}

	/** Strokes on each hole (0 = not played). */
	public int[] scores() {
		return scores.clone();
	}

	/** Where the ball lies (its contact point). */
	public Vec ballPosition() {
		return ballPos;
	}

	public Surface lie() {
		return lie;
	}

	public Vec cupPosition() {
		return cup();
	}

	HoleLayout currentHole() {
		return course.holes.get(hole);
	}

	/** Cup centre on the green surface. */
	Vec cup() {
		HoleLayout h = currentHole();
		return new Vec(h.pin().x(), h.greenY() + 1, h.pin().z());
	}

	int totalStrokes() {
		int total = 0;
		for (int s : scores) {
			total += s;
		}
		return total;
	}

	/** Score against par over the holes completed so far. */
	int toPar() {
		int diff = 0;
		for (int i = 0; i < scores.length; i++) {
			if (scores[i] > 0) {
				diff += scores[i] - course.holes.get(i).par();
			}
		}
		return diff;
	}
}
