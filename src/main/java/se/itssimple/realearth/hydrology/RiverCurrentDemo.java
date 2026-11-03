package se.itssimple.realearth.hydrology;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import se.itssimple.realearth.RealEarth;

/** Temporary test zone for validating currents before deriving them from mapped waterways. */
public final class RiverCurrentDemo {
	public static final int RADIUS = 16;
	public static final int DEPTH = 4;

	/** Render-region views expose the level that owns their snapshot. */
	public interface RenderView {
		Level realearth$currentLevel();
	}

	private record Zone(Level level, CurrentDemoPayload payload, Vec3 direction) {
		boolean contains(BlockPos pos) {
			BlockPos centre = payload.surface();
			int dx = pos.getX() - centre.getX();
			int dz = pos.getZ() - centre.getZ();
			int dy = centre.getY() - pos.getY();
			return dx >= -RADIUS && dx < RADIUS && dz >= -RADIUS && dz < RADIUS && dy >= 0 && dy < DEPTH;
		}
	}

	private static volatile Zone server;
	private static volatile Zone client;

	private RiverCurrentDemo() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(CurrentDemoPayload.TYPE, CurrentDemoPayload.CODEC);
		ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> send(handler.player));
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> send(player));
	}

	public static LiteralArgumentBuilder<CommandSourceStack> command() {
		return Commands.literal("water")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("test")
				.executes(ctx -> start(ctx.getSource(), 0))
				.then(Commands.argument("degrees", IntegerArgumentType.integer(0, 359))
					.executes(ctx -> start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "degrees")))))
			.then(Commands.literal("off").executes(ctx -> {
				set(ctx.getSource().getLevel(), BlockPos.ZERO, CurrentAngle.NONE);
				ctx.getSource().sendSuccess(() -> Component.literal("Water current test off."), false);
				return 1;
			}));
	}

	private static int start(CommandSourceStack source, int degrees) {
		ServerLevel level = source.getLevel();
		if (!RealEarth.isEarth(level)) {
			source.sendFailure(Component.literal("The water current test runs on Earth."));
			return 0;
		}
		BlockPos surface = BlockPos.containing(source.getPosition());
		if (!level.isLoaded(surface)) {
			source.sendFailure(Component.literal("Run the test from a loaded area."));
			return 0;
		}
		if (!level.getBlockState(surface).is(Blocks.WATER)) surface = surface.below();
		// A swimmer's feet can be below the top water block even at the surface.
		for (int i = 1; i < DEPTH && level.getBlockState(surface).is(Blocks.WATER)
			&& level.getBlockState(surface.above()).is(Blocks.WATER); i++) {
			surface = surface.above();
		}
		if (!level.getBlockState(surface).is(Blocks.WATER) || !level.getFluidState(surface).isSource()
			|| !level.getFluidState(surface.above()).isEmpty()
			|| !level.getBlockState(surface.above()).getCollisionShape(level, surface.above()).isEmpty()) {
			source.sendFailure(Component.literal("Run the test at the surface of still water."));
			return 0;
		}
		set(level, surface, CurrentAngle.encode(Math.toRadians(degrees)));
		source.sendSuccess(() -> Component.literal("Test current enabled within 16 blocks horizontally and 4 blocks deep. "
			+ "Heading: " + degrees + " degrees (0 east, 90 south). Use /earth water off to stop it."), false);
		return 1;
	}

	private static void set(ServerLevel level, BlockPos surface, byte angle) {
		Zone previous = server;
		server = angle == CurrentAngle.NONE ? null : new Zone(level,
			new CurrentDemoPayload(level.dimension(), surface.immutable(), angle), CurrentAngle.vector(angle));
		if (previous != null && previous.level() != level) {
			((ServerLevel) previous.level()).players().forEach(RiverCurrentDemo::send);
		}
		level.players().forEach(RiverCurrentDemo::send);
	}

	private static void send(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, CurrentDemoPayload.TYPE)) return;
		Zone zone = server;
		ServerPlayNetworking.send(player, zone != null && zone.level() == player.level() ? zone.payload() : CurrentDemoPayload.OFF);
	}

	/** Return the old zone's centre so the renderer can rebuild its affected sections. */
	public static BlockPos receive(Level level, CurrentDemoPayload payload) {
		Zone previous = client;
		client = payload != null && payload.appliesTo(level)
			? new Zone(level, payload, CurrentAngle.vector(payload.angle())) : null;
		return previous != null && previous.level() == level ? previous.payload().surface() : null;
	}

	public static Vec3 flowAt(BlockGetter view, BlockPos pos) {
		Zone serverZone = server;
		Zone clientZone = client;
		if (serverZone == null && clientZone == null) return Vec3.ZERO;
		Level level = view instanceof Level l ? l : view instanceof RenderView render ? render.realearth$currentLevel() : null;
		Zone zone = level != null && level.isClientSide() ? clientZone : serverZone;
		return zone != null && zone.level() == level && zone.contains(pos) && view.getBlockState(pos).is(Blocks.WATER)
			? zone.direction() : Vec3.ZERO;
	}
}
