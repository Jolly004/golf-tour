package work.benwalker.golftour.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import work.benwalker.golftour.client.ClubAnimator;
import work.benwalker.golftour.item.ClubItem;

/** Replaces the vanilla first-person pose for golf clubs with the animated swing. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class FirstPersonClubMixin {
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void golftour$club(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot,
							   InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
							   SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {
		if (hand == InteractionHand.MAIN_HAND && !state.isScoping && itemStack.getItem() instanceof ClubItem club) {
			poseStack.pushPose();
			ClubAnimator.apply(poseStack, club.club());
			state.mainHandRenderState.submit(poseStack, submitNodeCollector, lightCoords, OverlayTexture.NO_OVERLAY, 0);
			poseStack.popPose();
			ci.cancel();
		}
	}
}
