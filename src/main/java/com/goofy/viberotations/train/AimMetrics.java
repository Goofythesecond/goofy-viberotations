package com.goofy.viberotations.train;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Summary statistics over many analysed trials, and a distance between two such summaries.
 * The score of a seed is how close the model's statistics are to the player's.
 */
public final class AimMetrics {

	/** One broken metric can't dominate the score (same cap as analysis/tune.py). */
	private static final double CAP = Math.log(3);
	/** Metrics that can be near zero are compared with an offset so tiny values don't explode the ratio. */
	private static final Map<String, Double> OFFSET = Map.of(
			"undershoot %", 5.0, "overshoot %", 5.0, "peak time spread", 0.03,
			"sideways error", 0.01, "path curvature", 0.01, "speed unevenness", 0.02,
			"frame spikiness", 0.02, "end error deg", 0.2);

	private AimMetrics() {}

	public static Map<String, Double> of(List<TrialAnalysis.Features> fs) {
		Map<String, Double> m = new LinkedHashMap<>();
		m.put("reaction ms", pct(fs, TrialAnalysis.Features::reactionMs, 50));
		m.put("reaction spread", pct(fs, TrialAnalysis.Features::reactionMs, 75) / pct(fs, TrialAnalysis.Features::reactionMs, 25));

		List<double[]> segs = new ArrayList<>();
		for (TrialAnalysis.Features f : fs) {
			segs.addAll(List.of(f.segments()));
		}
		double[] fit = powerFit(segs);
		m.put("movement ms at 20 deg", fit == null ? Double.NaN : fit[0] * Math.pow(20, fit[1]));
		m.put("movement ms growth", fit == null ? Double.NaN : fit[1]);
		m.put("movement ms spread", fit == null ? Double.NaN : fit[2]);

		m.put("peak time", pct(fs, TrialAnalysis.Features::peakTime, 50));
		m.put("peak time spread", pct(fs, TrialAnalysis.Features::peakTime, 75) - pct(fs, TrialAnalysis.Features::peakTime, 25));
		m.put("speed unevenness", pct(fs, TrialAnalysis.Features::speedCv, 50));
		m.put("frame spikiness", pct(fs, TrialAnalysis.Features::spikiness, 50));

		List<Double> under = new ArrayList<>();
		List<Double> over = new ArrayList<>();
		for (TrialAnalysis.Features f : fs) {
			if (f.endpointRatio() < 0.92) {
				under.add(1 - f.endpointRatio());
			} else if (f.endpointRatio() > 1.08) {
				over.add(f.endpointRatio() - 1);
			}
		}
		m.put("undershoot %", fs.isEmpty() ? Double.NaN : 100.0 * under.size() / fs.size());
		m.put("overshoot %", fs.isEmpty() ? Double.NaN : 100.0 * over.size() / fs.size());
		m.put("undershoot size", TrialAnalysis.median(under));
		m.put("overshoot size", TrialAnalysis.median(over));
		m.put("sideways error", pct(fs, f -> Math.abs(f.sideError()), 50));
		m.put("path curvature", pct(fs, f -> Math.abs(f.curvature()), 50));
		m.put("movements per target", fs.stream().mapToDouble(TrialAnalysis.Features::submovements).average().orElse(Double.NaN));

		List<Double> pauses = new ArrayList<>();
		for (TrialAnalysis.Features f : fs) {
			for (double p : f.pauses()) {
				pauses.add(p);
			}
		}
		m.put("pause ms", TrialAnalysis.median(pauses));
		m.put("time to target <12 deg", pct(filter(fs, 0, 12), TrialAnalysis.Features::totalMs, 50));
		m.put("time to target 12-50 deg", pct(filter(fs, 12, 50), TrialAnalysis.Features::totalMs, 50));
		m.put("time to target >50 deg", pct(filter(fs, 50, 1000), TrialAnalysis.Features::totalMs, 50));
		m.put("end error deg", pct(fs, TrialAnalysis.Features::endError, 50));
		return m;
	}

	/** Mean capped |log ratio| over metrics both sides have. 0 = identical; NaN if nothing to compare. */
	public static double distance(Map<String, Double> you, Map<String, Double> model) {
		double sum = 0;
		int n = 0;
		for (Map.Entry<String, Double> e : you.entrySet()) {
			double d = metricDistance(e.getKey(), e.getValue(), model.getOrDefault(e.getKey(), Double.NaN));
			if (!Double.isNaN(d)) {
				sum += d;
				n++;
			}
		}
		return n == 0 ? Double.NaN : sum / n;
	}

	public static double metricDistance(String name, double you, double model) {
		if (Double.isNaN(you) || Double.isNaN(model) || Double.isInfinite(you) || Double.isInfinite(model)) {
			return Double.NaN;
		}
		double c = OFFSET.getOrDefault(name, 0.0);
		if (you + c <= 0 || model + c <= 0) {
			return you == model ? 0 : CAP;
		}
		return Math.min(CAP, Math.abs(Math.log((model + c) / (you + c))));
	}

	/** Distance → 0..100 score. */
	public static double score(double distance) {
		return Double.isNaN(distance) ? 0 : 100 * Math.exp(-distance);
	}

	/** Fits duration = a * amplitude^b; returns {a, b, residual sd} or null if the data can't support it. */
	static double[] powerFit(List<double[]> pts) {
		if (pts.size() < 6) {
			return null;
		}
		double mx = 0, my = 0;
		for (double[] p : pts) {
			mx += Math.log(p[0]);
			my += Math.log(p[1]);
		}
		mx /= pts.size();
		my /= pts.size();
		double sxx = 0, sxy = 0;
		for (double[] p : pts) {
			double dx = Math.log(p[0]) - mx;
			sxx += dx * dx;
			sxy += dx * (Math.log(p[1]) - my);
		}
		// Needs a real spread of sizes, otherwise the slope is noise.
		if (sxx / pts.size() < 0.09) {
			return null;
		}
		double b = sxy / sxx;
		double la = my - b * mx;
		double ss = 0;
		for (double[] p : pts) {
			double r = Math.log(p[1]) - (la + b * Math.log(p[0]));
			ss += r * r;
		}
		return new double[]{Math.exp(la), b, Math.sqrt(ss / pts.size())};
	}

	static List<TrialAnalysis.Features> filter(List<TrialAnalysis.Features> fs, double lo, double hi) {
		return fs.stream().filter(f -> f.amplitude() >= lo && f.amplitude() < hi).toList();
	}

	static double pct(List<TrialAnalysis.Features> fs, ToDoubleFunction<TrialAnalysis.Features> key, double p) {
		double[] xs = fs.stream().mapToDouble(key).filter(v -> !Double.isNaN(v)).sorted().toArray();
		return pct(xs, p);
	}

	static double pct(double[] sorted, double p) {
		if (sorted.length == 0) {
			return Double.NaN;
		}
		double k = (sorted.length - 1) * p / 100;
		int lo = (int) k;
		int hi = Math.min(lo + 1, sorted.length - 1);
		return sorted[lo] + (sorted[hi] - sorted[lo]) * (k - lo);
	}
}
