package com.goofy.viberotations.train;

import com.goofy.viberotations.engine.EngineParams;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A trained aim profile: one parameter set per {@link Style}, plus how well it reproduces the
 * player's training data. Plain fields so it serializes to JSON as-is.
 */
public final class AimSeed {

	public enum Status { INITIAL, ACCEPTED, MERGED, REJECTED }

	public int id;
	/** Seed this one was trained from, or 0. */
	public int parent;
	public int generation;
	public Status status = Status.INITIAL;
	/** Short fingerprint of the parameter values, e.g. {@code 3F9A-12C0}. */
	public String code = "";
	public String created = "";
	/** Score on the validation trials (0–100, higher = closer to you). This is the one compared. */
	public double score;
	/**
	 * Score on the trials it was fitted on. It's normally higher than {@link #score}; with few trials
	 * that is mostly sampling noise, but a gap that doesn't shrink as data grows means it memorised.
	 */
	public double trainScore;
	public int trials;
	public String note = "";
	/** style id → parameter name → value. */
	public Map<String, Map<String, Double>> params = new LinkedHashMap<>();
	/** style id → validation score. */
	public Map<String, Double> styleScores = new LinkedHashMap<>();
	/** metric → {you, model}, validation trials of the natural style. */
	public Map<String, double[]> metrics = new LinkedHashMap<>();
	/** Per {@link Trial#AMP_BINS} bin: how far off the model's time-to-target is (|log ratio|). */
	public double[] binWeakness = new double[Trial.AMP_BINS.length - 1];

	public EngineParams paramsFor(Style style) {
		Map<String, Double> m = params.get(style.id());
		if (m == null) {
			m = params.get(Style.NATURAL.id());
		}
		EngineParams p = new EngineParams();
		if (m != null) {
			m.forEach(p::setValue);
		}
		return p;
	}

	public void setParams(Style style, EngineParams p) {
		Map<String, Double> m = new LinkedHashMap<>();
		for (String name : EngineParams.names()) {
			m.put(name, p.value(name));
		}
		params.put(style.id(), m);
	}

	public Map<Style, EngineParams> allParams() {
		Map<Style, EngineParams> out = new EnumMap<>(Style.class);
		for (Style s : Style.values()) {
			if (params.containsKey(s.id())) {
				out.put(s, paramsFor(s));
			}
		}
		return out;
	}

	/** Recomputes {@link #code} from the parameter values. */
	public void fingerprint() {
		long h = 1125899906842597L;
		for (Map<String, Double> m : params.values()) {
			for (double v : m.values()) {
				h = 31 * h + Double.hashCode(Math.round(v * 1e4) / 1e4);
			}
		}
		code = String.format(Locale.ROOT, "%04X-%04X", (h >>> 48) & 0xFFFF, (h >>> 16) & 0xFFFF);
	}

	public String label() {
		return String.format(Locale.ROOT, "#%d %s (gen %d, %s, score %.1f)", id, code, generation,
				status.name().toLowerCase(Locale.ROOT), score);
	}
}
