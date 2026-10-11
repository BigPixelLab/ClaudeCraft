package k4k.claudecraft.client;

import k4k.claudecraft.ClaudeCraft;
import k4k.claudecraft.item.QuillData;
import k4k.claudecraft.item.QuillItem;
import k4k.claudecraft.note.NoteNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockBox;

/**
 * Screen for writing a note about the quill's selection. Sends only the text: the server takes
 * the agent and the selection from the quill itself.
 */
public class NoteEditScreen extends Screen {
	private static final int MAX_WIDTH = 320;
	private static final int BOX_HEIGHT = 100;
	/** Room under the text box for its character counter. */
	private static final int BUTTONS_OFFSET = 18;

	private final Text target;
	private EditBoxWidget editBox;

	/**
	 * @param agent  agent the note is for
	 * @param target description of the selection
	 */
	private NoteEditScreen(String agent, Text target) {
		super(Text.translatable("claudecraft.note.screen.title", agent));
		this.target = target;
	}

	/**
	 * Opens the editor if the held quill is bound and has a selection, otherwise tells the player what is missing.
	 *
	 * @param player local player
	 */
	public static void open(PlayerEntity player) {
		ItemStack quill = null;
		for (Hand hand : Hand.values()) {
			if (player.getStackInHand(hand).isOf(ClaudeCraft.QUILL)) {
				quill = player.getStackInHand(hand);
				break;
			}
		}
		if (quill == null) {
			return;
		}
		String agent = QuillData.getAgent(quill);
		if (agent == null) {
			player.sendMessage(Text.translatable("claudecraft.quill.need_agent").formatted(Formatting.RED), true);
			return;
		}
		BlockBox area = QuillData.getArea(quill);
		QuillData.SelectedEntity entity = QuillData.getEntity(quill);
		if (area == null && entity == null) {
			player.sendMessage(Text.translatable("claudecraft.quill.need_selection").formatted(Formatting.RED), true);
			return;
		}
		Text target = area != null ? Text.literal(QuillItem.describe(area)) : Text.literal(entity.name());
		MinecraftClient.getInstance().setScreen(new NoteEditScreen(agent, target));
	}

	/**
	 * Lays out the text box and the buttons.
	 */
	@Override
	protected void init() {
		int width = Math.min(MAX_WIDTH, this.width - 40);
		int x = (this.width - width) / 2;
		int top = Math.max(50, (this.height - BOX_HEIGHT - BUTTONS_OFFSET - 20) / 2);
		editBox = new EditBoxWidget(textRenderer, x, top, width, BOX_HEIGHT,
			Text.translatable("claudecraft.note.screen.placeholder"), Text.translatable("claudecraft.note.screen.text"));
		editBox.setMaxLength(NoteNetworking.MAX_TEXT_LENGTH);
		addDrawableChild(editBox);
		setInitialFocus(editBox);
		int buttonWidth = (width - 4) / 2;
		addDrawableChild(ButtonWidget.builder(Text.translatable("claudecraft.note.screen.save"), button -> save())
			.dimensions(x, top + BOX_HEIGHT + BUTTONS_OFFSET, buttonWidth, 20).build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
			.dimensions(x + buttonWidth + 4, top + BOX_HEIGHT + BUTTONS_OFFSET, buttonWidth, 20).build());
	}

	/**
	 * Draws the title and what is being annotated above the text box.
	 */
	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		int top = editBox.getY();
		context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, top - 28, 0xFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer, target, this.width / 2, top - 14, 0xA0A0A0);
		super.render(context, mouseX, mouseY, delta);
	}

	/**
	 * @return {@code false}: the world (and MCP) keep running while the player types
	 */
	@Override
	public boolean shouldPause() {
		return false;
	}

	/**
	 * Sends the text to the server and closes the screen.
	 */
	private void save() {
		String text = editBox.getText().strip();
		if (!text.isEmpty()) {
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeString(text, NoteNetworking.MAX_TEXT_LENGTH);
			ClientPlayNetworking.send(NoteNetworking.CREATE_NOTE, buf);
		}
		close();
	}
}
