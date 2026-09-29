"""Copy rotation-logs to a shareable dataset with personal info removed. Originals are not touched.

- Your player name -> "self"; server address -> "redacted".
- Other players' names -> stable pseudonyms (opponent_01, ...), consistent across all sessions.
- World x/z coordinates -> relative to the session's first position (y kept: it's needed for physics).
- Wall-clock times removed: folder dates -> session_01..., meta "started" dropped, epoch ms -> relative ms.
- System.nanoTime columns are kept: they're relative to an arbitrary origin and reveal nothing.

Usage: python3 analysis/redact.py <rotation-logs dir> <output dir>
Writes <output>/pseudonyms_PRIVATE.txt mapping real names to pseudonyms — keep that file OUT of any repo.
"""
import csv, glob, json, os, shutil, sys

NAME_COLS = {"target", "hit_player"}
X_COLS = [("x", "z"), ("target_x", "target_z")]  # shifted by the session's own origin


class Pseudo:
    def __init__(self, self_names):
        self.map = {n: "self" for n in self_names if n}
        self.n = 0

    def __call__(self, name):
        if not name:
            return name
        if name not in self.map:
            self.n += 1
            self.map[name] = f"opponent_{self.n:02d}"
        return self.map[name]


def redact_csv(src, dst, pseudo, epoch_col=None):
    with open(src, newline="") as fi:
        rd = csv.DictReader(fi)
        rows = list(rd)
        cols = rd.fieldnames
    origin = None
    t0 = None
    for r in rows:
        for c in NAME_COLS & set(cols):
            r[c] = pseudo(r[c])
        if "x" in cols and "z" in cols and r.get("x"):
            if origin is None:
                origin = (float(r["x"]), float(r["z"]))
            for cx, cz in X_COLS:
                if cx in cols and r.get(cx):
                    r[cx] = f"{float(r[cx]) - origin[0]:.4f}"
                    r[cz] = f"{float(r[cz]) - origin[1]:.4f}"
        if epoch_col and epoch_col in cols and r.get(epoch_col):
            if t0 is None:
                t0 = int(r[epoch_col])
            r[epoch_col] = str(int(r[epoch_col]) - t0)
    with open(dst, "w", newline="") as fo:
        w = csv.DictWriter(fo, fieldnames=cols, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    return len(rows)


def main():
    src, out = sys.argv[1], sys.argv[2]
    if os.path.exists(out):
        sys.exit(f"{out} already exists; remove it first")
    os.makedirs(out)

    self_names = set()
    for m in glob.glob(os.path.join(src, "*", "meta.json")):
        self_names.add(json.load(open(m)).get("player", ""))
    pseudo = Pseudo(self_names)

    player = sorted(d for d in glob.glob(os.path.join(src, "*/")) if not os.path.basename(d[:-1]).startswith("npc_"))
    npc = sorted(glob.glob(os.path.join(src, "npc_*/")))
    legacy = sorted(glob.glob(os.path.join(src, "pvp_*.csv")))
    total = 0

    for i, d in enumerate(player, 1):
        o = os.path.join(out, "player", f"session_{i:02d}")
        os.makedirs(o)
        meta = json.load(open(os.path.join(d, "meta.json")))
        meta["player"] = "self"
        meta["server"] = "redacted"
        meta.pop("started", None)
        json.dump(meta, open(os.path.join(o, "meta.json"), "w"), indent=2)
        for f in ("ticks.csv", "clicks.csv", "frames.csv"):
            if os.path.exists(os.path.join(d, f)):
                total += redact_csv(os.path.join(d, f), os.path.join(o, f), pseudo)

    for i, d in enumerate(npc, 1):
        o = os.path.join(out, "npc", f"npc_{i:02d}")
        os.makedirs(o)
        meta = json.load(open(os.path.join(d, "meta.json")))
        meta.pop("started", None)
        json.dump(meta, open(os.path.join(o, "meta.json"), "w"), indent=2)
        for f in glob.glob(os.path.join(d, "*.csv")):
            shutil.copy(f, o)  # engine data only: no names, coordinates or wall-clock times

    for i, f in enumerate(legacy, 1):
        o = os.path.join(out, "legacy_v1")
        os.makedirs(o, exist_ok=True)
        total += redact_csv(f, os.path.join(o, f"session_{i:02d}.csv"), pseudo, epoch_col="time_ms")

    with open(os.path.join(out, "pseudonyms_PRIVATE.txt"), "w") as fh:
        for real, fake in sorted(pseudo.map.items(), key=lambda kv: kv[1]):
            fh.write(f"{fake}\t{real}\n")
    print(f"{len(player)} player sessions, {len(npc)} NPC runs, {len(legacy)} legacy files, {total} rows, "
          f"{pseudo.n} opponents pseudonymized -> {out}")


if __name__ == "__main__":
    main()
