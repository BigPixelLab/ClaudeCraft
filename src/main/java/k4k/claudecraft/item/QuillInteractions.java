package k4k.claudecraft.item;

import k4k.claudecraft.ClaudeCraft;
import k4k.claudecraft.agent.AgentEntity;
import k4k.claudecraft.note.NoteService;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Quill controls. The client only forwards clicks to the server (or opens the note editor);
 * the server changes the quill's state.
 * <ul>
 *     <li>right-click an agent: bind the quill to it</li>
 *     <li>left / right-click a block: first / second corner of the area</li>
 *     <li>right-click another entity: select it</li>
 *     <li>sneak + right-click: write the note</li>
 *     <li>sneak + left-click a block in a note's area, or a noted entity: delete that note</li>
 * </ul>
 */
public final class QuillInteractions {
	/** Creative mode repeats block attacks while the button is held; ignore repeats this many ticks apart. */
	private static final int ATTACK_REPEAT_TICKS = 6;
	private static final Map<UUID, Long> LAST_ATTACK = new HashMap<>();

	/** Opens the note editor; set by the client entrypoint, a no-op on dedicated servers. */
	private static Consumer<PlayerEntity> editorOpener = player -> {
	};

	private QuillInteractions() {
	}

	/**
	 * @param opener opens the note editor for the local player
	 */
	public static void setEditorOpener(Consumer<PlayerEntity> opener) {
		editorOpener = opener;
	}

	/**
	 * Registers the click handlers. Events fire on both logical sides.
	 */
	public static void register() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (!player.getMainHandStack().isOf(ClaudeCraft.QUILL) || player.isSpectator()) {
				return ActionResult.PASS;
			}
			if (player instanceof ServerPlayerEntity serverPlayer && !isRepeatedAttack(serverPlayer)) {
				if (player.isSneaking()) {
					if (!NoteService.deleteAt(serverPlayer, pos)) {
						player.sendMessage(Text.translatable("claudecraft.note.nothing_here").formatted(Formatting.GRAY), true);
					}
				} else {
					ItemStack quill = player.getMainHandStack();
					QuillData.setFrom(quill, world.getRegistryKey(), pos);
					player.sendMessage(Text.translatable("claudecraft.quill.corner1", pos.toShortString()), true);
				}
			}
			return ActionResult.SUCCESS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			ItemStack quill = player.getStackInHand(hand);
			if (!quill.isOf(ClaudeCraft.QUILL) || player.isSpectator()) {
				return ActionResult.PASS;
			}
			if (player.isSneaking()) {
				if (world.isClient) {
					editorOpener.accept(player);
				}
				return ActionResult.FAIL;
			}
			if (!world.isClient) {
				BlockPos pos = hit.getBlockPos();
				QuillData.setTo(quill, world.getRegistryKey(), pos);
				BlockBox area = QuillData.getArea(quill);
				long volume = (long) area.getBlockCountX() * area.getBlockCountY() * area.getBlockCountZ();
				player.sendMessage(Text.translatable("claudecraft.quill.corner2", pos.toShortString(), volume), true);
			}
			return ActionResult.SUCCESS;
		});

		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack quill = player.getStackInHand(hand);
			if (!quill.isOf(ClaudeCraft.QUILL) || !player.isSneaking() || player.isSpectator()) {
				return TypedActionResult.pass(quill);
			}
			if (world.isClient) {
				editorOpener.accept(player);
			}
			return TypedActionResult.fail(quill);
		});

		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			ItemStack quill = player.getStackInHand(hand);
			if (!quill.isOf(ClaudeCraft.QUILL) || player.isSpectator()) {
				return ActionResult.PASS;
			}
			if (player.isSneaking()) {
				if (world.isClient) {
					editorOpener.accept(player);
				}
				return ActionResult.FAIL;
			}
			if (!world.isClient) {
				select(player, quill, entity);
			}
			return ActionResult.SUCCESS;
		});

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (!player.getMainHandStack().isOf(ClaudeCraft.QUILL) || !player.isSneaking() || player.isSpectator()) {
				return ActionResult.PASS;
			}
			if (player instanceof ServerPlayerEntity serverPlayer && !NoteService.deleteFor(serverPlayer, entity)) {
				player.sendMessage(Text.translatable("claudecraft.note.nothing_here").formatted(Formatting.GRAY), true);
			}
			return ActionResult.SUCCESS;
		});
	}

	/**
	 * Binds the quill to an agent, or selects any other entity.
	 */
	private static void select(PlayerEntity player, ItemStack quill, Entity entity) {
		if (entity instanceof AgentEntity agent && agent.getAgentName() != null) {
			QuillData.setAgent(quill, agent.getAgentName());
			player.sendMessage(Text.translatable("claudecraft.quill.bound", agent.getAgentName()).formatted(Formatting.GOLD), true);
			return;
		}
		String name = entity.getName().getString();
		QuillData.setEntity(quill, entity.getWorld().getRegistryKey(), new QuillData.SelectedEntity(entity.getUuid(), name));
		player.sendMessage(Text.translatable("claudecraft.quill.entity", name), true);
	}

	/**
	 * @return whether the player attacked a block with the quill only a moment ago
	 */
	private static boolean isRepeatedAttack(ServerPlayerEntity player) {
		long now = player.getWorld().getTime();
		Long last = LAST_ATTACK.put(player.getUuid(), now);
		return last != null && now - last >= 0 && now - last < ATTACK_REPEAT_TICKS;
	}
}
