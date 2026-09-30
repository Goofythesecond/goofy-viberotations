package com.goofy.viberotations.train;

import java.util.Locale;

/**
 * How the player is asked to move onto a training target. Each style gets its own fitted
 * {@link com.goofy.viberotations.engine.EngineParams} in a seed, because a deliberate
 * constant-speed sweep and a fight flick are different movements.
 */
public enum Style {
	NATURAL("Aim at it like you would in a fight", 2),
	CONSTANT("Move onto it at a constant speed", 1),
	ACCEL_MID("Speed up, fastest halfway, then slow down onto it", 1),
	FLICK("Flick onto it as fast as you can", 1),
	PRECISE("Slow and precise: land on it in one movement", 1);

	/** Shown to the player before and during the trial. */
	public final String instruction;
	/** Relative share of trials; natural aim matters most, so it gets more. */
	public final int weight;

	Style(String instruction, int weight) {
		this.instruction = instruction;
		this.weight = weight;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static Style parse(String s) {
		return valueOf(s.trim().toUpperCase(Locale.ROOT));
	}

	/**
	 * How well a trial followed this style's instruction, 0..100, or NaN when the style has no
	 * single right way to move.
	 */
	public double adherence(TrialAnalysis.Features f) {
		return switch (this) {
			case CONSTANT -> Double.isNaN(f.speedCv()) ? Double.NaN : 100 * clamp01(1 - f.speedCv());
			case ACCEL_MID -> 100 * clamp01(1 - 2 * Math.abs(f.peakTime() - 0.5));
			case PRECISE -> 100.0 / f.submovements() * (f.endpointRatio() > 1.08 ? 0.5 : 1);
			case NATURAL, FLICK -> Double.NaN;
		};
	}

	private static double clamp01(double v) {
		return Math.max(0, Math.min(1, v));
	}
}
