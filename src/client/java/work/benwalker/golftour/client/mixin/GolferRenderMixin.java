package work.benwalker.golftour.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;

import work.benwalker.golftour.client.GolferPose;
import work.benwalker.golftour.client.OtherGolfers;

/** A golfer squares up to the ball: body faces it, head looks down at it (ours, and friends' in a match). */
@Mixin(AvatarRenderer.class)
public class GolferRenderMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"),
		require = 0)
	private void golftour$stance(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		if (state.id == mc.player.getId()) {
			if (GolferPose.active() || GolferPose.swinging()) {
				state.bodyRot = GolferPose.facingYaw();
				state.yRot = 0f;
				state.xRot = 38f;
			}
			return;
		}
		OtherGolfers.Pose pose = OtherGolfers.pose(state.id);
		if (pose != null) {
			state.bodyRot = pose.facing();
			state.yRot = 0f;
			state.xRot = 38f;
		}
	}
}
