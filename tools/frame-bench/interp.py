"""InterpolateMaterial, reimplemented in numpy, step for step.

WHY THIS EXISTS. Iterating on the device costs ten minutes -- build, install,
launch, three minutes of menus, play, record, analyse -- to test one line of
shader. Worse, every run is a different scene at a different camera speed, so
two algorithms can never be compared on the same input. The first measurement of
the ghosting fix read 10.6% spill before and 27.0% after, on recordings of
different lengths of different action; that number says nothing about the change.

THE CONTRACT. This must stay a faithful port. Anything proved here has to be
transcribed back into the GLSL unchanged, so if the two drift the offline result
stops meaning anything. Every step below names the shader block it mirrors.

AND IT HAD DRIFTED. Until 2026-09-02 this file was the shader as it stood
before overlapped block motion compensation, before the projection step, before
the consistency test, before the source weights and before `carry` -- twenty
lines standing in for nine hundred. Every number the bench produced about the
interpolation from then on was about a shader that no longer shipped. This is
the current one: the field projected to the older site (f74b9bc), the four
predictions under raised-cosine windows, the photometric weights and the
consistency test, the edge fades, the OBMC blend and the still fallback.

THE SWITCHES. Each names one decision the shader takes, so a scene can score
the alternatives on identical input. The defaults are what ships in 0.5.14;
the alternatives were built on 2026-09-02, measured, and reverted with the
rest of that day, and they stay here because the measurements are in the
reverted commits and the code to repeat them should not have to be rewritten.

    newer_side   the newer-side round trip. On, as shipped.
    drop         which source the older-side disagreement removes: "older" is
                 what ships, "newer" the alternative, "none" switches it off.
    border_gate  in-frame fades, in pixels, on the source weights and on
                 fitMoving as well as on the occlusion terms. On, as shipped
                 since the step-3 change; off is the 0.5.14 behaviour.
    photometry   "luma" scores the R8 pair, as shipped; "colour" the RGB
                 targets.
    obmc         "fit" weights each of the four blocks' predictions by its
                 endpoint agreement under the raised-cosine window, as shipped
                 since step 4; "window" is the 0.5.14 blend; "fit9" does the
                 fit weighting over nine blocks.

CONVENTIONS. Fields are in raw matcher units -- pixels, ref to target, indexed
on the ref, which is the OLDER frame for the forward field and the warped
newer frame (N-1 geometry) for the backward one -- and `sign` is what
SignMaterial latches. The device latches -1.
"""
import numpy as np

LUMA = np.array([0.299, 0.587, 0.114], dtype=np.float32)


def luma(rgb):
    """LumaMaterial: the same perceptual weights."""
    return rgb @ LUMA


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def sample(img, u, v):
    """texture2D with GL_LINEAR and GL_CLAMP_TO_EDGE, in texture coordinates."""
    h, w = img.shape[:2]
    x = np.clip(u * w - 0.5, 0, w - 1)
    y = np.clip(v * h - 0.5, 0, h - 1)
    x0, y0 = np.floor(x).astype(np.int32), np.floor(y).astype(np.int32)
    x1, y1 = np.minimum(x0 + 1, w - 1), np.minimum(y0 + 1, h - 1)
    fx, fy = (x - x0), (y - y0)
    if img.ndim == 3:
        fx, fy = fx[..., None], fy[..., None]
    return (img[y0, x0] * (1 - fx) * (1 - fy) + img[y0, x1] * fx * (1 - fy)
            + img[y1, x0] * (1 - fx) * fy + img[y1, x1] * fx * fy)


def max_diff(a, b):
    """The shader's maxDiff: the largest channel step."""
    return np.abs(a - b).max(axis=-1)


def interpolate(newer, older, field, back, phase, sign=-1.0, *,
                newer_side=True, drop="older", border_gate=True,
                photometry="luma", consistency=True, diagnostics=None,
                obmc="fit", obmc_floor=4.0 / 255.0):
    """The shader's main().

    newer, older : (h, w, 3) float32 in [0, 1]
    field, back  : (gh, gw, 2) raw matcher units; `back` may be None, which is
                   `consistency = 0` on the device
    """
    h, w = newer.shape[:2]
    gh, gw = field.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    uv = np.stack([(xs + 0.5) / w, (ys + 0.5) / h], axis=-1)

    motion_scale = np.array([1.0 / w, 1.0 / h], dtype=np.float32)
    vector_size = np.array([gw, gh], dtype=np.float32)
    scale = motion_scale * sign
    if back is None:
        back = field
        consistency = False
    consistency = 1.0 if consistency else 0.0

    # ---- readField: four NEAREST taps under a raised-cosine window ----------
    def read_field(src, at):
        grid = at * vector_size - 0.5
        base = np.floor(grid)
        f = 0.5 - 0.5 * np.cos(np.pi * (grid - base))
        weight = np.stack([
            (1 - f[..., 0]) * (1 - f[..., 1]), f[..., 0] * (1 - f[..., 1]),
            (1 - f[..., 0]) * f[..., 1], f[..., 0] * f[..., 1]], axis=-1)
        bx = np.clip(base[..., 0].astype(np.int32), 0, gw - 1)
        by = np.clip(base[..., 1].astype(np.int32), 0, gh - 1)
        bx1, by1 = np.minimum(bx + 1, gw - 1), np.minimum(by + 1, gh - 1)
        m = [src[by, bx] * scale, src[by, bx1] * scale,
             src[by1, bx] * scale, src[by1, bx1] * scale]
        return weight, m

    def field_at(src, at):
        weight, m = read_field(src, at)
        return sum(weight[..., i:i + 1] * m[i] for i in range(4))

    # ---- the field, projected to this instant (the N-1 site) ---------------
    mean = field_at(field, uv)
    p = np.clip(uv + mean * phase, 0.0, 1.0)
    weight, m = read_field(field, p)
    mean = sum(weight[..., i:i + 1] * m[i] for i in range(4))

    # ---- photometry ---------------------------------------------------------
    if photometry == "colour":
        newer_p, older_p = newer, older
        diff = max_diff
    elif photometry == "luma":
        newer_p, older_p = luma(newer)[..., None], luma(older)[..., None]
        diff = max_diff
    else:
        raise ValueError(photometry)

    here_n = sample(newer_p, uv[..., 0], uv[..., 1])
    here_o = sample(older_p, uv[..., 0], uv[..., 1])
    fit_still = diff(here_n, here_o)

    mn_raw = uv - mean * (1.0 - phase)
    mo_raw = uv + mean * phase
    mn = np.clip(mn_raw, 0.0, 1.0)
    mo = np.clip(mo_raw, 0.0, 1.0)
    at_mn_n = sample(newer_p, mn[..., 0], mn[..., 1])
    at_mo_o = sample(older_p, mo[..., 0], mo[..., 1])
    at_mn_o = sample(older_p, mn[..., 0], mn[..., 1])
    at_mo_n = sample(newer_p, mo[..., 0], mo[..., 1])

    # In pixels, over 24 of them, on both axes alike.
    edge_n = np.minimum(mn_raw, 1.0 - mn_raw) / motion_scale
    edge_o = np.minimum(mo_raw, 1.0 - mo_raw) / motion_scale
    on_n = smoothstep(0.0, 24.0, np.minimum(edge_n[..., 0], edge_n[..., 1]))
    on_o = smoothstep(0.0, 24.0, np.minimum(edge_o[..., 0], edge_o[..., 1]))

    fit_moving = diff(at_mn_n, at_mo_o)
    if border_gate:
        fit_moving = fit_moving * on_n * on_o
    ratio = fit_still / (fit_still + fit_moving + 1.0 / 2550.0)
    carry = smoothstep(0.3, 0.7, ratio)

    still_at_n = 1.0 - smoothstep(2.0 / 255, 12.0 / 255, diff(at_mn_n, at_mn_o))
    still_at_o = 1.0 - smoothstep(2.0 / 255, 12.0 / 255, diff(at_mo_n, at_mo_o))
    moves = smoothstep(2.0 / 255, 12.0 / 255, fit_still)

    # ---- which frame can see this pixel ------------------------------------
    b_at_older = field_at(back, mo)
    e_older = np.linalg.norm((b_at_older + mean) / motion_scale, axis=-1)
    mag_back = np.linalg.norm(b_at_older / motion_scale, axis=-1)
    mag_older = np.linalg.norm(mean / motion_scale, axis=-1)
    tol_older = np.sqrt(0.01 * (mag_older ** 2 + mag_back ** 2) + 2.25)
    occ_older = consistency * smoothstep(tol_older, tol_older * 4.0, e_older) * on_o

    if newer_side:
        f_round = field_at(field, np.clip(mo_raw + b_at_older, 0.0, 1.0))
        e_newer = np.linalg.norm((f_round + b_at_older) / motion_scale, axis=-1)
        mag_newer = np.linalg.norm(f_round / motion_scale, axis=-1)
        tol_newer = np.sqrt(0.01 * (mag_newer ** 2 + mag_back ** 2) + 2.25)
        occ_newer = consistency * smoothstep(tol_newer, tol_newer * 4.0, e_newer) * on_n
    else:
        occ_newer = np.zeros_like(occ_older)

    w_older = (1.0 - phase) * (1.0 - still_at_o * moves)
    w_newer = phase * (1.0 - still_at_n * moves) * (1.0 - occ_newer)
    if drop == "older":
        w_older = w_older * (1.0 - occ_older)
    elif drop == "newer":
        w_newer = w_newer * (1.0 - occ_older)
    elif drop != "none":
        raise ValueError(drop)
    if border_gate:
        w_older = w_older * on_o
        w_newer = w_newer * on_n
    refused = (w_older + w_newer) < 1.0e-3
    w_older = np.where(refused, 1.0 - phase, w_older)
    w_newer = np.where(refused, phase, w_newer)

    # ---- overlapped block motion compensation ------------------------------
    def predict(v):
        from_newer = np.clip(uv - v * (1.0 - phase), 0.0, 1.0)
        from_older = np.clip(uv + v * phase, 0.0, 1.0)
        a = sample(older, from_older[..., 0], from_older[..., 1])
        b = sample(newer, from_newer[..., 0], from_newer[..., 1])
        blend = ((a * w_older[..., None] + b * w_newer[..., None])
                 / np.maximum(w_older + w_newer, 1.0e-4)[..., None])
        return blend, max_diff(a, b)

    if obmc == "fit9":
        # **Nine blocks, not four, and the fit picks among them.** A block that
        # straddles a silhouette carries one vector for all 64 of its pixels,
        # so the background inside it is dragged by the foreground's motion --
        # by the whole parallax difference, not by a block. The four nearest
        # blocks can all be that block's kind. One ring further out is where
        # the background's own vector still lives, and the endpoint fit is
        # what lets a pixel borrow it: the wrong vector disagrees with itself
        # by the contrast of the edge, the right one does not.
        grid = p * vector_size - 0.5
        centre = np.round(grid)
        wsum = None
        shown = 0.0
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                b = centre + np.array([dx, dy], dtype=np.float32)
                dist = grid - b
                r = np.clip(np.abs(dist) / 1.5, 0.0, 1.0)
                win = np.prod(0.5 + 0.5 * np.cos(np.pi * r), axis=-1)
                bx = np.clip(b[..., 0].astype(np.int32), 0, gw - 1)
                by = np.clip(b[..., 1].astype(np.int32), 0, gh - 1)
                v = field[by, bx] * scale
                pred, fit = predict(v)
                wf = win / (fit + obmc_floor)
                shown = shown + pred * wf[..., None]
                wsum = wf if wsum is None else wsum + wf
        shown = shown / np.maximum(wsum, 1e-6)[..., None]
        still = older * (1.0 - phase) + newer * phase
        shown = still + (shown - still) * carry[..., None]
        if diagnostics is not None:
            diagnostics.update(carry=carry, occ_older=occ_older, occ_newer=occ_newer,
                               w_older=w_older, w_newer=w_newer, mean=mean,
                               on_older=on_o, on_newer=on_n)
        return np.clip(shown, 0.0, 1.0)

    preds = [predict(m[i]) for i in range(4)]
    if obmc == "window":
        shown = sum(weight[..., i:i + 1] * preds[i][0] for i in range(4))
    elif obmc == "fit":
        # **The window says where the pixel sits; the fit says which block is
        # telling the truth about it.** At a silhouette between two depths the
        # four blocks hold two different motions, and a plain window blends
        # them across a 16 px band -- the smear on every object border. Each
        # prediction already fetched both of its endpoints, so how well they
        # agree costs nothing, and a vector that fetched the wall for a pixel on
        # the cabinet disagrees with itself by the whole contrast of the edge.
        # Where the four blocks agree the fits are equal and this is the window.
        fits = np.stack([1.0 / (preds[i][1] + obmc_floor) for i in range(4)], axis=-1)
        wf = weight * fits
        wf = wf / wf.sum(axis=-1, keepdims=True)
        shown = sum(wf[..., i:i + 1] * preds[i][0] for i in range(4))
    else:
        raise ValueError(obmc)

    # ---- did this pixel move, or is it painted on the screen? --------------
    still = older * (1.0 - phase) + newer * phase
    shown = still + (shown - still) * carry[..., None]

    if diagnostics is not None:
        diagnostics.update(carry=carry, occ_older=occ_older, occ_newer=occ_newer,
                           w_older=w_older, w_newer=w_newer, mean=mean,
                           on_older=on_o, on_newer=on_n)
    return np.clip(shown, 0.0, 1.0)
