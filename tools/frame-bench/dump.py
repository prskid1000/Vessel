"""Replay the shader on the device's own inputs, and say how far it lands from
the device's own output.

WHY. Every scene in this directory builds its field with the bench's block
matcher, whose fields are clean, coherent and in range. The device's are not:
its `fg truth` line reads synthesised frames 14 to 33% further from the real
frame than a perfect interpolation while the bench's object-border error sits at
three levels. The gap is the field, and until FrameSynthesizer.maybeDump the
field could not be brought here at all.

WHAT IT DOES.

    python dump.py pull            # adb run-as, into ./dump/NN/
    python dump.py [NN]            # replay dump NN (default: every one found)

For each dump: runs interp.interpolate on the device's frames and fields at the
device's phase and sign, and reports

    fidelity   mean |port - device output|, in levels. The README's contract
               says the port must stay faithful; this is the first number that
               can say whether it is. A few tenths is bilinear rounding; more
               is drift, and every other number here is fiction until it is
               fixed.
    truth      the device's own metric, recomputed: how far the output sits
               from frame N against how far N-1 was. No ground truth exists in
               a dump -- there is no real middle frame -- so this is the
               criterion the device already uses, not a substitute for one.

and writes, beside the dump, `replay.png` (device output | port | difference x4)
and `field.png` (vector magnitude over the newer frame), which is where a
silhouette fault shows as a field fault or as a blend fault.

Variants are listed the same way occside.py lists them, so a shader change can
be scored on the real field before it is built.
"""
import json
import os
import subprocess
import sys

import numpy as np
from PIL import Image

import interp

HERE = os.path.dirname(os.path.abspath(__file__))
DUMP = os.path.join(HERE, "dump")
PACKAGE = "app.vessel"


def pull():
    os.makedirs(DUMP, exist_ok=True)
    listing = subprocess.run(["adb", "shell", "run-as", PACKAGE, "ls", "files/fgdump"],
                             capture_output=True, text=True).stdout.split()
    if not listing:
        print("no dumps on the device (files/fgdump is empty or missing)")
        return
    for name in listing:
        out = os.path.join(DUMP, name)
        os.makedirs(out, exist_ok=True)
        for f in ("meta.json", "older.rgba", "newer.rgba", "shown.rgba", "field.f32", "back.f32",
                  "merged.f32", "mergedBack.f32", "global.f32"):
            data = subprocess.run(["adb", "exec-out", "run-as", PACKAGE, "cat",
                                   "files/fgdump/%s/%s" % (name, f)], capture_output=True).stdout
            if data:
                open(os.path.join(out, f), "wb").write(data)
        print("pulled", name, sorted(os.listdir(out)))


def load(folder):
    meta = json.load(open(os.path.join(folder, "meta.json")))
    w, h = meta["width"], meta["height"]
    gw, gh = meta["gridWidth"], meta["gridHeight"]

    def rgba(name):
        a = np.fromfile(os.path.join(folder, name), dtype=np.uint8)
        return a.reshape(h, w, 4)[..., :3].astype(np.float32) / 255.0

    def field(name):
        p = os.path.join(folder, name)
        if not os.path.exists(p) or os.path.getsize(p) != gw * gh * 16:
            return None
        return np.fromfile(p, dtype=np.float32).reshape(gh, gw, 4)[..., :2]

    # glReadPixels returns rows bottom-up. The shader samples every texture in
    # the orientation it was written, and all of these were written the same
    # way, so a consistent flip of everything is the identity for the port.
    meta["merged"] = field("merged.f32")
    meta["mergedBack"] = field("mergedBack.f32")
    g = os.path.join(folder, "global.f32")
    meta["global"] = np.fromfile(g, dtype=np.float32)[:3].tolist() if os.path.exists(g) and os.path.getsize(g) == 16 else None
    return meta, rgba("older.rgba"), rgba("newer.rgba"), rgba("shown.rgba"), field("field.f32"), field("back.f32")


def truth_metric(out, older, newer):
    """The device's `fg truth`: distance to N over N-1's distance to N, over
    pixels that changed at all."""
    d_synth = np.linalg.norm(out - newer, axis=-1)
    d_base = np.linalg.norm(older - newer, axis=-1)
    changed = d_base > 0.008
    return float(d_synth[changed].mean() / d_base[changed].mean())


VARIANTS = [
    ("as shipped", {}),
    ("plain window OBMC", dict(obmc="window")),
    ("3x3 blocks, fit-weighted", dict(obmc="fit9")),
    ("consistency off", dict(drop="none")),
    ("newer-side round trip off", dict(newer_side=False)),
]


def replay(folder):
    meta, older, newer, shown, field, back = load(folder)
    phase, sign = meta["phase"], meta["fieldSign"]
    if not meta.get("consistency", 1):
        back = None
    print("\n%s: %dx%d, grid %dx%d, phase %.3f, sign %+.0f, field mean %.0f px, interval %d ms"
          % (os.path.basename(folder), meta["width"], meta["height"], meta["gridWidth"],
             meta["gridHeight"], phase, sign, meta["fieldMagnitude"], meta["interval"]))
    mag = np.linalg.norm(field, axis=-1)
    print("  field: mean %.1f px, p50 %.1f, p95 %.1f, max %.1f, zero blocks %.1f%%"
          % (mag.mean(), np.median(mag), np.percentile(mag, 95), mag.max(),
             (mag < 0.5).mean() * 100))
    print("  %-30s %10s %10s" % ("variant", "fidelity", "truth"))
    print("  %-30s %10s %10.3f  (phase alone: %.3f)"
          % ("device output", "-", truth_metric(shown, older, newer), 1 - phase))
    if meta.get("global") is not None:
        gx, gy, agree = meta["global"]
        print("  global motion (%+.1f, %+.1f) px, %.0f%% of the coarse field agrees" % (gx, gy, agree * 100))
    if meta.get("merged") is not None:
        out = interp.interpolate(newer, older, meta["merged"], meta["mergedBack"], phase, sign)
        print("  %-30s %10.2f %10.3f" % ("before the gate", float(np.abs(out - shown).mean() * 255),
                                        truth_metric(out, older, newer)))
    first = None
    for label, kw in VARIANTS:
        out = interp.interpolate(newer, older, field, back, phase, sign, **kw)
        fid = float(np.abs(out - shown).mean() * 255)
        print("  %-30s %10.2f %10.3f" % (label, fid, truth_metric(out, older, newer)))
        if first is None:
            first = out
    print("  %-30s %10.2f %10.3f"
          % ("cross-fade", float(np.abs(older * (1 - phase) + newer * phase - shown).mean() * 255),
             truth_metric(older * (1 - phase) + newer * phase, older, newer)))

    to8 = lambda a: (np.clip(a, 0, 1) * 255).astype(np.uint8)
    diff = np.clip(np.abs(first - shown) * 4, 0, 1)
    strip = np.concatenate([to8(shown), to8(first), to8(diff)], axis=1)
    Image.fromarray(strip[::-1]).save(os.path.join(folder, "replay.png"))
    # Field magnitude, block-expanded, over the newer frame.
    full = np.repeat(np.repeat(mag, meta["blockY"], axis=0), meta["blockX"], axis=1)
    full = np.pad(full, ((0, newer.shape[0] - full.shape[0]), (0, newer.shape[1] - full.shape[1])),
                  mode="edge")
    heat = np.clip(full / max(1.0, np.percentile(mag, 99)), 0, 1)
    over = to8(newer * 0.5)
    over[..., 0] = np.maximum(over[..., 0], to8(heat))
    Image.fromarray(over[::-1]).save(os.path.join(folder, "field.png"))
    print("  wrote replay.png and field.png")


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "pull":
        pull()
        return
    names = sys.argv[1:] or sorted(d for d in os.listdir(DUMP) if os.path.isdir(os.path.join(DUMP, d)))
    if not names:
        print("nothing in dump/; run `python dump.py pull` with the device attached")
        return
    for name in names:
        replay(os.path.join(DUMP, name))


if __name__ == "__main__":
    main()
