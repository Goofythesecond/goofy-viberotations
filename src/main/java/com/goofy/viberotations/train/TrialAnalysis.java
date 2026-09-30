package com.goofy.viberotations.train;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Measures how the crosshair moved during one trial. Recorded and simulated trials go through
 * this same code, so any difference between them comes from the movement, not the measuring.
 *
 * <p>Frames are split into movements wherever the mouse is still for longer than a couple of
 * frames. The first movement that covers a real part of the distance is the <em>primary</em>
 * movement; everything after it is a correction.</p>
 */
public final class TrialAnalysis {

	/** Movements shorter than this (degrees) are tremor, not corrections. */
	private static final double MIN_SUBMOVEMENT_DEG = 0.3;

	/**
	 * @param amplitude     distance from start to target, degrees
	 * @param reactionMs    target shown → first frame of the primary movement
	 * @param primaryAmp    size of the primary movement, degrees
	 * @param primaryMs     primary movement duration
	 * @param peakTime      when the primary movement is fastest, 0..1 of its duration
	 * @param speedCv       spread of speed through the middle of the primary movement (0 = constant); NaN if too short
	 * @param spikiness     median frame-to-frame unevenness of speed in the primary movement
	 * @param endpointRatio primary endpoint along the target direction: 1 = on target, below = short
	 * @param sideError     primary endpoint's sideways error, as a fraction of the distance (signed)
	 * @param curvature     largest sideways bulge of the primary path, as a fraction of its length (signed)
	 * @param submovements  movements from the primary one to the end (1 = no corrections)
	 * @param totalMs       target shown → last movement ends
	 * @param endError      distance from the final aim to the target centre, degrees
	 * @param pauses        gaps between movements after the primary one, ms
	 * @param segments      {amplitude, duration ms} of every movement long enough to time
	 * @param frameMs       median time between frames inside movements
	 */
	public record Features(double amplitude, double reactionMs, double primaryAmp, double primaryMs, double peakTime,
			double speedCv, double spikiness, double endpointRatio, double sideError, double curvature,
			int submovements, double totalMs, double endError, double[] pauses, double[][] segments, double frameMs) {}

	private TrialAnalysis() {}

	/** Returns null when the trial has no movement worth measuring. */
	public static Features analyze(Trial tr) {
		int n = tr.frames();
		if (n < 2) {
			return null;
		}
		double[] t = tr.t();
		double gap = Math.max(25, 2.2 * medianInterval(t));

		// Split into movements: {first index, last index}.
		List<int[]> segs = new ArrayList<>();
		int s0 = 0;
		for (int i = 1; i <= n; i++) {
			if (i == n || t[i] - t[i - 1] > gap) {
				segs.add(new int[]{s0, i - 1});
				s0 = i;
			}
		}
		// Stop after the movement in progress when the crosshair settled on the target.
		if (!Double.isNaN(tr.endMs())) {
			int keep = 0;
			while (keep < segs.size() && t[segs.get(keep)[0]] <= tr.endMs()) {
				keep++;
			}
			segs = segs.subList(0, Math.max(1, keep));
		}

		// Cumulative aim offset from the start after each frame.
		double[] cy = new double[n];
		double[] cp = new double[n];
		double y = 0, p = 0;
		for (int i = 0; i < n; i++) {
			y += tr.dYaw()[i];
			p += tr.dPitch()[i];
			cy[i] = y;
			cp[i] = p;
		}
		double ty = tr.targetYaw() - tr.startYaw();
		double tp = tr.targetPitch() - tr.startPitch();
		double amp = Math.hypot(ty, tp);

		int primary = -1;
		for (int k = 0; k < segs.size(); k++) {
			if (segAmp(segs.get(k), cy, cp) >= Math.max(1.0, 0.15 * amp)) {
				primary = k;
				break;
			}
		}
		if (primary < 0) {
			return null;
		}
		int[] ps = segs.get(primary);
		int a = ps[0];
		int b = ps[1];
		double y0 = a > 0 ? cy[a - 1] : 0;
		double p0 = a > 0 ? cp[a - 1] : 0;
		// Endpoint relative to what was left to cover when the primary movement began.
		double ry = ty - y0, rp = tp - p0;
		double rem = Math.max(1e-6, Math.hypot(ry, rp));
		double my = cy[b] - y0, mp = cp[b] - p0;
		double along = (my * ry + mp * rp) / rem;
		double side = (mp * ry - my * rp) / rem;

		double frameMs = medianInterval(Arrays.copyOfRange(t, a, b + 1));
		double pDur = t[b] - t[a] + frameMs;

		// Per-frame speed (deg/s) with the first frame's interval assumed typical.
		int len = b - a + 1;
		double[] v = new double[len];
		for (int i = 0; i < len; i++) {
			double dt = i == 0 ? frameMs : Math.max(0.5, t[a + i] - t[a + i - 1]);
			v[i] = Math.hypot(tr.dYaw()[a + i], tr.dPitch()[a + i]) / dt * 1000;
		}
		double[] sm = smooth(v);
		int pk = 0;
		for (int i = 1; i < len; i++) {
			if (sm[i] > sm[pk]) {
				pk = i;
			}
		}
		double peakTime = len < 3 ? 0.5 : (t[a + pk] - t[a] + frameMs * 0.5) / pDur;

		List<Double> mid = new ArrayList<>();
		for (int i = 0; i < len; i++) {
			double frac = (t[a + i] - t[a] + frameMs * 0.5) / pDur;
			if (frac >= 0.2 && frac <= 0.8) {
				mid.add(sm[i]);
			}
		}
		double speedCv = Double.NaN;
		if (mid.size() >= 4) {
			double mean = mid.stream().mapToDouble(Double::doubleValue).average().orElse(0);
			double var = mid.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0);
			speedCv = mean > 0 ? Math.sqrt(var) / mean : Double.NaN;
		}
		List<Double> spikes = new ArrayList<>();
		for (int i = 0; i < len; i++) {
			if (v[i] > 0 && sm[i] > 0) {
				spikes.add(Math.abs(Math.log(v[i] / sm[i])));
			}
		}

		// Largest sideways distance of the path from the straight line between its ends.
		double chord = Math.max(1e-6, Math.hypot(my, mp));
		double bulge = 0;
		for (int i = a; i <= b; i++) {
			double d = ((cp[i] - p0) * my - (cy[i] - y0) * mp) / chord;
			if (Math.abs(d) > Math.abs(bulge)) {
				bulge = d;
			}
		}

		int subs = 0;
		int lastSeg = primary;
		List<Double> pauses = new ArrayList<>();
		for (int k = primary; k < segs.size(); k++) {
			if (k == primary || segAmp(segs.get(k), cy, cp) >= MIN_SUBMOVEMENT_DEG) {
				if (k != primary) {
					pauses.add(t[segs.get(k)[0]] - t[segs.get(lastSeg)[1]]);
				}
				subs++;
				lastSeg = k;
			}
		}
		int end = segs.get(lastSeg)[1];

		List<double[]> timed = new ArrayList<>();
		for (int[] sg : segs) {
			double sa = segAmp(sg, cy, cp);
			if (sg[1] - sg[0] >= 2 && sa >= 0.5) {
				timed.add(new double[]{sa, t[sg[1]] - t[sg[0]] + frameMs});
			}
		}

		return new Features(amp, t[a], Math.hypot(my, mp), pDur, peakTime, speedCv, median(spikes),
				along / rem, side / rem, bulge / chord, subs, t[end] + frameMs,
				Math.hypot(ty - cy[end], tp - cp[end]),
				pauses.stream().mapToDouble(Double::doubleValue).toArray(),
				timed.toArray(new double[0][]), frameMs);
	}

	private static double segAmp(int[] sg, double[] cy, double[] cp) {
		double y0 = sg[0] > 0 ? cy[sg[0] - 1] : 0;
		double p0 = sg[0] > 0 ? cp[sg[0] - 1] : 0;
		return Math.hypot(cy[sg[1]] - y0, cp[sg[1]] - p0);
	}

	/** Three-frame moving average. */
	private static double[] smooth(double[] v) {
		double[] out = new double[v.length];
		for (int i = 0; i < v.length; i++) {
			double sum = 0;
			int c = 0;
			for (int j = Math.max(0, i - 1); j <= Math.min(v.length - 1, i + 1); j++) {
				sum += v[j];
				c++;
			}
			out[i] = sum / c;
		}
		return out;
	}

	private static double medianInterval(double[] t) {
		if (t.length < 2) {
			return 8;
		}
		double[] d = new double[t.length - 1];
		for (int i = 1; i < t.length; i++) {
			d[i - 1] = t[i] - t[i - 1];
		}
		Arrays.sort(d);
		return Math.max(1, d[d.length / 2]);
	}

	public static double median(List<Double> xs) {
		if (xs.isEmpty()) {
			return Double.NaN;
		}
		double[] s = xs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
	}
}
