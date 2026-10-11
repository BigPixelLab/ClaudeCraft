package k4k.claudecraft.command;

import k4k.claudecraft.ClaudeCraftConfig;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Command output that records every feedback and error message so it can be returned to the MCP client.
 */
public final class CapturingCommandOutput implements CommandOutput {
	private static final TextColor ERROR_COLOR = TextColor.fromFormatting(Formatting.RED);

	private final List<String> messages = new ArrayList<>();
	private int errorCount;

	/**
	 * @return captured messages in the order they were sent; errors are prefixed with "error: "
	 */
	public List<String> getMessages() {
		return messages;
	}

	/**
	 * @return how many of the messages were errors
	 */
	public int getErrorCount() {
		return errorCount;
	}

	/**
	 * Records a message. Many commands report failures through {@code sendError} and return normally,
	 * and the only trace of that is the red root style it applies.
	 *
	 * @param message feedback or error text from a command
	 */
	@Override
	public void sendMessage(Text message) {
		if (ERROR_COLOR.equals(message.getStyle().getColor())) {
			errorCount++;
			messages.add("error: " + message.getString());
		} else {
			messages.add(message.getString());
		}
	}

	/**
	 * @return always {@code true}: we want all feedback
	 */
	@Override
	public boolean shouldReceiveFeedback() {
		return true;
	}

	/**
	 * @return always {@code true}: errors are part of the result
	 */
	@Override
	public boolean shouldTrackOutput() {
		return true;
	}

	/**
	 * @return whether operators also see the feedback in chat
	 */
	@Override
	public boolean shouldBroadcastConsoleToOps() {
		return ClaudeCraftConfig.get().broadcastFeedbackToOps;
	}
}
