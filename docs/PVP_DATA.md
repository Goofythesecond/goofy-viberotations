# Goofy VibeRotations — Data & Aim Model

This document covers what Goofy VibeRotations records, how to check a recording is valid, and what the data says about human aim. The aim model at the end is the reference for the planned singleplayer NPC opponent.

Last updated: 2026-09-29, from 10 sessions in format version 3.

---

## 1. Recording

- The mod runs on the client only. Press **J** to turn recording on or off; it starts on.
- A **fight** starts when another player is within 8 blocks (eye to nearest hitbox point), when you click, or when you get hit. It ends after 60 ticks (3 s) with none of those.
- Each connection to a world or server creates one folder: `.minecraft/rotation-logs/<yyyy-MM-dd_HH-mm-ss>/`.

| File | Rows |
|---|---|
| `meta.json` | Session info: `format_version`, player, server, sensitivity, FOV, raw mouse input, FPS limit |
| `ticks.csv` | One per client tick (50 ms) during a fight |
| `clicks.csv` | One per attack-key press, captured at the start of `Minecraft.startAttack` (before anything is sent to the server) |
| `frames.csv` | One per rendered frame with mouse movement during a fight (the raw input to `Entity.turn`) |

### Format versions
| Version | Change |
|---|---|
| 1 | Single `pvp_<date>.csv` per session. Superseded. |
| 2 | Folder layout. **Known bugs:** `frames.csv` `tick` is one lower than the tick row it belongs to; damage isn't linked to hits; ping is 0 until the first tab-list update. |
| 3 | Fixes all three. Adds `damage_from_hit`; ping is `-1` when unknown. |

Analyse only version 3 data unless you correct for the version 2 bugs.

---

## 2. Columns

Angles are in degrees. Yaw is **not wrapped** (it keeps counting past ±180°) so `d_yaw` stays continuous; yaw increases when turning right. Pitch is negative looking up. Speeds are in blocks per second unless named otherwise. Booleans are `0`/`1`. Empty cells mean the value isn't available.

### ticks.csv
| Group | Columns | Notes |
|---|---|---|
| Timing | `fight, tick, time_ns, fps, ping_ms` | `time_ns` = `System.nanoTime` |
| Position/rotation | `x, y, z, yaw, pitch, d_yaw, d_pitch` | Deltas are vs. the previous tick |
| Movement | `dx, dy, dz, speed_bps, move_dist, fight_dist` | `speed_bps` is horizontal only |
| State | `on_ground, sprinting, sneaking, fall_distance, crit_ready` | `crit_ready` uses vanilla's crit rules |
| Inputs | `key_w, key_a, key_s, key_d, key_jump, key_sneak, key_sprint, move_forward, move_strafe` | |
| Sprint | `sprint_started, sprint_stopped` | `sprint_stopped` marks W-taps and sprint resets |
| Items | `held_item, using_item, blocking` | Registry path, e.g. `netherite_sword` |
| Damage taken | `health, absorption, hurt_time, took_hit, health_lost, damage_from_hit, kb_x, kb_y, kb_z` | See below |
| Attack | `attack_cooldown` | 1.0 = fully charged |
| Clicks this tick | `clicks, hits, misses, blocked_clicks, hits_confirmed, confirm_delay_ticks` | See below |
| Target | `nearby_players, target, target_id` | Target = the player needing the smallest crosshair movement to reach their hitbox |
| Target state | `target_x/y/z, target_dx/dy/dz, target_speed_bps, target_yaw, target_pitch, target_on_ground, target_sprinting, target_sneaking, target_health, target_hurt_time, target_blocking, target_held_item` | Health is real on the recording server; some servers hide it |
| Spacing | `reach_dist, closing_speed_bps, approach_bps, strafe_bps` | `reach_dist` = eye to nearest hitbox point (what vanilla reach checks). `approach_bps` and `strafe_bps` split your horizontal velocity into toward-the-target and sideways parts |
| Aim | `yaw_to_target, pitch_to_target, yaw_error, pitch_error` | Angles to the **hitbox centre**. `yaw_error` > 0 means the target is to your right |
| Aim (hitbox) | `box_aim_error, ray_on_box, crosshair_on_target` | `box_aim_error` = degrees to the **nearest hitbox edge**, 0 when on it. `ray_on_box` ignores reach; `crosshair_on_target` is vanilla's pick, limited by reach |
| Opponent | `target_facing_me_deg` | 0 = looking straight at you |

**Hit confirmation:** a hit counts as confirmed when that player's hurt animation restarts within 10 ticks. `confirm_delay_ticks` roughly measures your ping plus server processing.

**Damage taken:** `took_hit` is the tick the hurt animation starts. The server sends your new health 1–2 ticks later, so `damage_from_hit` is filled on the tick your health drops, if a hit landed in the previous 5 ticks. It includes absorption.

### clicks.csv
`fight, tick, time_ns, yaw, pitch, result, blocked_by_miss_cooldown, attack_cooldown, would_crit, sprinting, on_ground, fall_distance, speed_bps, held_item, target, target_id, reach_dist, hit_dist, yaw_error, pitch_error, box_aim_error, ray_on_box, target_hurt_time`

- `result`: `HIT_PLAYER`, `HIT_ENTITY`, `BLOCK` or `MISS`.
- `blocked_by_miss_cooldown`: vanilla dropped the click because of the 10-tick miss penalty.
- The target is the player under the crosshair if there is one, otherwise the tracked target.

### frames.csv
`fight, tick, time_ns, frame_in_tick, d_yaw, d_pitch, yaw, pitch`

- `tick` matches the `ticks.csv` row whose `d_yaw` includes this frame, so the frames of a tick add up exactly to that tick's `d_yaw` (version 3).
- Only frames with mouse movement are written. A gap between frames means the mouse was still.

---

## 3. Checking a recording

Run `python3 analysis/check_data.py <folder containing session folders>`. A healthy version 3 recording shows:

| Check | Expected | Latest result |
|---|---|---|
| Tick step inside fights | always 1 | ✅ 11,659 of 11,659 |
| ms per tick | about 50 | ✅ 50.0 average, max 151 (lag spike) |
| Click counts in `ticks.csv` vs `clicks.csv` | equal | ✅ 299 = 299 |
| Frame sum minus tick `d_yaw` | 0 | ✅ 0.000 |
| Ping | > 0, stable | ✅ 52–152 ms |
| Damage linked to hits taken | most | ✅ 243 of 253 |
| Hits confirmed | high | ✅ 89% |

Things to filter before modelling:
- **Launches and teleports:** 57 ticks exceed 30 b/s, from wind charges, elytra, knockback and pearls. `strafe_bps` and `approach_bps` then hit ±280. Drop ticks with `speed_bps > 30`.
- **Looking straight down** (|pitch| > 80, mace dives) breaks the aim-height calculation.
- **Non-fight ticks:** only 5.3 of the 9.7 recorded minutes have a target. Many fight ticks are spent holding armour, apples or pearls.

---

## 4. Dataset (as of 2026-09-29)

| | Version 2 (3 sessions) | Version 3 (10 sessions) |
|---|---|---|
| Recorded | 3.5 min | 9.7 min |
| With a target | 3.3 min | 5.3 min (**2.4 min holding a sword**) |
| Clicks | 235 | 299 |
| Mouse frames | 13,650 | 31,495 |
| Server | one public PvP server (redacted) | same |
| Settings | sensitivity 0.385, FOV 90, raw input on, 260 FPS limit | same |

**Weapons play very differently, so model them separately:**

| | Sword | Mace |
|---|---|---|
| Clicks | 144 | 139 |
| Accuracy | 69% | 32% |
| Click interval (median) | 646 ms, timed to the cooldown | 148 ms, spam |
| Median cooldown at click | 1.00 | 0.12 |
| Hits that would crit | 65% | 13% |

---

## 5. Human aim model (sword)

From version 3 sword data (489 mouse movements, 184 flicks of 15° or more). The version 2 numbers are shown where they differ, to show how stable each result is.

### 5.1 Movements are discrete flicks
Aim moves in separate bursts, not continuous tracking. A **movement** is consecutive mouse frames less than 25 ms apart.

- **Pause between movements:** median 66 ms (10th–90th percentile: 28–336 ms).
- **Velocity profile:** peaks mid-flick (median at 0.50 of the duration; 10th–90th: 0.14–0.91). Speed builds up, then slows down.
- **Reversals inside one movement are rare:** 14 of 489. Corrections are separate movements.

### 5.2 Size, duration and speed
| Size | Duration | Peak speed |
|---|---|---|
| 0–2° | 36 ms | 45 °/s |
| 2–5° | 53 ms | 93 °/s |
| 5–10° | 81 ms | 171 °/s |
| 10–20° | 148 ms | 265 °/s |
| 20–40° | 180 ms | 421 °/s |
| 40–90° | 248 ms | 648 °/s |
| 90°+ | 325 ms | 1,337 °/s |

Power-law fits (size in degrees):

- **Duration ≈ 33 × size^0.50 ms**, spread: standard deviation 0.56 in log space. Version 2: 42 × size^0.48.
- **Peak speed ≈ 43 × size^0.69 °/s**, spread: standard deviation 0.43 in log space. Version 2: 42 × size^0.71.

The spread is large (about ×1.75 either way at one standard deviation). Randomising around the curve matters as much as the curve itself.

### 5.3 Undershoot and overshoot
After a flick of 15° or more, what follows within 300 ms:

- **Same-direction top-up (undershoot):** 48%.
- **Opposite correction (overshoot):** 28%. It's small (median 18% of the flick) and starts about 88 ms after the flick ends (10th–90th percentile: 30–215 ms).
- **Nothing:** 24%.

### 5.4 Reaction and tracking
- **Reaction delay:** rotation lines up best with the target's movement from **4 ticks (about 200 ms)** earlier. Version 2 peaked at 5–6 ticks.
- **Follow gain:** about 0.22. Aim follows roughly a fifth of the target's movement per tick, then catches up with flicks.
- **Trailing a strafing target:** aim sits a median **10° behind** the direction the target is moving.
- **No left/right bias:** signed yaw error has a median of −0.07°.
- **Accuracy while in reach:** median box error 0° (on the hitbox at least half the time), with 90% of ticks within 29°.

### 5.5 Aim point
- **Height:** median **0.98 blocks above the feet** (chest; a player is 1.8 tall). 10th–90th percentile: 0.26–1.65.
- **Pitch vs yaw:** vertical movements are about a third the size of horizontal ones (median ratio 0.31).
- **Mouse step:** the smallest step is 0.0961°, set by sensitivity 0.385. All mouse movement is a multiple of it.

### 5.6 Click timing (sword)
- Clicks are timed to the cooldown: median interval 646 ms (a sword recharges in 625 ms), median cooldown 1.00 at click.
- 65% of hits meet the crit conditions.

---

## 6. Open issues and next steps

1. **More sword data.** 2.4 minutes gives about 500 movements. Aim for 2,000+ (about 10 minutes of sword fighting with a target) before fixing the model's randomisation.
2. **Record weapons in separate sessions** (sword-only, mace-only), or always filter by `held_item`.
3. **Other combat mods are installed.** `autoclicker`, `clickthrough-plus`, `combat-hitbox` and `herobot` are in the instance. The logger can't tell their input from yours. Remove or disable them while recording so the dataset is purely human.
4. **Next milestone:** once the data is sufficient, build the rotation engine as a standalone class and compare its generated flicks against section 5 with the analysis scripts. Only after that, wire it into the singleplayer NPC.

## 7. Scripts

| Script | Purpose |
|---|---|
| `analysis/check_data.py <dir>` | Integrity checks plus click, tick and frame summaries |
| `analysis/aim_model.py <dir>` | Aim-model numbers (section 5). Set `ITEM=netherite_sword` to filter by held item |

`<dir>` is a folder containing session folders, e.g. `.minecraft/rotation-logs`. Point it at a folder with only version 3 sessions.
