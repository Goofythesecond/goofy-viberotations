package com.goofy.viberotations.npc;

import com.goofy.viberotations.engine.HumanRotationEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * {@code /npc auto} — keeps giving the NPC random targets and logs each flick to flicks.csv:
 * where it started, what it aimed for, where it ended and how long it took.
 *
 * <p>Targets come in bursts ({@link AutoSettings#burst}, 1 by default). All targets of a burst
 * are shown at once and the NPC goes through them in order; since it can already see the next
 * one, each flick after the first starts after a short pause instead of a full reaction. Between
 * bursts it rests for the configured delay.</p>
 */
final class NpcAutoRun {

	enum Mode {
		SHORT(1, 15), LONG(30, 180), MIXED(1, 180);

		final double min;
		final double max;

		Mode(double min, double max) {
			this.min = min;
			this.max = max;
		}
	}

	/** Shows the targets of a burst in the world. */
	interface Targets {
		/** Replaces what's shown. Waypoints are {yaw, pitch}; the aim starts at startYaw/startPitch. */
		void show(double startYaw, double startPitch, List<double[]> waypoints);

		void reached(int index);

		void clear();
	}

	/** Vertical part of each flick relative to the horizontal part, like recorded aim (~0.3). */
	private static final double PITCH_RATIO = 0.3;
	private static final double PITCH_LIMIT = 60;

	private final Mode mode;
	private final Random rng;
	private final AutoSettings settings;
	private final Targets targets;
	/** Smallest target distance; kept above the engine's arrival tolerance so every flick moves. */
	private final double minDeg;

	private List<double[]> burst;
	private List<String> kinds;
	private int burstNo;
	private int burstGapMs;
	/** {time ms, yaw, pitch} where the current flick started: the previous arrival, or the burst start. */
	private double[] from;
	private double restLeftMs;
	private int count;

	NpcAutoRun(Mode mode, long seed, AutoSettings settings, double toleranceDeg, Targets targets) {
		this.mode = mode;
		this.rng = new Random(seed);
		this.settings = settings;
		this.targets = targets;
		this.minDeg = toleranceDeg * 1.5;
	}

	int count() {
		return count;
	}

	/** Called every server tick after the engine has advanced. */
	void tick(HumanRotationEngine engine, NpcRecorder recorder) {
		for (HumanRotationEngine.Arrival a : engine.drainArrivals()) {
			if (burst == null || a.waypoint() >= burst.size()) {
				continue;
			}
			count++;
			double[] target = burst.get(a.waypoint());
			if (recorder != null) {
				recorder.flick(count, kinds.get(a.waypoint()), burstNo, a.waypoint(), a.waypoint() == 0 ? 0 : burstGapMs,
						from[0], from[1], from[2], target[0], target[1], a);
			}
			targets.reached(a.waypoint());
			from = new double[]{a.timeMs(), a.yaw(), a.pitch()};
		}
		if (engine.busy()) {
			return;
		}
		if (burst != null) {
			burst = null;
			targets.clear();
			restLeftMs = settings.restMinMs + rng.nextDouble() * Math.max(0, settings.restMaxMs - settings.restMinMs);
		}
		restLeftMs -= 50;
		if (restLeftMs > 0) {
			return;
		}
		startBurst(engine, recorder);
	}

	void stop() {
		targets.clear();
	}

	private void startBurst(HumanRotationEngine engine, NpcRecorder recorder) {
		int n = Math.max(1, settings.burst);
		burst = new ArrayList<>(n);
		kinds = new ArrayList<>(n);
		double yaw = engine.yaw();
		double pitch = engine.pitch();
		for (int i = 0; i < n; i++) {
			double[] t = next(yaw, pitch);
			burst.add(t);
			yaw = t[0];
			pitch = t[1];
		}
		burstNo++;
		burstGapMs = n > 1 ? settings.gapMs : 0;
		from = new double[]{engine.timeMs(), engine.yaw(), engine.pitch()};
		engine.setWaypoints(burst, burstGapMs, true);
		if (recorder != null) {
			recorder.waypoints(burst, burstGapMs);
		}
		if (settings.blocks) {
			targets.show(engine.yaw(), engine.pitch(), burst);
		}
	}

	/** A random target measured from the previous one; also records its kind. */
	private double[] next(double fromYaw, double fromPitch) {
		double amp;
		if (mode == Mode.MIXED) {
			boolean small = rng.nextBoolean();
			kinds.add(small ? "short" : "long");
			amp = logUniform(small ? Mode.SHORT : Mode.LONG);
		} else {
			kinds.add(mode.name().toLowerCase());
			amp = logUniform(mode);
		}
		double dir = rng.nextDouble() * 2 * Math.PI;
		// Squash the direction vertically, then rescale so the target is exactly 'amp' away.
		double dx = Math.cos(dir);
		double dy = Math.sin(dir) * PITCH_RATIO;
		double len = Math.hypot(dx, dy);
		double yaw = fromYaw + amp * dx / len;
		double pitch = fromPitch + amp * dy / len;
		if (Math.abs(pitch) > PITCH_LIMIT) {
			// Mirror vertically instead of clamping, so the distance stays the same.
			pitch = fromPitch - amp * dy / len;
		}
		// Only very long, near-vertical flicks can still overshoot the limit after mirroring.
		pitch = Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, pitch));
		return new double[]{yaw, pitch};
	}

	private double logUniform(Mode m) {
		double lo = Math.max(m.min, minDeg);
		double hi = Math.max(m.max, lo);
		return Math.exp(Math.log(lo) + rng.nextDouble() * (Math.log(hi) - Math.log(lo)));
	}
}
