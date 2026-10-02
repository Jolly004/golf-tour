package work.benwalker.golftour.command;

import java.util.Arrays;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import work.benwalker.golftour.course.CourseLayout;
import work.benwalker.golftour.course.HoleLayout;
import work.benwalker.golftour.game.GolfRound;
import work.benwalker.golftour.game.MatchManager;
import work.benwalker.golftour.game.RoundManager;
import work.benwalker.golftour.physics.Club;
import work.benwalker.golftour.physics.ShotType;
import work.benwalker.golftour.world.GolfDimension;

/**
 * {@code /golf play [hole]}, {@code /golf quit}, {@code /golf scorecard}, {@code /golf unplayable},
 * {@code /golf course}, {@code /golf match [create|join <host>|leave|start [holes]]}, and the operator test command
 * {@code /golf hit <club> <power> [accuracy]}.
 */
public final class GolfCommand {
	private GolfCommand() {
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("golf")
			.then(Commands.literal("play")
				.executes(c -> play(c, 1))
				.then(Commands.argument("hole", IntegerArgumentType.integer(1, 18)).executes(c -> play(c, IntegerArgumentType.getInteger(c, "hole")))))
			.then(Commands.literal("quit").executes(GolfCommand::quit))
			.then(Commands.literal("scorecard").executes(GolfCommand::scorecard))
			.then(Commands.literal("unplayable").executes(c -> {
				RoundManager.unplayable(c.getSource().getPlayerOrException());
				return 1;
			}))
			.then(Commands.literal("course").executes(GolfCommand::course))
			.then(Commands.literal("match")
				.executes(c -> {
					MatchManager.status(c.getSource().getPlayerOrException());
					return 1;
				})
				.then(Commands.literal("create").executes(c -> MatchManager.create(c.getSource().getPlayerOrException()) ? 1 : 0))
				.then(Commands.literal("join")
					.then(Commands.argument("host", EntityArgument.player())
						.executes(c -> MatchManager.join(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "host")) ? 1 : 0)))
				.then(Commands.literal("leave").executes(c -> {
					MatchManager.leave(c.getSource().getPlayerOrException());
					return 1;
				}))
				.then(Commands.literal("start")
					.executes(c -> MatchManager.start(c.getSource().getPlayerOrException(), 18) ? 1 : 0)
					.then(Commands.argument("holes", IntegerArgumentType.integer(1, 18))
						.executes(c -> MatchManager.start(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "holes")) ? 1 : 0))))
			.then(Commands.literal("flyover")
				.then(Commands.literal("on").executes(c -> flyover(c, true)))
				.then(Commands.literal("off").executes(c -> flyover(c, false))))
			.then(Commands.literal("hit")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("club", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Club.values()).map(Club::id), b))
					.then(Commands.argument("power", FloatArgumentType.floatArg(0, 1.1f))
						.executes(c -> hit(c, 0f))
						.then(Commands.argument("accuracy", FloatArgumentType.floatArg(-0.24f, 1f))
							.executes(c -> hit(c, FloatArgumentType.getFloat(c, "accuracy"))))))));
	}

	private static int play(CommandContext<CommandSourceStack> c, int hole) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		return RoundManager.start(player, hole - 1) ? 1 : 0;
	}

	private static int quit(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		RoundManager.quit(player);
		c.getSource().sendSuccess(() -> Component.literal("Thanks for playing!").withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int scorecard(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		GolfRound round = RoundManager.get(player);
		if (round == null) {
			c.getSource().sendFailure(Component.literal("You're not playing a round. Start one with /golf play"));
			return 0;
		}
		RoundManager.sendScorecard(player, round);
		return 1;
	}

	private static int flyover(CommandContext<CommandSourceStack> c, boolean on) throws CommandSyntaxException {
		RoundManager.setFlyovers(c.getSource().getPlayerOrException(), on);
		c.getSource().sendSuccess(() -> Component.literal("Hole flyovers " + (on ? "on" : "off")), false);
		return 1;
	}

	private static int course(CommandContext<CommandSourceStack> c) {
		var level = GolfDimension.level(c.getSource().getServer());
		if (level == null) {
			c.getSource().sendFailure(Component.literal("The golf course dimension is missing."));
			return 0;
		}
		CourseLayout course = GolfDimension.course(level);
		c.getSource().sendSuccess(() -> Component.literal(course.name + ": par " + course.totalPar() + ", " + course.totalYards() + " yards")
			.withStyle(ChatFormatting.GOLD), false);
		for (HoleLayout h : course.holes) {
			c.getSource().sendSuccess(() -> Component.literal("  Hole " + h.number() + "  par " + h.par() + "  " + h.yards() + " yds"), false);
		}
		return 1;
	}

	/** Test helper: plays a swing with the given club and power, aimed where the player is looking. */
	private static int hit(CommandContext<CommandSourceStack> c, float accuracy) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		GolfRound round = RoundManager.get(player);
		if (round == null) {
			c.getSource().sendFailure(Component.literal("Start a round first with /golf play"));
			return 0;
		}
		String id = StringArgumentType.getString(c, "club");
		Club club = Arrays.stream(Club.values()).filter(k -> k.id().equals(id)).findFirst().orElse(null);
		if (club == null) {
			c.getSource().sendFailure(Component.literal("Unknown club " + id));
			return 0;
		}
		float power = FloatArgumentType.getFloat(c, "power");
		RoundManager.hitFromCommand(player, round, club, ShotType.defaultFor(club), power, accuracy, player.getYRot());
		return 1;
	}
}
