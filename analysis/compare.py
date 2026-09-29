"""Compare mouse-movement statistics: recorded human frames vs. rotation-engine frames.

Usage:
  python3 analysis/compare.py <human rotation-logs dir> <engine dir> [--item netherite_sword]

<human dir> holds session folders (frames.csv + ticks.csv). <engine dir> holds a frames.csv
written by `./gradlew harness` or by the NPC recorder (or folders containing one).
Both sides go through the same segmentation, so differences come from the data, not the code.
"""
import csv, glob, math, os, statistics as st, sys

GAP_MS = 25          # frames further apart than this start a new movement
BIG_FLICK = 15       # degrees
CORRECTION_WINDOW = 300


def frame_files(root):
    if os.path.exists(os.path.join(root, "frames.csv")):
        return [root]
    # A folder of sessions: only player recordings. NPC runs (npc_*) must be passed directly.
    return sorted(d for d in glob.glob(os.path.join(root, "*/"))
                  if os.path.exists(os.path.join(d, "frames.csv")) and not os.path.basename(d.rstrip("/")).startswith("npc_"))


def load_frames(root, item=None):
    """Returns a list of sessions, each a list of (t_ms, d_yaw, d_pitch)."""
    sessions = []
    for d in frame_files(root):
        held = None
        if item and os.path.exists(os.path.join(d, "ticks.csv")):
            held = {r["tick"]: r["held_item"] for r in csv.DictReader(open(os.path.join(d, "ticks.csv")))}
        rows = []
        for r in csv.DictReader(open(os.path.join(d, "frames.csv"))):
            if held is not None and held.get(r["tick"]) != item:
                continue
            rows.append((int(r["time_ns"]) / 1e6, float(r["d_yaw"]), float(r["d_pitch"])))
        if rows:
            sessions.append(rows)
    return sessions


def movements(sessions):
    out = []  # (session index, frames)
    for si, rows in enumerate(sessions):
        cur = []
        for f in rows:
            if cur and f[0] - cur[-1][0] > GAP_MS:
                out.append((si, cur)); cur = []
            cur.append(f)
        if cur:
            out.append((si, cur))
    return out


def features(moves):
    feats = []
    for si, m in moves:
        if len(m) < 3:
            continue
        ay = sum(f[1] for f in m); ap = sum(f[2] for f in m)
        amp = math.hypot(ay, ap)
        dur = m[-1][0] - m[0][0]
        if dur <= 0 or amp <= 0:
            continue
        vels = [math.hypot(b[1], b[2]) / (max(b[0] - a[0], 1) / 1000) for a, b in zip(m, m[1:])]
        peak = max(vels)
        rev = sum(1 for a, b in zip(m, m[1:]) if a[1] * b[1] < 0 and abs(a[1]) > 0.2 and abs(b[1]) > 0.2)
        feats.append(dict(si=si, t0=m[0][0], t1=m[-1][0], amp=amp, ay=ay, ap=ap, dur=dur, peak=peak,
                          tpk=(vels.index(peak) + 1) / len(vels), rev=rev))
    return feats


def pct(xs, p):
    if not xs:
        return float("nan")
    xs = sorted(xs); k = (len(xs) - 1) * p / 100; lo = int(k); hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def fit(feats, key):
    pts = [(math.log(f["amp"]), math.log(f[key])) for f in feats if f["amp"] > 0.5]
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    mx, my = st.mean(xs), st.mean(ys)
    b = sum((x - mx) * (y - my) for x, y in pts) / sum((x - mx) ** 2 for x in xs)
    a = math.exp(my - b * mx)
    sd = st.pstdev([y - (math.log(a) + b * x) for x, y in pts])
    return a, b, sd


def metrics(feats):
    m = {}
    m["movements"] = len(feats)
    m["amplitude median"] = pct([f["amp"] for f in feats], 50)
    m["amplitude p90"] = pct([f["amp"] for f in feats], 90)
    m["duration median ms"] = pct([f["dur"] for f in feats], 50)
    m["duration p90 ms"] = pct([f["dur"] for f in feats], 90)
    m["peak speed median"] = pct([f["peak"] for f in feats], 50)
    m["peak speed p90"] = pct([f["peak"] for f in feats], 90)
    m["time of peak median"] = pct([f["tpk"] for f in feats], 50)
    m["time of peak p10"] = pct([f["tpk"] for f in feats], 10)
    m["time of peak p90"] = pct([f["tpk"] for f in feats], 90)
    m["pitch/yaw ratio median"] = pct([abs(f["ap"]) / abs(f["ay"]) for f in feats if abs(f["ay"]) > 1], 50)
    m["reversals inside movement %"] = 100 * sum(1 for f in feats if f["rev"]) / max(len(feats), 1)
    for lo, hi in [(0, 2), (2, 5), (5, 10), (10, 20), (20, 40), (40, 90), (90, 400)]:
        b = [f for f in feats if lo <= f["amp"] < hi]
        m[f"dur {lo}-{hi} deg ms"] = pct([f["dur"] for f in b], 50)
        m[f"peak {lo}-{hi} deg"] = pct([f["peak"] for f in b], 50)
    a, b, sd = fit(feats, "dur"); m["dur fit a"], m["dur fit b"], m["dur fit sd"] = a, b, sd
    a, b, sd = fit(feats, "peak"); m["peak fit a"], m["peak fit b"], m["peak fit sd"] = a, b, sd

    pauses, opp, same, big, ratio, delay = [], 0, 0, 0, [], []
    for a, b in zip(feats, feats[1:]):
        if a["si"] != b["si"]:
            continue
        gap = b["t0"] - a["t1"]
        if gap < 2000:
            pauses.append(gap)
        if abs(a["ay"]) >= BIG_FLICK:
            big += 1
            if gap <= CORRECTION_WINDOW:
                if a["ay"] * b["ay"] < 0:
                    opp += 1; ratio.append(abs(b["ay"]) / abs(a["ay"])); delay.append(gap)
                else:
                    same += 1
    m["pause median ms"] = pct(pauses, 50)
    m["pause p10 ms"] = pct(pauses, 10)
    m["pause p90 ms"] = pct(pauses, 90)
    m["after big flick: same-dir %"] = 100 * same / max(big, 1)
    m["after big flick: opposite %"] = 100 * opp / max(big, 1)
    m["after big flick: nothing %"] = 100 * (big - same - opp) / max(big, 1)
    m["correction size median"] = pct(ratio, 50)
    m["correction delay median ms"] = pct(delay, 50)
    return m


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    item = None
    if "--item" in sys.argv:
        item = sys.argv[sys.argv.index("--item") + 1]
        args = [a for a in args if a != item]
    human = metrics(features(movements(load_frames(args[0], item))))
    engine = metrics(features(movements(load_frames(args[1]))))
    print(f"{'metric':34} {'human':>10} {'engine':>10} {'engine/human':>13}")
    score = []
    for k in human:
        h, e = human[k], engine[k]
        r = e / h if h and not math.isnan(h) and not math.isnan(e) else float("nan")
        flag = "" if math.isnan(r) or 0.8 <= r <= 1.25 else "  <--"
        if k != "movements" and not math.isnan(r) and h:
            score.append(abs(math.log(r)) if r > 0 else 1)
        print(f"{k:34} {h:10.2f} {e:10.2f} {r:13.2f}{flag}")
    within = sum(1 for s in score if s <= math.log(1.25))
    print(f"\n{within}/{len(score)} metrics within ±25% of human. Mean |log ratio| = {st.mean(score):.3f} (0 = identical)")


if __name__ == "__main__":
    main()
