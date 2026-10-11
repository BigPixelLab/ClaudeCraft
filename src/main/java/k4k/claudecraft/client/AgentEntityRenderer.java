package k4k.claudecraft.client;

import k4k.claudecraft.agent.AgentEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ObserverBlock;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * Renders an agent as a small floating observer whose face points where the agent looks, with its name above.
 */
public class AgentEntityRenderer extends EntityRenderer<AgentEntity> {
	/** Observer facing south has its face on the +Z side, which matches yaw 0. */
	private static final BlockState MODEL = Blocks.OBSERVER.getDefaultState().with(ObserverBlock.FACING, Direction.SOUTH);
	private static final float SCALE = 0.5f;

	private final BlockRenderManager blockRenderManager;

	/**
	 * @param context renderer factory context
	 */
	public AgentEntityRenderer(EntityRendererFactory.Context context) {
		super(context);
		this.blockRenderManager = context.getBlockRenderManager();
		this.shadowRadius = 0.2f;
	}

	/**
	 * Draws the observer model and the name tag.
	 */
	@Override
	public void render(AgentEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		matrices.push();
		float bob = MathHelper.sin((entity.getWorld().getTime() + tickDelta) / 10f) * 0.03f;
		matrices.translate(0, entity.getHeight() / 2 + bob, 0);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-entity.getYaw(tickDelta)));
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(entity.getPitch(tickDelta)));
		matrices.scale(SCALE, SCALE, SCALE);
		matrices.translate(-0.5, -0.5, -0.5);
		blockRenderManager.renderBlockAsEntity(MODEL, matrices, vertexConsumers, light, OverlayTexture.DEFAULT_UV);
		matrices.pop();
		super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
	}

	/**
	 * Hides the agent while a screenshot is taken from inside it.
	 */
	@Override
	public boolean shouldRender(AgentEntity entity, Frustum frustum, double x, double y, double z) {
		return !ScreenshotService.isHidden(entity.getUuid()) && super.shouldRender(entity, frustum, x, y, z);
	}

	/**
	 * @return the block atlas, since the model is a block
	 */
	@Override
	public Identifier getTexture(AgentEntity entity) {
		return SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE;
	}
}
