# Goofy VibeRotations

A Fabric mod for Minecraft **1.21.11** for studying how people aim in PvP.

It does three things:

1. **Records your PvP:** rotation, mouse movement, movement, clicks, hits and damage, written to CSV while you play.
2. **Runs a singleplayer NPC** whose head is turned by a rotation model built from that data, so you can watch it, look through its eyes, and record it the same way.
3. **Compares the two offline:** Python scripts measure your recordings and the model's output with identical code and show where they differ.

A redacted sample dataset (about 17 minutes of real PvP, plus two NPC runs) is included in [`data/`](data/).

> The NPC and its commands only exist in **singleplayer** worlds. Nothing in this mod turns your own camera; it only records it (training mode included).

---

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.3+ for Minecraft 1.21.11 and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Download `goofy-viberotations-<version>.jar` from [Releases](../../releases) and put it in your `mods` folder.

## Recording your PvP

Recording is on by default. Press **J** to toggle it (rebind under *Controls → Goofy VibeRotations*).

- A **fight** starts when another player comes within 8 blocks, when you click, or when you get hit. It ends after 3 s without any of those.
- Each world/server connection gets a folder in `.minecraft/rotation-logs/<date>/`:

| File | Contents |
|---|---|
| `ticks.csv` | One row per tick (50 ms) during fights, 83 columns: you, your inputs, your target, aim error, reach, damage |
| `clicks.csv` | One row per attack click, captured the instant you click: hit/miss/block, cooldown, crit, aim error |
| `frames.csv` | Every mouse movement, once per rendered frame, during fights |
| `meta.json` | Format version, sensitivity, FOV, raw input, FPS limit |

Every column is documented in [docs/PVP_DATA.md](docs/PVP_DATA.md).

## The NPC (singleplayer)

| Command | Effect |
|---|---|
| `/npc spawn` | Spawn the NPC at your position, facing your direction |
| `/npc moveto <yaw> <pitch> [<yaw> <pitch> ...]` | Turn to each waypoint in order. `~10` = 10° relative to the previous one |
| `/npc delay <ms>` | Pause at each `moveto` waypoint, and the rest between auto targets/bursts (`0` = random 150–600 ms) |
| `/npc delay <min> <max>` | Random rest between auto targets/bursts, in ms |
| `/npc auto start [mixed\|short\|long]` | Flick to random targets until stopped: short 4–15°, long 30–180° |
| `/npc auto burst <count> [gap] [rest]` | Show `count` targets at once and flick through them back to back, pausing `gap` ms on each (default 0); `rest` ms between bursts. `/npc auto burst off` for one at a time |
| `/npc auto blocks on\|off` | Show auto targets as glowing target blocks (on by default; the current one glows) |
| `/npc auto settings` | Show the current auto settings |
| `/npc auto stop`, `/npc stop`, `/npc remove` | Stop / remove |
| `/npc record on\|off` | Record to `rotation-logs/npc_<date>/` (on by default) |
| `/npc param <name> [value]`, `/npc params reset` | Read or change model parameters (applies on next spawn) |
| `/npcview` | Toggle looking through the NPC's eyes |

NPC recordings use the same `frames.csv` format as player recordings, plus `flicks.csv` (start, target, end, error, time and sub-movements per auto flick, and `burst`, `burst_index`, `gap_ms`).

Inside a burst the NPC can already see the next target, so every flick after the first starts after a short pause (`pauseMedianMs`) instead of a full reaction (`reactionMedianMs`), and starts where the previous flick ended. Its `start_ms`…`end_ms` duration includes the `gap_ms` pause. Target blocks are display entities, so they have no collision, don't replace anything, and are removed when reached, on `/npc auto stop` and on `/npc remove`.

### How the rotation model works
`HumanRotationEngine` is plain Java (no Minecraft code), so the NPC and the offline harness run identical logic:

- **Flicks:** a reaction delay, then a flick whose duration grows with its size (a power law), with a minimum-jerk speed profile and a randomized peak time.
- **Corrections:** each flick lands short, long or close. Corrective flicks follow after short pauses until the target is within tolerance.
- **Mouse-like output:** movement arrives in uneven per-frame chunks, rounded to the mouse-sensitivity step.

Every parameter is listed with its default in `EngineParams`.

## Aim training and seeds

`/vrtrain start [count]` runs an aim-training session in any world (survival works; nothing in the world is changed). A **target block** appears around you, anywhere from 3° to 180° away (so sometimes behind you), with an instruction above the hotbar:

| Style | Instruction |
|---|---|
| natural | Aim at it like you would in a fight |
| constant | Move onto it at a constant speed |
| accel_mid | Speed up, fastest halfway, then slow down onto it |
| flick | Flick onto it as fast as you can |
| precise | Slow and precise: land on it in one movement |

Rest your crosshair on the block for a quarter second to finish it. After each target you see your reaction time, total time, where your speed peaked, whether you landed short or overshot, and (for constant / accel_mid / precise) how closely you followed the instruction. Targets go to the direction (8 sectors), distance (6 bands) and style with the least data so far, or to the distances where your current seed matches you worst.

When the session ends, a **seed** (one set of `EngineParams` per style) is fitted to all your training data in the background:

- Each trial is permanently put in *train* (2/3) or *validation* (1/3). Fitting only uses train trials; seeds are compared only on validation trials.
- The new seed starts from your current seed, so every session trains it further.
- It replaces the current seed only if it scores higher **and** wins in ≥75% of bootstrap resamples of the validation trials. Otherwise it is **rejected**. Each of its parameter groups (timing, speed profile, landing, corrections, reaction, mouse input) is then tried inside the current seed, and any group that clearly helps is merged in as a new seed.

| Command | Effect |
|---|---|
| `/vrtrain start [count]`, `/vrtrain stop` | Start / end a session (default 48 targets) |
| `/vrtrain fit [iterations]` | Re-fit from all data now |
| `/vrtrain seed` | Current seed: score, per-style scores, the 5 metrics furthest from you |
| `/vrtrain seeds`, `/vrtrain use <id>` | History (including rejected seeds) / roll back |
| `/vrtrain export [style]` | Print the parameters for `./gradlew harness -Pparams="..."` |
| `/vrtrain npc [style]` | Give the seed to the singleplayer NPC (next `/npc spawn`) |

Data goes to `.minecraft/rotation-logs/training/`: `sessions/<date>/` (`trials.csv`, plus a `frames.csv` in the recorder's format, so `analysis/compare.py` works on it) and `seeds/seed_<id>.json`.

## Analysis

Requires Python 3 (standard library only).

```bash
# Integrity checks + summary of player recordings
python3 analysis/check_data.py data/player

# Aim-model statistics (flick timing, corrections, reaction delay, aim height)
ITEM=netherite_sword python3 analysis/aim_model.py data/player

# Run the model offline and compare against human data metric by metric
./gradlew harness -Pcount=2000
python3 analysis/compare.py data/player build/harness/engine_seed1 --item netherite_sword

# Compare an in-game NPC run instead
python3 analysis/compare.py data/player data/npc/npc_02 --item netherite_sword

# Summarize an /npc auto run
python3 analysis/inspect_flicks.py data/npc/npc_02 --rows 20

# Redact your own recordings before sharing them
python3 analysis/redact.py ~/.minecraft/rotation-logs my-dataset
```

## Dataset (`data/`)

| Folder | Contents |
|---|---|
| `player/session_01..14` | Real PvP recordings (formats v2 and v3; see `meta.json`) |
| `legacy_v1/` | Earliest single-file format |
| `npc/npc_01..02` | NPC runs (`moveto` and `auto`) |

**Redaction:** player names are replaced (`self`, `opponent_01`…), the server is removed, x/z coordinates are made relative to each session's start, and wall-clock times are removed.

**Caveats:**
- One player, one server, mostly sword and mace fights.
- Format v2 sessions (`player/session_01–03`) have a known one-tick offset in `frames.csv` tick labels; timestamps are correct. See [docs/PVP_DATA.md](docs/PVP_DATA.md) §1.
- Other client-side combat mods were installed in the recording instance, so treat click timing as not guaranteed to be purely human input.

## Building

```bash
./gradlew build          # jar in build/libs/
```

Java 21+. The mod ID is `goofy-viberotations`.

## License

[CC0-1.0](LICENSE)
