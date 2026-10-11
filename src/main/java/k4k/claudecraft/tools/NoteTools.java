package k4k.claudecraft.tools;

import k4k.claudecraft.mcp.McpHttpServer;
import k4k.claudecraft.mcp.McpTool;
import k4k.claudecraft.mcp.Schema;
import k4k.claudecraft.mcp.ToolCall;
import k4k.claudecraft.mcp.ToolResult;
import k4k.claudecraft.note.Note;
import k4k.claudecraft.note.NoteService;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * Tools for picking up the notes players leave for agents with the quill.
 */
public final class NoteTools {
	private static final String AGENT_DESCRIPTION = "Agent to use instead of the session's attached agent.";

	private NoteTools() {
	}

	/**
	 * Notes reserved by {@code take_notes} and their description.
	 *
	 * @param ids  reserved note ids
	 * @param text description for Claude
	 */
	private record Taken(List<Integer> ids, String text) {
	}

	/**
	 * @param server MCP server to register the tools on
	 */
	public static void register(McpHttpServer server) {
		server.register(new McpTool(
			"peek_notes",
			"Shows how many notes players left for the agent and their first lines, without taking them.",
			Schema.object().string("agent", AGENT_DESCRIPTION, false).build(),
			NoteTools::peek
		));
		server.register(new McpTool(
			"take_notes",
			"Takes all notes players left for the agent with the quill: what they selected (blocks or an entity, "
				+ "with its current state) and what they wrote. Taken notes are removed from the world, so act on them "
				+ "and report the results in the conversation. Use screenshot to look at the place if needed.",
			Schema.object().string("agent", AGENT_DESCRIPTION, false).build(),
			NoteTools::take
		));
	}

	/**
	 * Implements {@code peek_notes}.
	 */
	private static ToolResult peek(ToolCall call) throws Exception {
		String agent = GameAccess.agentName(call);
		MinecraftServer server = GameAccess.server();
		List<Note> notes = GameAccess.onServer(server, () -> NoteService.pending(server, agent));
		if (notes.isEmpty()) {
			return ToolResult.text("No notes for agent '" + agent + "'.");
		}
		StringBuilder text = new StringBuilder().append(notes.size()).append(" note(s) for agent '").append(agent).append("':");
		for (Note note : notes) {
			text.append("\n#").append(note.id()).append(" from ").append(note.authorName()).append(": ")
				.append(note.text().lines().findFirst().orElse(""));
		}
		return ToolResult.text(text.toString());
	}

	/**
	 * Implements {@code take_notes}: notes are reserved now and deleted only once the response has been written.
	 */
	private static ToolResult take(ToolCall call) throws Exception {
		String agent = GameAccess.agentName(call);
		MinecraftServer server = GameAccess.server();
		Taken taken = GameAccess.onServer(server, () -> {
			List<Note> notes = NoteService.reserve(server, agent);
			List<Integer> ids = notes.stream().map(Note::id).toList();
			try {
				StringBuilder out = new StringBuilder().append(notes.size()).append(" note(s) for agent '").append(agent).append("':");
				for (Note note : notes) {
					out.append("\n\n").append(NoteService.describe(server, note));
				}
				return new Taken(ids, out.toString());
			} catch (RuntimeException e) {
				NoteService.release(server, ids);
				throw e;
			}
		});
		List<Integer> ids = taken.ids();
		if (ids.isEmpty()) {
			return ToolResult.text("No notes for agent '" + agent + "'.");
		}
		return ToolResult.text(taken.text()).afterResponse(delivered -> server.execute(() -> {
			if (delivered) {
				NoteService.commit(server, ids);
			} else {
				NoteService.release(server, ids);
			}
		}));
	}
}
