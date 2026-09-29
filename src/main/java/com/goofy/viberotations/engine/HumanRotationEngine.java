package com.goofy.viberotations.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Generates human-like rotation toward a queue of fixed yaw/pitch waypoints.
 *
 * <p>Pure Java with no Minecraft dependencies, so the NPC and the offline harness run the
 * same code. Time advances in simulated render frames; each frame produces a rotation delta
 * quantized to the mouse-sensitivity step, like real mouse input.</p>
 *
 * <p>Model (see docs/PVP_DATA.md section 5): after a reaction delay the engine makes a
 * flick toward the waypoint. The flick's endpoint undershoots, overshoots or lands close,
 * its duration follows a power law of its size, and its speed profile is a minimum-jerk
 * curve with a randomized peak time. After a short pause, corrective flicks follow until
 * the remaining error is within tolerance, then the engine dwells for the waypoint delay.</p>
 */
public final class HumanRotationEngine {

	public enum Phase { IDLE, REACTING, FLICK, PAUSE, DWELL }

	/** Receives every simulated frame that has a non-zero rotation. */
	public interface FrameSink {
		void frame(double timeMs, double dYaw, double dPitch, double yaw, double pitch, Phase phase, int waypoint);
	}

	private final EngineParams p;
	private final Random rng;

	// actual (quantized) rotation
	private double yaw;
	private double pitch;
	// unquantized carry so rounding error doesn't accumulate
	private double carryYaw;
	private double carryPitch;

	private final Deque<double[]> waypoints = new ArrayDeque<>();
	private double[] goal;
	private int waypointIndex = -1;
	private double dwellMs;

	private Phase phase = Phase.IDLE;
	private double phaseLeftMs;
	private int submovements;

	// current flick
	private double fStartYaw, fStartPitch, fDeltaYaw, fDeltaPitch;
	private double fDuration, fElapsed, fWarp, fCurve;
	private double fPrevYaw, fPrevPitch;

	private double timeMs;
	private double frameLeftMs;

	public HumanRotationEngine(EngineParams params, long seed, double yaw, double pitch) {
		this.p = params;
		this.rng = new Random(seed);
		this.yaw = yaw;
		this.pitch = pitch;
	}

	/** Replaces any queued waypoints. Each is {yaw, pitch}. {@code dwellMs} is the wait after reaching each one. */
	public void setWaypoints(List<double[]> targets, double dwellMs) {
		waypoints.clear();
		for (double[] t : targets) {
			waypoints.add(new double[]{t[0], clampPitch(t[1])});
		}
		this.dwellMs = dwellMs;
		waypointIndex = -1;
		nextWaypoint();
	}

	public void stop() {
		waypoints.clear();
		goal = null;
		phase = Phase.IDLE;
	}

	public boolean busy() {
		return phase != Phase.IDLE;
	}

	public double yaw() { return yaw; }
	public double pitch() { return pitch; }
	public Phase phase() { return phase; }
	public int waypointIndex() { return waypointIndex; }
	public double[] goal() { return goal; }
	public double timeMs() { return timeMs; }
	/** Flicks used for the current (or last) waypoint. */
	public int submovements() { return submovements; }

	/** Advances simulated time by {@code ms}, emitting frames to {@code sink} (may be null). */
	public void advance(double ms, FrameSink sink) {
		double end = timeMs + ms;
		while (true) {
			if (frameLeftMs <= 0) {
				frameLeftMs = frameTime();
			}
			if (timeMs + frameLeftMs > end) {
				frameLeftMs -= end - timeMs;
				timeMs = end;
				return;
			}
			timeMs += frameLeftMs;
			double dt = frameLeftMs;
			frameLeftMs = 0;
			frame(dt, sink);
		}
	}

	// ─── Per-frame state machine ─────────────────────────────────────────

	private void frame(double dt, FrameSink sink) {
		switch (phase) {
			case IDLE -> { }
			case REACTING, PAUSE -> {
				phaseLeftMs -= dt;
				if (phaseLeftMs <= 0) {
					startFlick();
				}
			}
			case DWELL -> {
				phaseLeftMs -= dt;
				if (phaseLeftMs <= 0) {
					nextWaypoint();
				}
			}
			case FLICK -> {
				fElapsed += dt;
				double u = Math.min(1.0, fElapsed / fDuration);
				double s = profile(Math.pow(u, fWarp));
				double bulge = fCurve * Math.sin(Math.PI * s);
				// perpendicular to the flick direction, scaled by its length
				double wantYaw = fStartYaw + fDeltaYaw * s - fDeltaPitch * bulge;
				double wantPitch = fStartPitch + fDeltaPitch * s + fDeltaYaw * bulge;
				emit(wantYaw - fPrevYaw, wantPitch - fPrevPitch, sink);
				fPrevYaw = wantYaw;
				fPrevPitch = wantPitch;
				if (u >= 1.0) {
					emit(0, 0, true, sink);
					endFlick();
				}
			}
		}
	}

	private void nextWaypoint() {
		goal = waypoints.poll();
		if (goal == null) {
			phase = Phase.IDLE;
			return;
		}
		waypointIndex++;
		submovements = 0;
		phase = Phase.REACTING;
		phaseLeftMs = logNormal(p.reactionMedianMs, p.reactionSigma);
	}

	private void startFlick() {
		double errYaw = goal[0] - yaw;
		double errPitch = goal[1] - pitch;
		double amp = Math.hypot(errYaw, errPitch);
		if (amp < p.toleranceDeg) {
			arrive();
			return;
		}
		submovements++;

		// Endpoint as a fraction of the remaining error along its direction.
		double r = rng.nextDouble();
		double k;
		if (r < p.pUndershoot) {
			k = 1 - Math.min(p.undershootMax, logNormal(p.undershootMedian, p.undershootSigma));
		} else if (r < p.pUndershoot + p.pOvershoot) {
			k = 1 + Math.min(p.overshootMax, logNormal(p.overshootMedian, p.overshootSigma));
		} else {
			k = 1 + rng.nextGaussian() * p.accurateSd;
		}
		double side = rng.nextGaussian() * p.directionSd;
		double dYaw = errYaw * k - errPitch * side;
		double dPitch = errPitch * k + errYaw * side;
		dPitch = clampPitch(pitch + dPitch) - pitch;

		double flickAmp = Math.hypot(dYaw, dPitch);
		fDuration = Math.max(p.minDurationMs, p.durA * Math.pow(flickAmp, p.durB) * Math.exp(rng.nextGaussian() * p.durSigma));
		double peak = clamp(p.peakTimeMean + rng.nextGaussian() * p.peakTimeSd, 0.1, 0.9);
		// Warp u so the minimum-jerk peak (at 0.5) happens at 'peak': peak^w = 0.5.
		fWarp = Math.log(0.5) / Math.log(peak);
		fCurve = rng.nextGaussian() * p.curvatureSd;
		fStartYaw = fPrevYaw = yaw;
		fStartPitch = fPrevPitch = pitch;
		fDeltaYaw = dYaw;
		fDeltaPitch = dPitch;
		fElapsed = 0;
		phase = Phase.FLICK;
	}

	private void endFlick() {
		double err = Math.hypot(goal[0] - yaw, goal[1] - pitch);
		if (err < p.toleranceDeg || submovements >= p.maxSubmovements) {
			arrive();
		} else {
			phase = Phase.PAUSE;
			phaseLeftMs = Math.min(p.pauseMaxMs, logNormal(p.pauseMedianMs, p.pauseSigma));
		}
	}

	private void arrive() {
		if (waypoints.isEmpty()) {
			phase = Phase.IDLE;
			return;
		}
		phase = Phase.DWELL;
		phaseLeftMs = dwellMs;
	}

	/**
	 * Applies a rotation, rounded to whole mouse steps. Delivery noise passes on more or less
	 * than this frame's share; whatever isn't passed on carries to the next frame, so the total
	 * is exact. {@code flush} passes on everything that is left.
	 */
	private void emit(double dYaw, double dPitch, FrameSink sink) {
		emit(dYaw, dPitch, false, sink);
	}

	private void emit(double dYaw, double dPitch, boolean flush, FrameSink sink) {
		double q = p.quantum();
		carryYaw += dYaw;
		carryPitch += dPitch;
		// Capped at 1: a frame can hold motion back but never pass on more than has built up,
		// otherwise the aim would briefly reverse mid-flick.
		double share = flush ? 1.0 : clamp(1 + rng.nextGaussian() * p.deliveryNoise, 0, 1);
		double outYaw = Math.round(carryYaw * share / q) * q;
		double outPitch = Math.round(carryPitch * share / q) * q;
		carryYaw -= outYaw;
		carryPitch -= outPitch;
		if (outYaw == 0 && outPitch == 0) {
			return;
		}
		double newPitch = clampPitch(pitch + outPitch);
		outPitch = newPitch - pitch;
		yaw += outYaw;
		pitch = newPitch;
		if (sink != null) {
			sink.frame(timeMs, outYaw, outPitch, yaw, pitch, phase, waypointIndex);
		}
	}

	// ─── Math ────────────────────────────────────────────────────────────

	/** Minimum-jerk curve with {@code profileTrim} cut off both ends, rescaled to run 0..1. */
	private double profile(double v) {
		double c = p.profileTrim;
		double lo = minJerk(c);
		double hi = minJerk(1 - c);
		return (minJerk(c + v * (1 - 2 * c)) - lo) / (hi - lo);
	}

	private static double minJerk(double v) {
		return v * v * v * (10 - 15 * v + 6 * v * v);
	}

	private double frameTime() {
		double base = 1000.0 / p.fps;
		return Math.max(1.0, base * (1 + rng.nextGaussian() * p.frameJitter));
	}

	private double logNormal(double median, double sigma) {
		return median * Math.exp(rng.nextGaussian() * sigma);
	}

	private static double clampPitch(double v) {
		return clamp(v, -90, 90);
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
