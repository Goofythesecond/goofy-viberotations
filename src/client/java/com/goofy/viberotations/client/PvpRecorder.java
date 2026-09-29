package com.goofy.viberotations.client;

import com.goofy.viberotations.VibeRotations;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Records PvP data into one folder per connection:
 * <ul>
 *   <li>{@code ticks.csv} — one row per client tick while a fight is active</li>
 *   <li>{@code clicks.csv} — one row per attack click, captured at the moment of the click</li>
 *   <li>{@code frames.csv} — every mouse turn (once per rendered frame) while a fight is active; {@code tick} is the ticks.csv row whose d_yaw includes it</li>
 *   <li>{@code meta.json} — server, player and input settings for the session</li>
 * </ul>
 * A fight starts when another player comes within {@link #TARGET_RANGE} or you click,
 * and ends after {@link #FIGHT_GAP_TICKS} ticks without either.
 */
public final class PvpRecorder {

	public static final PvpRecorder INSTANCE = new PvpRecorder();

	/** Bump when columns change so analysis scripts can tell formats apart. */
	private static final int FORMAT_VERSION = 3;
	private static final double TARGET_RANGE = 8.0;
	private static final int FIGHT_GAP_TICKS = 60;
	/** How long after a hit we wait for the server's hurt animation before calling it a ghost hit. */
	private static final int CONFIRM_WINDOW_TICKS = 10;
	/** Entity.turn multiplies its input by this to get degrees. */
	private static final double TURN_TO_DEGREES = 0.15;

	private static final String[] TICK_HEADER = {
			"fight", "tick", "time_ns", "fps", "ping_ms",
			// self
			"x", "y", "z", "yaw", "pitch", "d_yaw", "d_pitch",
			"dx", "dy", "dz", "speed_bps", "move_dist", "fight_dist",
			"on_ground", "sprinting", "sneaking", "fall_distance", "crit_ready",
			"key_w", "key_a", "key_s", "key_d", "key_jump", "key_sneak", "key_sprint",
			"move_forward", "move_strafe", "sprint_started", "sprint_stopped",
			"held_item", "using_item", "blocking",
			"health", "absorption", "hurt_time", "took_hit", "health_lost", "damage_from_hit", "kb_x", "kb_y", "kb_z",
			"attack_cooldown",
			// clicks this tick
			"clicks", "hits", "misses", "blocked_clicks", "hits_confirmed", "confirm_delay_ticks",
			// target
			"nearby_players", "target", "target_id",
			"target_x", "target_y", "target_z", "target_dx", "target_dy", "target_dz", "target_speed_bps",
			"target_yaw", "target_pitch", "target_on_ground", "target_sprinting", "target_sneaking",
			"target_health", "target_hurt_time", "target_blocking", "target_held_item",
			"reach_dist", "closing_speed_bps", "approach_bps", "strafe_bps",
			"yaw_to_target", "pitch_to_target", "yaw_error", "pitch_error",
			"box_aim_error", "ray_on_box", "crosshair_on_target", "target_facing_me_deg"
	};
	private static final int TARGET_COLUMNS = 30;

	private static final String[] CLICK_HEADER = {
			"fight", "tick", "time_ns", "yaw", "pitch", "result", "blocked_by_miss_cooldown",
			"attack_cooldown", "would_crit", "sprinting", "on_ground", "fall_distance",
			"speed_bps", "held_item",
			"target", "target_id", "reach_dist", "hit_dist", "yaw_error", "pitch_error",
			"box_aim_error", "ray_on_box", "target_hurt_time"
	};

	private static final String[] FRAME_HEADER = {
			"fight", "tick", "time_ns", "frame_in_tick", "d_yaw", "d_pitch", "yaw", "pitch"
	};

	private boolean enabled = true;

	// session
	private Path dir;
	private CsvFile ticks;
	private CsvFile clicks;
	private CsvFile frames;

	// timing / fights
	private long tick;
	private int fightId;
	private long lastActiveTick = Long.MIN_VALUE / 2;
	private double fightDist;
	private int frameInTick;

	// previous-tick state of the local player
	private boolean hasPrev;
	private Vec3 prevPos;
	private float prevYaw;
	private float prevPitch;
	private boolean prevSprinting;
	private int prevHurtTime;
	/** Health + absorption last tick. */
	private float prevHealth;
	private long lastHitTakenTick = Long.MIN_VALUE / 2;

	// current target, refreshed every tick and used by click rows
	private Player target;
	private double prevReach = Double.NaN;
	private int prevTargetId = -1;

	// per-tick click counters
	private int tickClicks;
	private int tickHits;
	private int tickMisses;
	private int tickBlocked;

	// hit confirmation: entity id -> previous hurtTime, and hits waiting for the server
	private final Map<Integer, Integer> prevHurtTimes = new HashMap<>();
	private final List<long[]> pendingHits = new ArrayList<>(); // {entityId, tick}

	private PvpRecorder() {}

	public boolean toggle() {
		enabled = !enabled;
		if (!enabled) {
			closeSession();
		}
		return enabled;
	}

	// ─── Tick ─────────────────────────────────────────────────────────────

	public void tick(Minecraft mc) {
		LocalPlayer self = mc.player;
		if (self == null || mc.level == null) {
			hasPrev = false;
			return;
		}
		tick++;
		frameInTick = 0;

		Vec3 pos = self.position();
		float yaw = self.getYRot();
		float pitch = self.getXRot();
		float health = self.getHealth();
		float totalHealth = health + self.getAbsorptionAmount();
		if (!hasPrev) {
			prevPos = pos;
			prevYaw = yaw;
			prevPitch = pitch;
			prevSprinting = self.isSprinting();
			prevHurtTime = self.hurtTime;
			prevHealth = totalHealth;
			hasPrev = true;
		}
		Vec3 delta = pos.subtract(prevPos);
		float dYaw = yaw - prevYaw;
		float dPitch = pitch - prevPitch;
		boolean sprintStarted = self.isSprinting() && !prevSprinting;
		boolean sprintStopped = !self.isSprinting() && prevSprinting;
		boolean tookHit = self.hurtTime > prevHurtTime;
		float healthLost = Math.max(0, prevHealth - totalHealth);
		if (tookHit) {
			lastHitTakenTick = tick;
		}
		// The health update arrives a tick or two after the hurt animation; attribute it to that hit.
		boolean damageFromHit = healthLost > 0 && tick - lastHitTakenTick <= 5;

		prevPos = pos;
		prevYaw = yaw;
		prevPitch = pitch;
		prevSprinting = self.isSprinting();
		prevHurtTime = self.hurtTime;
		prevHealth = totalHealth;

		// Hit confirmation runs even when disabled so the hurt-time map stays current.
		int[] confirmed = confirmHits(mc);

		Vec3 eye = self.getEyePosition();
		Vec3 look = self.getLookAngle();
		int nearby = 0;
		target = null;
		double bestAngle = Double.MAX_VALUE;
		for (Player other : mc.level.players()) {
			if (other == self || other.isSpectator() || !other.isAlive()) {
				continue;
			}
			if (AimMath.reach(eye, other.getBoundingBox()) > TARGET_RANGE) {
				continue;
			}
			nearby++;
			double angle = AimMath.boxAimError(eye, look, other.getBoundingBox());
			if (angle < bestAngle) {
				bestAngle = angle;
				target = other;
			}
		}

		int clickCount = tickClicks;
		int hitCount = tickHits;
		int missCount = tickMisses;
		int blockedCount = tickBlocked;
		tickClicks = tickHits = tickMisses = tickBlocked = 0;

		if (!enabled) {
			return;
		}
		boolean active = target != null || clickCount > 0 || tookHit || confirmed[0] > 0;
		if (!active && !inFight()) {
			prevReach = Double.NaN;
			return;
		}
		if (active) {
			touchFight();
		}
		double moveDist = delta.length();
		fightDist += moveDist;

		Input keys = self.input.keyPresses;
		Vec2 moveVec = self.input.getMoveVector();
		Vec3 motion = self.getDeltaMovement();

		CsvFile.Row row = new CsvFile.Row()
				.add(fightId).add(tick).add(System.nanoTime()).add(mc.getFps()).add(ping(mc, self))
				.add(pos.x).add(pos.y).add(pos.z).add(yaw).add(pitch).add(dYaw).add(dPitch)
				.add(delta.x).add(delta.y).add(delta.z).add(delta.horizontalDistance() * 20.0)
				.add(moveDist).add(fightDist)
				.add(self.onGround()).add(self.isSprinting()).add(self.isShiftKeyDown())
				.add(self.fallDistance).add(critReady(self))
				.add(keys.forward()).add(keys.left()).add(keys.backward()).add(keys.right())
				.add(keys.jump()).add(keys.shift()).add(keys.sprint())
				.add(moveVec.y).add(moveVec.x).add(sprintStarted).add(sprintStopped)
				.add(itemName(self)).add(self.isUsingItem()).add(self.isBlocking())
				.add(health).add(self.getAbsorptionAmount()).add(self.hurtTime).add(tookHit).add(healthLost);
		if (damageFromHit) {
			row.add(healthLost);
		} else {
			row.blank(1);
		}
		if (tookHit) {
			row.add(motion.x).add(motion.y).add(motion.z);
		} else {
			row.blank(3);
		}
		row.add(self.getAttackStrengthScale(0f))
				.add(clickCount).add(hitCount).add(missCount).add(blockedCount)
				.add(confirmed[0]).add(confirmed[0] > 0 ? confirmed[1] : -1)
				.add(nearby);

		if (target != null) {
			appendTarget(row, mc, self, eye, look, delta);
		} else {
			row.blank(TARGET_COLUMNS);
			prevReach = Double.NaN;
		}
		write(mc, Log.TICKS, row);
	}

	private void appendTarget(CsvFile.Row row, Minecraft mc, LocalPlayer self, Vec3 eye, Vec3 look, Vec3 selfDelta) {
		AABB box = target.getBoundingBox();
		Vec3 center = box.getCenter();
		Vec3 tDelta = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
		double reach = AimMath.reach(eye, box);
		double closing = (target.getId() == prevTargetId && !Double.isNaN(prevReach)) ? (prevReach - reach) * 20.0 : 0;
		prevReach = reach;
		prevTargetId = target.getId();

		// My horizontal velocity split into toward-target and sideways components.
		Vec3 toTarget = new Vec3(center.x - eye.x, 0, center.z - eye.z).normalize();
		Vec3 flat = new Vec3(selfDelta.x, 0, selfDelta.z);
		double approach = flat.dot(toTarget) * 20.0;
		double strafe = (flat.x * -toTarget.z + flat.z * toTarget.x) * 20.0;

		float yawTo = AimMath.yawTo(eye, center);
		float pitchTo = AimMath.pitchTo(eye, center);
		double facingMe = AimMath.angle(target.getLookAngle(), eye.subtract(target.getEyePosition()));

		row.add(target.getName().getString()).add(target.getId())
				.add(target.getX()).add(target.getY()).add(target.getZ())
				.add(tDelta.x).add(tDelta.y).add(tDelta.z).add(tDelta.horizontalDistance() * 20.0)
				.add(target.getYRot()).add(target.getXRot())
				.add(target.onGround()).add(target.isSprinting()).add(target.isShiftKeyDown())
				.add(target.getHealth()).add(target.hurtTime).add(target.isBlocking()).add(itemName(target))
				.add(reach).add(closing).add(approach).add(strafe)
				.add(yawTo).add(pitchTo)
				.add(Mth.wrapDegrees(yawTo - self.getYRot())).add(pitchTo - self.getXRot())
				.add(AimMath.boxAimError(eye, look, box)).add(AimMath.rayHits(eye, look, box, 64))
				.add(mc.crosshairPickEntity == target).add(facingMe);
	}

	// ─── Clicks ───────────────────────────────────────────────────────────

	/** Called from the start of Minecraft.startAttack, once per attack-key press. */
	public void onClick(Minecraft mc, boolean blockedByMissCooldown) {
		LocalPlayer self = mc.player;
		if (self == null || mc.level == null) {
			return;
		}
		HitResult hr = mc.hitResult;
		Entity hitEntity = hr instanceof EntityHitResult ehr ? ehr.getEntity() : null;
		String result;
		if (hitEntity instanceof Player) {
			result = "HIT_PLAYER";
		} else if (hitEntity != null) {
			result = "HIT_ENTITY";
		} else if (hr != null && hr.getType() == HitResult.Type.BLOCK) {
			result = "BLOCK";
		} else {
			result = "MISS";
		}

		tickClicks++;
		if (blockedByMissCooldown) {
			tickBlocked++;
		} else if (hitEntity instanceof Player) {
			tickHits++;
			pendingHits.add(new long[]{hitEntity.getId(), tick});
		} else {
			tickMisses++;
		}
		if (!enabled) {
			return;
		}
		touchFight();

		// Prefer the player actually under the crosshair, else the tracked target.
		Player aimed = hitEntity instanceof Player p ? p : target;
		Vec3 eye = self.getEyePosition();
		Vec3 look = self.getLookAngle();

		CsvFile.Row row = new CsvFile.Row()
				.add(fightId).add(tick).add(System.nanoTime())
				.add(self.getYRot()).add(self.getXRot()).add(result).add(blockedByMissCooldown)
				.add(self.getAttackStrengthScale(0f)).add(critReady(self))
				.add(self.isSprinting()).add(self.onGround()).add(self.fallDistance)
				.add(self.getDeltaMovement().horizontalDistance() * 20.0).add(itemName(self));
		if (aimed != null) {
			AABB box = aimed.getBoundingBox();
			Vec3 center = box.getCenter();
			row.add(aimed.getName().getString()).add(aimed.getId())
					.add(AimMath.reach(eye, box));
			if (hitEntity != null) {
				row.add(hr.getLocation().distanceTo(eye));
			} else {
				row.blank(1);
			}
			row.add(Mth.wrapDegrees(AimMath.yawTo(eye, center) - self.getYRot()))
					.add(AimMath.pitchTo(eye, center) - self.getXRot())
					.add(AimMath.boxAimError(eye, look, box)).add(AimMath.rayHits(eye, look, box, 64))
					.add(aimed.hurtTime);
		} else {
			row.blank(9);
		}
		write(mc, Log.CLICKS, row);
	}

	// ─── Frames ───────────────────────────────────────────────────────────

	/** Called after Entity.turn on the local player — raw mouse input, once per frame. */
	public void onTurn(LocalPlayer self, double yRot, double xRot) {
		if (!enabled || !inFight() || (yRot == 0 && xRot == 0)) {
			return;
		}
		CsvFile.Row row = new CsvFile.Row()
				.add(fightId).add(tick + 1).add(System.nanoTime()).add(frameInTick++)
				.add(yRot * TURN_TO_DEGREES).add(xRot * TURN_TO_DEGREES)
				.add(self.getYRot()).add(self.getXRot());
		write(Minecraft.getInstance(), Log.FRAMES, row);
	}

	// ─── Helpers ──────────────────────────────────────────────────────────

	private boolean inFight() {
		return tick - lastActiveTick <= FIGHT_GAP_TICKS;
	}

	/** Marks this tick as active, starting a new fight if the last one timed out. */
	private void touchFight() {
		if (!inFight()) {
			fightId++;
			fightDist = 0;
			prevReach = Double.NaN;
		}
		lastActiveTick = tick;
	}

	/**
	 * Matches hits against the server's hurt animation. A hit counts as confirmed when that
	 * player's hurtTime restarts within {@link #CONFIRM_WINDOW_TICKS}. Returns {count, delay of the last one}.
	 */
	private int[] confirmHits(Minecraft mc) {
		Map<Integer, Boolean> hurtStarted = new HashMap<>();
		for (Player p : mc.level.players()) {
			int prev = prevHurtTimes.getOrDefault(p.getId(), 0);
			hurtStarted.put(p.getId(), p.hurtTime > prev);
			prevHurtTimes.put(p.getId(), p.hurtTime);
		}
		prevHurtTimes.keySet().retainAll(hurtStarted.keySet());

		int count = 0;
		int delay = -1;
		for (Iterator<long[]> it = pendingHits.iterator(); it.hasNext(); ) {
			long[] hit = it.next();
			int id = (int) hit[0];
			if (Boolean.TRUE.equals(hurtStarted.get(id))) {
				count++;
				delay = (int) (tick - hit[1]);
				hurtStarted.put(id, false); // one hurt animation confirms one hit
				it.remove();
			} else if (tick - hit[1] > CONFIRM_WINDOW_TICKS) {
				it.remove();
			}
		}
		return new int[]{count, delay};
	}

	/** Mirrors vanilla's critical-hit conditions in Player.attack. */
	private static boolean critReady(LocalPlayer p) {
		return p.getAttackStrengthScale(0.5f) > 0.9f
				&& p.fallDistance > 0
				&& !p.onGround()
				&& !p.onClimbable()
				&& !p.isInWater()
				&& !p.hasEffect(MobEffects.BLINDNESS)
				&& !p.isPassenger()
				&& !p.isSprinting();
	}

	private static String itemName(Player p) {
		return BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath();
	}

	private static int ping(Minecraft mc, LocalPlayer self) {
		if (mc.getConnection() == null) {
			return -1;
		}
		PlayerInfo info = mc.getConnection().getPlayerInfo(self.getUUID());
		// The tab list reports 0 until the server sends the first latency update.
		return info == null || info.getLatency() == 0 ? -1 : info.getLatency();
	}

	// ─── Files ────────────────────────────────────────────────────────────

	private enum Log { TICKS, CLICKS, FRAMES }

	private void write(Minecraft mc, Log log, CsvFile.Row row) {
		try {
			if (dir == null) {
				openSession(mc);
			}
			switch (log) {
				case TICKS -> ticks.write(row);
				case CLICKS -> clicks.write(row);
				case FRAMES -> frames.write(row);
			}
		} catch (IOException e) {
			VibeRotations.LOGGER.error("Failed to write PvP log in {}", dir, e);
			closeSession();
			enabled = false;
		}
	}

	private void openSession(Minecraft mc) throws IOException {
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
		dir = mc.gameDirectory.toPath().resolve("rotation-logs").resolve(stamp);
		Files.createDirectories(dir);
		ticks = new CsvFile(dir.resolve("ticks.csv"), TICK_HEADER);
		clicks = new CsvFile(dir.resolve("clicks.csv"), CLICK_HEADER);
		frames = new CsvFile(dir.resolve("frames.csv"), FRAME_HEADER);
		Files.writeString(dir.resolve("meta.json"), meta(mc));
		VibeRotations.LOGGER.info("Recording PvP data to {}", dir);
	}

	private static String meta(Minecraft mc) {
		ServerData server = mc.getCurrentServer();
		return """
				{
				  "format_version": %d,
				  "minecraft_version": "%s",
				  "player": "%s",
				  "server": "%s",
				  "started": "%s",
				  "mouse_sensitivity": %s,
				  "fov": %d,
				  "raw_mouse_input": %s,
				  "framerate_limit": %d,
				  "target_range": %s,
				  "fight_gap_ticks": %d
				}
				""".formatted(
				FORMAT_VERSION,
				SharedConstants.getCurrentVersion().name(),
				mc.player == null ? "" : mc.player.getName().getString(),
				server == null ? "singleplayer" : server.ip,
				LocalDateTime.now(),
				mc.options.sensitivity().get(),
				mc.options.fov().get(),
				mc.options.rawMouseInput().get(),
				mc.options.framerateLimit().get(),
				TARGET_RANGE,
				FIGHT_GAP_TICKS);
	}

	/** Flushes and closes the session. The next recorded row starts a new folder. */
	public void closeSession() {
		for (CsvFile f : new CsvFile[]{ticks, clicks, frames}) {
			if (f == null) {
				continue;
			}
			try {
				f.close();
			} catch (IOException e) {
				VibeRotations.LOGGER.error("Failed to close PvP log in {}", dir, e);
			}
		}
		ticks = clicks = frames = null;
		dir = null;
		hasPrev = false;
		fightId = 0;
		lastActiveTick = Long.MIN_VALUE / 2;
		target = null;
		pendingHits.clear();
		prevHurtTimes.clear();
	}
}
