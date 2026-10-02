package work.benwalker.golftour.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import work.benwalker.golftour.course.CourseLayout;
import work.benwalker.golftour.course.Flyover;
import work.benwalker.golftour.course.HoleLayout;
import work.benwalker.golftour.entity.GolfBallEntity;
import work.benwalker.golftour.net.BallFlightPayload;
import work.benwalker.golftour.net.GolfNetworking;
import work.benwalker.golftour.net.GolfActionPayload;
import work.benwalker.golftour.net.HitBallPayload;
import work.benwalker.golftour.net.NoticePayload;
import work.benwalker.golftour.net.RoundStatePayload;
import work.benwalker.golftour.physics.BallSimulator;
import work.benwalker.golftour.physics.Club;
import work.benwalker.golftour.physics.ShotCalculator;
import work.benwalker.golftour.physics.ShotType;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Units;
import work.benwalker.golftour.physics.Vec;
import work.benwalker.golftour.registry.GolfAttachments;
import work.benwalker.golftour.registry.GolfEntities;
import work.benwalker.golftour.registry.GolfItems;
import work.benwalker.golftour.registry.GolfSounds;
import work.benwalker.golftour.world.GolfDimension;
import work.benwalker.golftour.world.LevelBallWorld;

/** Runs every player's round on the server: tee-off, shots, penalties, holing out and the scorecard. */
public final class RoundManager {
	/** A hole is picked up once the player reaches par plus this many strokes. */
	public static final int MAX_OVER_PAR = 5;
	/** Default bag layout: the hotbar, then five more clubs in the inventory row above it. */
	public static final Club[] HOTBAR = {Club.DRIVER, Club.WOOD_3, Club.HYBRID_4, Club.IRON_5, Club.IRON_7, Club.IRON_9, Club.PITCHING_WEDGE,
		Club.SAND_WEDGE, Club.PUTTER};
	public static final Club[] BAG = {Club.WOOD_5, Club.IRON_6, Club.IRON_8, Club.GAP_WEDGE, Club.LOB_WEDGE};

	private static final Map<UUID, GolfRound> ROUNDS = new HashMap<>();
	/** Fake players (automation, tests) aren't in the player list, so rounds remember them here. */
	private static final Map<UUID, ServerPlayer> FAKES = new HashMap<>();

	private RoundManager() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(RoundManager::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> onJoin(handler.player)));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onLeave(handler.player));
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> {
			if (GolfDimension.isCourse(from) && !GolfDimension.isCourse(to)) {
				leftCourse(player);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ROUNDS.clear());
	}

	public static GolfRound get(ServerPlayer player) {
		return ROUNDS.get(player.getUUID());
	}

	static GolfRound round(UUID player) {
		return ROUNDS.get(player);
	}

	/** Fake players (automation, tests) aren't in the player list; remember them so matches can reach them. */
	static void rememberIfFake(ServerPlayer player) {
		if (player instanceof net.fabricmc.fabric.api.entity.FakePlayer) {
			FAKES.put(player.getUUID(), player);
		}
	}

	/** An online player by id, including fake players with a round. */
	public static ServerPlayer player(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p != null ? p : FAKES.get(id);
	}

	// ---------------------------------------------------------------- starting and stopping

	/** Starts a new card at {@code holeIndex} (0-based). */
	public static boolean start(ServerPlayer player, int holeIndex) {
		if (MatchManager.of(player) != null) {
			player.sendSystemMessage(Component.literal("You're in a match. Leave it first with /golf match leave").withStyle(ChatFormatting.RED));
			return false;
		}
		return start(player, holeIndex, null, null, 0);
	}

	/** Starts (or resumes) a player's card as part of a match, on its tee spot. */
	static void startInMatch(ServerPlayer player, GolfMatch match, int slot, int holeIndex, int[] keepScores) {
		start(player, holeIndex, keepScores, match, slot);
	}

	private static boolean start(ServerPlayer player, int holeIndex, int[] keepScores, GolfMatch match, int slot) {
		MinecraftServer server = player.level().getServer();
		ServerLevel level = GolfDimension.level(server);
		if (level == null) {
			player.sendSystemMessage(Component.literal("The golf course dimension is missing. Is the Golf Tour data pack enabled?").withStyle(ChatFormatting.RED));
			return false;
		}
		CourseLayout course = GolfDimension.course(level);
		GolfRound old = ROUNDS.remove(player.getUUID());
		if (old != null) {
			discardBall(old);
			work.benwalker.golftour.compat.BuggyCompat.remove(level, old.buggy);
		}
		if (!player.hasAttached(GolfAttachments.STASH)) {
			stash(player);
		}
		equip(player);
		if (!(player instanceof net.fabricmc.fabric.api.entity.FakePlayer)) {
			player.setGameMode(GameType.ADVENTURE); // a fake player isn't in the player list to tell clients about
		}
		player.addEffect(new MobEffectInstance(MobEffects.SATURATION, MobEffectInstance.INFINITE_DURATION, 0, false, false));

		GolfRound round = new GolfRound(player.getUUID(), course);
		round.hole = Math.max(0, Math.min(17, holeIndex));
		round.match = match;
		round.teeSlot = slot;
		if (keepScores != null) {
			System.arraycopy(keepScores, 0, round.scores, 0, 18);
		}
		boolean fake = player instanceof net.fabricmc.fabric.api.entity.FakePlayer;
		if (fake) {
			FAKES.put(player.getUUID(), player);
			round.flyovers = false;
		}
		ROUNDS.put(player.getUUID(), round);
		startHole(player, round, false);
		if (!fake) {
			parkBuggy(level, round);
		}
		return true;
	}

	public static void quit(ServerPlayer player) {
		GolfRound round = ROUNDS.remove(player.getUUID());
		if (round != null) {
			discardBall(round);
			removeBuggy(player, round);
		}
		player.removeAttached(GolfAttachments.PROGRESS);
		player.removeEffect(MobEffects.SATURATION);
		restore(player, true);
		FAKES.remove(player.getUUID());
		MatchManager.onLeave(player.level().getServer(), player);
		GolfNetworking.send(player, RoundStatePayload.inactive());
		GolfNetworking.send(player, work.benwalker.golftour.net.MatchStatePayload.inactive());
	}

	private static void onJoin(ServerPlayer player) {
		GolfAttachments.Progress progress = player.getAttached(GolfAttachments.PROGRESS);
		boolean onCourse = GolfDimension.isCourse(player.level());
		if (onCourse && progress != null && player.hasAttached(GolfAttachments.STASH)) {
			int[] scores = progress.scores().stream().mapToInt(Integer::intValue).toArray();
			if (scores.length == 18) {
				if (MatchManager.rejoin(player, scores)) {
					return;
				}
				player.sendSystemMessage(Component.literal("Resuming your round on hole " + (progress.hole() + 1) + ".").withStyle(ChatFormatting.GREEN));
				start(player, progress.hole(), scores, null, 0);
				return;
			}
		}
		if (player.hasAttached(GolfAttachments.STASH)) {
			player.removeAttached(GolfAttachments.PROGRESS);
			restore(player, onCourse);
		}
	}

	private static void onLeave(ServerPlayer player) {
		MatchManager.onLeave(player.level().getServer(), player);
		GolfRound round = ROUNDS.remove(player.getUUID());
		if (round != null) {
			discardBall(round);
			removeBuggy(player, round);
		}
	}

	private static void leftCourse(ServerPlayer player) {
		GolfRound round = ROUNDS.remove(player.getUUID());
		if (round != null) {
			discardBall(round);
			removeBuggy(player, round);
			player.removeAttached(GolfAttachments.PROGRESS);
			player.removeEffect(MobEffects.SATURATION);
			restore(player, false);
			MatchManager.onLeave(player.level().getServer(), player);
			GolfNetworking.send(player, RoundStatePayload.inactive());
			GolfNetworking.send(player, work.benwalker.golftour.net.MatchStatePayload.inactive());
		}
	}

	private static void stash(ServerPlayer player) {
		Inventory inv = player.getInventory();
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			items.add(inv.getItem(i).copy());
		}
		player.setAttached(GolfAttachments.STASH, new GolfAttachments.Stash(items, player.gameMode(), player.level().dimension(),
			player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
		inv.clearContent();
	}

	private static void restore(ServerPlayer player, boolean teleportBack) {
		GolfAttachments.Stash stash = player.getAttached(GolfAttachments.STASH);
		if (stash == null) {
			return;
		}
		Inventory inv = player.getInventory();
		inv.clearContent();
		for (int i = 0; i < Math.min(inv.getContainerSize(), stash.items().size()); i++) {
			inv.setItem(i, stash.items().get(i));
		}
		player.setGameMode(stash.gameMode());
		player.removeAttached(GolfAttachments.STASH);
		if (teleportBack) {
			ServerLevel home = player.level().getServer().getLevel(stash.dimension());
			if (home != null) {
				player.teleportTo(home, stash.x(), stash.y(), stash.z(), Set.of(), stash.yaw(), stash.pitch(), true);
			}
		}
	}

	private static void equip(ServerPlayer player) {
		Inventory inv = player.getInventory();
		inv.clearContent();
		for (int i = 0; i < HOTBAR.length; i++) {
			inv.setItem(i, GolfItems.club(HOTBAR[i]));
		}
		for (int i = 0; i < BAG.length; i++) {
			inv.setItem(9 + i, GolfItems.club(BAG[i]));
		}
		inv.setSelectedSlot(0);
		player.connection.send(new ClientboundSetHeldSlotPacket(0));
	}

	// ---------------------------------------------------------------- holes

	/**
	 * Sets up the current hole. The first hole of a round puts the player on the tee (after the flyover); for
	 * later holes ({@code walkUp}) the player walks or drives to the tee themselves and the flyover plays when
	 * they get there.
	 */
	private static void startHole(ServerPlayer player, GolfRound round, boolean walkUp) {
		HoleLayout hole = round.currentHole();
		ServerLevel level = GolfDimension.level(player.level().getServer());
		round.strokes = 0;
		round.lie = Surface.TEE;
		round.ballPos = new Vec(Math.floor(hole.tee().x()) + 0.5, hole.teeY() + 1, Math.floor(hole.tee().z()) + 0.5);
		if (round.match != null) {
			// Side by side across the tee box, one ball per player.
			Vec dir = Vec.fromYaw(hole.teeYaw());
			Vec side = new Vec(-dir.z(), 0, dir.x());
			int n = Math.max(1, round.match.members.size());
			round.ballPos = round.ballPos.add(side.scale((round.teeSlot - (n - 1) / 2.0) * 1.6));
		}
		round.lastShotFrom = round.ballPos;
		round.lastLie = Surface.TEE;
		newWind(round);
		player.setAttached(GolfAttachments.PROGRESS, new GolfAttachments.Progress(round.course.seed, round.hole,
			java.util.Arrays.stream(round.scores).boxed().toList()));

		ensureBall(level, round);
		round.flyoverPending = false;
		if (walkUp) {
			round.state = GolfRound.State.ADDRESS;
			round.flyoverPending = round.flyovers;
			notice(player, "HOLE " + hole.number() + " · HEAD TO THE TEE", 0xFFFFFF);
		} else if (round.flyovers) {
			beginFlyover(player, round);
		} else {
			placePlayer(player, level, round, hole.teeYaw());
			round.state = GolfRound.State.ADDRESS;
		}
		sendState(player, round);
		play(player, GolfSounds.UI_HOLE_START, 0.7f, 1.0f);
		title(player, Component.literal("Hole " + hole.number()).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
			Component.literal("Par " + hole.par() + "  ·  " + hole.yards() + " yds").withStyle(ChatFormatting.GREEN), 50);
	}

	private static void nextHole(ServerPlayer player, GolfRound round) {
		if (round.hole >= round.course.holes.size() - 1) {
			finishRound(player, round);
			return;
		}
		round.hole++;
		startHole(player, round, true);
	}

	/** Moves a match player on to the group's next hole (they walk to its tee). */
	static void matchNextHole(ServerPlayer player, GolfRound round, int slot) {
		round.hole++;
		round.teeSlot = slot;
		startHole(player, round, true);
	}

	private static void finishRound(ServerPlayer player, GolfRound round) {
		finishRound(player, round, true);
	}

	/** Ends the card. {@code announce} shows the solo round summary (matches show their own standings). */
	static void finishRound(ServerPlayer player, GolfRound round, boolean announce) {
		round.state = GolfRound.State.FINISHED;
		player.removeAttached(GolfAttachments.PROGRESS);
		sendState(player, round);
		if (!announce) {
			sendScorecard(player, round);
			player.sendSystemMessage(Component.literal("[Rematch]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent.RunCommand("/golf match create")))
				.append(Component.literal("  "))
				.append(Component.literal("[Leave the course]").withStyle(s -> s.withColor(ChatFormatting.YELLOW).withClickEvent(new ClickEvent.RunCommand("/golf quit")))));
			return;
		}
		int total = round.totalStrokes();
		title(player, Component.literal("ROUND COMPLETE").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
			Component.literal("Total " + total + "  (" + formatToPar(round.toPar()) + ")").withStyle(ChatFormatting.WHITE), 100);
		play(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
		GolfEffects.crowd(player, GolfSounds.CROWD_CHEER, 1.0f);
		sendScorecard(player, round);
		player.sendSystemMessage(Component.literal("[Play again]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent.RunCommand("/golf play")))
			.append(Component.literal("  "))
			.append(Component.literal("[Leave the course]").withStyle(s -> s.withColor(ChatFormatting.YELLOW).withClickEvent(new ClickEvent.RunCommand("/golf quit")))));
	}

	private static void newWind(GolfRound round) {
		java.util.Random random = round.match == null ? round.random : new java.util.Random(round.match.windSeed ^ (round.hole * 0x9E3779B97F4A7C15L));
		double mph = 1 + Math.pow(random.nextDouble(), 1.4) * 14;
		round.windSpeed = Units.mphToBlocksPerSecond(mph);
		round.windYaw = random.nextFloat() * 360f;
	}

	private static void gust(GolfRound round) {
		if (round.match != null) {
			return; // everyone in a match plays the same wind
		}
		double mph = Units.blocksPerSecondToMph(round.windSpeed) * (0.9 + round.random.nextDouble() * 0.2);
		round.windSpeed = Units.mphToBlocksPerSecond(Math.max(0, Math.min(18, mph)));
		round.windYaw += (round.random.nextFloat() - 0.5f) * 20f;
	}

	// ---------------------------------------------------------------- shots

	public static void onHit(ServerPlayer player, HitBallPayload msg) {
		GolfRound round = ROUNDS.get(player.getUUID());
		if (round == null || round.state != GolfRound.State.ADDRESS || !GolfDimension.isCourse(player.level())) {
			return;
		}
		Vec ballCenter = round.ballPos.add(0, GolfBallEntity.RADIUS, 0);
		if (player.isPassenger() || player.position().distanceTo(new net.minecraft.world.phys.Vec3(ballCenter.x(), ballCenter.y(), ballCenter.z())) > 6) {
			notice(player, "Walk up to your ball to hit it", 0xFFE066);
			return;
		}
		if (!MatchManager.mayPlay(player, round)) {
			return;
		}
		Club club = Club.byId(msg.club());
		ShotType type = ShotType.byId(msg.shotType());
		ShotCalculator.Swing swing = new ShotCalculator.Swing(club, type, msg.power(), msg.accuracy(), msg.shape(), msg.spin(), msg.aimYaw(), round.lie,
			msg.puttTarget());
		hit(player, round, swing);
	}

	/** Plays a swing for the player (also used by the test command). */
	public static void hit(ServerPlayer player, GolfRound round, ShotCalculator.Swing swing) {
		ServerLevel level = (ServerLevel) player.level();
		ShotCalculator.Result calc = ShotCalculator.compute(swing);
		Vec wind = swing.club().isPutter() ? Vec.ZERO : Vec.fromYaw(round.windYaw).scale(round.windSpeed);
		BallSimulator.Result result = BallSimulator.simulate(new LevelBallWorld(level, round.course), round.ballPos, calc.launch(),
			BallSimulator.Options.shot(wind, round.cup(), round.random.nextLong()));

		round.strokes++;
		round.lastShotFrom = round.ballPos;
		round.lastLie = round.lie;
		round.flight = result;
		round.flightTick = 0;
		round.state = GolfRound.State.FLIGHT;
		ensureBall(level, round);

		Club club = swing.club();
		GolfEffects.strike(level, round.lastShotFrom, club, round.lastLie, swing.aimYaw());

		Vec carriedTo = result.firstLanding() != null ? result.firstLanding() : result.hazardPoint() != null ? result.hazardPoint() : result.rest();
		float carry = (float) Units.toYards(carriedTo.horizontalDistanceTo(round.ballPos));
		int landing = result.flightTicks() >= 0 ? result.flightTicks() : result.path().size() - 1;
		BallFlightPayload flight = new BallFlightPayload(round.ball.getId(), result.path(), landing, calc.quality().label,
			calc.quality().color, club.isPutter() ? 0f : carry, club.isPutter(), "");
		GolfNetworking.send(player, flight);
		if (round.match != null) {
			MatchManager.shareShot(level.getServer(), round.match, player, flight);
		}
		sendState(player, round);
	}

	/** The {@code /golf hit} test command: a swing aimed along {@code yaw}; putts are sized to reach the cup. */
	public static void hitFromCommand(ServerPlayer player, GolfRound round, Club club, ShotType type, double power, double accuracy, float yaw) {
		if (round.state != GolfRound.State.ADDRESS || !MatchManager.mayPlay(player, round)) {
			return;
		}
		double puttTarget = round.ballPos.horizontalDistanceTo(round.cup());
		hit(player, round, new ShotCalculator.Swing(club, type, power, accuracy, 0, 0, yaw, round.lie, puttTarget));
	}

	/**
	 * Practice/test hook: puts the player's ball at rest at (x, z) on the ground there, ready to play.
	 * Only between shots; returns false otherwise.
	 */
	public static boolean placeBall(ServerPlayer player, double x, double z) {
		GolfRound round = ROUNDS.get(player.getUUID());
		if (round == null || round.state != GolfRound.State.ADDRESS) {
			return false;
		}
		ServerLevel level = (ServerLevel) player.level();
		LevelBallWorld world = new LevelBallWorld(level, round.course);
		double y = world.surfaceHeight(x, z);
		if (Double.isNaN(y)) {
			return false;
		}
		round.ballPos = new Vec(x, y, z);
		round.lie = world.surfaceUnder(round.ballPos);
		moveBall(level, round, round.ballPos);
		sendState(player, round);
		return true;
	}

	public static void unplayable(ServerPlayer player) {
		GolfRound round = ROUNDS.get(player.getUUID());
		if (round == null || round.state != GolfRound.State.ADDRESS) {
			return;
		}
		if (round.strokes == 0) {
			player.sendSystemMessage(Component.literal("You haven't hit a shot on this hole yet.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		round.strokes++;
		round.ballPos = round.lastShotFrom;
		round.lie = round.lastLie;
		player.sendSystemMessage(Component.literal("Unplayable: one-stroke penalty, replaying from your last spot.").withStyle(ChatFormatting.YELLOW));
		ServerLevel level = (ServerLevel) player.level();
		moveBall(level, round, round.ballPos);
		sendState(player, round);
	}

	// ---------------------------------------------------------------- ticking

	private static void tick(MinecraftServer server) {
		for (GolfRound round : List.copyOf(ROUNDS.values())) {
			ServerPlayer player = player(server, round.player);
			if (player == null) {
				continue;
			}
			switch (round.state) {
				case FLYOVER -> tickFlyover(player, round);
				case FLIGHT -> tickFlight(player, round);
				case SETTLING, HOLED -> {
					if (--round.timer <= 0 && round.afterTimer != null) {
						Runnable action = round.afterTimer;
						round.afterTimer = null;
						action.run();
					}
				}
				default -> {
					ServerLevel level = GolfDimension.level(server);
					if (level != null && (round.ball == null || round.ball.isRemoved())) {
						ensureBall(level, round);
						sendState(player, round);
					}
					if (round.state == GolfRound.State.ADDRESS && round.flyoverPending && round.strokes == 0
						&& horizontalDistance(player, round.ballPos) <= TEE_ARRIVAL) {
						beginFlyover(player, round);
					}
				}
			}
		}
	}

	/** How close (blocks) the player gets to the tee before its flyover starts. */
	private static final double TEE_ARRIVAL = 12;

	private static double horizontalDistance(ServerPlayer player, Vec p) {
		double dx = player.getX() - p.x(), dz = player.getZ() - p.z();
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static void beginFlyover(ServerPlayer player, GolfRound round) {
		round.flyoverPending = false;
		player.stopRiding();
		round.state = GolfRound.State.FLYOVER;
		round.flyTick = 0;
		player.getAbilities().mayfly = true;
		player.getAbilities().flying = true;
		player.onUpdateAbilities();
		sendState(player, round);
	}

	/** Moves the player along the flyover path so the chunks the camera will see are streamed in. */
	private static void tickFlyover(ServerPlayer player, GolfRound round) {
		ServerLevel level = GolfDimension.level(player.level().getServer());
		round.flyTick++;
		if (round.flyTick >= Flyover.LEAD_IN + Flyover.TICKS || level == null) {
			endFlyover(player, round);
			return;
		}
		// Park the real player high above the middle of the hole: every chunk the camera will see is within
		// view distance, so the whole hole streams in during the lead-in.
		HoleLayout hole = round.currentHole();
		Vec mid = hole.pointAt(hole.lengthBlocks() / 2);
		double y = Math.max(hole.teeY(), hole.greenY()) + 60;
		player.teleportTo(level, mid.x(), y, mid.z(), Set.of(), player.getYRot(), 90f, false);
		player.resetFallDistance();
	}

	private static void endFlyover(ServerPlayer player, GolfRound round) {
		if (round.state != GolfRound.State.FLYOVER) {
			return;
		}
		player.getAbilities().flying = false;
		player.getAbilities().mayfly = false;
		player.onUpdateAbilities();
		player.resetFallDistance();
		ServerLevel level = GolfDimension.level(player.level().getServer());
		placePlayer(player, level, round, round.currentHole().teeYaw());
		round.state = GolfRound.State.ADDRESS;
		sendState(player, round);
	}

	public static void onAction(ServerPlayer player, GolfActionPayload action) {
		GolfRound round = ROUNDS.get(player.getUUID());
		if (round == null || !GolfDimension.isCourse(player.level())) {
			return;
		}
		switch (action.action()) {
			case GolfActionPayload.SKIP_FLYOVER -> endFlyover(player, round);
			case GolfActionPayload.GO_TO_BALL -> goToBall(player, round);
			case GolfActionPayload.CALL_BUGGY -> callBuggy(player, round);
			default -> {
			}
		}
	}

	/** Skips the walk: puts the player at their ball (or on the next tee, which starts its flyover). */
	private static void goToBall(ServerPlayer player, GolfRound round) {
		if (round.state != GolfRound.State.ADDRESS) {
			return;
		}
		if (round.flyoverPending && round.strokes == 0) {
			beginFlyover(player, round);
			return;
		}
		player.stopRiding();
		placePlayer(player, (ServerLevel) player.level(), round, aimYaw(round));
	}

	// ---------------------------------------------------------------- buggy

	/** Parks the player's buggy beside the tee box of the current hole. */
	private static void parkBuggy(ServerLevel level, GolfRound round) {
		if (!work.benwalker.golftour.compat.BuggyCompat.active()) {
			return;
		}
		HoleLayout hole = round.currentHole();
		Vec dir = Vec.fromYaw(hole.teeYaw());
		Vec side = new Vec(-dir.z(), 0, dir.x());
		Vec tee = new Vec(Math.floor(hole.tee().x()) + 0.5, hole.teeY() + 1, Math.floor(hole.tee().z()) + 0.5);
		Vec spot = tee.add(side.scale(HoleLayout.TEE_WIDTH / 2 + 3)).sub(dir.scale(2 + round.teeSlot * 3.5));
		double y = collisionTop(level, null, spot.x(), spot.z(), round.ballPos.y());
		round.buggy = work.benwalker.golftour.compat.BuggyCompat.spawn(level, spot.x(), Double.isNaN(y) ? round.ballPos.y() : y, spot.z(), hole.teeYaw());
	}

	/** Brings the buggy alongside the player (a new one if it's lost). */
	private static void callBuggy(ServerPlayer player, GolfRound round) {
		ServerLevel level = (ServerLevel) player.level();
		if (!work.benwalker.golftour.compat.BuggyCompat.active()) {
			notice(player, "NO BUGGY · INSTALL AUTOMOBILITY TO DRIVE", 0xC0C0C0);
			return;
		}
		if (player.isPassenger()) {
			return;
		}
		Vec look = Vec.fromYaw(player.getYRot());
		Vec right = new Vec(-look.z(), 0, look.x());
		Vec spot = new Vec(player.getX(), player.getY(), player.getZ()).add(right.scale(2.5)).add(look.scale(1));
		double y = collisionTop(level, null, spot.x(), spot.z(), player.getY());
		double sy = Double.isNaN(y) ? player.getY() : y;
		if (!work.benwalker.golftour.compat.BuggyCompat.move(level, round.buggy, spot.x(), sy, spot.z(), player.getYRot())) {
			if (work.benwalker.golftour.compat.BuggyCompat.find(level, round.buggy) != null) {
				return; // someone else is driving it
			}
			round.buggy = work.benwalker.golftour.compat.BuggyCompat.spawn(level, spot.x(), sy, spot.z(), player.getYRot());
		}
		notice(player, "YOUR BUGGY IS HERE", 0xFFE066);
	}

	private static void removeBuggy(ServerPlayer player, GolfRound round) {
		ServerLevel level = GolfDimension.level(player.level().getServer());
		if (level != null) {
			work.benwalker.golftour.compat.BuggyCompat.remove(level, round.buggy);
		}
		round.buggy = null;
	}

	public static void setFlyovers(ServerPlayer player, boolean enabled) {
		GolfRound round = ROUNDS.get(player.getUUID());
		if (round != null) {
			round.flyovers = enabled;
		}
	}

	private static void tickFlight(ServerPlayer player, GolfRound round) {
		ServerLevel level = (ServerLevel) player.level();
		BallSimulator.Result flight = round.flight;
		round.flightTick++;
		int index = Math.min(round.flightTick, flight.path().size() - 1);
		moveBall(level, round, flight.path().get(index));
		for (BallSimulator.Event event : flight.events()) {
			if (event.tick() == round.flightTick) {
				GolfEffects.flightEvent(level, event);
				if (event.type() == BallSimulator.EventType.LIP_OUT) {
					GolfEffects.crowd(player, GolfSounds.CROWD_GROAN, 0.8f);
				}
			}
		}
		if (index >= flight.path().size() - 1) {
			resolve(player, round);
		}
	}

	private static void resolve(ServerPlayer player, GolfRound round) {
		ServerLevel level = (ServerLevel) player.level();
		BallSimulator.Result result = round.flight;
		round.flight = null;
		HoleLayout hole = round.currentHole();

		switch (result.outcome()) {
			case HOLED -> {
				holeOut(player, round);
				return;
			}
			case WATER -> {
				round.strokes++;
				Vec drop = findDrop(level, round, result.hazardPoint() != null ? result.hazardPoint() : result.rest());
				round.ballPos = drop;
				round.lie = new LevelBallWorld(level, round.course).surfaceUnder(drop);
				notice(player, "WATER HAZARD · +1 PENALTY · TAKE A DROP", 0x5FD3FF);
				GolfEffects.crowd(player, GolfSounds.CROWD_GROAN, 0.9f);
			}
			case LOST -> strokeAndDistance(player, round, "LOST BALL · STROKE AND DISTANCE");
			default -> {
				if (round.course.isOutOfBounds(result.rest().x(), result.rest().z())) {
					strokeAndDistance(player, round, "OUT OF BOUNDS · STROKE AND DISTANCE");
				} else {
					round.ballPos = result.rest();
					round.lie = result.restSurface();
					reactToLie(player, round);
				}
			}
		}
		moveBall(level, round, round.ballPos);

		if (round.strokes >= hole.par() + MAX_OVER_PAR) {
			round.scores[round.hole] = hole.par() + MAX_OVER_PAR;
			notice(player, "PICKED UP · MAXIMUM SCORE FOR THE HOLE", 0xC0C0C0);
			round.state = GolfRound.State.HOLED;
			round.timer = 50;
			round.afterTimer = round.match == null ? () -> nextHole(player, round) : null;
			sendState(player, round);
			return;
		}

		round.state = GolfRound.State.SETTLING;
		round.timer = 30;
		round.afterTimer = () -> {
			gust(round);
			round.state = GolfRound.State.ADDRESS;
			sendState(player, round);
			if (round.lie != Surface.GREEN) {
				notice(player, round.lie.displayName.toUpperCase() + "  ·  " + Math.round(Units.toYards(round.ballPos.horizontalDistanceTo(round.cup()))) + " YDS TO THE PIN",
					0xFF000000 | round.lie.color);
			}
		};
		sendState(player, round);
	}

	/** The gallery applauds an approach that finds the green, louder the closer it finishes. */
	private static void reactToLie(ServerPlayer player, GolfRound round) {
		if (round.lie == Surface.GREEN && !round.lastLie.isPuttingSurface()) {
			double feet = Units.toFeet(round.ballPos.horizontalDistanceTo(round.cup()));
			if (feet < 8) {
				GolfEffects.crowd(player, GolfSounds.CROWD_CHEER, 0.9f);
			} else {
				GolfEffects.crowd(player, GolfSounds.CROWD_APPLAUSE, feet < 25 ? 0.9f : 0.5f);
			}
		} else if (round.lastLie == Surface.TEE && round.lie == Surface.FAIRWAY && round.currentHole().par() >= 4) {
			GolfEffects.crowd(player, GolfSounds.CROWD_APPLAUSE, 0.35f);
		}
	}

	private static void strokeAndDistance(ServerPlayer player, GolfRound round, String text) {
		GolfEffects.crowd(player, GolfSounds.CROWD_GROAN, 0.8f);
		round.strokes++;
		round.ballPos = round.lastShotFrom;
		round.lie = round.lastLie;
		notice(player, text, 0xFF6A6A);
	}

	private static void holeOut(ServerPlayer player, GolfRound round) {
		HoleLayout hole = round.currentHole();
		round.scores[round.hole] = round.strokes;
		moveBall((ServerLevel) player.level(), round, round.cup().add(0, -0.25, 0));
		int diff = round.strokes - hole.par();
		ChatFormatting color = diff < 0 ? ChatFormatting.RED : diff == 0 ? ChatFormatting.WHITE : ChatFormatting.AQUA;
		title(player, Component.literal(scoreName(round.strokes, hole.par())).withStyle(color, ChatFormatting.BOLD),
			Component.literal(round.strokes + (round.strokes == 1 ? " stroke" : " strokes") + "  ·  " + formatToPar(round.toPar()))
				.withStyle(ChatFormatting.WHITE), 60);
		boolean longPutt = round.lastLie.isPuttingSurface() && Units.toFeet(round.lastShotFrom.horizontalDistanceTo(round.cup())) > 20;
		if (diff < 0 || longPutt || round.lastLie != Surface.GREEN && round.lastLie != Surface.FRINGE) {
			GolfEffects.crowd(player, GolfSounds.CROWD_CHEER, 1.0f);
			GolfEffects.celebrate((ServerLevel) player.level(), round.cup(), diff);
			if (diff <= -2) {
				play(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
			}
		} else {
			GolfEffects.crowd(player, GolfSounds.CROWD_APPLAUSE, diff == 0 ? 0.8f : 0.45f);
		}
		round.state = GolfRound.State.HOLED;
		round.timer = 80;
		round.afterTimer = round.match == null ? () -> nextHole(player, round) : null;
		sendState(player, round);
	}

	public static String scoreName(int strokes, int par) {
		if (strokes == 1) {
			return "HOLE IN ONE!";
		}
		return switch (strokes - par) {
			case -3 -> "ALBATROSS!";
			case -2 -> "EAGLE!";
			case -1 -> "BIRDIE";
			case 0 -> "PAR";
			case 1 -> "BOGEY";
			case 2 -> "DOUBLE BOGEY";
			case 3 -> "TRIPLE BOGEY";
			default -> (strokes - par > 0 ? "+" : "") + (strokes - par);
		};
	}

	public static String formatToPar(int toPar) {
		return toPar == 0 ? "E" : toPar > 0 ? "+" + toPar : String.valueOf(toPar);
	}

	/** Penalty drop: back along the line of flight to the first dry, playable spot. */
	private static Vec findDrop(ServerLevel level, GolfRound round, Vec hazard) {
		Vec back = round.lastShotFrom.sub(hazard).horizontal();
		double length = back.length();
		Vec dir = length > 1e-6 ? back.scale(1 / length) : Vec.ZERO;
		for (double s = 1; s <= length; s += 0.5) {
			Vec p = hazard.add(dir.scale(s));
			int x = (int) Math.floor(p.x()), z = (int) Math.floor(p.z());
			LevelBallWorld world = new LevelBallWorld(level, round.course);
			double top = groundTop(level, world, x, z);
			if (Double.isNaN(top)) {
				continue;
			}
			Surface surface = world.surfaceUnder(new Vec(x + 0.5, top, z + 0.5));
			if (surface != Surface.BUNKER && surface != Surface.WATER && !round.course.isOutOfBounds(x + 0.5, z + 0.5)) {
				return new Vec(x + 0.5, top, z + 0.5);
			}
		}
		return round.lastShotFrom;
	}

	/**
	 * Top of the ground the ball would rest on in column (x, z), looking through things it passes through
	 * (stakes, flags, grass, lily pads). NaN if the column is water.
	 */
	private static double groundTop(ServerLevel level, LevelBallWorld world, int x, int z) {
		level.getChunk(x >> 4, z >> 4);
		int start = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
		for (int y = start; y > start - 8; y--) {
			work.benwalker.golftour.physics.BallWorld.Cell cell = world.cell(x, y, z);
			if (cell.kind() == work.benwalker.golftour.physics.BallWorld.Kind.WATER) {
				return Double.NaN;
			}
			if (cell.kind() == work.benwalker.golftour.physics.BallWorld.Kind.SOLID) {
				return y + cell.height();
			}
		}
		return start - 1;
	}

	// ---------------------------------------------------------------- helpers

	private static float aimYaw(GolfRound round) {
		Vec toCup = round.cup().sub(round.ballPos);
		if (round.lie == Surface.TEE) {
			HoleLayout hole = round.currentHole();
			return hole.teeYaw();
		}
		return Vec.yawOf(toCup);
	}

	private static void placePlayer(ServerPlayer player, ServerLevel level, GolfRound round, float yaw) {
		Vec dir = Vec.fromYaw(yaw);
		Vec stand = round.ballPos.sub(dir.scale(1.5));
		int x = (int) Math.floor(stand.x()), z = (int) Math.floor(stand.z());
		double y = collisionTop(level, player, stand.x(), stand.z(), round.ballPos.y());
		if (Double.isNaN(y)) {
			y = groundTop(level, new LevelBallWorld(level, round.course), x, z);
		}
		double standY = !Double.isNaN(y) && Math.abs(y - round.ballPos.y()) <= 2 ? y : round.ballPos.y();
		// Look at a sensible target (the pin, or a long drive away) so the crosshair aim starts on it.
		double reach = Math.min(round.ballPos.horizontalDistanceTo(round.cup()), 120) + 1.5;
		double drop = standY + player.getEyeHeight() - (round.lie.isPuttingSurface() ? round.cup().y() : round.ballPos.y());
		float pitch = (float) Math.toDegrees(Math.atan2(drop, reach));
		if (player instanceof net.fabricmc.fabric.api.entity.FakePlayer) {
			player.snapTo(stand.x(), standY, stand.z(), yaw, pitch); // not in the world, so no teleport
			return;
		}
		player.teleportTo(level, stand.x(), standY, stand.z(), Set.of(), yaw, pitch, true);
	}

	/**
	 * Top of what the player actually stands on at (x, z) near height {@code near}: the block collision shapes
	 * under a player-sized footprint, which with NoCubes are the smooth ground rather than the turf blocks.
	 * Standing exactly there keeps the player out of the ground, so the server never has to push them back.
	 */
	private static double collisionTop(ServerLevel level, @org.jspecify.annotations.Nullable Entity player, double x, double z, double near) {
		level.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
		var column = new net.minecraft.world.phys.AABB(x - 0.3, near - 2.5, z - 0.3, x + 0.3, near + 1.5, z + 0.3);
		double top = Double.NaN;
		for (var shape : level.getBlockCollisions(player, column)) {
			if (!shape.isEmpty()) {
				double y = shape.max(net.minecraft.core.Direction.Axis.Y);
				if (y <= near + 1.5 && (Double.isNaN(top) || y > top)) {
					top = y;
				}
			}
		}
		return Double.isNaN(top) ? top : top + 1e-3;
	}

	private static void ensureBall(ServerLevel level, GolfRound round) {
		if (round.ball != null && !round.ball.isRemoved()) {
			return;
		}
		int cx = (int) Math.floor(round.ballPos.x()) >> 4, cz = (int) Math.floor(round.ballPos.z()) >> 4;
		level.getChunk(cx, cz);
		GolfBallEntity ball = new GolfBallEntity(GolfEntities.GOLF_BALL, level);
		ball.setPos(round.ballPos.x(), round.ballPos.y() + GolfBallEntity.RADIUS, round.ballPos.z());
		level.addFreshEntity(ball);
		round.ball = ball;
	}

	private static void moveBall(ServerLevel level, GolfRound round, Vec contact) {
		ensureBall(level, round);
		round.ball.setPos(contact.x(), contact.y() + GolfBallEntity.RADIUS, contact.z());
	}

	private static void discardBall(GolfRound round) {
		if (round.ball != null) {
			round.ball.remove(Entity.RemovalReason.DISCARDED);
			round.ball = null;
		}
	}

	static void sendState(ServerPlayer player, GolfRound round) {
		int ballId = round.ball == null ? -1 : round.ball.getId();
		GolfNetworking.send(player, new RoundStatePayload(true, round.course.seed, round.hole, round.state.ordinal(), round.strokes,
			round.scores.clone(), ballId, round.ballPos, round.lie.ordinal(), (float) round.windSpeed, round.windYaw, round.cup()));
		if (round.match != null) {
			MatchManager.onRoundChanged(player.level().getServer(), round.match);
		}
	}

	static void title(ServerPlayer player, Component title, Component subtitle, int stay) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(5, stay, 15));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	static void notice(ServerPlayer player, String text, int color) {
		GolfNetworking.send(player, new NoticePayload(text, color & 0xFFFFFF));
	}

	private static void play(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
	}

	public static void sendScorecard(ServerPlayer player, GolfRound round) {
		player.sendSystemMessage(Component.literal(round.course.name + " — Scorecard").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		for (int nine = 0; nine < 2; nine++) {
			MutableComponent line = Component.literal(nine == 0 ? "OUT " : "IN  ").withStyle(ChatFormatting.GRAY);
			int strokes = 0, par = 0;
			for (int i = nine * 9; i < nine * 9 + 9; i++) {
				int s = round.scores[i];
				int p = round.course.holes.get(i).par();
				ChatFormatting color = s == 0 ? ChatFormatting.DARK_GRAY : s < p ? ChatFormatting.RED : s == p ? ChatFormatting.WHITE : ChatFormatting.AQUA;
				line.append(Component.literal(" " + (s == 0 ? "-" : s)).withStyle(color));
				if (s > 0) {
					strokes += s;
					par += p;
				}
			}
			line.append(Component.literal("   " + strokes + (par > 0 ? " (" + formatToPar(strokes - par) + ")" : "")).withStyle(ChatFormatting.YELLOW));
			player.sendSystemMessage(line);
		}
		player.sendSystemMessage(Component.literal("Total " + round.totalStrokes() + "  " + formatToPar(round.toPar())).withStyle(ChatFormatting.GOLD));
	}
}
