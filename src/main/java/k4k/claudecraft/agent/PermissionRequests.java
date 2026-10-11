package k4k.claudecraft.agent;

import k4k.claudecraft.ClaudeCraftException;
import k4k.claudecraft.ServerHost;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Requests from agents to raise their permission level. The host answers with clickable buttons in chat
 * ({@code /claudecraft permission approve|deny <id>}). Pending requests live only until the world closes.
 * Server thread only.
 */
public final class PermissionRequests {
	private static final Map<Integer, Request> PENDING = new LinkedHashMap<>();
	private static int nextId = 1;

	private PermissionRequests() {
	}

	/**
	 * A pending request.
	 *
	 * @param id        number the host refers to when answering
	 * @param agentName requesting agent
	 * @param level     requested level
	 * @param reason    why the agent needs it, shown to the host
	 * @param decision  completed with {@code true} when approved, {@code false} when denied
	 */
	public record Request(int id, String agentName, int level, String reason, CompletableFuture<Boolean> decision) {
	}

	/**
	 * Creates a request and asks the host in chat.
	 *
	 * @param server running server
	 * @param agent  requesting agent
	 * @param level  requested level, above the agent's current one
	 * @param reason why the agent needs it
	 * @return the request
	 * @throws ClaudeCraftException if the host is not in the world to answer
	 */
	public static Request create(MinecraftServer server, String agent, int level, String reason) {
		ServerPlayerEntity host = ServerHost.getPlayer(server);
		if (host == null) {
			throw new ClaudeCraftException("The host player is not in the world, so nobody can approve the request.");
		}
		Request request = new Request(nextId++, agent, level, reason, new CompletableFuture<>());
		PENDING.put(request.id(), request);
		host.sendMessage(prompt(request, AgentService.getPermissionLevel(server, agent)));
		return request;
	}

	/**
	 * Applies the host's answer.
	 *
	 * @param server  running server
	 * @param id      request id
	 * @param approve whether the host approved it
	 * @return feedback for the host
	 */
	public static Text decide(MinecraftServer server, int id, boolean approve) {
		Request request = PENDING.remove(id);
		if (request == null) {
			return Text.translatable("claudecraft.permission.unknown_request", id).formatted(Formatting.RED);
		}
		if (!approve) {
			request.decision().complete(false);
			return Text.translatable("claudecraft.permission.denied", request.agentName(), request.level());
		}
		try {
			AgentService.setPermissionLevel(server, request.agentName(), request.level());
		} catch (ClaudeCraftException e) {
			request.decision().complete(false);
			return Text.translatable("claudecraft.permission.agent_gone", request.agentName()).formatted(Formatting.RED);
		}
		request.decision().complete(true);
		return Text.translatable("claudecraft.permission.approved", request.agentName(), request.level());
	}

	/**
	 * @param agent agent name, or {@code null} for all agents
	 * @return pending requests, oldest first
	 */
	public static List<Request> pending(@Nullable String agent) {
		List<Request> result = new ArrayList<>();
		for (Request request : PENDING.values()) {
			if (agent == null || request.agentName().equals(agent)) {
				result.add(request);
			}
		}
		return result;
	}

	/**
	 * Denies everything still pending; called when the world closes.
	 */
	public static void clear() {
		PENDING.values().forEach(request -> request.decision().complete(false));
		PENDING.clear();
	}

	/**
	 * @return the chat message asking the host to decide
	 */
	private static Text prompt(Request request, int currentLevel) {
		MutableText approve = Text.translatable("claudecraft.permission.button.approve").styled(style -> style
			.withColor(Formatting.GREEN)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/claudecraft permission approve " + request.id()))
			.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.translatable("claudecraft.permission.button.approve.hover", request.level()))));
		MutableText deny = Text.translatable("claudecraft.permission.button.deny").styled(style -> style
			.withColor(Formatting.RED)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/claudecraft permission deny " + request.id())));
		return Text.translatable("claudecraft.permission.request", request.agentName(), request.level(), currentLevel, request.reason())
			.formatted(Formatting.GOLD)
			.append(" ").append(approve).append(" ").append(deny);
	}
}
