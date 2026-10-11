package k4k.claudecraft.agent;

import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Claude's presence in the world: a floating, intangible entity that commands are executed relative to
 * and that screenshots are taken from. Each agent has a unique name used to address it over MCP.
 */
public class AgentEntity extends Entity {
	/** Height of the agent's eyes above its feet; matches the center of the rendered model. */
	public static final float EYE_HEIGHT = 0.25f;
	private static final String NAME_KEY = "AgentName";

	/** Client-side glide shortest/longest durations, in ticks. */
	private static final int MIN_GLIDE_TICKS = 4;
	private static final int MAX_GLIDE_TICKS = 40;
	private static final int TURN_TICKS = 6;

	@Nullable
	private String agentName;

	private int glideTick;
	private int glideDuration;
	private double glideFromX;
	private double glideFromY;
	private double glideFromZ;
	private float glideFromYaw;
	private float glideFromPitch;
	private double glideToX;
	private double glideToY;
	private double glideToZ;
	private float glideYawDelta;
	private float glideToPitch;

	/**
	 * @param type  the agent entity type
	 * @param world world the agent lives in
	 */
	public AgentEntity(EntityType<? extends AgentEntity> type, World world) {
		super(type, world);
		this.noClip = true;
		this.setNoGravity(true);
		this.setCustomNameVisible(true);
	}

	/**
	 * @return the agent's unique name, or {@code null} until the server assigns one
	 */
	@Nullable
	public String getAgentName() {
		return agentName;
	}

	/**
	 * Sets the agent's name and mirrors it into the visible name tag.
	 *
	 * @param name new unique name
	 */
	public void setAgentName(String name) {
		this.agentName = name;
		this.setCustomName(Text.literal(name));
	}

	/**
	 * Agents carry no synced data besides the vanilla custom name.
	 */
	@Override
	protected void initDataTracker() {
	}

	/**
	 * @param nbt saved entity data
	 */
	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		if (nbt.contains(NAME_KEY, NbtElement.STRING_TYPE)) {
			setAgentName(nbt.getString(NAME_KEY));
		}
		// The base NBT reader resets this to false when /summon data has no CustomNameVisible
		this.setCustomNameVisible(true);
	}

	/**
	 * @param nbt entity data being saved
	 */
	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		if (agentName != null) {
			nbt.putString(NAME_KEY, agentName);
		}
	}

	/**
	 * Keeps the agent registered in {@link AgentIndex} and its chunks loaded.
	 */
	@Override
	public void tick() {
		super.tick();
		if (this.getWorld() instanceof ServerWorld world) {
			AgentService.onAgentTick(this, world);
		} else if (glideDuration > 0) {
			glide();
		}
	}

	/**
	 * Client side: instead of jumping to a new position or rotation, glide there.
	 * Purely visual; the server position (used for commands and screenshots) changes instantly.
	 */
	@Override
	public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int interpolationSteps, boolean interpolate) {
		double distance = Math.sqrt(this.squaredDistanceTo(x, y, z));
		float yawDelta = MathHelper.wrapDegrees(yaw - this.getYaw());
		if (distance < 1.0E-4 && Math.abs(yawDelta) < 0.01f && Math.abs(pitch - this.getPitch()) < 0.01f) {
			return;
		}
		glideFromX = this.getX();
		glideFromY = this.getY();
		glideFromZ = this.getZ();
		glideFromYaw = this.getYaw();
		glideFromPitch = this.getPitch();
		glideToX = x;
		glideToY = y;
		glideToZ = z;
		// Turn the short way round; the yaw may leave the -180..180 range, which rendering handles fine
		glideYawDelta = yawDelta;
		glideToPitch = pitch;
		glideTick = 0;
		glideDuration = distance < 1.0E-4
			? TURN_TICKS
			: MathHelper.clamp((int) Math.round(4 * Math.sqrt(distance)), MIN_GLIDE_TICKS, MAX_GLIDE_TICKS);
	}

	/**
	 * Advances the client-side glide by one tick with ease-in-out timing.
	 */
	private void glide() {
		glideTick++;
		float t = (float) glideTick / glideDuration;
		float eased = t * t * (3 - 2 * t);
		this.setPosition(
			MathHelper.lerp(eased, glideFromX, glideToX),
			MathHelper.lerp(eased, glideFromY, glideToY),
			MathHelper.lerp(eased, glideFromZ, glideToZ)
		);
		this.setYaw(glideFromYaw + glideYawDelta * eased);
		this.setPitch(MathHelper.lerp(eased, glideFromPitch, glideToPitch));
		if (glideTick >= glideDuration) {
			glideDuration = 0;
		}
	}

	/**
	 * Teleports (same world, cross-dimension and {@code requestTeleport}) all end here. An agent teleported into
	 * an unloaded chunk stops ticking, so {@link #tick} could never record its new location; record it right away.
	 */
	@Override
	public void refreshPositionAndAngles(double x, double y, double z, float yaw, float pitch) {
		super.refreshPositionAndAngles(x, y, z, yaw, pitch);
		if (agentName != null && this.getWorld() instanceof ServerWorld world) {
			AgentService.onAgentMoved(this, world);
		}
	}

	/**
	 * Unregisters the agent when it is destroyed for good (killed or discarded), but not when its chunk unloads.
	 *
	 * @param reason why the entity is removed
	 */
	@Override
	public void remove(RemovalReason reason) {
		super.remove(reason);
		if (reason.shouldDestroy() && this.getWorld() instanceof ServerWorld world) {
			AgentService.onAgentDestroyed(this, world.getServer());
		}
	}

	/**
	 * @return {@link #EYE_HEIGHT}: the agent looks out of the center of its model
	 */
	@Override
	protected float getEyeHeight(EntityPose pose, EntityDimensions dimensions) {
		return EYE_HEIGHT;
	}

	/**
	 * Agents cannot be hurt; they are removed with {@code /kill} or the {@code agent_remove} tool.
	 */
	@Override
	public boolean damage(DamageSource source, float amount) {
		return false;
	}

	/**
	 * @return {@code true}, so the crosshair can target the agent to bind a quill to it
	 */
	@Override
	public boolean canHit() {
		return true;
	}

	/**
	 * @return {@code false}, so the agent is never pushed by entities
	 */
	@Override
	public boolean isPushable() {
		return false;
	}

	/**
	 * @return pistons leave agents alone
	 */
	@Override
	public PistonBehavior getPistonBehavior() {
		return PistonBehavior.IGNORE;
	}
}
