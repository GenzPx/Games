#!/usr/bin/env python3
"""Procedural CC0 audio: wind, breath, heart, crunch, UI, radio static."""
from __future__ import annotations

import math
import random
import struct
import wave
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "web" / "assets" / "audio"
SR = 44100


def write_wav(path: Path, samples, sr=SR):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        frames = bytearray()
        for s in samples:
            v = max(-1.0, min(1.0, s))
            frames += struct.pack("<h", int(v * 32767))
        w.writeframes(frames)


def one_pole(x, state, coef):
    y = state + coef * (x - state)
    return y, y


def wind(seconds=12.0, seed=3):
    rng = random.Random(seed)
    n = int(seconds * SR)
    brown = 0.0
    lp = 0.0
    gust = 0.0
    out = []
    for i in range(n):
        t = i / SR
        white = rng.uniform(-1, 1)
        brown = (brown + 0.02 * white) * 0.985
        lp, _ = (lp + 0.012 * (brown - lp), None)
        gust = gust * 0.9995 + rng.uniform(-1, 1) * 0.004
        howl = math.sin(t * 0.7 + math.sin(t * 0.13) * 2.0) * 0.35
        env = 0.55 + 0.45 * (0.5 + 0.5 * math.sin(t * 0.35 + 0.4))
        fade = min(1.0, t / 0.4, (seconds - t) / 0.4)
        out.append((lp * 1.8 + brown * 0.25 + howl * 0.08 + gust) * env * fade * 0.55)
    return out


def breath_cycle(rate_hz=0.28, effort=0.45, seconds=8.0, seed=11):
    """Inhale/exhale loop. effort 0..1 = how ragged."""
    rng = random.Random(seed)
    n = int(seconds * SR)
    lp = 0.0
    out = []
    period = 1.0 / rate_hz
    for i in range(n):
        t = i / SR
        ph = (t % period) / period
        # inhale 0-0.42, exhale 0.42-1
        if ph < 0.42:
            local = ph / 0.42
            env = math.sin(local * math.pi) ** 1.2
            band = 0.55
        else:
            local = (ph - 0.42) / 0.58
            env = math.sin(local * math.pi) ** 1.05 * 0.85
            band = 0.32
        white = rng.uniform(-1, 1)
        lp = lp + (0.08 + band * 0.12) * (white - lp)
        rasp = abs(white) * effort * 0.25
        fade = min(1.0, t / 0.2, (seconds - t) / 0.2)
        out.append((lp * 1.6 + rasp) * env * fade * (0.28 + effort * 0.45))
    return out


def heartbeat(bpm=84, seconds=4.0):
    n = int(seconds * SR)
    out = [0.0] * n
    interval = 60.0 / bpm
    t = 0.08
    while t < seconds:
        for name, delay, width, amp in (("lub", 0.0, 0.028, 0.9), ("dub", 0.18, 0.022, 0.55)):
            c = t + delay
            for i in range(n):
                dt = i / SR - c
                if 0 <= dt < width * 4:
                    out[i] += amp * math.exp(-dt / width) * math.sin(2 * math.pi * 48 * dt)
        t += interval
    peak = max(1e-6, max(abs(x) for x in out))
    return [x / peak * 0.7 for x in out]


def crunch(seed=21):
    rng = random.Random(seed)
    n = int(0.28 * SR)
    out = []
    for i in range(n):
        t = i / SR
        env = math.exp(-t * 18) * (1 if t < 0.02 else math.exp(-(t - 0.02) * 10))
        grains = 0.0
        for k in range(5):
            grains += rng.uniform(-1, 1) * math.sin(2 * math.pi * (700 + k * 180) * t)
        out.append((rng.uniform(-1, 1) * 0.6 + grains * 0.15) * env * 0.7)
    return out


def ice_axe(seed=8):
    rng = random.Random(seed)
    n = int(0.35 * SR)
    out = []
    for i in range(n):
        t = i / SR
        click = math.exp(-t * 70) * math.sin(2 * math.pi * 920 * t)
        body = math.exp(-t * 14) * rng.uniform(-1, 1) * 0.5
        out.append((click * 0.7 + body) * 0.8)
    return out


def ui_blip(freq=640, ms=90):
    n = int(ms / 1000 * SR)
    out = []
    for i in range(n):
        t = i / SR
        env = math.sin(math.pi * i / n) ** 1.4
        out.append(math.sin(2 * math.pi * freq * t) * env * 0.28)
    return out


def radio_static(seconds=2.5, seed=4):
    rng = random.Random(seed)
    n = int(seconds * SR)
    out = []
    for i in range(n):
        t = i / SR
        hiss = rng.uniform(-1, 1) * 0.22
        crack = 1.0 if rng.random() < 0.004 else 0.0
        tone = math.sin(2 * math.pi * 780 * t) * 0.04
        fade = min(1.0, t / 0.1, (seconds - t) / 0.15)
        out.append((hiss + crack * rng.uniform(-0.6, 0.6) + tone) * fade)
    return out


def storm_layer(seconds=10.0, seed=99):
    rng = random.Random(seed)
    n = int(seconds * SR)
    lp = 0.0
    out = []
    for i in range(n):
        t = i / SR
        white = rng.uniform(-1, 1)
        lp = lp + 0.04 * (white - lp)
        roar = math.sin(t * 0.9) * 0.2
        fade = min(1.0, t / 0.5, (seconds - t) / 0.5)
        out.append((lp * 1.4 + roar) * fade * 0.7)
    return out


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    write_wav(OUT / "wind.wav", wind())
    write_wav(OUT / "storm.wav", storm_layer())
    write_wav(OUT / "breath_calm.wav", breath_cycle(0.22, 0.25, 9.0, 1))
    write_wav(OUT / "breath_work.wav", breath_cycle(0.38, 0.55, 7.0, 2))
    write_wav(OUT / "breath_death.wav", breath_cycle(0.55, 0.95, 6.0, 3))
    write_wav(OUT / "heart.wav", heartbeat(88, 5.0))
    write_wav(OUT / "crunch.wav", crunch())
    write_wav(OUT / "axe.wav", ice_axe())
    write_wav(OUT / "ui_ok.wav", ui_blip(720, 80))
    write_wav(OUT / "ui_warn.wav", ui_blip(320, 160))
    write_wav(OUT / "radio.wav", radio_static())
    print("audio written to", OUT)


if __name__ == "__main__":
    main()
