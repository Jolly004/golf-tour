package work.benwalker.golftour.registry;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import work.benwalker.golftour.GolfTour;

/** Data saved on the player so a round survives a crash or relog without losing their real inventory. */
public final class GolfAttachments {
	/** The player's own inventory, game mode and position, held while they play a round. */
	public record Stash(List<ItemStack> items, GameType gameMode, ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {
		public static final Codec<Stash> CODEC = RecordCodecBuilder.create(i -> i.group(
			ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(Stash::items),
			GameType.CODEC.fieldOf("game_mode").forGetter(Stash::gameMode),
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Stash::dimension),
			Codec.DOUBLE.fieldOf("x").forGetter(Stash::x),
			Codec.DOUBLE.fieldOf("y").forGetter(Stash::y),
			Codec.DOUBLE.fieldOf("z").forGetter(Stash::z),
			Codec.FLOAT.fieldOf("yaw").forGetter(Stash::yaw),
			Codec.FLOAT.fieldOf("pitch").forGetter(Stash::pitch)
		).apply(i, Stash::new));
	}

	/** Round progress: which hole is next and the card so far (0 = not played). */
	public record Progress(long courseSeed, int hole, List<Integer> scores) {
		public static final Codec<Progress> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.LONG.fieldOf("course_seed").forGetter(Progress::courseSeed),
			Codec.INT.fieldOf("hole").forGetter(Progress::hole),
			Codec.INT.listOf().fieldOf("scores").forGetter(Progress::scores)
		).apply(i, Progress::new));
	}

	public static final AttachmentType<Stash> STASH = AttachmentRegistry.createPersistent(GolfTour.id("stash"), Stash.CODEC);
	public static final AttachmentType<Progress> PROGRESS = AttachmentRegistry.createPersistent(GolfTour.id("progress"), Progress.CODEC);

	private GolfAttachments() {
	}

	public static void init() {
	}
}
