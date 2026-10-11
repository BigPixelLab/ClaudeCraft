package k4k.claudecraft.item;

import net.minecraft.block.BlockState;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The quill: bound to an agent by right-clicking it, then selects blocks or an entity and leaves a note
 * for that agent. Clicks are handled in {@link QuillInteractions}.
 */
public class QuillItem extends Item {
	/**
	 * @param settings item settings
	 */
	public QuillItem(Settings settings) {
		super(settings);
	}

	/**
	 * @return {@code false}: left-clicking selects a corner instead of breaking the block
	 */
	@Override
	public boolean canMine(BlockState state, World world, BlockPos pos, PlayerEntity miner) {
		return false;
	}

	/**
	 * @return {@code true} once bound to an agent, so a ready quill is easy to tell apart
	 */
	@Override
	public boolean hasGlint(ItemStack stack) {
		return QuillData.getAgent(stack) != null;
	}

	/**
	 * Shows the bound agent, the selection and how to use the quill.
	 */
	@Override
	public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
		String agent = QuillData.getAgent(stack);
		tooltip.add(agent == null
			? Text.translatable("claudecraft.quill.tooltip.unbound").formatted(Formatting.RED)
			: Text.translatable("claudecraft.quill.tooltip.agent", agent).formatted(Formatting.GOLD));
		BlockBox area = QuillData.getArea(stack);
		QuillData.SelectedEntity entity = QuillData.getEntity(stack);
		if (area != null) {
			tooltip.add(Text.translatable("claudecraft.quill.tooltip.area", describe(area)).formatted(Formatting.GRAY));
		} else if (entity != null) {
			tooltip.add(Text.translatable("claudecraft.quill.tooltip.entity", entity.name()).formatted(Formatting.GRAY));
		} else {
			tooltip.add(Text.translatable("claudecraft.quill.tooltip.nothing").formatted(Formatting.GRAY));
		}
		for (int i = 1; i <= 4; i++) {
			tooltip.add(Text.translatable("claudecraft.quill.tooltip.help" + i).formatted(Formatting.DARK_GRAY));
		}
	}

	/**
	 * @param area selected blocks
	 * @return "x y z" for a single block or "x y z .. x y z" for an area
	 */
	public static String describe(BlockBox area) {
		String from = area.getMinX() + " " + area.getMinY() + " " + area.getMinZ();
		if (area.getBlockCountX() == 1 && area.getBlockCountY() == 1 && area.getBlockCountZ() == 1) {
			return from;
		}
		return from + " .. " + area.getMaxX() + " " + area.getMaxY() + " " + area.getMaxZ();
	}
}
