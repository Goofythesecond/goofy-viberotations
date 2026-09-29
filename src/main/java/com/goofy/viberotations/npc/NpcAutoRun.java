package com.goofy.viberotations.npc;

import com.goofy.viberotations.engine.HumanRotationEngine;

import java.util.List;
import java.util.Random;

/**
 * {@code /npc auto} — keeps giving the NPC random waypoints (short and/or long flicks) and logs
 * each one to flicks.csv: where it started, what it aimed for, where it ended and how long it took.
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

	/** Vertical part of each flick relative to the horizontal part, like recorded aim (~0.3). */
	private static final double PITCH_RATIO = 0.3;
	private static final double PITCH_LIMIT = 60;

	private final Mode mode;
	private final Random rng;
	private final int delayMs;
	/** Smallest target distance; kept above the engine's arrival tolerance so every flick moves. */
	private final double minDeg;

	private Flick pending;
	private double gapLeftMs;
	private int count;

	NpcAutoRun(Mode mode, long seed, int delayMs, double toleranceDeg) {
		this.mode = mode;
		this.rng = new Random(seed);
		this.delayMs = delayMs;
		this.minDeg = toleranceDeg * 1.5;
	}

	int count() {
		return count;
	}

	/** Called every server tick after the engine has advanced. */
	void tick(HumanRotationEngine engine, NpcRecorder recorder) {
		if (engine.busy()) {
			return;
		}
		if (pending != null) {
			count++;
			if (recorder != null) {
				recorder.flick(count, pending, engine);
			}
			pending = null;
			// Gap before the next flick: /npc delay if set, else 150–600 ms.
			gapLeftMs = delayMs > 0 ? delayMs : 150 + rng.nextDouble() * 450;
		}
		gapLeftMs -= 50;
		if (gapLeftMs > 0) {
			return;
		}
		String kind;
		double amp;
		if (mode == Mode.MIXED) {
			boolean small = rng.nextBoolean();
			kind = small ? "short" : "long";
			amp = logUniform(small ? Mode.SHORT : Mode.LONG);
		} else {
			kind = mode.name().toLowerCase();
			amp = logUniform(mode);
		}
		double dir = rng.nextDouble() * 2 * Math.PI;
		// Squash the direction vertically, then rescale so the target is exactly 'amp' away.
		double dx = Math.cos(dir);
		double dy = Math.sin(dir) * PITCH_RATIO;
		double len = Math.hypot(dx, dy);
		double yaw = engine.yaw() + amp * dx / len;
		double pitch = engine.pitch() + amp * dy / len;
		if (Math.abs(pitch) > PITCH_LIMIT) {
			// Mirror vertically instead of clamping, so the distance stays the same.
			pitch = engine.pitch() - amp * dy / len;
		}
		// Only very long, near-vertical flicks can still overshoot the limit after mirroring.
		pitch = Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, pitch));
		pending = new Flick(kind, engine.timeMs(), engine.yaw(), engine.pitch(), yaw, pitch);
		engine.setWaypoints(List.of(new double[]{yaw, pitch}), 0);
		if (recorder != null) {
			recorder.waypoints(List.of(new double[]{yaw, pitch}), 0);
		}
	}

	private double logUniform(Mode m) {
		double lo = Math.max(m.min, minDeg);
		double hi = Math.max(m.max, lo);
		return Math.exp(Math.log(lo) + rng.nextDouble() * (Math.log(hi) - Math.log(lo)));
	}

	record Flick(String kind, double startMs, double startYaw, double startPitch, double targetYaw, double targetPitch) {}
}
