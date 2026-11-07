package net.apotheoticstudios.gaiaearth.world;

import java.util.function.BooleanSupplier;

/**
 * Whether the code running now is working on Earth, for what 1.21.1 doesn't tell: a biome's temperature at a block
 * is asked without its level. Set on the server while an Earth level ticks and while Earth's features are placed; on
 * the client's render thread, Earth when the client is in it.
 */
public final class OnEarth {
	private static final ThreadLocal<Boolean> HERE = new ThreadLocal<>();
	private static volatile Thread clientThread;
	private static volatile BooleanSupplier clientOnEarth = () -> false;

	private OnEarth() {
	}

	public static boolean now() {
		Boolean here = HERE.get();
		if (here != null) return here;
		return Thread.currentThread() == clientThread && clientOnEarth.getAsBoolean();
	}

	public static void enter(boolean earth) {
		HERE.set(earth);
	}

	public static void leave() {
		HERE.remove();
	}

	/** The client's render thread, and whether the client's level is Earth. */
	public static void client(Thread thread, BooleanSupplier onEarth) {
		clientOnEarth = onEarth;
		clientThread = thread;
	}
}
