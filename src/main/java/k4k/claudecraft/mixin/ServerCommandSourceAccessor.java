package k4k.claudecraft.mixin;

import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes who actually issued a command: {@code execute as} replaces the source entity but keeps the output.
 */
@Mixin(ServerCommandSource.class)
public interface ServerCommandSourceAccessor {
	/**
	 * @return the output the command's feedback goes to
	 */
	@Accessor("output")
	CommandOutput getOutput();
}
