"""Summarize an NPC /npc auto run (flicks.csv).

Usage:
  python3 analysis/inspect_flicks.py <npc_... folder | rotation-logs folder>   (latest npc_ run if given rotation-logs)
  add --rows N to also print the first N flicks
"""
import csv, glob, math, os, statistics as st, sys


def pct(xs, p):
    if not xs:
        return float("nan")
    xs = sorted(xs); k = (len(xs) - 1) * p / 100; lo = int(k); hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def line(name, xs, unit=""):
    if not xs:
        print(f"  {name:28} n/a"); return
    print(f"  {name:28} n={len(xs):<5} median={pct(xs,50):8.2f}{unit}  p10={pct(xs,10):8.2f}  p90={pct(xs,90):8.2f}  max={max(xs):8.2f}")


def main():
    path = sys.argv[1]
    if not os.path.exists(os.path.join(path, "flicks.csv")):
        runs = sorted(d for d in glob.glob(os.path.join(path, "npc_*/")) if os.path.exists(os.path.join(d, "flicks.csv")))
        if not runs:
            sys.exit(f"No flicks.csv under {path}")
        path = runs[-1]
    rows = list(csv.DictReader(open(os.path.join(path, "flicks.csv"))))
    print(f"Run: {path}\nFlicks: {len(rows)}")
    if not rows:
        return
    F = lambda r, k: float(r[k])

    for kind in sorted({r["kind"] for r in rows}) + ["all"]:
        rs = rows if kind == "all" else [r for r in rows if r["kind"] == kind]
        print(f"\n== {kind} ({len(rs)})")
        line("amplitude (deg)", [F(r, "amplitude") for r in rs])
        line("time to settle (ms)", [F(r, "duration_ms") for r in rs])
        line("end error (deg)", [F(r, "end_error") for r in rs])
        line("sub-movements", [int(r["submovements"]) for r in rs])

    print("\n== Settle time by size (includes reaction delay)")
    print(f"  {'size':>10} {'n':>5} {'settle ms':>10} {'end err':>8} {'1 flick':>8} {'2':>5} {'3+':>5}")
    for lo, hi in [(0, 2), (2, 5), (5, 10), (10, 20), (20, 40), (40, 90), (90, 400)]:
        b = [r for r in rows if lo <= F(r, "amplitude") < hi]
        if not b:
            continue
        subs = [int(r["submovements"]) for r in b]
        n = len(b)
        print(f"  {lo:>4}-{hi:<4}° {n:>5} {pct([F(r,'duration_ms') for r in b],50):>10.0f} "
              f"{pct([F(r,'end_error') for r in b],50):>8.2f} {100*subs.count(1)/n:>7.0f}% "
              f"{100*subs.count(2)/n:>4.0f}% {100*sum(1 for s in subs if s>=3)/n:>4.0f}%")

    # consistency checks: each flick starts where the previous ended
    jumps = 0
    for a, b in zip(rows, rows[1:]):
        if abs(F(b, "start_yaw") - F(a, "end_yaw")) > 0.01 or abs(F(b, "start_pitch") - F(a, "end_pitch")) > 0.01:
            jumps += 1
    print(f"\nChecks: {jumps} flicks did not start where the previous one ended (expected 0)")
    capped = sum(1 for r in rows if int(r["submovements"]) >= 6)
    print(f"        {capped} flicks hit the sub-movement cap (maxSubmovements)")

    if "--rows" in sys.argv:
        n = int(sys.argv[sys.argv.index("--rows") + 1])
        print(f"\nFirst {n} flicks:")
        print(f"  {'n':>4} {'kind':>6} {'start yaw/pitch':>18} {'target yaw/pitch':>18} {'end yaw/pitch':>18} {'err':>6} {'ms':>6} {'subs':>4}")
        for r in rows[:n]:
            print(f"  {r['n']:>4} {r['kind']:>6} {F(r,'start_yaw'):8.2f}/{F(r,'start_pitch'):<8.2f} "
                  f"{F(r,'target_yaw'):8.2f}/{F(r,'target_pitch'):<8.2f} {F(r,'end_yaw'):8.2f}/{F(r,'end_pitch'):<8.2f} "
                  f"{F(r,'end_error'):6.2f} {F(r,'duration_ms'):6.0f} {r['submovements']:>4}")


if __name__ == "__main__":
    main()
