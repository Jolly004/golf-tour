package work.benwalker.golftour.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;

import work.benwalker.golftour.client.SwingInput;
import work.benwalker.golftour.client.SwingMeter;

/** Routes mouse buttons to the swing, and while swinging, mouse movement to the club instead of the camera. */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	@Shadow
	private double accumulatedDX;
	@Shadow
	private double accumulatedDY;

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void golftour$onButton(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (SwingInput.onMouseButton(handle, info.button(), action)) {
			ci.cancel();
		}
	}

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void golftour$swingStick(double timeDelta, CallbackInfo ci) {
		if (SwingMeter.capturing()) {
			SwingMeter.onMouse(accumulatedDX, accumulatedDY, Minecraft.getInstance().getWindow().getScreenHeight());
			ci.cancel();
		}
	}
}
