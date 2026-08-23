"""InterpolateMaterial, reimplemented in numpy against dumped real inputs.

WHY THIS EXISTS. Iterating on the device costs ten minutes -- build, install,
launch, three minutes of menus, play, record, analyse -- to test one line of
shader. Worse, every run is a different scene at a different camera speed, so
two algorithms can never be compared on the same input. The first measurement of
the ghosting fix read 10.6% spill before and 27.0% after, on recordings of
different lengths of different action; that number says nothing about the change.

With the two real frames and the motion field dumped from the device, the shader
is twenty lines here, the artefact reproduces exactly, and a candidate is judged
against the frame that actually produced it -- in seconds, with only the thing
under test changing.

THE CONTRACT. This must stay a faithful port. Anything proved here has to be
transcribed back into the GLSL unchanged, so if the two drift the offline result
stops meaning anything. Every step below names the shader line it mirrors.
"""
import os
import numpy as np

DUMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dump")


def load(width, height, block):
    newer = np.fromfile(os.path.join(DUMP, "newer.rgba"), dtype=np.uint8)
    older = np.fromfile(os.path.join(DUMP, "older.rgba"), dtype=np.uint8)
    newer = newer.reshape(height, width, 4)[..., :3].astype(np.float32) / 255.0
    older = older.reshape(height, width, 4)[..., :3].astype(np.float32) / 255.0

    gw, gh = width // block, height // block
    field = np.fromfile(os.path.join(DUMP, "field.f32"), dtype=np.float32)
    field = field.reshape(gh, gw, 4)[..., :2]
    # glReadPixels returns rows bottom-up; the shader samples with the same
    # orientation the texture was written in, so flip both to match.
    return newer[::-1], older[::-1], field[::-1]


def luma(rgb):
    """LumaMaterial: the same perceptual weights, so the static test agrees."""
    return rgb @ np.array([0.299, 0.587, 0.114], dtype=np.float32)


def sample(img, u, v):
    """texture2D with GL_LINEAR and GL_CLAMP_TO_EDGE, in texture coordinates."""
    h, w = img.shape[:2]
    x = np.clip(u * w - 0.5, 0, w - 1)
    y = np.clip(v * h - 0.5, 0, h - 1)
    x0, y0 = np.floor(x).astype(np.int32), np.floor(y).astype(np.int32)
    x1, y1 = np.minimum(x0 + 1, w - 1), np.minimum(y0 + 1, h - 1)
    fx, fy = (x - x0)[..., None], (y - y0)[..., None]
    return (img[y0, x0] * (1 - fx) * (1 - fy) + img[y0, x1] * fx * (1 - fy)
            + img[y1, x0] * (1 - fx) * fy + img[y1, x1] * fx * fy)


def interpolate(newer, older, field, phase, sign, static_mode="blend"):
    """The shader's main(), step for step.

    static_mode:
      "off"    - no static suppression, the original behaviour
      "scale"  - carry multiplies the vectors (the first attempt)
      "blend"  - carry mixes towards an uncompensated cross-fade (the second)
    """
    h, w = newer.shape[:2]
    gh, gw = field.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    uv = np.stack([(xs + 0.5) / w, (ys + 0.5) / h], axis=-1)

    motion_scale = np.array([1.0 / w, 1.0 / h], dtype=np.float32)
    vector_size = np.array([gw, gh], dtype=np.float32)
    scale = motion_scale * sign

    def blocks(at):
        """The four block vectors around `at`, and their raised-cosine weights."""
        grid = at * vector_size - 0.5
        base = np.floor(grid)
        f = grid - base
        wgt = 0.5 - 0.5 * np.cos(np.pi * f)
        weight = np.stack([
            (1 - wgt[..., 0]) * (1 - wgt[..., 1]), wgt[..., 0] * (1 - wgt[..., 1]),
            (1 - wgt[..., 0]) * wgt[..., 1], wgt[..., 0] * wgt[..., 1]], axis=-1)
        bx = np.clip(base[..., 0].astype(np.int32), 0, gw - 1)
        by = np.clip(base[..., 1].astype(np.int32), 0, gh - 1)
        bx1, by1 = np.minimum(bx + 1, gw - 1), np.minimum(by + 1, gh - 1)
        m = [field[by, bx], field[by, bx1], field[by1, bx], field[by1, bx1]]
        return [v * scale for v in m], weight

    # First read: the field where this pixel sits in frame N.
    m, weight = blocks(uv)
    mean = sum(weight[..., i:i + 1] * m[i] for i in range(4))

    # Re-read where this instant's content actually came from.
    p = np.clip(uv - mean * (1.0 - phase), 0.0, 1.0)
    m, weight = blocks(p)

    def predict(v):
        from_newer = np.clip(uv - v * (1.0 - phase), 0.0, 1.0)
        from_older = np.clip(uv + v * phase, 0.0, 1.0)
        a = sample(older, from_older[..., 0], from_older[..., 1])
        b = sample(newer, from_newer[..., 0], from_newer[..., 1])
        return a * (1 - phase) + b * phase

    if static_mode == "scale":
        moved = np.abs(luma(newer) - luma(older))
        carry = np.clip((moved - 2 / 255) / (10 / 255), 0, 1)
        carry = carry * carry * (3 - 2 * carry)
        m = [v * carry[..., None] for v in m]

    shown = sum(weight[..., i:i + 1] * predict(m[i]) for i in range(4))

    if static_mode == "fit":
        # **Ask which hypothesis explains this pixel, rather than whether it
        # changed.** `moved` alone cannot separate a static overlay from a flat
        # wall: both are unchanged at uv, and only one of them is still. So score
        # the two hypotheses against each other -- staying put versus moving by
        # the block's vector -- and let the pixel choose.
        ln, lo = luma(newer), luma(older)
        fit_zero = np.abs(sample(ln[..., None], uv[..., 0], uv[..., 1])[..., 0]
                          - sample(lo[..., None], uv[..., 0], uv[..., 1])[..., 0])
        fn = np.clip(uv - mean * (1.0 - phase), 0.0, 1.0)
        fo = np.clip(uv + mean * phase, 0.0, 1.0)
        fit_moved = np.abs(sample(ln[..., None], fn[..., 0], fn[..., 1])[..., 0]
                           - sample(lo[..., None], fo[..., 0], fo[..., 1])[..., 0])
        ratio = fit_zero / (fit_zero + fit_moved + 1e-4)
        # Only a decisive win for staying put suppresses. Where the two are
        # comparable -- a flat wall, where neither hypothesis is contradicted --
        # motion wins, because that is the answer that is right everywhere else.
        carry = np.clip((ratio - 0.30) / 0.40, 0, 1)
        carry = carry * carry * (3 - 2 * carry)
        still = older * (1 - phase) + newer * phase
        shown = still + (shown - still) * carry[..., None]

    if static_mode == "blend":
        moved = np.abs(luma(newer) - luma(older))
        carry = np.clip((moved - 2 / 255) / (10 / 255), 0, 1)
        carry = carry * carry * (3 - 2 * carry)
        still = older * (1 - phase) + newer * phase
        shown = still + (shown - still) * carry[..., None]

    return np.clip(shown, 0.0, 1.0)
