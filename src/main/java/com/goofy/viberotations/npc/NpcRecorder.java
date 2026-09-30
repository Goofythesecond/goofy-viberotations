package com.goofy.viberotations.npc;

import com.goofy.viberotations.VibeRotations;
import com.goofy.viberotations.engine.EngineParams;
import com.goofy.viberotations.engine.HumanRotationEngine;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Records the NPC's rotations to {@code rotation-logs/npc_<date>/}. {@code frames.csv} uses the
 * same columns as the player recorder (plus phase and waypoint), so analysis/compare.py and
 * analysis/aim_model.py section 1 run on it unchanged. Times are the engine's simulated clock.
 */
final class NpcRecorder {

	private final Path dir;
	private final BufferedWriter frames;
	private final BufferedWriter ticks;
	private final BufferedWriter waypoints;
	private final BufferedWriter flicks;
	private long tick;
	private int frameInTick;
	private int batch;

	NpcRecorder(EngineParams params, long seed) throws IOException {
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
		dir = FabricLoader.getInstance().getGameDir().resolve("rotation-logs").resolve("npc_" + stamp);
		Files.createDirectories(dir);
		frames = Files.newBufferedWriter(dir.resolve("frames.csv"));
		frames.write("fight,tick,time_ns,frame_in_tick,d_yaw,d_pitch,yaw,pitch,phase,waypoint");
		frames.newLine();
		ticks = Files.newBufferedWriter(dir.resolve("ticks.csv"));
		ticks.write("tick,time_ms,yaw,pitch,d_yaw,goal_yaw,goal_pitch,error_deg,phase,waypoint");
		ticks.newLine();
		waypoints = Files.newBufferedWriter(dir.resolve("waypoints.csv"));
		waypoints.write("batch,index,yaw,pitch,delay_ms,issued_tick");
		waypoints.newLine();
		flicks = Files.newBufferedWriter(dir.resolve("flicks.csv"));
		flicks.write("n,kind,start_ms,end_ms,duration_ms,start_yaw,start_pitch,target_yaw,target_pitch,"
				+ "end_yaw,end_pitch,amplitude,end_error,submovements,burst,burst_index,gap_ms");
		flicks.newLine();
		Files.writeString(dir.resolve("meta.json"), String.format(Locale.ROOT,
				"{\n  \"source\": \"npc\",\n  \"seed\": %d,\n  \"started\": \"%s\",\n  \"params\": %s\n}\n",
				seed, LocalDateTime.now(), params.toJson()));
		VibeRotations.LOGGER.info("Recording NPC rotations to {}", dir);
	}

	void frame(double t, double dYaw, double dPitch, double yaw, double pitch, HumanRotationEngine.Phase phase, int wp) {
		write(frames, String.format(Locale.ROOT, "1,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%s,%d",
				tick + 1, (long) (t * 1_000_000L), frameInTick++, dYaw, dPitch, yaw, pitch, phase, wp));
	}

	void tick(HumanRotationEngine e, double dYaw) {
		tick++;
		frameInTick = 0;
		if (!e.busy()) {
			return;
		}
		double[] g = e.goal();
		String goal = g == null ? ",," : String.format(Locale.ROOT, "%.4f,%.4f,%.4f",
				g[0], g[1], Math.hypot(g[0] - e.yaw(), g[1] - e.pitch()));
		write(ticks, String.format(Locale.ROOT, "%d,%.1f,%.4f,%.4f,%.4f,%s,%s,%d",
				tick, e.timeMs(), e.yaw(), e.pitch(), dYaw, goal, e.phase(), e.waypointIndex()));
	}

	void waypoints(List<double[]> wps, int delayMs) {
		batch++;
		for (int i = 0; i < wps.size(); i++) {
			write(waypoints, String.format(Locale.ROOT, "%d,%d,%.4f,%.4f,%d,%d",
					batch, i, wps.get(i)[0], wps.get(i)[1], delayMs, tick));
		}
	}

	/**
	 * One row per /npc auto flick, written when the engine reaches it. Inside a burst a flick
	 * starts where the previous one ended, so its duration includes the {@code gap_ms} pause.
	 */
	void flick(int n, String kind, int burst, int index, int gapMs, double startMs, double startYaw, double startPitch,
			double targetYaw, double targetPitch, HumanRotationEngine.Arrival end) {
		double amp = Math.hypot(targetYaw - startYaw, targetPitch - startPitch);
		double err = Math.hypot(targetYaw - end.yaw(), targetPitch - end.pitch());
		write(flicks, String.format(Locale.ROOT, "%d,%s,%.1f,%.1f,%.1f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%d,%d,%d",
				n, kind, startMs, end.timeMs(), end.timeMs() - startMs,
				startYaw, startPitch, targetYaw, targetPitch, end.yaw(), end.pitch(),
				amp, err, end.submovements(), burst, index, gapMs));
		try {
			flicks.flush();
		} catch (IOException ex) {
			VibeRotations.LOGGER.error("Failed to flush NPC flick log in {}", dir, ex);
		}
	}

	private void write(BufferedWriter w, String line) {
		try {
			w.write(line);
			w.newLine();
		} catch (IOException e) {
			VibeRotations.LOGGER.error("Failed to write NPC log in {}", dir, e);
		}
	}

	void close() {
		for (BufferedWriter w : new BufferedWriter[]{frames, ticks, waypoints, flicks}) {
			try {
				w.close();
			} catch (IOException e) {
				VibeRotations.LOGGER.error("Failed to close NPC log in {}", dir, e);
			}
		}
	}
}
