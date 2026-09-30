package com.goofy.viberotations.client;

import com.goofy.viberotations.VibeRotations;
import com.goofy.viberotations.engine.EngineParams;
import com.goofy.viberotations.npc.NpcController;
import com.goofy.viberotations.train.AimMetrics;
import com.goofy.viberotations.train.AimSeed;
import com.goofy.viberotations.train.SeedFitter;
import com.goofy.viberotations.train.Style;
import com.goofy.viberotations.train.Trial;
import com.goofy.viberotations.train.TrialAnalysis;
import com.goofy.viberotations.train.TrialStore;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code /vrtrain} — aim training that turns your own movements into an {@link AimSeed}.
 *
 * <p>A target block appears somewhere around you (client-side only; the world isn't changed)
 * with an instruction such as "constant speed" or "flick". You move onto it with your mouse;
 * every mouse frame is recorded. Targets are spread over 8 directions × 6 distances (3° up to
 * 180°, so behind you too), and each new one goes to the direction/distance/style with the least
 * data or where the current seed matches you worst.</p>
 *
 * <p>When a session ends, a new seed is fitted on a background thread and compared with the
 * current one on held-out trials: it is accepted if it scores higher, otherwise rejected, with
 * any of its parameter groups that do help merged into the current seed.</p>
 *
 * <p>Nothing here moves the camera; the mod only watches your input.</p>
 */
public final class TrainingMode {

	private static final int SECTORS = 8;
	private static final int BINS = Trial.AMP_BINS.length - 1;
	private static final double PITCH_LIMIT = 70;
	private static final long MS = 1_000_000L;
	/** Mouse must be still this long before a target appears, so reaction time is clean. */
	private static final long STILL_NS = 350 * MS;
	private static final long FEEDBACK_NS = 1200 * MS;
	/** Crosshair must stay on the target this long to finish a trial. */
	private static final long DWELL_NS = 250 * MS;
	private static final long TIMEOUT_NS = 6000 * MS;
	private static final double MAX_WALK = 0.75;
	private static final int DEFAULT_TRIALS = 48;
	private static final int FIT_ITERATIONS = 120;
	private static final BlockState TARGET = Blocks.TARGET.defaultBlockState();
	private static final int BLOCK_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	/** Declared after the constants: the constructor sizes arrays with {@link #BINS}, which isn't a compile-time constant. */
	public static final TrainingMode INSTANCE = new TrainingMode();

	private enum State { OFF, WAITING, ACTIVE }

	private final Path trainingDir = FabricLoader.getInstance().getGameDir().resolve("rotation-logs").resolve("training");
	private final SeedStore seeds = new SeedStore(trainingDir);
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "VibeRotations seed fitting");
		t.setDaemon(true);
		return t;
	});
	private volatile boolean fitting;
	private final Random rng = new Random();

	// session
	private State state = State.OFF;
	private Path sessionDir;
	private CsvFile trialsCsv;
	private CsvFile framesCsv;
	private int planned;
	private int completed;
	private int trialNo;
	private long tick;
	private final int[][] cellCounts = new int[BINS][SECTORS];
	private final int[][] styleCounts = new int[BINS][Style.values().length];
	private double[] weakness = new double[BINS];
	private final Map<Style, List<TrialAnalysis.Features>> sessionFeatures = new EnumMap<>(Style.class);
	private final Map<Style, List<Double>> sessionAdherence = new EnumMap<>(Style.class);

	// next / current trial
	private Style style;
	private int ampBin;
	private int sector;
	private long readyAtNs;
	private long lastTurnNs;
	private int placementFailures;
	private BlockPos pos;
	private BlockState saved;
	private AABB box;
	private Vec3 startPos;
	private double startYaw, startPitch, targetYaw, targetPitch, radius, distance;
	private long shownNs;
	private long onBoxSinceNs;
	private int frameInTick;
	private final List<double[]> frames = new ArrayList<>();

	private TrainingMode() {}

	public void register() {
		ClientCommandRegistrationCallback.EVENT.register((d, registry) -> d.register(ClientCommandManager.literal("vrtrain")
				.then(ClientCommandManager.literal("start")
						.executes(ctx -> start(DEFAULT_TRIALS))
						.then(ClientCommandManager.argument("trials", IntegerArgumentType.integer(1, 1000))
								.executes(ctx -> start(IntegerArgumentType.getInteger(ctx, "trials")))))
				.then(ClientCommandManager.literal("stop").executes(ctx -> {
					if (state == State.OFF) {
						return fail("Not training");
					}
					finish(Minecraft.getInstance(), true);
					return 1;
				}))
				.then(ClientCommandManager.literal("fit")
						.executes(ctx -> fit(FIT_ITERATIONS))
						.then(ClientCommandManager.argument("iterations", IntegerArgumentType.integer(0, 5000))
								.executes(ctx -> fit(IntegerArgumentType.getInteger(ctx, "iterations")))))
				.then(ClientCommandManager.literal("seed").executes(ctx -> showSeed()))
				.then(ClientCommandManager.literal("seeds").executes(ctx -> listSeeds()))
				.then(ClientCommandManager.literal("use")
						.then(ClientCommandManager.argument("id", IntegerArgumentType.integer(1))
								.executes(ctx -> use(IntegerArgumentType.getInteger(ctx, "id")))))
				.then(ClientCommandManager.literal("export")
						.executes(ctx -> export(Style.NATURAL, false))
						.then(ClientCommandManager.argument("style", StringArgumentType.word())
								.suggests((ctx, b) -> {
									for (Style s : Style.values()) {
										b.suggest(s.id());
									}
									return b.buildFuture();
								})
								.executes(ctx -> exportArg(StringArgumentType.getString(ctx, "style"), false))))
				.then(ClientCommandManager.literal("npc")
						.executes(ctx -> export(Style.NATURAL, true))
						.then(ClientCommandManager.argument("style", StringArgumentType.word())
								.suggests((ctx, b) -> {
									for (Style s : Style.values()) {
										b.suggest(s.id());
									}
									return b.buildFuture();
								})
								.executes(ctx -> exportArg(StringArgumentType.getString(ctx, "style"), true))))));
	}

	// ─── Session ─────────────────────────────────────────────────────────

	private int start(int trials) {
		Minecraft mc = Minecraft.getInstance();
		if (state != State.OFF) {
			return fail("Already training. /vrtrain stop first.");
		}
		if (mc.player == null || mc.level == null) {
			return fail("Join a world first");
		}
		try {
			loadCoverage();
			String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
			sessionDir = TrialStore.sessions(trainingDir).resolve(stamp);
			Files.createDirectories(sessionDir);
			trialsCsv = new CsvFile(sessionDir.resolve("trials.csv"), TrialStore.TRIAL_HEADER);
			framesCsv = new CsvFile(sessionDir.resolve("frames.csv"), TrialStore.FRAME_HEADER);
			Files.writeString(sessionDir.resolve("meta.json"), String.format(Locale.ROOT,
					"{\n  \"source\": \"training\",\n  \"sensitivity\": %s,\n  \"fov\": %s,\n  \"raw_mouse_input\": %s,\n"
							+ "  \"fps_limit\": %s,\n  \"planned_trials\": %d\n}\n",
					mc.options.sensitivity().get(), mc.options.fov().get(), mc.options.rawMouseInput().get(),
					mc.options.framerateLimit().get(), trials));
		} catch (IOException e) {
			VibeRotations.LOGGER.error("Failed to start a training session", e);
			closeFiles();
			return fail("Could not create the training folder: " + e.getMessage());
		}
		planned = trials;
		completed = 0;
		trialNo = 0;
		placementFailures = 0;
		sessionFeatures.clear();
		sessionAdherence.clear();
		state = State.WAITING;
		readyAtNs = System.nanoTime() + 1500 * MS;
		planNext();
		chat(Component.literal("Aim training: " + trials + " targets. ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal("Stand still in an open area. A target block appears around you (it may be "
						+ "behind you); follow the instruction above your hotbar and rest your crosshair on it. "
						+ "/vrtrain stop to end early.").withStyle(ChatFormatting.GRAY)));
		return 1;
	}

	/** Counts existing trials per cell and style, and loads the current seed's weak spots. */
	private void loadCoverage() throws IOException {
		for (int b = 0; b < BINS; b++) {
			java.util.Arrays.fill(cellCounts[b], 0);
			java.util.Arrays.fill(styleCounts[b], 0);
		}
		for (TrialStore.Planned p : TrialStore.plans(trainingDir)) {
			if ("hit".equals(p.result()) && p.ampBin() >= 0 && p.ampBin() < BINS && p.sector() >= 0 && p.sector() < SECTORS) {
				cellCounts[p.ampBin()][p.sector()]++;
				styleCounts[p.ampBin()][p.style().ordinal()]++;
			}
		}
		AimSeed cur = seeds.current();
		weakness = cur != null && cur.binWeakness.length == BINS ? cur.binWeakness.clone() : new double[BINS];
	}

	/**
	 * Chooses the next cell and style: fewest trials first, pulled toward distances where the
	 * current seed is furthest from you. Style counts are divided by each style's weight.
	 */
	private void planNext() {
		double best = Double.MAX_VALUE;
		for (int b = 0; b < BINS; b++) {
			for (int s = 0; s < SECTORS; s++) {
				double pri = cellCounts[b][s] - 2 * weakness[b] + rng.nextDouble() * 0.5;
				if (pri < best) {
					best = pri;
					ampBin = b;
					sector = s;
				}
			}
		}
		best = Double.MAX_VALUE;
		for (Style st : Style.values()) {
			double pri = (double) styleCounts[ampBin][st.ordinal()] / st.weight + rng.nextDouble() * 0.3;
			if (pri < best) {
				best = pri;
				style = st;
			}
		}
	}

	public void tick(Minecraft mc) {
		if (state == State.OFF) {
			return;
		}
		tick++;
		frameInTick = 0;
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			finish(mc, false);
			return;
		}
		long now = System.nanoTime();
		if (state == State.WAITING) {
			if (now < readyAtNs) {
				return;
			}
			if (mc.screen != null) {
				return;
			}
			actionBar(mc, Component.literal("Next: " + style.instruction + " — hold still").withStyle(ChatFormatting.GRAY));
			if (now - lastTurnNs >= STILL_NS) {
				show(mc, player, now);
			}
			return;
		}

		// ACTIVE
		updateOnBox(player, now);
		if (mc.screen != null) {
			endTrial(mc, "aborted", now);
		} else if (player.position().distanceTo(startPos) > MAX_WALK) {
			endTrial(mc, "moved", now);
			chat(Component.literal("Stand still during a target — that one was skipped.").withStyle(ChatFormatting.YELLOW));
		} else if (onBoxSinceNs != 0 && now - onBoxSinceNs >= DWELL_NS) {
			endTrial(mc, "hit", now);
		} else if (now - shownNs > TIMEOUT_NS) {
			endTrial(mc, "timeout", now);
		} else if (!mc.level.getBlockState(pos).is(Blocks.TARGET)) {
			// The server overwrote the client-side block (chunk resend); put it back.
			mc.level.setBlock(pos, TARGET, BLOCK_FLAGS);
		}
	}

	/** Called from Entity.turn for the local player, once per rendered frame. */
	public void onTurn(LocalPlayer player, double yRot, double xRot) {
		if (state == State.OFF || (yRot == 0 && xRot == 0)) {
			return;
		}
		long now = System.nanoTime();
		lastTurnNs = now;
		if (state != State.ACTIVE) {
			return;
		}
		double dy = yRot * 0.15;
		double dp = xRot * 0.15;
		frames.add(new double[]{(now - shownNs) / 1e6, dy, dp});
		try {
			framesCsv.write(new CsvFile.Row().add(trialNo).add(tick).add(now).add(frameInTick++)
					.add(dy).add(dp).add(player.getYRot()).add(player.getXRot()));
		} catch (IOException e) {
			VibeRotations.LOGGER.error("Failed to write training frame", e);
		}
		updateOnBox(player, now);
	}

	private void updateOnBox(LocalPlayer player, long now) {
		if (AimMath.rayHits(player.getEyePosition(), player.getLookAngle(), box, 64)) {
			if (onBoxSinceNs == 0) {
				onBoxSinceNs = now;
			}
		} else {
			onBoxSinceNs = 0;
		}
	}

	// ─── Targets ─────────────────────────────────────────────────────────

	private void show(Minecraft mc, LocalPlayer player, long now) {
		Vec3 eye = player.getEyePosition();
		double yaw0 = player.getYRot();
		double pitch0 = player.getXRot();
		double lo = Trial.AMP_BINS[ampBin];
		double hi = Trial.AMP_BINS[ampBin + 1];
		for (int attempt = 0; attempt < 24; attempt++) {
			double amp = Math.exp(Math.log(lo) + rng.nextDouble() * (Math.log(hi) - Math.log(lo)));
			double dir = (sector + rng.nextDouble()) * (2 * Math.PI / SECTORS);
			double dYaw = amp * Math.cos(dir);
			double dPitch = amp * Math.sin(dir);
			if (Math.abs(pitch0 + dPitch) > PITCH_LIMIT) {
				dPitch = -dPitch;
			}
			if (Math.abs(pitch0 + dPitch) > PITCH_LIMIT) {
				// Too big to fit vertically: keep the size, move the excess into yaw.
				dPitch = Mth.clamp(pitch0 + dPitch, -PITCH_LIMIT, PITCH_LIMIT) - pitch0;
				double sign = Math.cos(dir) == 0 ? (rng.nextBoolean() ? 1 : -1) : Math.signum(Math.cos(dir));
				dYaw = sign * Math.sqrt(Math.max(0, amp * amp - dPitch * dPitch));
			}
			// Smaller angles need a farther (smaller-looking) block so it doesn't already cover the crosshair.
			double want = Mth.clamp(0.5 / Math.tan(Math.toRadians(amp / 3)), 6, 32);
			for (double scale : new double[]{1, 0.8, 1.25, 0.65}) {
				if (tryPlace(mc, player, eye, yaw0, pitch0, yaw0 + dYaw, pitch0 + dPitch, want * scale, lo, hi)) {
					placementFailures = 0;
					shownNs = now;
					onBoxSinceNs = 0;
					frames.clear();
					frameInTick = 0;
					trialNo++;
					state = State.ACTIVE;
					actionBar(mc, Component.literal(style.instruction).withStyle(ChatFormatting.YELLOW));
					return;
				}
			}
		}
		// Blocked in every direction tried for this cell; try another cell next tick.
		cellCounts[ampBin][sector]++;
		planNext();
		if (++placementFailures >= 8) {
			chat(Component.literal("Can't find room for targets here. Move somewhere open and run /vrtrain start again.")
					.withStyle(ChatFormatting.RED));
			finish(mc, false);
		}
	}

	private boolean tryPlace(Minecraft mc, LocalPlayer player, Vec3 eye, double yaw0, double pitch0,
			double yaw, double pitch, double dist, double lo, double hi) {
		Vec3 dir = Vec3.directionFromRotation((float) pitch, (float) yaw);
		BlockPos p = BlockPos.containing(eye.add(dir.scale(dist)));
		if (!mc.level.isLoaded(p) || !mc.level.isInWorldBounds(p) || !mc.level.getBlockState(p).isAir()) {
			return false;
		}
		Vec3 c = p.getCenter();
		double d = eye.distanceTo(c);
		if (d < 4 || new AABB(p).inflate(0.5).contains(eye)) {
			return false;
		}
		HitResult hit = mc.level.clip(new ClipContext(eye, c, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.MISS) {
			return false;
		}
		// Recompute the target from the block centre; yaw stays unwrapped relative to the start.
		double ty = yaw0 + Mth.wrapDegrees(AimMath.yawTo(eye, c) - (float) yaw0);
		double tp = AimMath.pitchTo(eye, c);
		double r = Math.toDegrees(Math.atan(0.5 / d));
		double amp = Math.hypot(ty - yaw0, tp - pitch0);
		if (amp < lo * 0.85 || amp > hi * 1.15 || amp < 2.5 * r || Math.abs(tp) > PITCH_LIMIT + 5) {
			return false;
		}
		pos = p;
		saved = mc.level.getBlockState(p);
		box = new AABB(p);
		startPos = player.position();
		startYaw = yaw0;
		startPitch = pitch0;
		targetYaw = ty;
		targetPitch = tp;
		radius = r;
		distance = d;
		mc.level.setBlock(p, TARGET, BLOCK_FLAGS);
		return true;
	}

	private void endTrial(Minecraft mc, String result, long now) {
		restoreBlock(mc);
		double endMs = onBoxSinceNs == 0 ? Double.NaN : (onBoxSinceNs - shownNs) / 1e6;
		try {
			trialsCsv.write(new CsvFile.Row().add(trialNo).add(style.id()).add(result).add(shownNs)
					.add(startYaw).add(startPitch).add(targetYaw).add(targetPitch).add(radius).add(distance)
					.add(ampBin).add(sector).add(Double.isNaN(endMs) ? "" : String.format(Locale.ROOT, "%.3f", endMs)));
		} catch (IOException e) {
			VibeRotations.LOGGER.error("Failed to write training trial", e);
		}
		state = State.WAITING;
		readyAtNs = now + FEEDBACK_NS;
		if (!result.equals("hit")) {
			actionBar(mc, Component.literal(result.equals("timeout") ? "Too slow — skipped" : "Skipped").withStyle(ChatFormatting.RED));
			planNext();
			return;
		}
		completed++;
		cellCounts[ampBin][sector]++;
		styleCounts[ampBin][style.ordinal()]++;
		feedback(mc, new Trial("live", style, startYaw, startPitch, targetYaw, targetPitch, radius, endMs,
				frames.stream().mapToDouble(f -> f[0]).toArray(),
				frames.stream().mapToDouble(f -> f[1]).toArray(),
				frames.stream().mapToDouble(f -> f[2]).toArray()));
		if (completed >= planned) {
			finish(mc, true);
		} else {
			planNext();
		}
	}

	/** Shows how the trial went, and keeps it for the end-of-session summary. */
	private void feedback(Minecraft mc, Trial trial) {
		TrialAnalysis.Features f = TrialAnalysis.analyze(trial);
		if (f == null) {
			actionBar(mc, Component.literal("No movement recorded").withStyle(ChatFormatting.GRAY));
			return;
		}
		sessionFeatures.computeIfAbsent(style, s -> new ArrayList<>()).add(f);
		double adh = style.adherence(f);
		if (!Double.isNaN(adh)) {
			sessionAdherence.computeIfAbsent(style, s -> new ArrayList<>()).add(adh);
		}
		String landing = f.endpointRatio() < 0.92 ? "short" : f.endpointRatio() > 1.08 ? "overshot" : "on target";
		String text = String.format(Locale.ROOT, "%d/%d  %.0f° · reaction %.0f ms · %.0f ms total · peak at %.0f%% · %s · %d move%s",
				completed, planned, f.amplitude(), f.reactionMs(), f.totalMs(), 100 * f.peakTime(), landing,
				f.submovements(), f.submovements() == 1 ? "" : "s");
		if (!Double.isNaN(adh)) {
			text += String.format(Locale.ROOT, " · instruction %.0f%%", adh);
		}
		actionBar(mc, Component.literal(text).withStyle(ChatFormatting.GREEN));
	}

	private void finish(Minecraft mc, boolean fitAfter) {
		if (state == State.ACTIVE) {
			endTrial(mc, "aborted", System.nanoTime());
		}
		restoreBlock(mc);
		state = State.OFF;
		closeFiles();
		if (mc.player == null) {
			return;
		}
		chat(Component.literal(String.format(Locale.ROOT, "Training finished: %d targets. Saved to %s",
				completed, sessionDir == null ? "?" : trainingDir.relativize(sessionDir))).withStyle(ChatFormatting.GOLD));
		for (Style s : Style.values()) {
			List<TrialAnalysis.Features> fs = sessionFeatures.get(s);
			if (fs == null || fs.isEmpty()) {
				continue;
			}
			double reaction = TrialAnalysis.median(fs.stream().map(TrialAnalysis.Features::reactionMs).toList());
			double moves = fs.stream().mapToInt(TrialAnalysis.Features::submovements).average().orElse(0);
			List<Double> adh = sessionAdherence.getOrDefault(s, List.of());
			chat(Component.literal(String.format(Locale.ROOT, "  %s: %d targets, reaction %.0f ms, %.1f movements each%s",
					s.id(), fs.size(), reaction, moves,
					adh.isEmpty() ? "" : String.format(Locale.ROOT, ", followed the instruction %.0f%%",
							adh.stream().mapToDouble(Double::doubleValue).average().orElse(0))))
					.withStyle(ChatFormatting.GRAY));
		}
		if (fitAfter && completed > 0) {
			fit(FIT_ITERATIONS);
		}
	}

	private void restoreBlock(Minecraft mc) {
		if (pos != null && mc.level != null && mc.level.getBlockState(pos).is(Blocks.TARGET)) {
			mc.level.setBlock(pos, saved, BLOCK_FLAGS);
		}
		pos = null;
	}

	private void closeFiles() {
		for (CsvFile f : new CsvFile[]{trialsCsv, framesCsv}) {
			if (f != null) {
				try {
					f.close();
				} catch (IOException e) {
					VibeRotations.LOGGER.error("Failed to close training log", e);
				}
			}
		}
		trialsCsv = null;
		framesCsv = null;
	}

	/** Disconnect or shutdown: the level is going away, so just close the files. */
	public void close() {
		if (state != State.OFF) {
			pos = null;
			state = State.OFF;
			closeFiles();
		}
	}

	// ─── Seeds ───────────────────────────────────────────────────────────

	private int fit(int iterations) {
		if (fitting) {
			return fail("Already fitting a seed");
		}
		Minecraft mc = Minecraft.getInstance();
		double sensitivity = mc.options.sensitivity().get();
		fitting = true;
		chat(Component.literal("Fitting a seed to your training data…").withStyle(ChatFormatting.GRAY));
		worker.execute(() -> {
			try {
				List<Trial> trials = TrialStore.load(trainingDir);
				SeedFitter fitter = new SeedFitter(trials, sensitivity, iterations,
						msg -> mc.execute(() -> chat(Component.literal(msg).withStyle(ChatFormatting.DARK_GRAY))));
				AimSeed current = seeds.current();
				SeedFitter.Outcome out = fitter.evolve(current, seeds.nextId());
				seeds.save(out.candidate());
				if (out.merged() != null) {
					seeds.save(out.merged());
				}
				if (current != null) {
					seeds.save(current); // its score was refreshed on the new data
				}
				seeds.setCurrent(out.result().id);
				AimSeed result = out.result();
				mc.execute(() -> {
					ChatFormatting colour = result == out.candidate() || result == out.merged() ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
					chat(Component.literal(out.summary()).withStyle(colour));
					chat(Component.literal("Current seed: " + result.label() + ". /vrtrain seed for details.")
							.withStyle(ChatFormatting.GRAY));
				});
			} catch (Exception e) {
				VibeRotations.LOGGER.error("Seed fitting failed", e);
				mc.execute(() -> chat(Component.literal("Seed fitting failed: " + e.getMessage()).withStyle(ChatFormatting.RED)));
			} finally {
				fitting = false;
			}
		});
		return 1;
	}

	private int showSeed() {
		AimSeed s = currentOrFail();
		if (s == null) {
			return 0;
		}
		chat(Component.literal("Seed " + s.label()).withStyle(ChatFormatting.GOLD));
		chat(Component.literal(String.format(Locale.ROOT, "  %d trials. Validation %.1f, training %.1f. %s",
				s.trials, s.score, s.trainScore, s.note)).withStyle(ChatFormatting.GRAY));
		StringBuilder styles = new StringBuilder("  Per style:");
		s.styleScores.forEach((k, v) -> styles.append(String.format(Locale.ROOT, " %s %.1f", k, v)));
		chat(Component.literal(styles.toString()).withStyle(ChatFormatting.GRAY));
		chat(Component.literal("  Furthest from you (you → model):").withStyle(ChatFormatting.GRAY));
		s.metrics.entrySet().stream()
				.filter(e -> !Double.isNaN(AimMetrics.metricDistance(e.getKey(), e.getValue()[0], e.getValue()[1])))
				.sorted((a, b) -> Double.compare(
						AimMetrics.metricDistance(b.getKey(), b.getValue()[0], b.getValue()[1]),
						AimMetrics.metricDistance(a.getKey(), a.getValue()[0], a.getValue()[1])))
				.limit(5)
				.forEach(e -> chat(Component.literal(String.format(Locale.ROOT, "    %s: %.2f → %.2f",
						e.getKey(), e.getValue()[0], e.getValue()[1])).withStyle(ChatFormatting.GRAY)));
		return 1;
	}

	private int listSeeds() {
		try {
			AimSeed cur = seeds.current();
			List<AimSeed> all = seeds.all();
			if (all.isEmpty()) {
				return fail("No seeds yet. /vrtrain start to record training data.");
			}
			for (AimSeed s : all) {
				boolean isCur = cur != null && cur.id == s.id;
				chat(Component.literal((isCur ? "▶ " : "  ") + s.label() + (s.note.isEmpty() ? "" : " — " + s.note))
						.withStyle(isCur ? ChatFormatting.GREEN : s.status == AimSeed.Status.REJECTED ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY));
			}
			return 1;
		} catch (IOException e) {
			return fail("Could not read seeds: " + e.getMessage());
		}
	}

	private int use(int id) {
		try {
			AimSeed s = seeds.load(id);
			if (s == null) {
				return fail("No seed #" + id);
			}
			seeds.setCurrent(id);
			chat(Component.literal("Now using " + s.label()).withStyle(ChatFormatting.GREEN));
			return 1;
		} catch (IOException e) {
			return fail("Could not switch seed: " + e.getMessage());
		}
	}

	private int exportArg(String style, boolean toNpc) {
		try {
			return export(Style.parse(style), toNpc);
		} catch (IllegalArgumentException e) {
			return fail("Unknown style: " + style);
		}
	}

	/** Prints the style's parameters (for the harness or /npc param), or hands them to the NPC. */
	private int export(Style style, boolean toNpc) {
		AimSeed s = currentOrFail();
		if (s == null) {
			return 0;
		}
		EngineParams p = s.paramsFor(style);
		if (toNpc) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.getSingleplayerServer() == null) {
				return fail("The NPC only exists in singleplayer");
			}
			mc.getSingleplayerServer().execute(() -> NpcController.useParams(p));
			chat(Component.literal("NPC will use seed " + s.code + " (" + style.id() + ") from the next /npc spawn")
					.withStyle(ChatFormatting.GREEN));
			return 1;
		}
		String pairs = p.toPairs();
		VibeRotations.LOGGER.info("Seed {} {} params: {}", s.code, style.id(), pairs);
		chat(Component.literal(pairs).withStyle(ChatFormatting.GRAY));
		chat(Component.literal("Also written to the log. Use with ./gradlew harness -Pparams=\"...\"").withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private AimSeed currentOrFail() {
		try {
			AimSeed s = seeds.current();
			if (s == null) {
				fail("No seed yet. /vrtrain start, then it's fitted when the session ends.");
			}
			return s;
		} catch (IOException e) {
			fail("Could not read the current seed: " + e.getMessage());
			return null;
		}
	}

	// ─── Output ──────────────────────────────────────────────────────────

	private static void actionBar(Minecraft mc, Component c) {
		mc.gui.setOverlayMessage(c, false);
	}

	private static void chat(Component c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.displayClientMessage(c, false);
		}
	}

	private static int fail(String msg) {
		chat(Component.literal(msg).withStyle(ChatFormatting.RED));
		return 0;
	}
}
