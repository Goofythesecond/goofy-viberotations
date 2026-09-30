package com.goofy.viberotations.npc;

import java.util.Locale;

/**
 * {@code /npc auto} settings. Kept across runs and read live, so changes apply from the next
 * burst of a run that is already going.
 */
final class AutoSettings {

	static final int DEFAULT_REST_MIN_MS = 150;
	static final int DEFAULT_REST_MAX_MS = 600;

	/** Targets per burst; 1 = one target at a time. */
	int burst = 1;
	/** Pause on each target inside a burst before moving to the next. */
	int gapMs = 0;
	/** Rest between bursts (or between single targets): uniform in [min, max]. */
	int restMinMs = DEFAULT_REST_MIN_MS;
	int restMaxMs = DEFAULT_REST_MAX_MS;
	/** Show each target as a glowing block. */
	boolean blocks = true;

	String describe() {
		String rest = restMinMs == restMaxMs ? restMinMs + " ms" : restMinMs + "–" + restMaxMs + " ms";
		String b = burst <= 1 ? "one target at a time, " + rest + " between targets"
				: String.format(Locale.ROOT, "bursts of %d (%d ms on each target), %s between bursts", burst, gapMs, rest);
		return b + ", target blocks " + (blocks ? "on" : "off");
	}
}
