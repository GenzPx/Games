#!/usr/bin/env python3
"""Generate mountain heightmap, route, camps, and shared constants.

Single source of numeric truth for the C engine and the web client.
"""
from __future__ import annotations

import json
import math
import os
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "web"
ASSETS = WEB / "assets"
DATA = ASSETS / "data"
TEX = ASSETS / "textures"
ENGINE_INC = ROOT / "engine" / "include"

SIZE = 256
WORLD_M = 2400.0  # horizontal extent
BASE_ALT = 4280.0
SUMMIT_ALT = 8849.0


def clamp(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


def smoothstep(e0, e1, x):
    t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def hash2(ix, iy):
    n = (ix * 374761393 + iy * 668265263) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return (n & 0xFFFFFF) / 16777215.0


def noise2(x, y):
    x0, y0 = math.floor(x), math.floor(y)
    fx, fy = x - x0, y - y0
    u = fx * fx * (3 - 2 * fx)
    v = fy * fy * (3 - 2 * fy)
    a = hash2(x0, y0)
    b = hash2(x0 + 1, y0)
    c = hash2(x0, y0 + 1)
    d = hash2(x0 + 1, y0 + 1)
    return (a + (b - a) * u) + ((c + (d - c) * u) - (a + (b - a) * u)) * v


def fbm(x, y, octaves=6):
    amp, freq, total, norm = 1.0, 1.0, 0.0, 0.0
    for _ in range(octaves):
        total += noise2(x * freq, y * freq) * amp
        norm += amp
        amp *= 0.5
        freq *= 2.05
    return total / norm


def height_norm(u, v):
    """u,v in 0..1. Peak slightly north of center, ridge from south."""
    dx = (u - 0.50) * 2.0
    dy = (v - 0.62) * 2.0
    r = math.hypot(dx, dy * 0.92)

    # main pyramid
    peak = math.exp(-((dx * 1.55) ** 2 + (dy * 1.15) ** 2) * 2.15)
    # long south ridge (the climbing route)
    ridge_x = dx + 0.08 * math.sin(v * 9.0)
    ridge = math.exp(-(ridge_x ** 2) * 18.0) * smoothstep(0.05, 0.62, v) * (1.0 - smoothstep(0.78, 1.0, v))
    # west shoulder / satellite
    sat = math.exp(-((u - 0.28) ** 2 * 55 + (v - 0.48) ** 2 * 40)) * 0.38
    # north drop (steep north face)
    north = 1.0 - 0.35 * smoothstep(0.70, 0.95, v)

    n = fbm(u * 6.5, v * 6.5, 6)
    n2 = fbm(u * 18.0 + 20, v * 18.0, 4)
    detail = (n - 0.5) * 0.16 + (n2 - 0.5) * 0.05

    # icefall terraces on lower ridge
    terrace = 0.0
    if 0.18 < v < 0.42:
        terrace = 0.035 * math.sin(v * 70.0) * math.exp(-(ridge_x ** 2) * 10)

    h = (peak * 0.78 + ridge * 0.55 + sat) * north + detail + terrace
    # flatten valleys
    h = max(h, 0.02 + 0.04 * n)
    # summit spike
    h += 0.08 * math.exp(-r * r * 90.0)
    return clamp(h, 0.0, 1.0)


def build_heightmap():
    hm = [[0.0] * SIZE for _ in range(SIZE)]
    for j in range(SIZE):
        v = j / (SIZE - 1)
        for i in range(SIZE):
            u = i / (SIZE - 1)
            hm[j][i] = height_norm(u, v)
    return hm


def alt_from_h(h):
    return BASE_ALT + h * (SUMMIT_ALT - BASE_ALT)


def write_png_gray16(path: Path, grid):
    """16-bit grayscale PNG, no extra deps."""
    h, w = len(grid), len(grid[0])
    raw = bytearray()
    for row in grid:
        raw.append(0)
        for val in row:
            iv = int(clamp(val, 0, 1) * 65535.0 + 0.5)
            raw += struct.pack(">H", iv)

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        out += struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        return out

    ihdr = struct.pack(">IIBBBBB", w, h, 16, 0, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", ihdr)
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    path.write_bytes(png)


def write_png_rgb8(path: Path, pixels, w, h):
    raw = bytearray()
    i = 0
    for _y in range(h):
        raw.append(0)
        for _x in range(w):
            raw += bytes(pixels[i : i + 3])
            i += 3
    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        out += struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        return out
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    path.write_bytes(png)


def sample(hm, u, v):
    x = clamp(u, 0, 1) * (SIZE - 1)
    y = clamp(v, 0, 1) * (SIZE - 1)
    x0, y0 = int(x), int(y)
    x1, y1 = min(x0 + 1, SIZE - 1), min(y0 + 1, SIZE - 1)
    tx, ty = x - x0, y - y0
    a = hm[y0][x0] + (hm[y0][x1] - hm[y0][x0]) * tx
    b = hm[y1][x0] + (hm[y1][x1] - hm[y1][x0]) * tx
    return a + (b - a) * ty


def uv_to_world(u, v):
    return {
        "x": (u - 0.5) * WORLD_M,
        "z": (v - 0.5) * WORLD_M,
    }


def build_route(hm):
    """South-to-north ridge waypoints with real altitudes."""
    pts = []
    # v from 0.16 (approach) to 0.62 (summit)
    keys = [
        (0.505, 0.168, "approach", "Lembah Pendekatan"),
        (0.502, 0.205, "bc", "Base Camp"),
        (0.498, 0.248, "icefall", "Icefall"),
        (0.500, 0.292, "c1", "Camp I"),
        (0.496, 0.348, "western", "Western Cwm"),
        (0.494, 0.402, "c2", "Camp II"),
        (0.490, 0.458, "lhotse", "Lhotse Face"),
        (0.492, 0.508, "c3", "Camp III"),
        (0.496, 0.548, "yellow", "Yellow Band"),
        (0.500, 0.575, "c4", "Camp IV / South Col"),
        (0.502, 0.598, "balcony", "The Balcony"),
        (0.500, 0.612, "hillary", "Hillary Step"),
        (0.500, 0.622, "summit", "Summit"),
    ]
    for u, v, key, name in keys:
        h = sample(hm, u, v)
        w = uv_to_world(u, v)
        pts.append({
            "id": key,
            "name": name,
            "u": u,
            "v": v,
            "x": round(w["x"], 2),
            "z": round(w["z"], 2),
            "alt": round(alt_from_h(h), 1),
            "h": h,
        })
    return pts


def build_camps(route):
    camp_ids = {"bc": 0, "c1": 1, "c2": 2, "c3": 3, "c4": 4}
    camps = []
    for p in route:
        if p["id"] in camp_ids:
            camps.append({
                **p,
                "index": camp_ids[p["id"]],
                "shelter": True,
                "o2_cache": 2 if p["id"] in ("c3", "c4") else (1 if p["id"] == "c2" else 0),
                "food": 3 if p["id"] == "bc" else 1,
            })
    return camps


CONSTANTS = {
    "SEA_LEVEL_SPO2": 98.0,
    "DEATH_ZONE_M": 8000.0,
    "HAPE_SPO2_TRIGGER": 62.0,
    "HACE_CLARITY_TRIGGER": 0.35,
    "HYPOTHERMIA_C": 35.0,
    "SEVERE_HYPO_C": 32.0,
    "COLLAPSE_SPO2": 46.0,
    "DEATH_ZONE_HOURS": 18.0,
    "BASE_CORE_C": 36.8,
    "VO2_SEA": 52.0,
    "O2_BOTTLE_MIN": 6.0,
    "O2_FLOW_LPM": 2.5,
}


def write_c_header(path: Path):
    lines = [
        "/* AUTO-GENERATED by sim/generate_world.py — do not edit */",
        "#pragma once",
        f"#define TA_WORLD_M {WORLD_M:.1f}",
        f"#define TA_BASE_ALT {BASE_ALT:.1f}",
        f"#define TA_SUMMIT_ALT {SUMMIT_ALT:.1f}",
        f"#define TA_HM_SIZE {SIZE}",
    ]
    for k, v in CONSTANTS.items():
        lines.append(f"#define TA_{k} {v}")
    path.write_text("\n".join(lines) + "\n")


def write_js_gen(path: Path, route, camps):
    payload = {
        "worldM": WORLD_M,
        "baseAlt": BASE_ALT,
        "summitAlt": SUMMIT_ALT,
        "hmSize": SIZE,
        "constants": CONSTANTS,
        "route": route,
        "camps": camps,
    }
    path.write_text("export const WORLD = " + json.dumps(payload, indent=2) + ";\n")


def main():
    DATA.mkdir(parents=True, exist_ok=True)
    TEX.mkdir(parents=True, exist_ok=True)
    ENGINE_INC.mkdir(parents=True, exist_ok=True)

    print("building heightmap…")
    hm = build_heightmap()
    write_png_gray16(TEX / "heightmap.png", hm)

    # preview color relief
    prev = []
    for j in range(SIZE):
        for i in range(SIZE):
            h = hm[j][i]
            alt = alt_from_h(h)
            if alt < 5000:
                r, g, b = 90, 92, 78
            elif alt < 6200:
                t = (alt - 5000) / 1200
                r = int(90 + t * 80)
                g = int(92 + t * 70)
                b = int(78 + t * 60)
            elif alt < 8000:
                t = (alt - 6200) / 1800
                r = int(170 + t * 55)
                g = int(162 + t * 60)
                b = int(138 + t * 80)
            else:
                r, g, b = 236, 240, 246
            # ridge highlight
            if i > 0 and i < SIZE - 1:
                slope = abs(hm[j][min(i + 2, SIZE - 1)] - hm[j][max(i - 2, 0)])
                if slope > 0.035:
                    r, g, b = int(r * 0.7), int(g * 0.7), int(b * 0.75)
            prev.extend([r, g, b])
    write_png_rgb8(TEX / "relief.png", prev, SIZE, SIZE)

    route = build_route(hm)
    camps = build_camps(route)

    world = {
        "name": "Chomo Hoshi",
        "localName": "The Morning Star",
        "elevation": SUMMIT_ALT,
        "worldM": WORLD_M,
        "baseAlt": BASE_ALT,
        "hmSize": SIZE,
        "constants": CONSTANTS,
        "route": route,
        "camps": camps,
    }
    (DATA / "world.json").write_text(json.dumps(world, indent=2))
    write_c_header(ENGINE_INC / "ta_generated.h")
    write_js_gen(WEB / "js" / "generated.js", route, camps)

    print("summit alt sample:", route[-1]["alt"])
    print("base camp alt:", next(p["alt"] for p in route if p["id"] == "bc"))
    print("wrote world, header, js, heightmap")


if __name__ == "__main__":
    main()
