package com.goofy.viberotations.train;

import com.goofy.viberotations.engine.EngineParams;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses the engine itself as a stand-in player with known parameters, so the fitter's results
 * can be checked against the truth.
 */
class SeedFitterTest {

	private static final double SENS = 0.5;

	/** A "player" noticeably different from the defaults: slower, more undershoot, slower reaction. */
	private static EngineParams player() {
		EngineParams p = new EngineParams();
		p.sensitivity = SENS;
		p.durA = 70;
		p.durB = 0.4;
		p.pUndershoot = 0.65;
		p.pOvershoot = 0.1;
		p.reactionMedianMs = 320;
		p.pauseMedianMs = 110;
		return p;
	}

	/** Trials spread like the trainer spreads them, played by {@code who}. */
	private static List<Trial> trials(EngineParams who, Style style, int n, long seed) {
		Random rng = new Random(seed);
		List<Trial> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int bin = i % (Trial.AMP_BINS.length - 1);
			double lo = Trial.AMP_BINS[bin], hi = Trial.AMP_BINS[bin + 1];
			double amp = Math.exp(Math.log(lo) + rng.nextDouble() * (Math.log(hi) - Math.log(lo)));
			double dir = rng.nextDouble() * 2 * Math.PI;
			double pitch0 = rng.nextGaussian() * 10;
			double dp = Math.max(-60, Math.min(60, pitch0 + amp * Math.sin(dir) * 0.4)) - pitch0;
			Trial target = new Trial(style.id() + seed + "/" + i, style, 0, pitch0, amp * Math.cos(dir), pitch0 + dp,
					1, Double.NaN, new double[0], new double[0], new double[0]);
			out.add(target.simulate(who, seed * 7919 + i));
		}
		return out;
	}

	@Test
	void analysesASimulatedTrial() {
		Trial t = trials(player(), Style.NATURAL, 1, 3).get(0);
		TrialAnalysis.Features f = TrialAnalysis.analyze(t);
		assertNotNull(f);
		assertTrue(f.reactionMs() > 50 && f.reactionMs() < 2000, "reaction " + f.reactionMs());
		assertTrue(f.submovements() >= 1);
		assertTrue(f.peakTime() > 0 && f.peakTime() <= 1);
	}

	@Test
	void fittedSeedIsCloserToThePlayerThanDefaults() {
		List<Trial> data = trials(player(), Style.NATURAL, 160, 1);
		SeedFitter fitter = new SeedFitter(data, SENS, 60, null);

		AimSeed defaults = new AimSeed();
		EngineParams d = new EngineParams();
		d.sensitivity = SENS;
		defaults.setParams(Style.NATURAL, d);

		AimSeed fitted = fitter.fit(null);
		SeedFitter.Comparison c = fitter.compare(defaults, fitted);
		assertTrue(c.wins(), "gain " + c.gain() + ", confidence " + c.confidence());

		EngineParams p = fitted.paramsFor(Style.NATURAL);
		assertTrue(Math.abs(p.reactionMedianMs - 320) < Math.abs(new EngineParams().reactionMedianMs - 320),
				"reaction " + p.reactionMedianMs);
		assertTrue(p.pUndershoot > new EngineParams().pUndershoot, "pUndershoot " + p.pUndershoot);
	}

	@Test
	void keepsCurrentSeedWhenCandidateIsNoBetter() {
		List<Trial> data = trials(player(), Style.NATURAL, 120, 2);
		SeedFitter fitter = new SeedFitter(data, SENS, 0, null);
		AimSeed truth = new AimSeed();
		truth.id = 1;
		truth.generation = 1;
		truth.setParams(Style.NATURAL, player());
		fitter.score(truth);

		SeedFitter.Outcome out = fitter.evolve(truth, 2);
		assertSame(truth, out.result());
		assertEquals(AimSeed.Status.REJECTED, out.candidate().status);
	}

	@Test
	void replacesOrImprovesAPoorSeed() {
		List<Trial> data = trials(player(), Style.NATURAL, 120, 4);
		SeedFitter fitter = new SeedFitter(data, SENS, 60, null);
		AimSeed poor = new AimSeed();
		poor.id = 1;
		poor.generation = 1;
		EngineParams bad = player();
		bad.durA = 200;
		bad.reactionMedianMs = 120;
		bad.pOvershoot = 0.6;
		bad.pUndershoot = 0.1;
		poor.setParams(Style.NATURAL, bad);
		fitter.score(poor);
		double before = poor.score;

		SeedFitter.Outcome out = fitter.evolve(poor, 2);
		assertTrue(out.result() != poor, out.summary());
		assertTrue(out.result().score >= before + SeedFitter.MIN_GAIN, out.summary());
		assertEquals(2, out.result().generation);
	}

	@Test
	void constantSpeedStyleLearnsALinearProfile() {
		EngineParams sweeper = player();
		sweeper.profileLinear = 0.9;
		sweeper.profileTrim = 0;
		List<Trial> data = new ArrayList<>(trials(player(), Style.NATURAL, 60, 5));
		data.addAll(trials(sweeper, Style.CONSTANT, 60, 6));
		SeedFitter fitter = new SeedFitter(data, SENS, 80, null);
		AimSeed s = fitter.fit(null);
		assertTrue(s.paramsFor(Style.CONSTANT).profileLinear > s.paramsFor(Style.NATURAL).profileLinear,
				"constant " + s.paramsFor(Style.CONSTANT).profileLinear + " natural " + s.paramsFor(Style.NATURAL).profileLinear);
	}
}
