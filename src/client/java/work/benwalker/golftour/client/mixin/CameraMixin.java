package work.benwalker.golftour.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

import work.benwalker.golftour.client.CinematicCamera;

/** Hands the camera to the hole flyover and the ball chase cam. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	public abstract Vec3 position();

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void golftour$ballCam(float partialTicks, CallbackInfo ci) {
		if (CinematicCamera.update(position(), partialTicks)) {
			setPosition(CinematicCamera.position());
			setRotation(CinematicCamera.yaw(), CinematicCamera.pitch());
			detached = !CinematicCamera.flyoverActive();
		}
	}
}
