package com.goofy.viberotations.train;

import com.goofy.viberotations.engine.EngineParams;
import com.goofy.viberotations.engine.HumanRotationEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * One training target: where the crosshair started, where the target was, and every non-zero
 * mouse frame from the moment it appeared. Times are ms since the target appeared.
 *
 * @param endMs when the crosshair entered the target for the last time (analysis stops after
 *              the movement in progress at that moment); NaN for simulated trials, which end
 *              when the engine stops
 */
public record Trial(String id, Style style, double startYaw, double startPitch, double targetYaw, double targetPitch,
		double radiusDeg, double endMs, double[] t, double[] dYaw, double[] dPitch) {

	/** Amplitude bins (degrees) the trainer spreads targets over. */
	public static final double[] AMP_BINS = {3, 6, 12, 25, 50, 100, 180};

	public double amplitude() {
		return Math.hypot(targetYaw - startYaw, targetPitch - startPitch);
	}

	public int frames() {
		return t.length;
	}

	/** Index into {@link #AMP_BINS} for an amplitude, clamped to the first/last bin. */
	public static int ampBin(double amp) {
		for (int i = 1; i < AMP_BINS.length - 1; i++) {
			if (amp < AMP_BINS[i]) {
				return i - 1;
			}
		}
		return AMP_BINS.length - 2;
	}

	/** Runs the engine on this trial's start and target and returns what it did, as a trial. */
	public Trial simulate(EngineParams params, long seed) {
		HumanRotationEngine engine = new HumanRotationEngine(params, seed, startYaw, startPitch);
		List<double[]> frames = new ArrayList<>();
		engine.setWaypoints(List.of(new double[]{targetYaw, targetPitch}), 0);
		HumanRotationEngine.FrameSink sink = (time, dy, dp, y, p, phase, wp) -> frames.add(new double[]{time, dy, dp});
		while (engine.busy() && engine.timeMs() < 10_000) {
			engine.advance(50, sink);
		}
		double[] ft = new double[frames.size()];
		double[] fy = new double[frames.size()];
		double[] fp = new double[frames.size()];
		for (int i = 0; i < ft.length; i++) {
			ft[i] = frames.get(i)[0];
			fy[i] = frames.get(i)[1];
			fp[i] = frames.get(i)[2];
		}
		return new Trial(id, style, startYaw, startPitch, targetYaw, targetPitch, radiusDeg, Double.NaN, ft, fy, fp);
	}
}
