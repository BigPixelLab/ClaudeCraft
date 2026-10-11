package k4k.claudecraft.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import k4k.claudecraft.client.PauseControl;
import k4k.claudecraft.client.ScreenshotService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the frame for agent screenshots and keeps singleplayer from pausing while MCP tools work.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow
	@Final
	MinecraftClient client;

	/**
	 * Starts a queued screenshot before the frame is rendered.
	 */
	@Inject(method = "render", at = @At("HEAD"))
	private void claudecraft$onFrameStart(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
		ScreenshotService.onFrameStart(client);
	}

	/**
	 * Captures the frame after the world (and post effects) are drawn but before HUD and screens.
	 */
	@Inject(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gl/Framebuffer;beginWrite(Z)V", ordinal = 0, shift = At.Shift.AFTER)
	)
	private void claudecraft$onWorldRendered(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
		ScreenshotService.onWorldRendered(client);
	}

	/**
	 * Captures screenshots that include the HUD and open screens.
	 */
	@Inject(method = "render", at = @At("TAIL"))
	private void claudecraft$onFrameEnd(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
		ScreenshotService.onFrameEnd(client);
	}

	/**
	 * Holds off the pause-on-lost-focus while MCP tools need the world running; see {@link PauseControl}.
	 */
	@ModifyExpressionValue(
		method = "render",
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/option/GameOptions;pauseOnLostFocus:Z")
	)
	private boolean claudecraft$keepRunningWithoutFocus(boolean pauseOnLostFocus) {
		return pauseOnLostFocus && !PauseControl.suppressLostFocusPause();
	}

	/**
	 * Applies the field of view requested for a screenshot.
	 */
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void claudecraft$overrideFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
		Double fov = ScreenshotService.getFovOverride();
		if (fov != null) {
			cir.setReturnValue(fov);
		}
	}
}
