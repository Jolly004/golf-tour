package work.benwalker.golftour.item;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import work.benwalker.golftour.physics.Club;

/** A golf club. Swinging is handled by the client swing meter, so using the item itself does nothing. */
public class ClubItem extends Item {
	private final Club club;

	public ClubItem(Club club, Properties properties) {
		super(properties);
		this.club = club;
	}

	public Club club() {
		return club;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		return InteractionResult.FAIL;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		if (club.isPutter()) {
			builder.accept(Component.literal("Look at your target on the green and swing").withStyle(ChatFormatting.GRAY));
		} else {
			builder.accept(Component.literal("Carry " + (int) club.carryYards + " yds").withStyle(ChatFormatting.GREEN));
			builder.accept(Component.literal(String.format("Launch %.0f°  Spin %d rpm", club.launchDegrees, (int) club.spinRpm)).withStyle(ChatFormatting.GRAY));
		}
	}
}
