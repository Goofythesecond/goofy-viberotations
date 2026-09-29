package com.goofy.viberotations.npc;

import com.goofy.viberotations.VibeRotations;
import com.goofy.viberotations.engine.EngineParams;
import com.goofy.viberotations.engine.HumanRotationEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.decoration.Mannequin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Singleplayer-only NPC whose head is turned by {@link HumanRotationEngine}.
 *
 * <p>Commands: {@code /npc spawn | moveto <yaw> <pitch> [<yaw> <pitch> ...] | auto start [mixed|short|long] |
 * auto stop | delay <ms> | stop |
 * remove | record on|off | param <name> [value] | params reset}. In moveto, a value starting with
 * {@code ~} is relative to the previous waypoint (or the NPC's current rotation for the first).</p>
 */
public final class NpcController {

	private static Mannequin npc;
	private static HumanRotationEngine engine;
	private static EngineParams params = new EngineParams();
	private static NpcRecorder recorder;
	private static boolean recording = true;
	private static int delayMs = 0;
	private static long seed = System.nanoTime();
	private static NpcAutoRun auto;

	private NpcController() {}

	public static void register() {
		// Only registered for singleplayer (integrated server) worlds. Don't use .requires() with
		// getServer(): it runs while building the client's command list with a source that has no
		// server, and throwing there kicks the joining player.
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
			if (env == Commands.CommandSelection.INTEGRATED) {
				registerCommands(dispatcher);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(NpcController::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> remove());
	}

	/** The live NPC, or null. Read by the client's first-person view in the same JVM. */
	public static Mannequin npc() {
		return npc != null && !npc.isRemoved() ? npc : null;
	}

	// ─── Tick ────────────────────────────────────────────────────────────

	private static void tick(MinecraftServer server) {
		if (npc == null) {
			return;
		}
		if (npc.isRemoved()) {
			remove();
			return;
		}
		double yawBefore = engine.yaw();
		engine.advance(50, recorder == null ? null : recorder::frame);
		float yaw = (float) engine.yaw();
		float pitch = (float) engine.pitch();
		npc.setYRot(yaw);
		npc.setYHeadRot(yaw);
		npc.setXRot(pitch);
		if (recorder != null) {
			recorder.tick(engine, engine.yaw() - yawBefore);
		}
		if (auto != null) {
			auto.tick(engine, recorder);
		}
	}

	// ─── Commands ────────────────────────────────────────────────────────

	private static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("npc")
				.then(Commands.literal("spawn").executes(NpcController::spawn))
				.then(Commands.literal("moveto")
						.then(Commands.argument("waypoints", StringArgumentType.greedyString())
								.executes(NpcController::moveTo)))
				.then(Commands.literal("delay")
						.then(Commands.argument("ms", IntegerArgumentType.integer(0, 60_000))
								.executes(ctx -> {
									delayMs = IntegerArgumentType.getInteger(ctx, "ms");
									return ok(ctx, "Waypoint delay: " + delayMs + " ms");
								})))
				.then(Commands.literal("stop").executes(ctx -> {
					stopAuto(ctx);
					if (engine != null) {
						engine.stop();
					}
					return ok(ctx, "Stopped");
				}))
				.then(Commands.literal("auto")
						.then(Commands.literal("start")
								.executes(ctx -> startAuto(ctx, NpcAutoRun.Mode.MIXED))
								.then(Commands.literal("mixed").executes(ctx -> startAuto(ctx, NpcAutoRun.Mode.MIXED)))
								.then(Commands.literal("short").executes(ctx -> startAuto(ctx, NpcAutoRun.Mode.SHORT)))
								.then(Commands.literal("long").executes(ctx -> startAuto(ctx, NpcAutoRun.Mode.LONG))))
						.then(Commands.literal("stop").executes(ctx -> {
							stopAuto(ctx);
							if (engine != null) {
								engine.stop();
							}
							return 1;
						})))
				.then(Commands.literal("remove").executes(ctx -> {
					remove();
					return ok(ctx, "NPC removed");
				}))
				.then(Commands.literal("record")
						.then(Commands.literal("on").executes(ctx -> setRecording(ctx, true)))
						.then(Commands.literal("off").executes(ctx -> setRecording(ctx, false))))
				.then(Commands.literal("param")
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests((ctx, b) -> {
									EngineParams.names().forEach(b::suggest);
									return b.buildFuture();
								})
								.executes(ctx -> {
									String name = StringArgumentType.getString(ctx, "name");
									try {
										return ok(ctx, name + " = " + params.get(name));
									} catch (IllegalArgumentException e) {
										return fail(ctx, e.getMessage());
									}
								})
								.then(Commands.argument("value", StringArgumentType.word())
										.executes(ctx -> {
											String name = StringArgumentType.getString(ctx, "name");
											try {
												params.set(name, StringArgumentType.getString(ctx, "value"));
											} catch (IllegalArgumentException e) {
												return fail(ctx, e.getMessage());
											}
											return ok(ctx, name + " = " + params.get(name) + " (applies from the next /npc spawn)");
										}))))
				.then(Commands.literal("params")
						.then(Commands.literal("reset").executes(ctx -> {
							params = new EngineParams();
							return ok(ctx, "Parameters reset to defaults (applies from the next /npc spawn)");
						}))));
	}

	private static int spawn(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player;
		try {
			player = ctx.getSource().getPlayerOrException();
		} catch (Exception e) {
			return fail(ctx, "Run this as a player");
		}
		remove();
		ServerLevel level = ctx.getSource().getLevel();
		Mannequin m = EntityType.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
		if (m == null) {
			return fail(ctx, "Could not create the NPC");
		}
		float yaw = player.getYRot();
		float pitch = player.getXRot();
		m.snapTo(player.position(), yaw, pitch);
		m.setYHeadRot(yaw);
		m.setInvulnerable(true);
		m.setSilent(true);
		m.setCustomName(Component.literal("VibeRotations NPC"));
		m.setCustomNameVisible(true);
		level.addFreshEntity(m);
		npc = m;
		seed = System.nanoTime();
		engine = new HumanRotationEngine(params.copy(), seed, yaw, pitch);
		if (recording) {
			startRecorder(ctx.getSource().getServer());
		}
		return ok(ctx, String.format(Locale.ROOT, "NPC spawned facing yaw %.1f pitch %.1f%s", yaw, pitch,
				recorder != null ? " — recording" : ""));
	}

	private static int startAuto(CommandContext<CommandSourceStack> ctx, NpcAutoRun.Mode mode) {
		if (npc() == null) {
			return fail(ctx, "No NPC. Use /npc spawn first.");
		}
		if (recorder == null) {
			recording = true;
			startRecorder(ctx.getSource().getServer());
		}
		auto = new NpcAutoRun(mode, seed ^ System.nanoTime(), delayMs, params.toleranceDeg);
		String gap = delayMs > 0 ? delayMs + " ms" : "150–600 ms";
		return ok(ctx, "Auto flicks (" + mode.name().toLowerCase() + ") started, " + gap
				+ " between flicks. Logging to flicks.csv. /npc auto stop to end.");
	}

	private static void stopAuto(CommandContext<CommandSourceStack> ctx) {
		if (auto != null) {
			ctx.getSource().sendSuccess(() -> Component.literal("Auto flicks stopped after " + auto.count() + " flicks"), false);
			auto = null;
		}
	}

	private static int moveTo(CommandContext<CommandSourceStack> ctx) {
		auto = null;
		if (npc() == null) {
			return fail(ctx, "No NPC. Use /npc spawn first.");
		}
		String[] tok = StringArgumentType.getString(ctx, "waypoints").trim().split("\\s+");
		if (tok.length % 2 != 0) {
			return fail(ctx, "Give yaw/pitch pairs: /npc moveto <yaw> <pitch> [<yaw> <pitch> ...]");
		}
		List<double[]> wps = new ArrayList<>();
		double prevYaw = engine.yaw();
		double prevPitch = engine.pitch();
		try {
			for (int i = 0; i < tok.length; i += 2) {
				double y = parse(tok[i], prevYaw);
				double p = parse(tok[i + 1], prevPitch);
				if (p < -90 || p > 90) {
					return fail(ctx, "Pitch must be between -90 and 90: " + tok[i + 1]);
				}
				wps.add(new double[]{y, p});
				prevYaw = y;
				prevPitch = p;
			}
		} catch (NumberFormatException e) {
			return fail(ctx, "Not a number: " + e.getMessage());
		}
		engine.setWaypoints(wps, delayMs);
		if (recorder != null) {
			recorder.waypoints(wps, delayMs);
		}
		return ok(ctx, wps.size() + " waypoint(s), " + delayMs + " ms delay");
	}

	private static double parse(String s, double relativeTo) {
		if (s.startsWith("~")) {
			return relativeTo + (s.length() == 1 ? 0 : Double.parseDouble(s.substring(1)));
		}
		return Double.parseDouble(s);
	}

	private static int setRecording(CommandContext<CommandSourceStack> ctx, boolean on) {
		recording = on;
		if (on && npc() != null && recorder == null) {
			startRecorder(ctx.getSource().getServer());
		} else if (!on) {
			closeRecorder();
		}
		return ok(ctx, "NPC recording " + (on ? "on" : "off"));
	}

	// ─── Lifecycle ───────────────────────────────────────────────────────

	private static void startRecorder(MinecraftServer server) {
		closeRecorder();
		try {
			recorder = new NpcRecorder(params, seed);
		} catch (Exception e) {
			VibeRotations.LOGGER.error("Failed to start NPC recording", e);
			recorder = null;
		}
	}

	private static void closeRecorder() {
		if (recorder != null) {
			recorder.close();
			recorder = null;
		}
	}

	private static void remove() {
		closeRecorder();
		if (npc != null && !npc.isRemoved()) {
			npc.discard();
		}
		npc = null;
		engine = null;
		auto = null;
	}

	private static int ok(CommandContext<CommandSourceStack> ctx, String msg) {
		ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
		ctx.getSource().sendFailure(Component.literal(msg));
		return 0;
	}
}
