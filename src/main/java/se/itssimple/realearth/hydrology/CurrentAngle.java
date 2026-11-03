// SPDX-License-Identifier: MPL-2.0
// Adapted from Dynamic Waters' VanillaRiverFlow.java. See META-INF/NOTICE-DYNAMIC-WATERS.txt.
package se.itssimple.realearth.hydrology;

import net.minecraft.world.phys.Vec3;

/** Quantised world-space direction, measured from east towards south. */
final class CurrentAngle {
	static final byte NONE = (byte) 255;
	// Use a multiple of four so each cardinal heading has an exact slot.
	private static final int STEPS = 252;

	private CurrentAngle() {
	}

	static byte encode(double angle) {
		double a = angle % (Math.PI * 2.0);
		if (a < 0) a += Math.PI * 2.0;
		return (byte) (Math.round(a / (Math.PI * 2.0) * STEPS) % STEPS);
	}

	static Vec3 vector(byte angle) {
		if (!valid(angle)) return Vec3.ZERO;
		double radians = (angle & 0xFF) / (double) STEPS * Math.PI * 2.0;
		return new Vec3(Math.cos(radians), 0, Math.sin(radians));
	}

	static boolean valid(byte angle) {
		return Byte.toUnsignedInt(angle) < STEPS;
	}
}
