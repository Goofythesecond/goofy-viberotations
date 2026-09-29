package com.goofy.viberotations.engine;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tunable parameters for {@link HumanRotationEngine}. Defaults come from the recorded
 * sword data in docs/PVP_DATA.md (section 5). Every field can be changed by name with
 * {@link #set(String, String)}, from the /npc param command or harness arguments.
 */
public final class EngineParams {

	// ─── Flick timing: duration = durA * amplitude^durB * lognormal(durSigma) ───
	public double durA = 38.8;
	public double durB = 0.48;
	public double durSigma = 0.5;
	public double minDurationMs = 12;

	/** Where in the flick speed peaks (0..1): normal(peakTimeMean, peakTimeSd), clamped to 0.1..0.9. */
	public double peakTimeMean = 0.5;
	public double peakTimeSd = 0.16;

	/**
	 * Trims this fraction off both ends of the minimum-jerk curve. Pure minimum-jerk starts and
	 * stops so gently that the first and last frames fall below one mouse step; real flicks
	 * start and stop more abruptly.
	 */
	public double profileTrim = 0.1;

	/** Sideways bulge of the flick path, as a fraction of amplitude (normal, sd). */
	public double curvatureSd = 0.04;
	/** Direction error of the flick endpoint, as a fraction of amplitude (normal, sd). */
	public double directionSd = 0.05;

	// ─── Flick outcome. Remaining probability = accurate. ───
	public double pUndershoot = 0.48;
	public double pOvershoot = 0.22;
	/** Undershoot: endpoint = amplitude * (1 - lognormal(median, sigma)), capped. */
	public double undershootMedian = 0.15;
	public double undershootSigma = 0.6;
	public double undershootMax = 0.6;
	/** Overshoot: endpoint = amplitude * (1 + lognormal(median, sigma)), capped. */
	public double overshootMedian = 0.3;
	public double overshootSigma = 0.7;
	public double overshootMax = 0.8;
	/** Accurate: endpoint = amplitude * (1 + normal(0, sd)). */
	public double accurateSd = 0.03;

	// ─── Pauses and reaction ───
	/** Pause between sub-movements: lognormal(median, sigma) ms. */
	public double pauseMedianMs = 62;
	public double pauseSigma = 1.0;
	public double pauseMaxMs = 400;
	/** Delay before the first movement toward a new waypoint: lognormal(median, sigma) ms. */
	public double reactionMedianMs = 220;
	public double reactionSigma = 0.25;

	// ─── Arrival ───
	/** A waypoint counts as reached when the remaining error is below this (degrees). */
	public double toleranceDeg = 2.5;
	/** Give up correcting after this many sub-movements per waypoint. */
	public int maxSubmovements = 6;

	// ─── Input simulation ───
	/** Mouse sensitivity; sets the smallest rotation step, exactly as vanilla does. */
	public double sensitivity = 0.3849765258215962;
	/**
	 * Uneven mouse delivery: each frame passes on (1 + normal(0, sd)) of its share of the motion,
	 * the rest carries to the next frame. Real input arrives in uneven chunks, which gives
	 * human flicks their spiky per-frame speed.
	 */
	public double deliveryNoise = 0.35;
	/** Simulated render rate and its frame-time jitter (fraction). */
	public double fps = 120;
	public double frameJitter = 0.15;

	/** Smallest rotation step in degrees (vanilla: (s*0.6+0.2)^3 * 8 * 0.15). */
	public double quantum() {
		double f = sensitivity * 0.6 + 0.2;
		return f * f * f * 8.0 * 0.15;
	}

	public EngineParams copy() {
		EngineParams p = new EngineParams();
		for (Field f : fields()) {
			try {
				f.set(p, f.get(this));
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
		return p;
	}

	/** Sets a field by name. Throws IllegalArgumentException for unknown names or bad values. */
	public void set(String name, String value) {
		for (Field f : fields()) {
			if (f.getName().equals(name)) {
				try {
					if (f.getType() == int.class) {
						f.setInt(this, Integer.parseInt(value));
					} else {
						f.setDouble(this, Double.parseDouble(value));
					}
					return;
				} catch (NumberFormatException | IllegalAccessException e) {
					throw new IllegalArgumentException("Bad value for " + name + ": " + value);
				}
			}
		}
		throw new IllegalArgumentException("Unknown parameter: " + name);
	}

	/** Applies "name=value,name=value" pairs. */
	public void apply(String pairs) {
		if (pairs == null || pairs.isBlank()) {
			return;
		}
		for (String pair : pairs.split(",")) {
			String[] kv = pair.split("=", 2);
			if (kv.length != 2) {
				throw new IllegalArgumentException("Expected name=value, got: " + pair);
			}
			set(kv[0].trim(), kv[1].trim());
		}
	}

	public static List<String> names() {
		List<String> names = new ArrayList<>();
		for (Field f : fields()) {
			names.add(f.getName());
		}
		return names;
	}

	public String get(String name) {
		for (Field f : fields()) {
			if (f.getName().equals(name)) {
				try {
					return String.valueOf(f.get(this));
				} catch (IllegalAccessException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		throw new IllegalArgumentException("Unknown parameter: " + name);
	}

	/** JSON object of all parameters, for meta.json. */
	public String toJson() {
		StringBuilder sb = new StringBuilder("{");
		List<Field> fs = fields();
		for (int i = 0; i < fs.size(); i++) {
			try {
				sb.append(String.format(Locale.ROOT, "\"%s\": %s", fs.get(i).getName(), fs.get(i).get(this)));
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
			if (i < fs.size() - 1) {
				sb.append(", ");
			}
		}
		return sb.append('}').toString();
	}

	private static List<Field> fields() {
		List<Field> out = new ArrayList<>();
		for (Field f : EngineParams.class.getDeclaredFields()) {
			if (!Modifier.isStatic(f.getModifiers())) {
				out.add(f);
			}
		}
		return out;
	}
}
