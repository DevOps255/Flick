#!/usr/bin/env python3
"""Checks that the AGSL port of the NameDrop wave computes the same thing as the original.

The original (SpriteKit GLSL) counts y upward from the bottom of the image. The
port (AGSL) counts y downward from the top. This script evaluates both on random
pixels and times and compares which pixel each one samples and how much it
brightens it. Run it after changing the shader math.
"""
import math
import random

SCALE, SHARPNESS, SPREAD = 10.0, 0.8, 0.1


def original(u_time, cx, cy):
    """NameDrop ContentView.swift. Returns (sample_x, sample_y, gain) in texture coordinates, y up."""
    offset = (u_time - math.floor(u_time)) / u_time
    current = u_time * offset * 0.5
    center = (0.5, 1.05)
    dist = math.hypot(cx - center[0], cy - center[1])
    if current - SPREAD <= dist <= current + SPREAD:
        diff = dist - current
        scale_diff = 1.0 - abs(diff * SCALE) ** SHARPNESS
        diff_time = diff * scale_diff
        length = math.hypot(cx - center[0], cy - center[1])
        direction = ((cx - center[0]) / length, (cy - center[1]) / length)
        sx = cx + direction[0] * diff_time / (current * dist * 10)
        sy = cy + direction[1] * diff_time / (current * dist * 10)
        return sx, sy, scale_diff / (current * dist * 40.0)
    return cx, cy, 0.0


def port(time, px, py, w, h):
    """The AGSL shader. Pixel coordinates, y down. Returns (sample_px, sample_py, gain)."""
    current = (time - math.floor(time)) * 0.5
    center = (0.5, -0.05)
    cx, cy = px / w, py / h
    dist = math.hypot(cx - center[0], cy - center[1])
    if current > 0.0001 and current - SPREAD <= dist <= current + SPREAD:
        diff = dist - current
        scale_diff = 1.0 - abs(diff * SCALE) ** SHARPNESS
        diff_time = diff * scale_diff
        length = math.hypot(cx - center[0], cy - center[1])
        direction = ((cx - center[0]) / length, (cy - center[1]) / length)
        cx += direction[0] * diff_time / (current * dist * 10.0)
        cy += direction[1] * diff_time / (current * dist * 10.0)
        return cx * w, cy * h, scale_diff / (current * dist * 40.0)
    return px, py, 0.0


random.seed(1)
w, h = 1080.0, 2400.0
affected = 0
for _ in range(200000):
    t = random.uniform(0.01, 3.0)
    px, py = random.uniform(0, w), random.uniform(0, h)
    ox, oy, og = original(t, px / w, 1.0 - py / h)  # same pixel, y flipped
    ax, ay, ag = port(t, px, py, w, h)
    assert abs(ox * w - ax) < 1e-6 and abs((1.0 - oy) * h - ay) < 1e-6 and abs(og - ag) < 1e-9, (t, px, py)
    affected += og != 0.0
print(f"identical on 200000 samples, {affected} of them inside the wave band")
