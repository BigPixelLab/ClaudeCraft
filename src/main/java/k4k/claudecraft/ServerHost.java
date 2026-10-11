package k4k.claudecraft;

import k4k.claudecraft.mixin.ServerCommandSourceAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Helpers for the player hosting the world: the person who runs the MCP server and decides what agents may do.
 * Server thread only.
 */
public final class ServerHost {
	private ServerHost() {
	}

	/**
	 * @param server running server
	 * @return the hosting player, or {@code null} if they are not in the world
	 */
	@Nullable
	public static ServerPlayerEntity getPlayer(MinecraftServer server) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (server.isHost(player.getGameProfile())) {
				return player;
			}
		}
		return null;
	}

	/**
	 * Checks the command's output rather than its entity: an agent running
	 * {@code execute as <host> run ...} gets the host as entity but keeps its own output.
	 *
	 * @param source command source
	 * @return whether the host player typed (or clicked) this command themselves
	 */
	public static boolean isIssuedByHost(ServerCommandSource source) {
		return ((ServerCommandSourceAccessor) source).getOutput() instanceof ServerPlayerEntity player
			&& source.getServer().isHost(player.getGameProfile());
	}
}
