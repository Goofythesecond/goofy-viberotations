package com.goofy.viberotations.train;

import com.goofy.viberotations.engine.EngineParams;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Fits an {@link AimSeed} to training trials and decides whether it replaces the current one.
 *
 * <p>Every trial is permanently assigned to <em>train</em> (2 in 3) or <em>validation</em>
 * (1 in 3) by a hash of its id. Fitting only sees train trials; seeds are compared only on
 * validation trials, with a paired bootstrap so a lucky split can't decide it. A candidate that
 * just memorised its training data therefore doesn't beat the current seed and is rejected.</p>
 *
 * <p>Fitting a style: estimate parameters directly from the measured features, compare that
 * with the current seed's parameters, and hill-climb from the better one. The simulated side
 * uses fixed random seeds per trial, so two parameter sets are always compared on identical
 * noise.</p>
 */
public final class SeedFitter {

	/** A style needs this many trials before it gets its own parameters. */
	public static final int MIN_STYLE_TRIALS = 6;
	/** A new seed must beat the current one by this much to replace it. */
	public static final double MIN_GAIN = 0.5;
	/** ...and be the better one in at least this share of bootstrap resamples of the validation trials. */
	public static final double MIN_CONFIDENCE = 0.75;
	private static final int BOOTSTRAP = 200;
	private static final int TRAIN_REPS = 2;
	private static final int VALIDATION_REPS = 3;

	/** name → {min, max, log scale (1/0)}. */
	private static final Map<String, double[]> SPACE = new LinkedHashMap<>();
	/** Parameter groups swapped as a unit when merging a rejected seed into the current one. */
	private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

	static {
		space("durA", 5, 300, true);
		space("durB", 0.15, 0.9, false);
		space("durSigma", 0.1, 1.2, false);
		space("peakTimeMean", 0.2, 0.8, false);
		space("peakTimeSd", 0.02, 0.35, false);
		space("profileTrim", 0, 0.3, false);
		space("profileLinear", 0, 1, false);
		space("curvatureSd", 0, 0.25, false);
		space("directionSd", 0, 0.3, false);
		space("pUndershoot", 0, 0.9, false);
		space("pOvershoot", 0, 0.7, false);
		space("undershootMedian", 0.02, 0.6, true);
		space("overshootMedian", 0.02, 0.8, true);
		space("accurateSd", 0.005, 0.1, true);
		space("pauseMedianMs", 10, 400, true);
		space("pauseSigma", 0.2, 1.8, false);
		space("pauseMaxMs", 100, 2500, true);
		space("reactionMedianMs", 80, 900, true);
		space("reactionSigma", 0.05, 0.9, false);
		space("toleranceDeg", 0.2, 6, true);
		space("deliveryNoise", 0, 0.9, false);
		space("fps", 30, 400, true);

		GROUPS.put("timing", List.of("durA", "durB", "durSigma", "minDurationMs"));
		GROUPS.put("speed profile", List.of("peakTimeMean", "peakTimeSd", "profileTrim", "profileLinear", "curvatureSd"));
		GROUPS.put("landing", List.of("pUndershoot", "pOvershoot", "undershootMedian", "undershootSigma", "undershootMax",
				"overshootMedian", "overshootSigma", "overshootMax", "accurateSd", "directionSd"));
		GROUPS.put("corrections", List.of("pauseMedianMs", "pauseSigma", "pauseMaxMs", "toleranceDeg", "maxSubmovements"));
		GROUPS.put("reaction", List.of("reactionMedianMs", "reactionSigma"));
		GROUPS.put("mouse input", List.of("deliveryNoise", "fps", "frameJitter"));
	}

	private static void space(String name, double lo, double hi, boolean log) {
		SPACE.put(name, new double[]{lo, hi, log ? 1 : 0});
	}

	/** Recorded trials of one style, split and pre-analysed. */
	private static final class Data {
		final List<Trial> train = new ArrayList<>();
		final List<Trial> val = new ArrayList<>();
		Map<String, Double> trainMetrics;
		Map<String, Double> valMetrics;
		List<TrialAnalysis.Features> trainFeatures;

		int size() {
			return train.size() + val.size();
		}
	}

	/** What {@link #evolve} decided. {@code result} is the seed to use from now on. */
	public record Outcome(AimSeed result, AimSeed candidate, AimSeed merged, String summary) {}

	private final Map<Style, Data> data = new EnumMap<>(Style.class);
	private final double sensitivity;
	private final int iterations;
	private final Consumer<String> progress;

	public SeedFitter(List<Trial> trials, double sensitivity, int iterations, Consumer<String> progress) {
		this.sensitivity = sensitivity;
		this.iterations = iterations;
		this.progress = progress == null ? s -> { } : progress;
		for (Trial t : trials) {
			if (TrialAnalysis.analyze(t) == null) {
				continue;
			}
			Data d = data.computeIfAbsent(t.style(), s -> new Data());
			(isValidation(t) ? d.val : d.train).add(t);
		}
		for (Data d : data.values()) {
			d.trainFeatures = analyze(d.train);
			d.trainMetrics = AimMetrics.of(d.trainFeatures);
			d.valMetrics = AimMetrics.of(analyze(d.val));
		}
	}

	public static boolean isValidation(Trial t) {
		return Math.floorMod(mix(t.id().hashCode()), 3) == 0;
	}

	/** Styles with enough trials to fit on their own. */
	public List<Style> fittable() {
		List<Style> out = new ArrayList<>();
		for (Style s : Style.values()) {
			Data d = data.get(s);
			if (d != null && d.size() >= MIN_STYLE_TRIALS && d.train.size() >= 3) {
				out.add(s);
			}
		}
		return out;
	}

	public int trialCount() {
		return data.values().stream().mapToInt(Data::size).sum();
	}

	// ─── Evolution ───────────────────────────────────────────────────────

	/**
	 * Trains a candidate from {@code current} (or from scratch) and keeps whichever is better on
	 * validation trials. A losing candidate isn't thrown away blindly: each of its parameter
	 * groups is tried inside the current seed and kept if it helps.
	 */
	public Outcome evolve(AimSeed current, int nextId) {
		if (fittable().isEmpty()) {
			throw new IllegalStateException("Not enough training data yet: need " + MIN_STYLE_TRIALS
					+ " usable trials of at least one style (have " + trialCount() + " in total)");
		}
		AimSeed cand = fit(current);
		cand.id = nextId;
		cand.parent = current == null ? 0 : current.id;
		cand.generation = current == null ? 1 : current.generation + 1;
		score(cand);

		if (current == null) {
			cand.status = AimSeed.Status.INITIAL;
			cand.note = "First seed";
			return new Outcome(cand, cand, null, String.format(Locale.ROOT,
					"New seed %s scored %.1f on %d validation trials.", cand.label(), cand.score, validationCount()));
		}
		double before = current.score;
		// More data than last time, so re-score the current seed on the same trials as the candidate.
		score(current);
		String rescored = Math.abs(before - current.score) > 0.05
				? String.format(Locale.ROOT, " (re-scored %.1f → %.1f on the new data)", before, current.score) : "";

		Comparison c = compare(current, cand);
		if (c.wins()) {
			cand.status = AimSeed.Status.ACCEPTED;
			cand.note = String.format(Locale.ROOT, "Beat #%d by %.1f (%.0f%% of resamples)", current.id, c.gain(), 100 * c.confidence());
			return new Outcome(cand, cand, null, String.format(Locale.ROOT,
					"Accepted: new seed %.1f vs current %.1f%s, better in %.0f%% of resamples.",
					cand.score, current.score, rescored, 100 * c.confidence()));
		}

		cand.status = AimSeed.Status.REJECTED;
		cand.note = String.format(Locale.ROOT, "%+.1f vs #%d, better in only %.0f%% of resamples",
				c.gain(), current.id, 100 * c.confidence());
		progress.accept(String.format(Locale.ROOT, "Candidate %.1f vs current %.1f isn't clearly better; checking which parts of it are…",
				cand.score, current.score));
		List<String> taken = new ArrayList<>();
		AimSeed merged = merge(current, cand, taken);
		Comparison mc = taken.isEmpty() ? null : compare(current, merged);
		if (mc != null && mc.wins()) {
			merged.id = nextId + 1;
			merged.parent = current.id;
			merged.generation = current.generation + 1;
			merged.status = AimSeed.Status.MERGED;
			merged.note = "Took " + String.join(", ", taken) + " from rejected #" + cand.id;
			return new Outcome(merged, cand, merged, String.format(Locale.ROOT,
					"Rejected the new seed (%.1f vs %.1f%s), but merged its better parts (%s) into the current one: %.1f.",
					cand.score, current.score, rescored, String.join(", ", taken), merged.score));
		}
		return new Outcome(current, cand, null, String.format(Locale.ROOT,
				"Rejected the new seed (%.1f vs current %.1f%s). None of its parts clearly improved the current seed, so it stays.",
				cand.score, current.score, rescored));
	}

	/**
	 * Result of comparing seed B against seed A on the validation trials.
	 *
	 * @param gain       B's score minus A's, all validation trials
	 * @param confidence share of bootstrap resamples of those trials where B scored higher
	 */
	public record Comparison(double gain, double confidence) {
		public boolean wins() {
			return gain >= MIN_GAIN && confidence >= MIN_CONFIDENCE;
		}
	}

	/**
	 * Paired bootstrap: both seeds are scored on the same resampled sets of validation trials, so
	 * the luck of which trials happened to land in validation cancels out between them.
	 */
	public Comparison compare(AimSeed a, AimSeed b) {
		Map<Style, EngineParams[]> pairs = new EnumMap<>(Style.class);
		for (Style s : fittable()) {
			pairs.put(s, new EngineParams[]{a.paramsFor(s), b.paramsFor(s)});
		}
		return compare(pairs);
	}

	private Comparison compare(Map<Style, EngineParams[]> pairs) {
		record Side(List<TrialAnalysis.Features> you, List<List<TrialAnalysis.Features>> a, List<List<TrialAnalysis.Features>> b, int weight) {}
		List<Side> sides = new ArrayList<>();
		for (Map.Entry<Style, EngineParams[]> e : pairs.entrySet()) {
			Data d = data.get(e.getKey());
			// Styles with too few validation trials are judged on their training trials.
			List<Trial> ts = d.val.size() >= 3 ? d.val : d.train;
			List<TrialAnalysis.Features> you = new ArrayList<>();
			List<List<TrialAnalysis.Features>> sa = new ArrayList<>();
			List<List<TrialAnalysis.Features>> sb = new ArrayList<>();
			for (Trial t : ts) {
				TrialAnalysis.Features h = TrialAnalysis.analyze(t);
				if (h == null) {
					continue;
				}
				you.add(h);
				sa.add(simulate(List.of(t), e.getValue()[0], VALIDATION_REPS, 0x55L));
				sb.add(simulate(List.of(t), e.getValue()[1], VALIDATION_REPS, 0x55L));
			}
			if (!you.isEmpty()) {
				sides.add(new Side(you, sa, sb, d.size()));
			}
		}
		if (sides.isEmpty()) {
			return new Comparison(0, 0);
		}
		Random rng = new Random(0xB007L);
		double gain = 0;
		int better = 0;
		for (int it = 0; it <= BOOTSTRAP; it++) {
			double diff = 0;
			int weight = 0;
			for (Side s : sides) {
				int n = s.you().size();
				List<TrialAnalysis.Features> you = new ArrayList<>();
				List<TrialAnalysis.Features> fa = new ArrayList<>();
				List<TrialAnalysis.Features> fb = new ArrayList<>();
				for (int k = 0; k < n; k++) {
					// Iteration 0 is the full sample; the rest resample trials with replacement.
					int i = it == 0 ? k : rng.nextInt(n);
					you.add(s.you().get(i));
					fa.addAll(s.a().get(i));
					fb.addAll(s.b().get(i));
				}
				Map<String, Double> ym = AimMetrics.of(you);
				diff += s.weight() * (AimMetrics.score(AimMetrics.distance(ym, AimMetrics.of(fb)))
						- AimMetrics.score(AimMetrics.distance(ym, AimMetrics.of(fa))));
				weight += s.weight();
			}
			diff /= weight;
			if (it == 0) {
				gain = diff;
			} else if (diff > 0) {
				better++;
			}
		}
		return new Comparison(gain, (double) better / BOOTSTRAP);
	}

	private AimSeed merge(AimSeed current, AimSeed cand, List<String> taken) {
		AimSeed merged = copy(current);
		for (Style style : fittable()) {
			EngineParams best = merged.paramsFor(style);
			EngineParams other = cand.paramsFor(style);
			for (Map.Entry<String, List<String>> g : GROUPS.entrySet()) {
				EngineParams trial = best.copy();
				for (String name : g.getValue()) {
					trial.setValue(name, other.value(name));
				}
				Map<Style, EngineParams[]> pair = new EnumMap<>(Style.class);
				pair.put(style, new EngineParams[]{best, trial});
				if (compare(pair).wins()) {
					best = trial;
					taken.add(style.id() + " " + g.getKey());
				}
			}
			merged.setParams(style, best);
		}
		score(merged);
		return merged;
	}

	// ─── Fitting ─────────────────────────────────────────────────────────

	/** Fits every style with enough data; others copy the natural (or first fitted) style. */
	public AimSeed fit(AimSeed start) {
		AimSeed seed = new AimSeed();
		List<Style> styles = fittable();
		for (Style style : styles) {
			seed.setParams(style, fitStyle(style, start == null ? null : start.paramsFor(style)));
		}
		EngineParams base = seed.paramsFor(styles.contains(Style.NATURAL) ? Style.NATURAL : styles.get(0));
		for (Style s : Style.values()) {
			if (!seed.params.containsKey(s.id())) {
				// Carry over what the previous seed had for styles without enough data yet.
				seed.setParams(s, start != null && start.params.containsKey(s.id()) ? start.paramsFor(s) : base);
			}
		}
		seed.fingerprint();
		return seed;
	}

	private EngineParams fitStyle(Style style, EngineParams from) {
		Data d = data.get(style);
		EngineParams est = estimate(d.trainFeatures, style);
		double estScore = trainScore(style, est);
		EngineParams cur = est;
		double curScore = estScore;
		if (from != null) {
			EngineParams f = from.copy();
			f.sensitivity = sensitivity;
			double fs = trainScore(style, f);
			if (fs > curScore) {
				cur = f;
				curScore = fs;
			}
		}
		progress.accept(String.format(Locale.ROOT, "Fitting %s on %d trials (start %.1f)…", style.id(), d.train.size(), curScore));
		Random rng = new Random(mix(style.ordinal() + 31L * d.size()));
		List<String> names = new ArrayList<>(SPACE.keySet());
		for (int i = 0; i < iterations; i++) {
			EngineParams cand = cur.copy();
			int changes = 1 + rng.nextInt(3);
			for (int c = 0; c < changes; c++) {
				String name = names.get(rng.nextInt(names.size()));
				double[] sp = SPACE.get(name);
				double v = cand.value(name);
				v = sp[2] == 1
						? Math.max(sp[0], v) * Math.exp(rng.nextGaussian() * 0.25)
						: v + rng.nextGaussian() * (sp[1] - sp[0]) * 0.12;
				cand.setValue(name, Math.max(sp[0], Math.min(sp[1], v)));
			}
			// Outcome probabilities must leave room for "accurate".
			double sum = cand.pUndershoot + cand.pOvershoot;
			if (sum > 0.97) {
				cand.pUndershoot *= 0.97 / sum;
				cand.pOvershoot *= 0.97 / sum;
			}
			double s = trainScore(style, cand);
			if (s > curScore) {
				cur = cand;
				curScore = s;
			}
		}
		return cur;
	}

	/** Parameters read straight off the measurements; a starting point for the search. */
	EngineParams estimate(List<TrialAnalysis.Features> fs, Style style) {
		EngineParams p = new EngineParams();
		p.sensitivity = sensitivity;
		List<double[]> segs = new ArrayList<>();
		for (TrialAnalysis.Features f : fs) {
			segs.addAll(List.of(f.segments()));
		}
		double[] fit = AimMetrics.powerFit(segs);
		if (fit != null) {
			p.durB = clamp(fit[1], 0.15, 0.9);
			p.durA = clamp(fit[0], 5, 300);
			p.durSigma = clamp(fit[2], 0.1, 1.2);
		}
		double[] peaks = fs.stream().filter(f -> f.primaryMs() >= 4 * f.frameMs()).mapToDouble(TrialAnalysis.Features::peakTime).toArray();
		if (peaks.length >= 3) {
			p.peakTimeMean = clamp(mean(peaks), 0.2, 0.8);
			p.peakTimeSd = clamp(sd(peaks), 0.02, 0.35);
		}
		if (style == Style.CONSTANT) {
			p.profileLinear = 0.6;
		}

		List<Double> under = new ArrayList<>();
		List<Double> over = new ArrayList<>();
		List<Double> acc = new ArrayList<>();
		for (TrialAnalysis.Features f : fs) {
			double k = f.endpointRatio();
			(k < 0.92 ? under : k > 1.08 ? over : acc).add(k);
		}
		int n = Math.max(1, fs.size());
		p.pUndershoot = clamp((double) under.size() / n, 0, 0.9);
		p.pOvershoot = clamp((double) over.size() / n, 0, 0.7);
		if (under.size() >= 2) {
			double[] ls = under.stream().mapToDouble(k -> Math.log(1 - k)).toArray();
			p.undershootMedian = clamp(Math.exp(median(ls)), 0.02, 0.6);
			p.undershootSigma = clamp(sd(ls), 0.2, 1.2);
		}
		if (over.size() >= 2) {
			double[] ls = over.stream().mapToDouble(k -> Math.log(k - 1)).toArray();
			p.overshootMedian = clamp(Math.exp(median(ls)), 0.02, 0.8);
			p.overshootSigma = clamp(sd(ls), 0.2, 1.2);
		}
		if (acc.size() >= 2) {
			p.accurateSd = clamp(sd(acc.stream().mapToDouble(k -> k - 1).toArray()), 0.005, 0.1);
		}
		p.directionSd = clamp(sd(fs.stream().mapToDouble(TrialAnalysis.Features::sideError).toArray()), 0.005, 0.3);
		p.curvatureSd = clamp(sd(fs.stream().mapToDouble(TrialAnalysis.Features::curvature).toArray()), 0.005, 0.25);

		double[] pauses = fs.stream().flatMapToDouble(f -> java.util.Arrays.stream(f.pauses())).filter(x -> x > 0).map(Math::log).toArray();
		if (pauses.length >= 3) {
			p.pauseMedianMs = clamp(Math.exp(median(pauses)), 10, 400);
			p.pauseSigma = clamp(sd(pauses), 0.2, 1.8);
			double[] sorted = java.util.Arrays.stream(pauses).map(Math::exp).sorted().toArray();
			p.pauseMaxMs = clamp(Math.max(AimMetrics.pct(sorted, 95), p.pauseMedianMs * 2), 100, 2500);
		}
		double[] reactions = fs.stream().mapToDouble(TrialAnalysis.Features::reactionMs).filter(x -> x > 0).map(Math::log).toArray();
		if (reactions.length >= 3) {
			p.reactionMedianMs = clamp(Math.exp(median(reactions)), 80, 900);
			p.reactionSigma = clamp(sd(reactions), 0.05, 0.9);
		}
		double[] endErr = fs.stream().mapToDouble(TrialAnalysis.Features::endError).sorted().toArray();
		if (endErr.length >= 3) {
			p.toleranceDeg = clamp(AimMetrics.pct(endErr, 90), 0.2, 6);
		}
		p.maxSubmovements = (int) clamp(fs.stream().mapToInt(TrialAnalysis.Features::submovements).max().orElse(6), 2, 10);
		double frame = median(fs.stream().mapToDouble(TrialAnalysis.Features::frameMs).toArray());
		if (!Double.isNaN(frame)) {
			p.fps = clamp(1000 / frame, 30, 400);
		}
		// Anything the data couldn't pin down (too few samples) keeps its default.
		EngineParams defaults = new EngineParams();
		for (String name : EngineParams.names()) {
			if (Double.isNaN(p.value(name))) {
				p.setValue(name, defaults.value(name));
			}
		}
		return p;
	}

	// ─── Scoring ─────────────────────────────────────────────────────────

	/** Fills in the seed's validation score, train score, per-style scores, metric table and weak spots. */
	public void score(AimSeed seed) {
		double valSum = 0, trainSum = 0;
		int valN = 0, trainN = 0;
		seed.styleScores.clear();
		for (Style style : fittable()) {
			Data d = data.get(style);
			EngineParams p = seed.paramsFor(style);
			double v = validationScore(style, p);
			double t = trainScore(style, p);
			seed.styleScores.put(style.id(), v);
			valSum += v * d.size();
			valN += d.size();
			trainSum += t * d.train.size();
			trainN += d.train.size();
		}
		seed.score = valN == 0 ? 0 : valSum / valN;
		seed.trainScore = trainN == 0 ? 0 : trainSum / trainN;
		seed.trials = trialCount();

		// Metric table for the natural style (or whichever has most data), all its trials.
		Style shown = data.containsKey(Style.NATURAL) ? Style.NATURAL
				: data.entrySet().stream().max((a, b) -> a.getValue().size() - b.getValue().size()).orElseThrow().getKey();
		Data d = data.get(shown);
		List<Trial> all = new ArrayList<>(d.train);
		all.addAll(d.val);
		Map<String, Double> you = AimMetrics.of(analyze(all));
		Map<String, Double> model = AimMetrics.of(simulate(all, seed.paramsFor(shown), VALIDATION_REPS, 0x77L));
		seed.metrics.clear();
		you.forEach((k, v) -> seed.metrics.put(k, new double[]{v, model.getOrDefault(k, Double.NaN)}));

		// Time-to-target per amplitude bin over every style, to steer what the trainer asks for next.
		int bins = Trial.AMP_BINS.length - 1;
		List<List<Double>> youT = new ArrayList<>();
		List<List<Double>> modelT = new ArrayList<>();
		for (int i = 0; i < bins; i++) {
			youT.add(new ArrayList<>());
			modelT.add(new ArrayList<>());
		}
		for (Map.Entry<Style, Data> e : data.entrySet()) {
			EngineParams p = seed.paramsFor(e.getKey());
			List<Trial> ts = new ArrayList<>(e.getValue().train);
			ts.addAll(e.getValue().val);
			for (Trial t : ts) {
				int bin = Trial.ampBin(t.amplitude());
				TrialAnalysis.Features h = TrialAnalysis.analyze(t);
				TrialAnalysis.Features m = TrialAnalysis.analyze(t.simulate(p, simSeed(t, 0, 0x99L)));
				if (h != null && m != null) {
					youT.get(bin).add(h.totalMs());
					modelT.get(bin).add(m.totalMs());
				}
			}
		}
		for (int i = 0; i < bins; i++) {
			seed.binWeakness[i] = youT.get(i).size() < 2 ? 0
					: Math.abs(Math.log(TrialAnalysis.median(modelT.get(i)) / TrialAnalysis.median(youT.get(i))));
		}
		seed.fingerprint();
	}

	private int validationCount() {
		return data.values().stream().mapToInt(d -> d.val.size()).sum();
	}

	private double trainScore(Style style, EngineParams p) {
		Data d = data.get(style);
		return AimMetrics.score(AimMetrics.distance(d.trainMetrics, AimMetrics.of(simulate(d.train, p, TRAIN_REPS, 0x11L))));
	}

	/** Validation score, or the train score when a style has too few validation trials to measure. */
	private double validationScore(Style style, EngineParams p) {
		Data d = data.get(style);
		if (d.val.size() < 3) {
			return trainScore(style, p);
		}
		return AimMetrics.score(AimMetrics.distance(d.valMetrics, AimMetrics.of(simulate(d.val, p, VALIDATION_REPS, 0x55L))));
	}

	private static List<TrialAnalysis.Features> simulate(List<Trial> trials, EngineParams p, int reps, long salt) {
		List<TrialAnalysis.Features> out = new ArrayList<>();
		for (Trial t : trials) {
			for (int r = 0; r < reps; r++) {
				TrialAnalysis.Features f = TrialAnalysis.analyze(t.simulate(p, simSeed(t, r, salt)));
				if (f != null) {
					out.add(f);
				}
			}
		}
		return out;
	}

	private static long simSeed(Trial t, int rep, long salt) {
		return mix(t.id().hashCode() * 1_000_003L + rep) ^ salt;
	}

	private static List<TrialAnalysis.Features> analyze(List<Trial> trials) {
		List<TrialAnalysis.Features> out = new ArrayList<>();
		for (Trial t : trials) {
			TrialAnalysis.Features f = TrialAnalysis.analyze(t);
			if (f != null) {
				out.add(f);
			}
		}
		return out;
	}

	// ─── Helpers ─────────────────────────────────────────────────────────

	public static AimSeed copy(AimSeed s) {
		AimSeed c = new AimSeed();
		c.id = s.id;
		c.parent = s.parent;
		c.generation = s.generation;
		c.status = s.status;
		c.code = s.code;
		c.created = s.created;
		c.score = s.score;
		c.trainScore = s.trainScore;
		c.trials = s.trials;
		c.note = s.note;
		s.params.forEach((k, v) -> c.params.put(k, new LinkedHashMap<>(v)));
		c.styleScores.putAll(s.styleScores);
		c.metrics.putAll(s.metrics);
		c.binWeakness = s.binWeakness.clone();
		return c;
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	private static double clamp(double v, double lo, double hi) {
		return Double.isNaN(v) ? v : Math.max(lo, Math.min(hi, v));
	}

	private static double mean(double[] xs) {
		double s = 0;
		for (double x : xs) {
			s += x;
		}
		return xs.length == 0 ? Double.NaN : s / xs.length;
	}

	private static double sd(double[] xs) {
		if (xs.length < 2) {
			return Double.NaN;
		}
		double m = mean(xs);
		double s = 0;
		for (double x : xs) {
			s += (x - m) * (x - m);
		}
		return Math.sqrt(s / (xs.length - 1));
	}

	private static double median(double[] xs) {
		if (xs.length == 0) {
			return Double.NaN;
		}
		double[] s = xs.clone();
		java.util.Arrays.sort(s);
		return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
	}
}
