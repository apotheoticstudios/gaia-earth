// SPDX-License-Identifier: MPL-2.0
// Adapted from Dynamic Waters' SyncVanillaFlowPacket.java. See META-INF/NOTICE-DYNAMIC-WATERS.txt.
package se.itssimple.realearth.hydrology;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import se.itssimple.realearth.RealEarth;

/** A single temporary current zone, sent to clients in the affected level. */
public record CurrentDemoPayload(ResourceKey<Level> dimension, BlockPos surface, byte angle) implements CustomPacketPayload {
	public static final Type<CurrentDemoPayload> TYPE = new Type<>(RealEarth.id("current_demo_v2"));
	public static final StreamCodec<FriendlyByteBuf, CurrentDemoPayload> CODEC =
		StreamCodec.ofMember(CurrentDemoPayload::write, CurrentDemoPayload::new);
	static final CurrentDemoPayload OFF = new CurrentDemoPayload(Level.OVERWORLD, BlockPos.ZERO, CurrentAngle.NONE);

	private CurrentDemoPayload(FriendlyByteBuf buffer) {
		this(buffer.readResourceKey(Registries.DIMENSION), buffer.readBlockPos(), buffer.readByte());
	}

	private void write(FriendlyByteBuf buffer) {
		buffer.writeResourceKey(dimension);
		buffer.writeBlockPos(surface);
		buffer.writeByte(angle);
	}

	public boolean enabled() {
		return CurrentAngle.valid(angle);
	}

	public boolean appliesTo(Level level) {
		return enabled() && level != null && dimension.equals(level.dimension()) && RealEarth.isEarth(level)
			&& !level.isOutsideBuildHeight(surface);
	}

	@Override
	public Type<CurrentDemoPayload> type() {
		return TYPE;
	}
}
