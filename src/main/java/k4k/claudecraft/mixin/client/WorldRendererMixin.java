package k4k.claudecraft.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import k4k.claudecraft.client.ScreenshotService;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets the host player appear in screenshots taken from an agent.
 */
@Mixin(WorldRenderer.class)
abstract class WorldRendererMixin {
	/**
	 * Vanilla skips {@code entity instanceof ClientPlayerEntity} unless the camera is attached to it,
	 * which would make the host invisible from the agent's point of view.
	 */
	@WrapOperation(
		method = "render",
		at = @At(value = "CONSTANT", args = "classValue=net/minecraft/client/network/ClientPlayerEntity")
	)
	private boolean claudecraft$renderLocalPlayerForAgent(Object entity, Operation<Boolean> original) {
		return original.call(entity) && !ScreenshotService.isCapturingFromAgent();
	}
}
