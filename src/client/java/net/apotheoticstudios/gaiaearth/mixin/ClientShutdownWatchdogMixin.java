package net.apotheoticstudios.gaiaearth.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.ClientShutdownWatchdog;
import java.time.Duration;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import net.apotheoticstudios.gaiaearth.RealEarth;

@Mixin(ClientShutdownWatchdog.class)
public abstract class ClientShutdownWatchdogMixin {
	@WrapOperation(method = "lambda$startShutdownWatchdog$0",
		at = @At(value = "INVOKE", target = "Ljava/lang/Thread;sleep(Ljava/time/Duration;)V"))
	private static void realearth$allowTimeForWorldSave(Duration delay, Operation<Void> sleep) {
		sleep.call(delay);
		// A large local save can legitimately outlast the 15-second client watchdog.
		if (RealEarth.server() instanceof IntegratedServer server && !server.isRunning() && !server.isShutdown()) {
			RealEarth.LOGGER.info("The single-player server is still shutting down. Giving the save more time.");
			sleep.call(Duration.ofSeconds(45));
		}
	}
}
