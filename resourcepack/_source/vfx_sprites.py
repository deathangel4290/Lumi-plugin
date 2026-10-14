"""Textures and models for the ability sprites (SpriteFx in the plugin).

Run from the repository root:  python3 resourcepack/_source/vfx_sprites.py

Every texture is symmetric about its horizontal centre line, so it reads the same from above and
below. "flat" sprites lie in the model's XZ plane (slashes, ground rings); "card" sprites stand in
the XY plane and are shown camera-facing (flash, eclipse).
"""
import json
import math
import os

import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "assets", "luminara")
TEX = os.path.join(ROOT, "textures", "item", "vfx")
MODELS = os.path.join(ROOT, "models", "vfx")
ITEMS = os.path.join(ROOT, "items", "vfx")
N = 128


def grid(n=N):
    c = (n - 1) / 2.0
    y, x = np.mgrid[0:n, 0:n].astype(np.float64)
    dx, dy = (x - c) / c, (y - c) / c            # -1..1, +x right, +y down
    return dx, dy, np.hypot(dx, dy), np.arctan2(dy, dx)


def smooth(edge0, edge1, v):
    t = np.clip((v - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def ramp(v, stops):
    """Colour ramp: v in 0..1, stops = [(pos, (r,g,b)), ...]."""
    out = np.zeros(v.shape + (3,))
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        m = (v >= p0) & (v <= p1)
        t = ((v - p0) / max(p1 - p0, 1e-6))[m][:, None]
        out[m] = np.array(c0) * (1 - t) + np.array(c1) * t
    out[v > stops[-1][0]] = stops[-1][1]
    return out


def save(name, rgb, alpha):
    a = np.clip(alpha, 0, 1)
    img = np.dstack([np.clip(rgb, 0, 255), a * 255]).astype(np.uint8)
    img[a < 1 / 255.0] = 0                       # no colour bleeding from invisible pixels
    Image.fromarray(img, "RGBA").save(os.path.join(TEX, name + ".png"))


def slash(name, stops, glow):
    """A crescent bulging toward +x: thick in the middle, tapering to points, bright on the leading edge."""
    dx, dy, r, a = grid()
    span = math.radians(115)
    t = np.clip(1 - np.abs(a) / span, 0, 1)                 # 1 in the middle of the arc, 0 at the tips
    thick = 0.42 * np.sin(t * math.pi / 2) ** 1.2
    outer = 0.92
    depth = (outer - r) / np.maximum(thick, 1e-4)           # 0 at the leading edge, 1 at the inner edge
    body = (depth >= 0) & (depth <= 1) & (t > 0)
    core = np.where(body, (1 - np.clip(depth, 0, 1)) ** 0.9, 0.0)
    # soft halo just outside the leading edge
    halo = np.clip(1 - (r - outer) / 0.05, 0, 1) * (r > outer) * t ** 0.7
    # a faint motion smear inside the crescent
    smear = np.clip(1 - (outer - thick - r) / 0.35, 0, 1) * (depth > 1) * t ** 2 * 0.22
    alpha = np.maximum.reduce([np.clip(core * 1.25, 0, 1) * (0.45 + 0.55 * t ** 0.5), halo * 0.5, smear])
    shade = np.maximum(core, halo * 0.8)
    rgb = ramp(shade, stops)
    rgb = rgb * (1 - smear[..., None]) + np.array(glow) * smear[..., None]
    save(name, rgb, alpha)


def flash(name):
    """A warm white burst with a few long rays."""
    dx, dy, r, a = grid()
    glow = np.exp(-(r / 0.28) ** 2) + 0.35 * np.exp(-(r / 0.6) ** 2)
    rays = (np.abs(np.cos(a * 4)) ** 24 + 0.5 * np.abs(np.cos(a * 4 + math.pi / 4)) ** 30) * np.clip(1 - r, 0, 1) ** 1.5
    v = np.clip(glow + rays * 0.7, 0, 1)
    v *= smooth(1.0, 0.85, r)
    rgb = ramp(v, [(0, (255, 170, 40)), (0.5, (255, 225, 140)), (1, (255, 255, 245))])
    save(name, rgb, v ** 0.9)


def ring(name, stops, cracks=False):
    """A ground shockwave: a bright rim at the edge fading inward, optionally broken by cracks."""
    dx, dy, r, a = grid()
    rim = 0.9
    inside = np.clip((r - (rim - 0.38)) / 0.38, 0, 1) * (r <= rim)
    edge = np.exp(-((r - rim) / 0.035) ** 2)
    v = np.maximum(inside ** 2.2 * 0.7, edge)
    if cracks:
        rng = np.random.default_rng(7)
        crack = np.zeros_like(r)
        for k in range(14):
            ang = (k / 14.0) * 2 * math.pi + rng.uniform(-0.12, 0.12)
            wob = ang + 0.05 * np.sin(r * 9 + k * 1.7) + 0.02 * np.sin(r * 23 + k)
            d = np.abs(np.angle(np.exp(1j * (a - wob)))) * r
            crack = np.maximum(crack, np.exp(-(d / 0.016) ** 2) * (r < rim) * (r > 0.3) * smooth(0.3, 0.5, r))
        crack = np.maximum(crack, np.flipud(crack))         # keep it symmetric top/bottom
        v = np.maximum(v * 0.75, crack * 0.95)
    v *= r <= 0.99
    save(name, ramp(v, stops), v)


def rune_circle(name):
    """A magic circle: two rings, a twelve-point star and small runes, all in gold."""
    dx, dy, r, a = grid()
    line = lambda d, w: np.exp(-(d / w) ** 2)
    v = np.zeros_like(r)
    v = np.maximum(v, line(r - 0.93, 0.018))
    v = np.maximum(v, line(r - 0.80, 0.012))
    v = np.maximum(v, line(r - 0.36, 0.012) * 0.8)
    # twelve-point star: chords between points spaced 5 apart on a circle of radius 0.8
    pts = [(0.8 * math.cos(2 * math.pi * k / 12), 0.8 * math.sin(2 * math.pi * k / 12)) for k in range(12)]
    for k in range(12):
        (x0, y0), (x1, y1) = pts[k], pts[(k + 5) % 12]
        ex, ey = x1 - x0, y1 - y0
        L2 = ex * ex + ey * ey
        t = np.clip(((dx - x0) * ex + (dy - y0) * ey) / L2, 0, 1)
        d = np.hypot(dx - (x0 + t * ex), dy - (y0 + t * ey))
        v = np.maximum(v, line(d, 0.009) * 0.75)
    # rune marks between the two outer rings
    band = (r > 0.82) & (r < 0.91)
    tick = (np.abs(np.cos(a * 18)) > 0.93) | ((np.abs(np.cos(a * 36 + 0.4)) > 0.985) & (r > 0.855))
    v = np.maximum(v, (band & tick) * 0.9)
    glow = np.exp(-(r / 0.22) ** 2) * 0.35
    v = np.clip(np.maximum(v, glow), 0, 1)
    save(name, ramp(v, [(0, (255, 160, 30)), (0.6, (255, 210, 90)), (1, (255, 250, 210))]), v)


def eclipse(name):
    """A black disc with a white corona and a thin purple rim light."""
    dx, dy, r, a = grid()
    disc = 0.48
    body = smooth(disc + 0.01, disc - 0.01, r)
    corona = np.exp(-((r - disc) / 0.05) ** 2) + 0.55 * np.exp(-((r - disc) / 0.2) ** 2) * (r > disc)
    streaks = (np.abs(np.cos(a * 7)) ** 10 * 0.5 + np.abs(np.cos(a * 11 + 1)) ** 16 * 0.4) \
        * np.exp(-((r - disc) / 0.32) ** 2) * (r > disc)
    light = np.clip(corona + streaks, 0, 1) * (1 - body)
    light *= smooth(1.0, 0.8, r)
    rgb = ramp(light, [(0, (120, 60, 200)), (0.5, (220, 200, 255)), (1, (255, 255, 255))])
    rgb = rgb * (1 - body[..., None]) + np.array((8, 4, 16)) * body[..., None]
    save(name, rgb, np.maximum(body * 0.97, light))


# ------------------------------------------------------------------ ritual (white: tinted per weapon)
def _segments(dx, dy, segs, width):
    """Anti-aliased strokes: max over segments of a soft line of `width` (in -1..1 units)."""
    v = np.zeros_like(dx)
    for (x0, y0), (x1, y1) in segs:
        ex, ey = x1 - x0, y1 - y0
        L2 = ex * ex + ey * ey or 1e-9
        t = np.clip(((dx - x0) * ex + (dy - y0) * ey) / L2, 0, 1)
        d = np.hypot(dx - (x0 + t * ex), dy - (y0 + t * ey))
        v = np.maximum(v, smooth(width, width * 0.35, d))
    return v


def _ring(r, at, width):
    return smooth(width, width * 0.35, np.abs(r - at))


def _glyphs(count, r0, r1, seed):
    """Little made-up runes in a band: 2-4 strokes on a 3x3 lattice per cell, read around the circle."""
    rng = np.random.default_rng(seed)
    segs = []
    lattice = [(i, j) for i in range(3) for j in range(3)]
    for g in range(count):
        a0 = 2 * math.pi * g / count
        span = 2 * math.pi / count * 0.62
        def at(i, j):
            ang = a0 + span * (i / 2.0 - 0.5)
            rad = r0 + (r1 - r0) * (j / 2.0)
            return (rad * math.cos(ang), rad * math.sin(ang))
        want, made = rng.integers(3, 5), 0
        while made < want:
            a, b = rng.choice(len(lattice), 2, replace=False)
            (i0, j0), (i1, j1) = lattice[a], lattice[b]
            if abs(i0 - i1) + abs(j0 - j1) > 2:
                continue
            segs.append((at(i0, j0), at(i1, j1)))
            made += 1
    return segs


def polygon(k, radius, turn=0.0, step=1):
    pts = [(radius * math.cos(turn + 2 * math.pi * i / k), radius * math.sin(turn + 2 * math.pi * i / k)) for i in range(k)]
    return [(pts[i], pts[(i + step) % k]) for i in range(k)]


def ritual_outer(name, n=256):
    dx, dy, r, a = grid(n)
    w = 3.4 / n                                            # about one texel either side
    v = np.maximum.reduce([
        _ring(r, 0.965, w * 1.6), _ring(r, 0.93, w), _ring(r, 0.80, w), _ring(r, 0.77, w * 1.4),
        _ring(r, 0.52, w),
        _segments(dx, dy, _glyphs(28, 0.815, 0.915, 3), w * 0.9),
        _segments(dx, dy, polygon(8, 0.77, step=3), w),           # eight-point star
        _segments(dx, dy, polygon(4, 0.77, 0.0), w * 0.8),
        _segments(dx, dy, polygon(4, 0.77, math.pi / 4), w * 0.8),
    ])
    # small nodes where the star touches the ring
    for i in range(8):
        ang = 2 * math.pi * i / 8
        v = np.maximum(v, _ring(np.hypot(dx - 0.77 * math.cos(ang), dy - 0.77 * math.sin(ang)), 0.03, w))
    fill = np.clip(1 - r / 0.97, 0, 1) ** 0.6 * 0.10 * (r < 0.965)       # faint glow inside
    alpha = np.clip(np.maximum(v, fill), 0, 1)
    save(name, np.full(r.shape + (3,), 255.0), alpha)


def ritual_inner(name, n=128):
    dx, dy, r, a = grid(n)
    w = 2.8 / n
    v = np.maximum.reduce([
        _ring(r, 0.95, w * 1.4), _ring(r, 0.86, w),
        _segments(dx, dy, polygon(6, 0.86, math.pi / 2, step=2), w),      # hexagram
        _ring(r, 0.43, w), _ring(r, 0.12, w * 1.5),
        _segments(dx, dy, _glyphs(12, 0.47, 0.6, 11), w * 0.9),
    ])
    for i in range(6):
        ang = math.pi / 2 + 2 * math.pi * i / 6
        v = np.maximum(v, smooth(0.045, 0.02, np.hypot(dx - 0.95 * math.cos(ang), dy - 0.95 * math.sin(ang))))
    fill = smooth(0.5, 0.0, r) * 0.18
    save(name, np.full(r.shape + (3,), 255.0), np.clip(np.maximum(v, fill), 0, 1))


def ritual_beam(name, height, fade):
    """A soft beam: bright core, soft edges, optionally fading out toward the top (v = 0)."""
    w = 32
    x = (np.arange(w) - (w - 1) / 2) / ((w - 1) / 2)
    profile = np.exp(-(x / 0.22) ** 2) + 0.45 * np.exp(-(x / 0.6) ** 2)
    profile = np.clip(profile / profile.max(), 0, 1)
    v = np.linspace(0, 1, height)[:, None]
    along = smooth(0.0, 0.85, v) * smooth(1.0, 0.97, v) if fade else np.ones_like(v)
    alpha = profile[None, :] * along
    core = np.exp(-(x / 0.12) ** 2)[None, :] * np.ones_like(v)
    rgb = np.dstack([np.full(alpha.shape, 255.0)] * 3) * (0.75 + 0.25 * core[..., None])
    a = np.clip(alpha, 0, 1)
    img = np.dstack([np.clip(rgb, 0, 255), a * 255]).astype(np.uint8)
    img[a < 1 / 255.0] = 0
    Image.fromarray(img, "RGBA").save(os.path.join(TEX, name + ".png"))


def ritual_pulse(name):
    dx, dy, r, a = grid()
    v = np.maximum(np.exp(-((r - 0.9) / 0.03) ** 2), np.clip((r - 0.55) / 0.35, 0, 1) ** 2.5 * 0.5 * (r <= 0.9))
    save(name, np.full(r.shape + (3,), 255.0), np.clip(v, 0, 1) * (r < 0.99))


# ------------------------------------------------------------------ tintable slash / flash
def slash_layers(name):
    """Two layers for a slash in any colour: a white body (tinted in game) and an untinted white-hot
    leading edge drawn just above it, so a tinted slash still has a bright rim."""
    dx, dy, r, a = grid()
    span = math.radians(115)
    t = np.clip(1 - np.abs(a) / span, 0, 1)
    thick = 0.42 * np.sin(t * math.pi / 2) ** 1.2
    outer = 0.92
    depth = (outer - r) / np.maximum(thick, 1e-4)
    body = (depth >= 0) & (depth <= 1) & (t > 0)
    core = np.where(body, (1 - np.clip(depth, 0, 1)) ** 0.9, 0.0)
    halo = np.clip(1 - (r - outer) / 0.05, 0, 1) * (r > outer) * t ** 0.7
    smear = np.clip(1 - (outer - thick - r) / 0.35, 0, 1) * (depth > 1) * t ** 2 * 0.22
    alpha = np.maximum.reduce([np.clip(core * 1.25, 0, 1) * (0.45 + 0.55 * t ** 0.5), halo * 0.5, smear])
    white = np.full(r.shape + (3,), 255.0)
    save(name + "_body", white * (0.55 + 0.45 * core[..., None]), alpha)
    edge = np.where(body, np.clip(1 - depth / 0.22, 0, 1), 0.0) * t ** 0.6
    save(name + "_edge", white, np.clip(edge * 0.95, 0, 1))


def flash_layers(name):
    dx, dy, r, a = grid()
    glow = np.exp(-(r / 0.32) ** 2) + 0.35 * np.exp(-(r / 0.65) ** 2)
    rays = (np.abs(np.cos(a * 4)) ** 24 + 0.5 * np.abs(np.cos(a * 4 + math.pi / 4)) ** 30) * np.clip(1 - r, 0, 1) ** 1.5
    v = np.clip(glow + rays * 0.7, 0, 1) * smooth(1.0, 0.85, r)
    white = np.full(r.shape + (3,), 255.0)
    save(name + "_body", white, v ** 0.9)
    core = np.exp(-(r / 0.13) ** 2)
    save(name + "_core", white, np.clip(core * 1.2, 0, 1))


def layered_model(name, shape, layers, gap):
    """Layer 0 is tinted (tintindex 0). Each other layer is drawn untinted `gap` units to BOTH sides of
    it, so whichever side the sprite is seen from, the bright layer is the nearer one and is not
    hidden behind the tinted body."""
    elements = []
    axis = 1 if shape["faces"][0] == "up" else 2
    for i, layer in enumerate(layers):
        for side in ((0,) if i == 0 else (1, -1)):
            frm, to = list(shape["from"]), list(shape["to"])
            frm[axis] += gap * i * side
            to[axis] += gap * i * side
            faces = {f: {"uv": [0, 0, 16, 16], "texture": "#" + str(i)} for f in shape["faces"]}
            if i == 0:
                for face in faces.values():
                    face["tintindex"] = 0
            elements.append({"from": frm, "to": to, "shade": False, "light_emission": 15, "faces": faces})
    textures = {str(i): "luminara:item/vfx/" + layer for i, layer in enumerate(layers)}
    textures["particle"] = textures["0"]
    with open(os.path.join(MODELS, name + ".json"), "w") as f:
        json.dump({"textures": textures, "elements": elements}, f, indent=2)
        f.write("\n")
    item = {"type": "minecraft:model", "model": "luminara:vfx/" + name,
            "tints": [{"type": "minecraft:custom_model_data", "index": 0, "default": 0xFFFFFF}]}
    with open(os.path.join(ITEMS, name + ".json"), "w") as f:
        json.dump({"model": item}, f, indent=2)
        f.write("\n")


FLAT = {"from": [0, 8, 0], "to": [16, 8, 16], "faces": ("up", "down")}
CARD = {"from": [0, 0, 8], "to": [16, 16, 8], "faces": ("north", "south")}
CROSS = [{"from": [0, 0, 8], "to": [16, 16, 8], "faces": ("north", "south")},
         {"from": [8, 0, 0], "to": [8, 16, 16], "faces": ("east", "west")}]


def model(name, shape, tint=False):
    """tint: faces take tintindex 0 and the item definition reads the colour from custom_model_data."""
    tex = "luminara:item/vfx/" + name
    shapes = shape if isinstance(shape, list) else [shape]
    elements = []
    for sh in shapes:
        faces = {f: {"uv": [0, 0, 16, 16], "texture": "#0"} for f in sh["faces"]}
        if tint:
            for face in faces.values():
                face["tintindex"] = 0
        elements.append({"from": sh["from"], "to": sh["to"], "shade": False, "light_emission": 15, "faces": faces})
    data = {"textures": {"0": tex, "particle": tex}, "elements": elements}
    with open(os.path.join(MODELS, name + ".json"), "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    with open(os.path.join(ITEMS, name + ".json"), "w") as f:
        item = {"type": "minecraft:model", "model": "luminara:vfx/" + name}
        if tint:
            item["tints"] = [{"type": "minecraft:custom_model_data", "index": 0, "default": 0xC88CFF}]
        json.dump({"model": item}, f, indent=2)
        f.write("\n")


def main():
    for d in (TEX, MODELS, ITEMS):
        os.makedirs(d, exist_ok=True)
    slash("slash_red", [(0, (90, 0, 10)), (0.55, (230, 40, 40)), (1, (255, 215, 200))], (120, 10, 20))
    slash("slash_light", [(0, (200, 160, 60)), (0.55, (255, 235, 170)), (1, (255, 255, 255))], (255, 230, 160))
    slash("slash_dark", [(0, (20, 0, 40)), (0.6, (90, 30, 170)), (1, (200, 150, 255))], (40, 0, 70))
    flash("flash")
    ring("shockwave_gold", [(0, (255, 150, 30)), (0.6, (255, 215, 100)), (1, (255, 255, 230))])
    ring("shockwave_magma", [(0, (120, 20, 0)), (0.5, (255, 90, 10)), (1, (255, 220, 120))], cracks=True)
    rune_circle("rune_gold")
    eclipse("eclipse")
    for name in ("slash_red", "slash_light", "slash_dark", "shockwave_gold", "shockwave_magma", "rune_gold"):
        model(name, FLAT)
    for name in ("flash", "eclipse"):
        model(name, CARD)
    ritual_outer("ritual_circle_outer")
    ritual_inner("ritual_circle_inner")
    ritual_pulse("ritual_pulse")
    ritual_beam("ritual_beam", 32, fade=False)
    ritual_beam("ritual_sky", 128, fade=True)
    for name in ("ritual_circle_outer", "ritual_circle_inner", "ritual_pulse"):
        model(name, FLAT, tint=True)
    for name in ("ritual_beam", "ritual_sky"):
        model(name, CROSS, tint=True)
    slash_layers("slash_tint")
    flash_layers("flash_tint")
    layered_model("slash_tint", FLAT, ["slash_tint_body", "slash_tint_edge"], 0.05)
    layered_model("flash_tint", CARD, ["flash_tint_body", "flash_tint_core"], 0.05)


if __name__ == "__main__":
    main()
