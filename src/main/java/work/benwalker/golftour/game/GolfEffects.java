package work.benwalker.golftour.game;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import work.benwalker.golftour.physics.BallSimulator;
import work.benwalker.golftour.physics.Club;
import work.benwalker.golftour.physics.Surface;
import work.benwalker.golftour.physics.Vec;
import work.benwalker.golftour.registry.GolfSounds;

/** Sounds and particles for strikes, bounces, hazards, holing out and the gallery's reactions. */
public final class GolfEffects {
	private GolfEffects() {
	}

	public static void strike(ServerLevel level, Vec ball, Club club, Surface lie, float yaw) {
		BlockState ground = level.getBlockState(BlockPos.containing(ball.x(), ball.y() - 0.01, ball.z()));
		if (club.isPutter()) {
			sound(level, ball, GolfSounds.CLUB_PUTTER, 0.9f, 1.0f);
			return;
		}
		sound(level, ball, GolfSounds.SWING_WHOOSH, 0.8f, club.category == Club.Category.WEDGE ? 1.1f : 0.95f);
		if (lie == Surface.BUNKER) {
			sound(level, ball, GolfSounds.CLUB_SAND, 1.0f, 1.0f);
			Vec fwd = Vec.fromYaw(yaw);
			particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState()), ball.add(fwd.scale(0.4)).add(0, 0.3, 0), 70, 0.5, 0.4, 0.5, 0.25);
			particles(level, new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState()), ball.add(0, 0.6, 0), 30, 0.7, 0.5, 0.7, 0.05);
			return;
		}
		SoundEvent hit = switch (club.category) {
			case DRIVER, WOOD, HYBRID -> GolfSounds.CLUB_DRIVER;
			case WEDGE -> GolfSounds.CLUB_WEDGE;
			default -> GolfSounds.CLUB_IRON;
		};
		float pitch = club.category == Club.Category.WOOD ? 0.95f : club.category == Club.Category.HYBRID ? 1.05f : 1.0f;
		sound(level, ball, hit, 1.0f, pitch);
		boolean teedUp = lie == Surface.TEE && (club.category == Club.Category.DRIVER || club.category == Club.Category.WOOD);
		if (!teedUp && !ground.isAir()) {
			// Divot: turf flies forward off the club face.
			Vec fwd = Vec.fromYaw(yaw);
			int count = club.category == Club.Category.IRON || club.category == Club.Category.WEDGE ? 22 : 10;
			particles(level, new BlockParticleOption(ParticleTypes.BLOCK, ground), ball.add(fwd.scale(0.5)).add(0, 0.15, 0), count, 0.25, 0.15, 0.25, 0.15);
		} else {
			particles(level, ParticleTypes.POOF, ball.add(0, 0.1, 0), 3, 0.05, 0.02, 0.05, 0.01);
		}
	}

	public static void flightEvent(ServerLevel level, BallSimulator.Event event) {
		Vec p = event.pos();
		switch (event.type()) {
			case BOUNCE -> {
				Surface s = event.surface();
				SoundEvent sound = switch (s) {
					case GREEN, FRINGE -> GolfSounds.LAND_GREEN;
					case BUNKER -> GolfSounds.LAND_SAND;
					case HARD -> GolfSounds.LAND_HARD;
					default -> GolfSounds.LAND_GRASS;
				};
				sound(level, p, sound, 0.9f, 1.0f);
				BlockState ground = level.getBlockState(BlockPos.containing(p.x(), p.y() - 0.01, p.z()));
				if (s == Surface.BUNKER) {
					particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState()), p.add(0, 0.1, 0), 16, 0.15, 0.1, 0.15, 0.08);
				} else if (!ground.isAir()) {
					particles(level, new BlockParticleOption(ParticleTypes.BLOCK, ground), p.add(0, 0.05, 0), 5, 0.1, 0.05, 0.1, 0.05);
				}
			}
			case LEAVES -> {
				sound(level, p, GolfSounds.BALL_TREE, 0.9f, 1.15f);
				particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.defaultBlockState()), p, 14, 0.4, 0.4, 0.4, 0.1);
			}
			case WALL -> sound(level, p, GolfSounds.BALL_TREE, 1.0f, 0.9f);
			case SPLASH -> {
				sound(level, p, GolfSounds.BALL_SPLASH, 1.0f, 1.0f);
				particles(level, ParticleTypes.SPLASH, p.add(0, 0.2, 0), 60, 0.3, 0.2, 0.3, 0.3);
				particles(level, ParticleTypes.BUBBLE, p.add(0, -0.2, 0), 20, 0.25, 0.1, 0.25, 0.05);
			}
			case LIP_OUT -> sound(level, p, GolfSounds.BALL_LIP, 1.0f, 1.0f);
			case HOLED -> sound(level, p, GolfSounds.BALL_CUP, 1.0f, 1.0f);
		}
	}

	/** Confetti and sparkle at the cup; more for better scores. */
	public static void celebrate(ServerLevel level, Vec cup, int toPar) {
		int bursts = toPar <= -2 ? 3 : toPar == -1 ? 2 : 1;
		int[] colors = {0xFFD23F, 0xFF4F4F, 0x4FC3FF, 0x7CFF6B, 0xFFFFFF};
		for (int b = 0; b < bursts; b++) {
			Vec at = cup.add(0, 1.2 + b * 0.6, 0);
			particles(level, ParticleTypes.FIREWORK, at, 25 + 15 * b, 0.3, 0.4, 0.3, 0.12);
			for (int c : colors) {
				particles(level, new DustParticleOptions(c, 1.2f), at.add(0, 0.5, 0), 10, 0.8, 0.8, 0.8, 0.02);
			}
		}
		if (toPar <= -2) {
			particles(level, ParticleTypes.TOTEM_OF_UNDYING, cup.add(0, 1, 0), 80, 0.4, 0.8, 0.4, 0.4);
		}
	}

	/** The gallery reacts around the player (the "crowd" is wherever the golfer is). */
	public static void crowd(ServerPlayer player, SoundEvent sound, float volume) {
		player.level().playSound(null, player.getX(), player.getY() + 1, player.getZ(), sound, SoundSource.AMBIENT, volume,
			0.95f + player.getRandom().nextFloat() * 0.1f);
	}

	public static void sound(ServerLevel level, Vec p, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, p.x(), p.y(), p.z(), sound, SoundSource.PLAYERS, volume, pitch);
	}

	private static void particles(ServerLevel level, ParticleOptions particle, Vec p, int count, double dx, double dy, double dz, double speed) {
		level.sendParticles(particle, true, true, p.x(), p.y(), p.z(), count, dx, dy, dz, speed);
	}
}
