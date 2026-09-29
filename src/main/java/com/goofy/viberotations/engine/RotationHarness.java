package com.goofy.viberotations.engine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Offline test harness: runs the engine through many random waypoints and writes the
 * resulting mouse frames in the same format as a recorded frames.csv, so
 * analysis/compare.py can measure engine output and human data with identical code.
 *
 * <p>Usage: {@code ./gradlew harness -Pcount=2000 -Pseed=1 -Pparams="durSigma=0.4,pauseSigma=1.1"}</p>
 */
public final class RotationHarness {

	public static void main(String[] args) throws IOException {
		Path out = Path.of(arg(args, "out", "build/harness"));
		int count = Integer.parseInt(arg(args, "count", "2000"));
		long seed = Long.parseLong(arg(args, "seed", "1"));
		EngineParams params = new EngineParams();
		// In a fight the next target is already known while the last flick ends, so the gap
		// before each waypoint looks like a normal pause, not a from-rest reaction. Pass
		// --reaction=keep to test from-rest reactions instead.
		if (!arg(args, "reaction", "").equals("keep")) {
			params.reactionMedianMs = params.pauseMedianMs;
			params.reactionSigma = params.pauseSigma;
		}
		params.apply(arg(args, "params", ""));

		// Waypoint sizes: lognormal, median 20 deg, long tail to 180 (targets move around you in a fight).
		Random rng = new Random(seed ^ 0x5DEECE66DL);
		HumanRotationEngine engine = new HumanRotationEngine(params, seed, 0, 0);
		Path session = out.resolve("engine_seed" + seed);
		Files.createDirectories(session);

		int[] frames = {0};
		try (BufferedWriter w = Files.newBufferedWriter(session.resolve("frames.csv"))) {
			w.write("fight,tick,time_ns,frame_in_tick,d_yaw,d_pitch,yaw,pitch,phase,waypoint");
			w.newLine();
			HumanRotationEngine.FrameSink sink = (t, dy, dp, y, p, phase, wp) -> {
				try {
					w.write(String.format(Locale.ROOT, "1,%d,%d,0,%.4f,%.4f,%.4f,%.4f,%s,%d",
							(long) (t / 50.0) + 1, (long) (t * 1_000_000L), dy, dp, y, p, phase, wp));
					w.newLine();
					frames[0]++;
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			};
			for (int i = 0; i < count; i++) {
				double amp = Math.min(180, Math.max(0.5, 20 * Math.exp(rng.nextGaussian() * 1.35)));
				double dir = rng.nextDouble() * 2 * Math.PI;
				// Humans turn mostly horizontally: vertical is ~0.3x horizontal.
				double dYaw = amp * Math.cos(dir);
				double dPitch = amp * Math.sin(dir) * 0.3;
				double targetPitch = Math.max(-60, Math.min(60, engine.pitch() + dPitch));
				engine.setWaypoints(List.of(new double[]{engine.yaw() + dYaw, targetPitch}), 0);
				while (engine.busy()) {
					engine.advance(50, sink);
				}
			}
		}
		Files.writeString(session.resolve("meta.json"), String.format(Locale.ROOT,
				"{\n  \"source\": \"engine\",\n  \"count\": %d,\n  \"seed\": %d,\n  \"params\": %s\n}\n",
				count, seed, params.toJson()));
		System.out.printf("Wrote %d frames for %d waypoints to %s%n", frames[0], count, session.toAbsolutePath());
	}

	private static String arg(String[] args, String name, String def) {
		for (String a : args) {
			if (a.startsWith("--" + name + "=")) {
				return a.substring(name.length() + 3);
			}
		}
		return def;
	}
}
