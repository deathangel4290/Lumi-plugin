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


def grid():
    c = (N - 1) / 2.0
    y, x = np.mgrid[0:N, 0:N].astype(np.float64)
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


FLAT = {"from": [0, 8, 0], "to": [16, 8, 16], "faces": ("up", "down")}
CARD = {"from": [0, 0, 8], "to": [16, 16, 8], "faces": ("north", "south")}


def model(name, shape):
    tex = "luminara:item/vfx/" + name
    faces = {f: {"uv": [0, 0, 16, 16], "texture": "#0"} for f in shape["faces"]}
    data = {
        "textures": {"0": tex, "particle": tex},
        "elements": [{"from": shape["from"], "to": shape["to"], "shade": False, "light_emission": 15, "faces": faces}],
    }
    with open(os.path.join(MODELS, name + ".json"), "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    with open(os.path.join(ITEMS, name + ".json"), "w") as f:
        json.dump({"model": {"type": "minecraft:model", "model": "luminara:vfx/" + name}}, f, indent=2)
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


if __name__ == "__main__":
    main()
