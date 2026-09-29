"""Extract human aim characteristics from goofy-viberotations v2/v3 sessions, for an NPC aim model."""
import csv, glob, math, os, sys, statistics as st
from collections import defaultdict

BASE = sys.argv[1]
dirs = sorted(d for d in glob.glob(os.path.join(BASE, "*/")) if os.path.exists(d + "ticks.csv"))

def load(name):
    out = []
    for d in dirs:
        for r in csv.DictReader(open(d + name)):
            r["sess"] = d; out.append(r)
    return out

def F(v): return float(v) if v not in ("", None) else None
def pct(xs, p):
    xs = sorted(xs); k = (len(xs) - 1) * p / 100; lo = int(k); hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)
def summ(name, xs, unit=""):
    xs = [x for x in xs if x is not None]
    if not xs: print(f"  {name}: n/a"); return
    print(f"  {name}: n={len(xs)} mean={st.mean(xs):.2f}{unit} med={pct(xs,50):.2f} p10={pct(xs,10):.2f} p90={pct(xs,90):.2f}")
def corr(a, b):
    ma, mb = st.mean(a), st.mean(b)
    num = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    den = math.sqrt(sum((x - ma) ** 2 for x in a) * sum((y - mb) ** 2 for y in b))
    return num / den if den else 0
def wrap(a): return (a + 180) % 360 - 180

T = load("ticks.csv"); FR = load("frames.csv")
ITEM = os.environ.get("ITEM")
if ITEM:
    held = {(r["sess"], r["tick"]): r["held_item"] for r in T}
    T = [r for r in T if r["held_item"] == ITEM]
    FR = [r for r in FR if held.get((r["sess"], r["tick"])) == ITEM]
    print(f"(filtered to {ITEM}: {len(T)} ticks, {len(FR)} frames)")

# ─── 1. Flicks from per-frame mouse input ──────────────────────────────
# A movement = consecutive mouse frames with gaps < 25 ms; ends at a pause.
print("== 1. Mouse movements (from frames, all sessions)")
moves = []
cur = []
prev_t = None
for r in FR:
    t = int(r["time_ns"]) / 1e6
    if prev_t is not None and (t - prev_t > 25 or r["sess"] != cur[-1]["sess"] if cur else False):
        if cur: moves.append(cur)
        cur = []
    cur.append({"t": t, "dy": F(r["d_yaw"]), "dp": F(r["d_pitch"]), "sess": r["sess"]})
    prev_t = t
if cur: moves.append(cur)

feat = []
for m in moves:
    if len(m) < 3: continue
    amp_y = sum(f["dy"] for f in m); amp_p = sum(f["dp"] for f in m)
    amp = math.hypot(amp_y, amp_p)
    dur = m[-1]["t"] - m[0]["t"]
    if dur <= 0: continue
    vels = []
    for a, b in zip(m, m[1:]):
        dt = max(b["t"] - a["t"], 1) / 1000
        vels.append(math.hypot(b["dy"], b["dp"]) / dt)
    peak = max(vels); ipk = vels.index(peak)
    # direction reversals inside one movement = overshoot corrections
    rev = sum(1 for a, b in zip(m, m[1:]) if a["dy"] * b["dy"] < 0 and abs(a["dy"]) > 0.2 and abs(b["dy"]) > 0.2)
    feat.append(dict(amp=amp, amp_y=amp_y, amp_p=amp_p, dur=dur, peak=peak,
                     tpk=(ipk + 1) / len(vels), rev=rev, n=len(m)))
print(f"  movements: {len(feat)}")
summ("amplitude (deg)", [f["amp"] for f in feat])
summ("duration (ms)", [f["dur"] for f in feat])
summ("peak speed (deg/s)", [f["peak"] for f in feat])
summ("time of peak (0=start,1=end)", [f["tpk"] for f in feat])
summ("|pitch| / |yaw| amplitude ratio", [abs(f["amp_p"]) / abs(f["amp_y"]) for f in feat if abs(f["amp_y"]) > 1])
print(f"  movements with direction reversals: {sum(1 for f in feat if f['rev'] > 0)}/{len(feat)}")

print("  amplitude bucket -> median duration / median peak speed / count")
for lo, hi in [(0, 2), (2, 5), (5, 10), (10, 20), (20, 40), (40, 90), (90, 400)]:
    b = [f for f in feat if lo <= f["amp"] < hi]
    if b:
        print(f"    {lo:>3}-{hi:<3} deg: {pct([f['dur'] for f in b],50):6.0f} ms  {pct([f['peak'] for f in b],50):7.0f} deg/s  n={len(b)}")

# pauses between movements
pauses = []
for a, b in zip(moves, moves[1:]):
    if a[-1]["sess"] == b[0]["sess"]:
        p = b[0]["t"] - a[-1]["t"]
        if p < 2000: pauses.append(p)
summ("pause between movements (ms, <2s)", pauses)

# ─── 2. Tracking a moving target (ticks) ───────────────────────────────
print("\n== 2. Tracking (ticks with target in 6 blocks)")
seqs = defaultdict(list)
for r in T:
    if r["target"] and F(r["reach_dist"]) <= 6:
        seqs[(r["sess"], r["fight"], r["target"])].append(r)
my_v, tgt_v, err = [], [], []
pairs = []
for rows in seqs.values():
    for a, b in zip(rows, rows[1:]):
        if int(b["tick"]) - int(a["tick"]) != 1: continue
        tv = wrap(F(b["yaw_to_target"]) - F(a["yaw_to_target"]))   # target's angular motion
        mv = F(b["d_yaw"])
        pairs.append((a["sess"], int(b["tick"]), mv, tv))
        my_v.append(mv); tgt_v.append(tv); err.append(F(b["yaw_error"]))
print(f"  tick pairs: {len(pairs)}")
print(f"  corr(my yaw speed, target angular speed) same tick: {corr(my_v, tgt_v):.2f}")
# lag: my rotation k ticks later vs target motion now
idx = {(s, t): (mv, tv) for s, t, mv, tv in pairs}
for k in range(0, 7):
    a, b = [], []
    for (s, t), (mv, tv) in idx.items():
        n = idx.get((s, t + k))
        if n: a.append(tv); b.append(n[0])
    if len(a) > 50:
        print(f"    lag {k} ticks ({k*50} ms): corr={corr(a, b):.2f}")
# gain: how much of the target's angular motion you follow (regression slope)
if my_v:
    mt = st.mean(tgt_v); mm = st.mean(my_v)
    slope = sum((x - mt) * (y - mm) for x, y in zip(tgt_v, my_v)) / sum((x - mt) ** 2 for x in tgt_v)
    print(f"  follow gain (my d_yaw per 1 deg of target motion): {slope:.2f}")

close = [r for r in T if r["target"] and F(r["reach_dist"]) <= 3]
summ("signed yaw error in reach (deg, + = target right of crosshair)", [F(r["yaw_error"]) for r in close])
summ("|yaw error| to centre in reach", [abs(F(r["yaw_error"])) for r in close])
summ("box aim error in reach (0 = on hitbox)", [F(r["box_aim_error"]) for r in close])
# lag behind strafing: does error point the way the target is moving sideways?
lead = []
for r in close:
    tv = F(r["target_dx"]), F(r["target_dz"])
    # target lateral angular direction from my perspective ~ sign of strafe relative to line of sight
    dx = F(r["target_x"]) - F(r["x"]); dz = F(r["target_z"]) - F(r["z"])
    d = math.hypot(dx, dz)
    if d < 0.3: continue
    lat = (tv[0] * dz - tv[1] * dx) / d   # sideways speed of target (blocks/tick)
    if abs(lat) > 0.05:
        lead.append(F(r["yaw_error"]) * (1 if lat > 0 else -1))
summ("yaw error along target's sideways motion (- = aim trails behind)", lead)

# ─── 3. Where on the body ──────────────────────────────────────────────
print("\n== 3. Aim height on the target (ticks in reach, ray on box)")
heights = []
for r in close:
    if r["ray_on_box"] != "1" or abs(F(r["pitch"])) > 80: continue
    ex, ey, ez = F(r["x"]), F(r["y"]) + 1.62, F(r["z"])
    dx = F(r["target_x"]) - ex; dz = F(r["target_z"]) - ez
    hd = math.hypot(dx, dz)
    yh = ey + math.tan(math.radians(-F(r["pitch"]))) * hd
    heights.append(yh - F(r["target_y"]))
summ("aim height above target feet (blocks, player is 1.8 tall)", heights)
summ("|pitch error| to centre in reach", [abs(F(r["pitch_error"])) for r in close])

# ─── 4. Idle hand noise ────────────────────────────────────────────────
print("\n== 4. Small movements")
small = [f for f in feat if f["amp"] < 1.0]
print(f"  movements under 1 deg: {len(small)}/{len(feat)}")
steps = [abs(F(r["d_yaw"])) for r in FR if F(r["d_yaw"]) != 0]
print(f"  smallest yaw step (sensitivity quantum): {min(steps):.4f} deg")

# ─── 5. Corrections after flicks + main-sequence fit ───────────────────
print("\n== 5. Corrections after big flicks (>=15 deg)")
seq = []
for m in moves:
    if len(m) < 3: continue
    seq.append((m[0]["t"], m[-1]["t"], sum(f["dy"] for f in m), m[0]["sess"]))
corr_ratio, corr_delay, same_dir = [], [], 0
big = 0
for a, b in zip(seq, seq[1:]):
    if abs(a[2]) < 15 or a[3] != b[3]: continue
    big += 1
    gap = b[0] - a[1]
    if gap > 300: continue
    if a[2] * b[2] < 0:
        corr_ratio.append(abs(b[2]) / abs(a[2])); corr_delay.append(gap)
    else:
        same_dir += 1
print(f"  big flicks: {big}; followed within 300ms by opposite correction: {len(corr_ratio)}, by same-direction top-up: {same_dir}")
summ("overshoot correction size (fraction of flick)", corr_ratio)
summ("delay before correction (ms)", corr_delay)

xs = [math.log(f["amp"]) for f in feat if f["amp"] > 0.5]
for key in ("dur", "peak"):
    ys = [math.log(f[key]) for f in feat if f["amp"] > 0.5]
    mx, my = st.mean(xs), st.mean(ys)
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
    a = math.exp(my - b * mx)
    res = [y - (math.log(a) + b * x) for x, y in zip(xs, ys)]
    print(f"  {key} ~= {a:.1f} * amplitude^{b:.2f}   (log residual sd {st.pstdev(res):.2f})")
