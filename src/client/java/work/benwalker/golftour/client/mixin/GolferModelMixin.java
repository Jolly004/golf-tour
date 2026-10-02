package work.benwalker.golftour.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import work.benwalker.golftour.client.GolferPose;
import work.benwalker.golftour.client.OtherGolfers;

/**
 * Poses a golfer's arms and torso through the swing: hands together over the ball at address, up over the right
 * shoulder at the top, and through to a high finish on the left. The local golfer follows the mouse swing; other
 * players in a match follow {@link OtherGolfers}.
 */
@Mixin(PlayerModel.class)
public abstract class GolferModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void golftour$swing(AvatarRenderState state, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		float s;
		boolean putter;
		if (state.id == mc.player.getId()) {
			if (!(GolferPose.active() || GolferPose.swinging())) {
				return;
			}
			s = (float) GolferPose.swing();
			putter = GolferPose.club() != null && GolferPose.club().isPutter();
		} else {
			OtherGolfers.Pose pose = OtherGolfers.pose(state.id);
			if (pose == null) {
				return;
			}
			s = (float) pose.swing();
			putter = pose.putter();
		}
		HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
		float raise = Math.abs(s);
		float putterScale = putter ? 0.35f : 1f;
		float lift = 2.25f * raise * putterScale;
		float together = 0.34f * (1 - Math.min(1, raise * 1.3f));

		model.rightArm.xRot = -0.42f - lift;
		model.leftArm.xRot = -0.42f - lift;
		model.rightArm.yRot = -0.9f * s * putterScale;
		model.leftArm.yRot = -0.9f * s * putterScale;
		model.rightArm.zRot = -together;
		model.leftArm.zRot = together;
		model.body.yRot = 0.5f * s * putterScale;
		model.rightLeg.xRot = 0;
		model.leftLeg.xRot = 0;
	}
}
