package com.goofy.viberotations.train;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads training sessions from {@code rotation-logs/training/sessions/<stamp>/}.
 *
 * <p>{@code trials.csv} has one row per target; {@code frames.csv} has the same columns as the
 * PvP recorder's frames.csv with {@code fight} = trial number, so analysis/compare.py runs on a
 * training session unchanged.</p>
 */
public final class TrialStore {

	public static final String[] TRIAL_HEADER = {
			"trial", "style", "result", "shown_ns", "start_yaw", "start_pitch", "target_yaw", "target_pitch",
			"radius_deg", "distance", "amp_bin", "sector", "end_ms"
	};
	public static final String[] FRAME_HEADER = {
			"fight", "tick", "time_ns", "frame_in_tick", "d_yaw", "d_pitch", "yaw", "pitch"
	};

	/** A trial row without its frames, for counting coverage. */
	public record Planned(Style style, String result, int ampBin, int sector) {}

	private TrialStore() {}

	public static Path sessions(Path trainingDir) {
		return trainingDir.resolve("sessions");
	}

	/** Every completed ("hit") trial of every session, oldest session first. */
	public static List<Trial> load(Path trainingDir) throws IOException {
		List<Trial> out = new ArrayList<>();
		for (Path s : sessionDirs(trainingDir)) {
			out.addAll(loadSession(s));
		}
		return out;
	}

	/** Every trial row (including timeouts), for coverage counts. */
	public static List<Planned> plans(Path trainingDir) throws IOException {
		List<Planned> out = new ArrayList<>();
		for (Path s : sessionDirs(trainingDir)) {
			for (Map<String, String> r : read(s.resolve("trials.csv"))) {
				try {
					out.add(new Planned(Style.parse(r.get("style")), r.get("result"),
							Integer.parseInt(r.get("amp_bin")), Integer.parseInt(r.get("sector"))));
				} catch (RuntimeException ignored) {
					// Half-written last row of a crashed session.
				}
			}
		}
		return out;
	}

	static List<Trial> loadSession(Path dir) throws IOException {
		Map<String, List<double[]>> frames = new HashMap<>();
		for (Map<String, String> r : read(dir.resolve("frames.csv"))) {
			try {
				frames.computeIfAbsent(r.get("fight"), k -> new ArrayList<>()).add(new double[]{
						Long.parseLong(r.get("time_ns")), Double.parseDouble(r.get("d_yaw")), Double.parseDouble(r.get("d_pitch"))});
			} catch (RuntimeException ignored) {
				// Half-written last row.
			}
		}
		List<Trial> out = new ArrayList<>();
		String session = dir.getFileName().toString();
		for (Map<String, String> r : read(dir.resolve("trials.csv"))) {
			try {
				if (!"hit".equals(r.get("result"))) {
					continue;
				}
				long shown = Long.parseLong(r.get("shown_ns"));
				List<double[]> fs = frames.getOrDefault(r.get("trial"), List.of());
				double[] t = new double[fs.size()];
				double[] dy = new double[fs.size()];
				double[] dp = new double[fs.size()];
				for (int i = 0; i < t.length; i++) {
					t[i] = (fs.get(i)[0] - shown) / 1e6;
					dy[i] = fs.get(i)[1];
					dp[i] = fs.get(i)[2];
				}
				out.add(new Trial(session + "/" + r.get("trial"), Style.parse(r.get("style")),
						d(r, "start_yaw"), d(r, "start_pitch"), d(r, "target_yaw"), d(r, "target_pitch"),
						d(r, "radius_deg"), d(r, "end_ms"), t, dy, dp));
			} catch (RuntimeException ignored) {
				// Half-written last row.
			}
		}
		return out;
	}

	private static List<Path> sessionDirs(Path trainingDir) throws IOException {
		Path root = sessions(trainingDir);
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(root)) {
			return s.filter(p -> Files.exists(p.resolve("trials.csv"))).sorted().toList();
		}
	}

	private static double d(Map<String, String> r, String k) {
		return Double.parseDouble(r.get(k));
	}

	private static List<Map<String, String>> read(Path csv) throws IOException {
		List<Map<String, String>> rows = new ArrayList<>();
		if (!Files.exists(csv)) {
			return rows;
		}
		try (BufferedReader in = Files.newBufferedReader(csv)) {
			String headerLine = in.readLine();
			if (headerLine == null) {
				return rows;
			}
			String[] header = headerLine.split(",", -1);
			String line;
			while ((line = in.readLine()) != null) {
				String[] v = line.split(",", -1);
				if (v.length != header.length) {
					continue;
				}
				Map<String, String> row = new LinkedHashMap<>();
				for (int i = 0; i < header.length; i++) {
					row.put(header[i], v[i]);
				}
				rows.add(row);
			}
		}
		return rows;
	}
}
