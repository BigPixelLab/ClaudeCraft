package k4k.claudecraft.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.util.Util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps singleplayer running while MCP tools work, even though the user is in another window.
 * <p>
 * A paused integrated server neither ticks nor sends world updates, so commands would have no visible effect
 * and screenshots would show a stale world. While a lease is held, the pause menu is closed and the vanilla
 * pause-on-lost-focus is suppressed. Once the last lease is released (plus a short grace period for the server
 * to send its updates), vanilla takes over again: it re-pauses the game only if the window is still unfocused,
 * so a user who came back to the game keeps playing.
 */
public final class PauseControl {
	/** Lets the server tick a few times after the work is done so clients receive its results. */
	private static final long GRACE_MILLIS = 500;
	private static final long UNPAUSE_TIMEOUT_MILLIS = 2000;
	/** Roughly one server tick, so the integrated server notices the client is no longer paused. */
	private static final long SERVER_TICK_MILLIS = 60;

	private static final AtomicInteger LEASES = new AtomicInteger();
	private static volatile long graceUntil;

	private PauseControl() {
	}

	/**
	 * Unpauses the game for the duration of a tool call. Call from an MCP worker thread, never the render thread.
	 *
	 * @return a lease to close when the work is done
	 */
	public static AutoCloseable acquire() throws InterruptedException {
		LEASES.incrementAndGet();
		MinecraftClient client = MinecraftClient.getInstance();
		if (!client.isPaused()) {
			return PauseControl::release;
		}
		client.execute(() -> {
			if (client.currentScreen instanceof GameMenuScreen) {
				client.setScreen(null);
			}
		});
		long deadline = Util.getMeasuringTimeMs() + UNPAUSE_TIMEOUT_MILLIS;
		while (client.isPaused()) {
			if (Util.getMeasuringTimeMs() > deadline) {
				// Some other pausing screen is open (options, a mod's menu...): don't close what the user is using
				return PauseControl::release;
			}
			TimeUnit.MILLISECONDS.sleep(10);
		}
		TimeUnit.MILLISECONDS.sleep(SERVER_TICK_MILLIS);
		return PauseControl::release;
	}

	/**
	 * @return whether the vanilla pause-on-lost-focus must not trigger right now
	 */
	public static boolean suppressLostFocusPause() {
		return LEASES.get() > 0 || Util.getMeasuringTimeMs() < graceUntil;
	}

	/**
	 * Ends a lease; the game may pause again after the grace period.
	 */
	private static void release() {
		graceUntil = Util.getMeasuringTimeMs() + GRACE_MILLIS;
		LEASES.decrementAndGet();
	}
}
