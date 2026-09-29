"""Auto-tune rotation-engine parameters against recorded human data.

Hill-climbing search: perturb a few parameters, run the harness, score with compare.py's
metrics, keep the change if the score improves. Each candidate is scored on 2 seeds so a
lucky seed can't win.

Usage (build first with ./gradlew compileJava):
  python3 analysis/tune.py <human rotation-logs dir> [--item netherite_sword] [--iters 80] [--start "k=v,..."]

Prints the best parameter string; pass it to ./gradlew harness -Pparams="..." or /npc param.
"""
import math, os, random, subprocess, sys, tempfile

sys.path.insert(0, os.path.dirname(__file__))
import compare  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLASSES = os.path.join(ROOT, "build", "classes", "java", "main")

# name: (min, max, log-scale)
SPACE = {
    "durA": (20, 90, True), "durB": (0.25, 0.6, False), "durSigma": (0.2, 0.8, False),
    "peakTimeSd": (0.08, 0.3, False), "profileTrim": (0.0, 0.25, False),
    "pUndershoot": (0.25, 0.6, False), "pOvershoot": (0.08, 0.35, False),
    "undershootMedian": (0.05, 0.4, True), "overshootMedian": (0.1, 0.6, True),
    "pauseMedianMs": (15, 120, True), "pauseSigma": (0.5, 1.6, False), "pauseMaxMs": (200, 1200, True),
    "toleranceDeg": (0.5, 6, True), "deliveryNoise": (0.1, 0.8, False), "fps": (80, 200, True),
}
SKIP = {"movements"}          # depends on how many waypoints the harness runs
CAP = math.log(3)             # one broken metric can't dominate the score


def run(params, seed, out):
    subprocess.run(["java", "-cp", CLASSES, "com.goofy.viberotations.engine.RotationHarness",
                    f"--out={out}", "--count=700", f"--seed={seed}", f"--params={params}"],
                   check=True, capture_output=True)
    return compare.metrics(compare.features(compare.movements(compare.load_frames(os.path.join(out, f"engine_seed{seed}")))))


def score(human, params, out):
    total = []
    for seed in (11, 22):
        eng = run(params, seed, out)
        errs = []
        for k, h in human.items():
            e = eng[k]
            if k in SKIP or not h or math.isnan(h) or math.isnan(e):
                continue
            errs.append(min(CAP, abs(math.log(e / h))) if e > 0 else CAP)
        total.append(sum(errs) / len(errs))
    return sum(total) / len(total)


def fmt(p):
    return ",".join(f"{k}={v:.4g}" for k, v in sorted(p.items()))


def main():
    args = sys.argv[1:]
    opt = lambda n, d: args[args.index(n) + 1] if n in args else d
    item = opt("--item", None)
    iters = int(opt("--iters", "80"))
    start = opt("--start", "")
    human = compare.metrics(compare.features(compare.movements(compare.load_frames(args[0], item))))

    cur = {}
    for pair in filter(None, start.split(",")):
        k, v = pair.split("="); cur[k] = float(v)
    rng = random.Random(7)
    with tempfile.TemporaryDirectory() as out:
        best = score(human, fmt(cur), out)
        print(f"start score {best:.4f}  {fmt(cur)}", flush=True)
        for i in range(iters):
            cand = dict(cur)
            for k in rng.sample(list(SPACE), rng.choice([1, 2, 3])):
                lo, hi, log = SPACE[k]
                base = cand.get(k, (lo * hi) ** 0.5 if log else (lo + hi) / 2)
                v = base * math.exp(rng.gauss(0, 0.25)) if log else base + rng.gauss(0, (hi - lo) * 0.12)
                cand[k] = min(hi, max(lo, v))
            s = score(human, fmt(cand), out)
            if s < best:
                best, cur = s, cand
                print(f"[{i+1}/{iters}] improved {best:.4f}  {fmt(cur)}", flush=True)
        print(f"\nBEST {best:.4f}\n{fmt(cur)}")


if __name__ == "__main__":
    main()
