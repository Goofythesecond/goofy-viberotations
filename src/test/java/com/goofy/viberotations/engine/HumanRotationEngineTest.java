package com.goofy.viberotations.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanRotationEngineTest {

	private static final List<double[]> BURST = List.of(
			new double[]{40, 0}, new double[]{25, 8}, new double[]{70, -5}, new double[]{60, 0});

	private static List<HumanRotationEngine.Arrival> run(long seed, boolean anticipate, double dwellMs) {
		HumanRotationEngine e = new HumanRotationEngine(new EngineParams(), seed, 0, 0);
		e.setWaypoints(BURST, dwellMs, anticipate);
		List<HumanRotationEngine.Arrival> out = new ArrayList<>();
		while (e.busy()) {
			e.advance(50, null);
			out.addAll(e.drainArrivals());
		}
		return out;
	}

	@Test
	void reportsEveryWaypointOfABurstInOrderNearItsTarget() {
		EngineParams p = new EngineParams();
		for (long seed = 0; seed < 50; seed++) {
			List<HumanRotationEngine.Arrival> a = run(seed, true, 0);
			assertEquals(BURST.size(), a.size());
			for (int i = 0; i < a.size(); i++) {
				assertEquals(i, a.get(i).waypoint());
				double err = Math.hypot(a.get(i).yaw() - BURST.get(i)[0], a.get(i).pitch() - BURST.get(i)[1]);
				// Arrives within tolerance unless it ran out of corrections.
				assertTrue(err < p.toleranceDeg || a.get(i).submovements() >= p.maxSubmovements, "err " + err);
				if (i > 0) {
					assertTrue(a.get(i).timeMs() > a.get(i - 1).timeMs());
				}
			}
		}
	}

	@Test
	void anticipatedBurstsAreFasterAndGapsAddTime() {
		double normal = 0, anticipated = 0, gapped = 0;
		for (long seed = 0; seed < 200; seed++) {
			normal += run(seed, false, 0).get(3).timeMs();
			anticipated += run(seed, true, 0).get(3).timeMs();
			gapped += run(seed, true, 200).get(3).timeMs();
		}
		// Three follow-up flicks skip a ~220 ms reaction for a ~62 ms pause.
		assertTrue(normal - anticipated > 200 * 200, "normal " + normal / 200 + " anticipated " + anticipated / 200);
		// Three 200 ms gaps; the random draws differ once timing shifts, so allow slack.
		assertTrue(gapped - anticipated > 450 * 200, "gapped " + gapped / 200 + " anticipated " + anticipated / 200);
	}
}
