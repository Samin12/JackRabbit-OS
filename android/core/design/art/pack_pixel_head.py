"""Pack the voxel "Pixel head" orb frames into the compact atlases the app draws from.

Input: rendered RGBA frames for each size bucket (transparent background). A bucket directory is
either a 48-frame loop (frames_000..047.png, yaw = 28 * sin(2 pi i / 48)) or the 25 unique poses
(pose_00..24.png, yaw -28 .. +28). Each bucket needs three variants: idle, active (eyes lit) and
blink (eyes closed), as sibling directories <idle>, <idle>_active, <idle>_blink.

Output (per bucket of frame size S):
  res/drawable-nodpi/pixel_head_S.png        25 idle poses, cropped to the union bounding box
  res/drawable-nodpi/pixel_head_S_eyes.png   eye patches: 25 "active" then 25 "blink" patches
  src/main/java/.../PixelHeadAtlas.java      generated geometry (cells, anchors, patch rects)

Only the eyes differ between variants, so the app draws the idle pose and paints the eye patch
of the active / blink variant over it. A loop frame i and frame 24 - i have the same yaw, so 25
poses replace 48 frames. Atlases are palette-quantized (8-bit PNG with alpha) to keep the APK small.

Usage (any Python with Pillow + numpy):
  python pack_pixel_head.py --bucket 72=<dir> --bucket 112=<dir> --bucket 176=<dir> --bucket 256=<dir>
"""
from __future__ import annotations

import argparse
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(HERE)
RES = os.path.join(MODULE, "src", "main", "res", "drawable-nodpi")
JAVA = os.path.join(MODULE, "src", "main", "java", "com", "resonolabs", "ui", "design", "PixelHeadAtlas.java")

POSES = 25
COLUMNS = 5
GUTTER = 2          # transparent pixels around every cell / patch so bilinear sampling never bleeds
PATCH_MARGIN = 2    # unchanged pixels kept around each eye difference so patch edges are invisible
LOOP_POSE_FRAMES = list(range(36, 48)) + list(range(0, 13))


def load_variant(path: str) -> list[np.ndarray]:
    if os.path.exists(os.path.join(path, "pose_00.png")):
        names = [f"pose_{p:02d}.png" for p in range(POSES)]
    else:
        names = [f"frames_{i:03d}.png" for i in LOOP_POSE_FRAMES]
    out = []
    for name in names:
        im = Image.open(os.path.join(path, name)).convert("RGBA")
        out.append(np.asarray(im, np.uint8).copy())
    return out


def union_box(frames: list[np.ndarray]) -> tuple[int, int, int, int]:
    x0 = y0 = 10 ** 9
    x1 = y1 = -1
    for f in frames:
        ys, xs = np.nonzero(f[..., 3])
        x0, y0 = min(x0, xs.min()), min(y0, ys.min())
        x1, y1 = max(x1, xs.max()), max(y1, ys.max())
    return int(x0), int(y0), int(x1) + 1, int(y1) + 1


def crop(frame: np.ndarray, x0: int, y0: int, w: int, h: int) -> np.ndarray:
    """Crop with transparent padding where the rect leaves the frame."""
    out = np.zeros((h, w, 4), np.uint8)
    fh, fw = frame.shape[:2]
    sx0, sy0 = max(0, x0), max(0, y0)
    sx1, sy1 = min(fw, x0 + w), min(fh, y0 + h)
    out[sy0 - y0:sy1 - y0, sx0 - x0:sx1 - x0] = frame[sy0:sy1, sx0:sx1]
    return out


def premultiplied(a: np.ndarray) -> np.ndarray:
    f = a.astype(np.float32) / 255.0
    f[..., :3] *= f[..., 3:4]
    return f


def over(src: np.ndarray, dst: np.ndarray) -> np.ndarray:
    """Premultiplied SRC_OVER of two straight-alpha uint8 images; returns premultiplied float."""
    s, d = premultiplied(src), premultiplied(dst)
    return s + d * (1.0 - s[..., 3:4])


def quantize(rgba: np.ndarray, colors: int = 256) -> Image.Image:
    """Weighted k-means palette on premultiplied RGBA (the art is near-grey, so 256 entries are
    visually lossless); returns a P-mode image with a per-entry alpha (tRNS) chunk."""
    flat = rgba.reshape(-1, 4)
    keys, inverse, counts = np.unique(flat, axis=0, return_inverse=True, return_counts=True)
    inverse = inverse.reshape(-1)
    pts = premultiplied(keys[None])[0] * 255.0
    w = counts.astype(np.float64)
    transparent = keys[:, 3] == 0
    if len(keys) <= colors:
        centers = keys.astype(np.float64)
        assign = np.arange(len(keys))
    else:
        # seed: fully transparent + most frequent colours spread by a farthest-point pass
        k = colors - 1
        order = np.argsort(-w)
        seeds = [order[0]]
        dist = np.sum((pts - pts[order[0]]) ** 2, 1)
        for _ in range(k - 1):
            nxt = int(np.argmax(dist * np.sqrt(w)))
            seeds.append(nxt)
            dist = np.minimum(dist, np.sum((pts - pts[nxt]) ** 2, 1))
        c = pts[seeds].astype(np.float64)
        opaque = ~transparent
        po, wo = pts[opaque].astype(np.float64), w[opaque]

        def nearest(p):
            d = (p * p).sum(1)[:, None] - 2.0 * p @ c.T + (c * c).sum(1)[None]
            return d.argmin(1)

        for _ in range(30):
            a = nearest(po)
            mass = np.bincount(a, weights=wo, minlength=k)
            for ch in range(4):
                s = np.bincount(a, weights=wo * po[:, ch], minlength=k)
                c[:, ch] = np.where(mass > 0, s / np.maximum(mass, 1e-9), c[:, ch])
        assign = nearest(pts.astype(np.float64)) + 1
        assign[transparent] = 0
        # back to straight alpha palette entries
        centers = np.zeros((colors, 4), np.float64)
        alpha = np.clip(c[:, 3], 0, 255)
        rgb = np.where(alpha[:, None] > 0, c[:, :3] * 255.0 / np.maximum(alpha[:, None], 1e-6), 0)
        centers[1:, :3] = np.clip(rgb, 0, 255)
        centers[1:, 3] = alpha
    pal = np.clip(np.rint(centers), 0, 255).astype(np.uint8)
    idx = assign[inverse].astype(np.uint8).reshape(rgba.shape[:2])
    im = Image.fromarray(idx, "P")
    palette = np.zeros((256, 3), np.uint8)
    palette[:len(pal)] = pal[:, :3]
    im.putpalette(palette.reshape(-1).tolist())
    alphas = np.zeros(256, np.uint8)
    alphas[:len(pal)] = pal[:, 3]
    im.info["transparency"] = bytes(alphas.tolist())
    return im


def decode_palette(im: Image.Image) -> np.ndarray:
    return np.asarray(im.convert("RGBA"), np.uint8)


def pack_bucket(size: int, idle_dir: str) -> dict:
    idle = load_variant(idle_dir)
    active = load_variant(idle_dir + "_active")
    blink = load_variant(idle_dir + "_blink")
    x0, y0, x1, y1 = union_box(idle + active + blink)
    ix0, iy0, ix1, iy1 = union_box(idle)
    cw, ch = (x1 - x0) + 2 * GUTTER, (y1 - y0) + 2 * GUTTER
    ox, oy = x0 - GUTTER, y0 - GUTTER          # frame coords of a cell's top-left
    rows = (POSES + COLUMNS - 1) // COLUMNS
    atlas = np.zeros((rows * ch, COLUMNS * cw, 4), np.uint8)
    cells = []
    for p, f in enumerate(idle):
        c = crop(f, ox, oy, cw, ch)
        cells.append(c)
        r, k = divmod(p, COLUMNS)
        atlas[r * ch:(r + 1) * ch, k * cw:(k + 1) * cw] = c

    patches = {}
    for name, frames in (("active", active), ("blink", blink)):
        boxes = []
        for p in range(POSES):
            v = crop(frames[p], ox, oy, cw, ch)
            diff = np.any(v != cells[p], axis=2)
            ys, xs = np.nonzero(diff)
            boxes.append((xs.min() - PATCH_MARGIN, ys.min() - PATCH_MARGIN,
                          xs.max() + 1 + PATCH_MARGIN, ys.max() + 1 + PATCH_MARGIN))
        pw = max(b[2] - b[0] for b in boxes)
        ph = max(b[3] - b[1] for b in boxes)
        rects = []
        for b in boxes:
            px = int(round((b[0] + b[2] - pw) / 2))
            py = int(round((b[1] + b[3] - ph) / 2))
            px = max(GUTTER, min(cw - GUTTER - pw, px))
            py = max(GUTTER, min(ch - GUTTER - ph, py))
            rects.append((px, py))
        patches[name] = (pw, ph, rects, frames)

    aw, ah = patches["active"][0] + 2 * GUTTER, patches["active"][1] + 2 * GUTTER
    bw, bh = patches["blink"][0] + 2 * GUTTER, patches["blink"][1] + 2 * GUTTER
    blink_top = rows * ah
    eyes = np.zeros((blink_top + rows * bh, COLUMNS * max(aw, bw), 4), np.uint8)
    worst = 0.0
    for name, top, sw, sh in (("active", 0, aw, ah), ("blink", blink_top, bw, bh)):
        pw, ph, rects, frames = patches[name]
        for p in range(POSES):
            v = crop(frames[p], ox, oy, cw, ch)
            px, py = rects[p]
            patch = v[py:py + ph, px:px + pw].copy()
            base = cells[p][py:py + ph, px:px + pw]
            # Keep what changed plus opaque surroundings (opaque over anything is exact); drop
            # unchanged soft edge pixels, which would double their alpha when drawn twice.
            changed = np.any(patch != base, axis=2)
            keep = changed | ((patch[..., 3] == 255) & (base[..., 3] == 255))
            patch[~keep] = 0
            # A changed soft edge pixel (glow over the silhouette) gets the colour that, drawn
            # over the idle pixel, reproduces the variant ("un-over"); a pure colour shift at equal
            # alpha cannot be expressed and keeps the idle pixel.
            soft = changed & (patch[..., 3] < 255) & (base[..., 3] > 0)
            if soft.any():
                pv, pb = premultiplied(patch[soft][None])[0], premultiplied(base[soft][None])[0]
                ia = pb[:, 3:4]
                pa = np.clip((pv[:, 3:4] - ia) / np.maximum(1.0 - ia, 1e-6), 0.0, 1.0)
                prem = np.clip(pv[:, :3] - pb[:, :3] * (1.0 - pa), 0.0, pa)
                rgb = np.where(pa > 1e-6, prem / np.maximum(pa, 1e-6), 0.0)
                patch[soft] = np.rint(np.concatenate([rgb, pa], 1) * 255.0).astype(np.uint8)
            r, k = divmod(p, COLUMNS)
            ey, ex = top + r * sh + GUTTER, k * sw + GUTTER
            eyes[ey:ey + ph, ex:ex + pw] = patch
            # the composite the app draws (idle, then the patch over it) must match the variant
            comp = over(patch, base)
            want = premultiplied(v[py:py + ph, px:px + pw])
            worst = max(worst, float(np.abs(comp - want).max()) * 255.0)

    idle_png = quantize(atlas)
    eyes_png = quantize(eyes)
    q_err = float(np.abs(premultiplied(decode_palette(idle_png)) - premultiplied(atlas)).max()) * 255
    q_mean = float(np.abs(premultiplied(decode_palette(idle_png)) - premultiplied(atlas)).mean()) * 255
    os.makedirs(RES, exist_ok=True)
    idle_path = os.path.join(RES, f"pixel_head_{size}.png")
    eyes_path = os.path.join(RES, f"pixel_head_{size}_eyes.png")
    idle_png.save(idle_path, optimize=True, transparency=idle_png.info["transparency"])
    eyes_png.save(eyes_path, optimize=True, transparency=eyes_png.info["transparency"])
    print(f"bucket {size}: cell {cw}x{ch}, atlas {atlas.shape[1]}x{atlas.shape[0]} "
          f"{os.path.getsize(idle_path) // 1024} KB, eyes {eyes.shape[1]}x{eyes.shape[0]} "
          f"{os.path.getsize(eyes_path) // 1024} KB; patch overlay max err {worst:.1f}/255, "
          f"palette err max {q_err:.1f} mean {q_mean:.2f}")
    return dict(
        size=size, cell_w=cw, cell_h=ch,
        anchor_x=size / 2.0 - ox, anchor_y=size / 2.0 - oy,
        head_h=float(iy1 - iy0),
        active_w=patches["active"][0], active_h=patches["active"][1],
        blink_w=patches["blink"][0], blink_h=patches["blink"][1],
        active_slot_w=aw, active_slot_h=ah, blink_slot_w=bw, blink_slot_h=bh, blink_top=blink_top,
        active_xy=patches["active"][2], blink_xy=patches["blink"][2])


def ints(values) -> str:
    return "{" + ", ".join(str(int(v)) for v in values) + "}"


def floats(values) -> str:
    return "{" + ", ".join(f"{v:.2f}f" for v in values) + "}"


def write_java(buckets: list[dict]) -> None:
    def table(key, idx):
        rows = ",\n            ".join(ints(xy[idx] for xy in b[key]) for b in buckets)
        return "{\n            " + rows + "}"

    src = f'''package com.resonolabs.ui.design;

/**
 * Geometry of the packed Pixel head atlases. GENERATED by core/design/art/pack_pixel_head.py;
 * do not edit by hand. Buckets are ordered smallest first; poses run from yaw -28 (0) through
 * front (12) to +28 degrees (24). All coordinates are bucket pixels.
 */
final class PixelHeadAtlas {{
    private PixelHeadAtlas() {{}}

    static final int POSES = {POSES};
    static final int COLUMNS = {COLUMNS};
    static final int GUTTER = {GUTTER};
    /** Nominal square frame size each bucket was rendered at. */
    static final int[] FRAME = {ints(b["size"] for b in buckets)};
    static final int[] CELL_W = {ints(b["cell_w"] for b in buckets)};
    static final int[] CELL_H = {ints(b["cell_h"] for b in buckets)};
    /** The head's centre (middle of its swing extents) inside a cell. */
    static final float[] ANCHOR_X = {floats(b["anchor_x"] for b in buckets)};
    static final float[] ANCHOR_Y = {floats(b["anchor_y"] for b in buckets)};
    /** Wing tip to neck height of the head, the size the app scales to. */
    static final float[] HEAD_H = {floats(b["head_h"] for b in buckets)};
    static final int[] ACTIVE_W = {ints(b["active_w"] for b in buckets)};
    static final int[] ACTIVE_H = {ints(b["active_h"] for b in buckets)};
    static final int[] ACTIVE_SLOT_W = {ints(b["active_slot_w"] for b in buckets)};
    static final int[] ACTIVE_SLOT_H = {ints(b["active_slot_h"] for b in buckets)};
    static final int[] BLINK_W = {ints(b["blink_w"] for b in buckets)};
    static final int[] BLINK_H = {ints(b["blink_h"] for b in buckets)};
    static final int[] BLINK_SLOT_W = {ints(b["blink_slot_w"] for b in buckets)};
    static final int[] BLINK_SLOT_H = {ints(b["blink_slot_h"] for b in buckets)};
    /** Top of the blink patch grid inside the eyes atlas (active patches start at 0). */
    static final int[] BLINK_TOP = {ints(b["blink_top"] for b in buckets)};
    /** Per pose: where the eye patch goes inside the idle cell. */
    static final int[][] ACTIVE_X = {table("active_xy", 0)};
    static final int[][] ACTIVE_Y = {table("active_xy", 1)};
    static final int[][] BLINK_X = {table("blink_xy", 0)};
    static final int[][] BLINK_Y = {table("blink_xy", 1)};
}}
'''
    with open(JAVA, "w") as f:
        f.write(src)
    print("wrote", os.path.relpath(JAVA, MODULE))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bucket", action="append", required=True, help="SIZE=DIR (idle frames dir)")
    args = ap.parse_args()
    specs = sorted((int(s.split("=", 1)[0]), s.split("=", 1)[1]) for s in args.bucket)
    buckets = [pack_bucket(size, os.path.expanduser(d)) for size, d in specs]
    write_java(buckets)


if __name__ == "__main__":
    main()
