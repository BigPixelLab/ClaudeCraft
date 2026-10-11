package k4k.claudecraft.mixin.client;

import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the camera's smoothed eye height so screenshots can start at the agent's eye level immediately.
 */
@Mixin(Camera.class)
public interface CameraAccessor {
	/**
	 * @return current eye height offset
	 */
	@Accessor("cameraY")
	float getCameraY();

	/**
	 * @param value new eye height offset
	 */
	@Accessor("cameraY")
	void setCameraY(float value);

	/**
	 * @return eye height offset of the previous tick
	 */
	@Accessor("lastCameraY")
	float getLastCameraY();

	/**
	 * @param value new previous-tick eye height offset
	 */
	@Accessor("lastCameraY")
	void setLastCameraY(float value);
}
