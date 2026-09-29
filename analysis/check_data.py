import csv, glob, os, sys, statistics as st
from collections import Counter, defaultdict

BASE = sys.argv[1]
dirs = sorted(d for d in glob.glob(os.path.join(BASE, "*/")) if os.path.exists(d + "ticks.csv"))

def load(name):
    out = []
    for d in dirs:
        with open(d + name) as fh:
            rd = csv.reader(fh)
            header = next(rd)
            for i, vals in enumerate(rd):
                if len(vals) != len(header):
                    print(f"  !! {os.path.basename(d[:-1])}/{name} row {i+2}: {len(vals)} fields, header has {len(header)}")
                    continue
                r = dict(zip(header, vals)); r["sess"] = os.path.basename(d[:-1]); out.append(r)
    return out

def F(v): return float(v) if v not in ("", None) else None
def pct(xs, p):
    xs = sorted(xs); k = (len(xs) - 1) * p / 100; lo = int(k); hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)
def summ(name, xs):
    xs = [x for x in xs if x is not None]
    if not xs: print(f"  {name}: n/a"); return
    print(f"  {name}: n={len(xs)} mean={st.mean(xs):.3f} med={pct(xs,50):.3f} p10={pct(xs,10):.3f} p90={pct(xs,90):.3f} min={min(xs):.3f} max={max(xs):.3f}")

print("== Integrity")
T = load("ticks.csv"); C = load("clicks.csv"); FR = load("frames.csv")
print(f"  ticks={len(T)} clicks={len(C)} frames={len(FR)}")

# tick continuity inside a fight
gaps = Counter()
for a, b in zip(T, T[1:]):
    if a["sess"] == b["sess"] and a["fight"] == b["fight"]:
        gaps[int(b["tick"]) - int(a["tick"])] += 1
print("  tick step within fights:", dict(gaps.most_common(5)))
dt = [(int(b["time_ns"]) - int(a["time_ns"])) / 1e6 for a, b in zip(T, T[1:])
      if a["sess"] == b["sess"] and int(b["tick"]) - int(a["tick"]) == 1]
summ("ms per tick", dt)

# clicks: counts in ticks.csv vs clicks.csv
print(f"  clicks in ticks.csv={sum(int(r['clicks']) for r in T)} rows in clicks.csv={len(C)}")
# frames sum vs tick d_yaw
fy = defaultdict(float)
for r in FR: fy[(r["sess"], r["tick"])] += F(r["d_yaw"])
diffs = []
for r in T:
    k = (r["sess"], r["tick"])
    if k in fy: diffs.append(abs(fy[k] - F(r["d_yaw"])))
summ("|sum(frame d_yaw) - tick d_yaw| (should be ~0 except tick-boundary shift)", diffs)

# ---- clicks ----
print("\n== Clicks")
res = Counter(r["result"] for r in C); print("  results:", dict(res))
blocked = sum(int(r["blocked_by_miss_cooldown"]) for r in C); print(f"  dropped by vanilla miss-cooldown: {blocked}")
hits = sum(int(r["hits"]) for r in T); conf = sum(int(r["hits_confirmed"]) for r in T)
print(f"  hits={hits} server-confirmed={conf} ({conf/max(hits,1)*100:.0f}%)")
summ("confirm delay (ticks)", [int(r["confirm_delay_ticks"]) for r in T if int(r["hits_confirmed"]) > 0])
summ("ping ms", [int(r["ping_ms"]) for r in T if int(r["ping_ms"]) >= 0])
hp = [r for r in C if r["result"] == "HIT_PLAYER"]; ms = [r for r in C if r["result"] == "MISS"]
print(f"  accuracy (player hits / all clicks): {len(hp)/max(len(C),1)*100:.1f}%")
summ("cooldown at click", [F(r["attack_cooldown"]) for r in C])
print(f"  would_crit on hits: {sum(int(r['would_crit']) for r in hp)}/{len(hp)}")
summ("reach on hits", [F(r["reach_dist"]) for r in hp])
summ("reach on misses", [F(r["reach_dist"]) for r in ms if r["reach_dist"]])
summ("box aim error on misses (deg)", [F(r["box_aim_error"]) for r in ms if r["box_aim_error"]])
inreach_aim = [r for r in ms if r["reach_dist"] and F(r["reach_dist"]) <= 3]
print(f"  misses in reach: {len(inreach_aim)}/{len(ms)}; of those ray on box: {sum(int(r['ray_on_box']) for r in inreach_aim)}")
print("  held items when clicking:", dict(Counter(r["held_item"] for r in C).most_common(4)))

# click interval regularity (autoclicker check)
ivals = []
by_sess = defaultdict(list)
for r in C: by_sess[r["sess"]].append(int(r["time_ns"]))
for ts in by_sess.values():
    ts.sort()
    ivals += [(b - a) / 1e6 for a, b in zip(ts, ts[1:]) if (b - a) / 1e6 < 1000]
summ("click interval ms (<1s)", ivals)
if len(ivals) > 2:
    print(f"  interval stdev={st.pstdev(ivals):.1f}ms, coefficient of variation={st.pstdev(ivals)/st.mean(ivals):.2f}")
    print("  interval histogram (50ms bins):", dict(sorted(Counter(int(i // 50) * 50 for i in ivals).items())))

# ---- ticks ----
print("\n== Ticks")
tg = [r for r in T if r["target"]]
print(f"  ticks with target {len(tg)}/{len(T)}; opponents: {dict(Counter(r['target'] for r in tg).most_common(6))}")
summ("box aim error while in reach", [F(r["box_aim_error"]) for r in tg if F(r["reach_dist"]) <= 3])
print(f"  ray on box while in reach: {sum(int(r['ray_on_box']) for r in tg if F(r['reach_dist'])<=3)}/{sum(1 for r in tg if F(r['reach_dist'])<=3)}")
th = [r for r in T if r["took_hit"] == "1"]
print(f"  hits taken: {len(th)}, health lost: {sum(F(r['health_lost']) for r in th):.1f}")
summ("target health", [F(r["target_health"]) for r in tg])
print(f"  sprint resets (sprint_stopped): {sum(int(r['sprint_stopped']) for r in T)}, crit_ready ticks {sum(int(r['crit_ready']) for r in T)}")
summ("strafe b/s |abs|", [abs(F(r["strafe_bps"])) for r in tg])
summ("approach b/s", [F(r["approach_bps"]) for r in tg])
summ("target_facing_me_deg", [F(r["target_facing_me_deg"]) for r in tg])
summ("fps", [int(r["fps"]) for r in T])

# ---- frames ----
print("\n== Frames")
per_tick = Counter((r["sess"], r["tick"]) for r in FR)
summ("mouse frames per tick", list(per_tick.values()))
dyaw = [abs(F(r["d_yaw"])) for r in FR if F(r["d_yaw"]) != 0]
summ("|d_yaw| per frame (deg)", dyaw)
steps = Counter(round(x, 5) for x in dyaw)
print("  most common per-frame |d_yaw|:", steps.most_common(6))
fdt = []
prev = None
for r in FR:
    if prev and prev["sess"] == r["sess"]:
        d = (int(r["time_ns"]) - int(prev["time_ns"])) / 1e6
        if d < 100: fdt.append(d)
    prev = r
summ("ms between mouse frames", fdt)
