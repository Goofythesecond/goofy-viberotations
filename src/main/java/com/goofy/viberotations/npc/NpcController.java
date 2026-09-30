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
 * auto stop | auto burst <count> [gapMs] [restMs] | auto burst off | auto blocks on|off | auto settings |
 * delay <ms> | delay <minMs> <maxMs> | stop | remove | record on|off | param <name> [value] | params reset}. In moveto, a value starting with
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
	private static final AutoSettings autoSettings = new AutoSettings();

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

	/** Replaces the parameters used from the next /npc spawn, e.g. with a trained seed. Call on the server thread. */
	public static void useParams(EngineParams p) {
		params = p.copy();
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
								.executes(ctx -> setDelay(ctx, IntegerArgumentType.getInteger(ctx, "ms"), -1))
								.then(Commands.argument("maxMs", IntegerArgumentType.integer(0, 60_000))
										.executes(ctx -> setDelay(ctx, IntegerArgumentType.getInteger(ctx, "ms"),
												IntegerArgumentType.getInteger(ctx, "maxMs"))))))
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
						}))
						.then(Commands.literal("burst")
								.then(Commands.literal("off").executes(ctx -> {
									autoSettings.burst = 1;
									return ok(ctx, "Auto: " + autoSettings.describe());
								}))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
										.executes(ctx -> setBurst(ctx, -1, -1))
										.then(Commands.argument("gapMs", IntegerArgumentType.integer(0, 10_000))
												.executes(ctx -> setBurst(ctx, IntegerArgumentType.getInteger(ctx, "gapMs"), -1))
												.then(Commands.argument("restMs", IntegerArgumentType.integer(0, 60_000))
														.executes(ctx -> setBurst(ctx, IntegerArgumentType.getInteger(ctx, "gapMs"),
																IntegerArgumentType.getInteger(ctx, "restMs")))))))
						.then(Commands.literal("blocks")
								.then(Commands.literal("on").executes(ctx -> setBlocks(ctx, true)))
								.then(Commands.literal("off").executes(ctx -> setBlocks(ctx, false))))
						.then(Commands.literal("settings").executes(ctx -> ok(ctx, "Auto: " + autoSettings.describe()))))
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
		NpcTargets.removeAll(level);
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
		if (auto != null) {
			auto.stop();
		}
		engine.stop();
		auto = new NpcAutoRun(mode, seed ^ System.nanoTime(), autoSettings, params.toleranceDeg,
				new NpcTargets((ServerLevel) npc.level(), npc));
		return ok(ctx, "Auto flicks (" + mode.name().toLowerCase() + ") started: " + autoSettings.describe()
				+ ". Logging to flicks.csv. /npc auto stop to end.");
	}

	private static void stopAuto(CommandContext<CommandSourceStack> ctx) {
		if (auto != null) {
			auto.stop();
			ctx.getSource().sendSuccess(() -> Component.literal("Auto flicks stopped after " + auto.count() + " flicks"), false);
			auto = null;
		}
	}

	/** {@code /npc delay}: the moveto waypoint delay, and the auto rest (a range if max is given; 0 = default 150–600 ms). */
	private static int setDelay(CommandContext<CommandSourceStack> ctx, int ms, int maxMs) {
		if (maxMs >= 0 && maxMs < ms) {
			return fail(ctx, "Max must be at least min");
		}
		delayMs = ms;
		if (maxMs >= 0) {
			autoSettings.restMinMs = ms;
			autoSettings.restMaxMs = maxMs;
		} else if (ms == 0) {
			autoSettings.restMinMs = AutoSettings.DEFAULT_REST_MIN_MS;
			autoSettings.restMaxMs = AutoSettings.DEFAULT_REST_MAX_MS;
		} else {
			autoSettings.restMinMs = ms;
			autoSettings.restMaxMs = ms;
		}
		return ok(ctx, "Waypoint delay: " + delayMs + " ms. Auto: " + autoSettings.describe());
	}

	private static int setBurst(CommandContext<CommandSourceStack> ctx, int gapMs, int restMs) {
		autoSettings.burst = IntegerArgumentType.getInteger(ctx, "count");
		if (gapMs >= 0) {
			autoSettings.gapMs = gapMs;
		}
		if (restMs >= 0) {
			autoSettings.restMinMs = restMs;
			autoSettings.restMaxMs = restMs;
		}
		return ok(ctx, "Auto: " + autoSettings.describe() + (auto != null ? " (from the next burst)" : ""));
	}

	private static int setBlocks(CommandContext<CommandSourceStack> ctx, boolean on) {
		autoSettings.blocks = on;
		return ok(ctx, "Auto: " + autoSettings.describe());
	}

	private static int moveTo(CommandContext<CommandSourceStack> ctx) {
		if (auto != null) {
			auto.stop();
			auto = null;
		}
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
		if (auto != null) {
			auto.stop();
		}
		if (npc != null && npc.level() instanceof ServerLevel level) {
			NpcTargets.removeAll(level);
		}
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
