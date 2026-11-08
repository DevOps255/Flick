#!/usr/bin/env python3
"""Checks the AGSL port of the AirDrop shader (https://www.shadertoy.com/view/dlXczX).

Three checks, run after any change to the shader math:
1. With the screen treated as square, the port gives the same colours as the original GLSL.
   The original counts y upward and the port downward, and the blur is replaced by a stub that is
   identical on both sides, so only the logic around the blur is compared.
2. With the real aspect ratio, the wave and the glow are round on screen. The original stretches them.
3. The tap blur of the port is close to the blur of the original on a hard edge.
"""
import math
import random

PI = 3.14159265359


def clamp(x, a, b):
    return max(a, min(b, x))


def smoothstep(e0, e1, x):
    t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def mix(a, b, t):
    return a + (b - a) * t


def mixv(a, b, t):
    return [mix(x, y, t) for x, y in zip(a, b)]


def map_range(v, a, b, c, d):
    return c + (v - a) * (d - c) / (b - a)


def ease_in_cubic(x):
    return 1.0 - math.cos((x * PI) / 2.0)


def image(u, v):
    """A smooth test picture in original coordinates (y up)."""
    return [0.5 + 0.5 * math.sin(3 * u + v), 0.5 + 0.5 * math.cos(2 * v), u * v, 1.0]


def blur_stub(u, v):
    return [0.9 * c for c in image(u, v)]


# ---------------- original, GLSL semantics: y up, uv in 0..1 ----------------
def original(u, v, t):
    uv = [u, v]
    range_ = (1.0 + math.sin(t)) * 0.5
    g = []
    for lo, hi, col in ((-.2, 1.6, (.8549, .9922, 1.)), (-.3, 1.5, (1., .9882, .8549)), (-.4, 1.4, (1., .8902, .8784))):
        m = map_range(range_, 0., 1., lo, hi)
        p = smoothstep(0., .25, math.hypot(uv[0] - .5, uv[1] - m))
        g.append((p, mixv(list(col), [1., 1., 1.], p)))
    pct = g[0][0]
    blurpct = smoothstep(0., 1., uv[1]) * range_
    blurred = blur_stub(uv[0], uv[1])
    uv[1] -= ease_in_cubic(uv[1]) * .1 * range_
    range2 = 0. if math.sin(t) < 0. else math.sin(t)
    st2 = [uv[0], uv[1]]
    r = 5. * range2
    dx, dy = st2[0] - .5, st2[1] - 1.
    d = (dx * dx + dy * dy) * 4.0
    circle = smoothstep(r - r * .5, r, d) * (1. - smoothstep(r, r + r * .5, d)) if r > 1e-4 else 0.
    wpct = 1. - circle
    cc = [c ** 8 for c in mixv([1.069, 1.077, 1.100], [1., 1., 1.], wpct)]
    wstretch = st2[1] * .3 * range2 * (wpct - st2[1])
    st2[1] += wstretch
    wcolor = image(st2[0], st2[1])
    wcolor = [wcolor[0] * cc[0], wcolor[1] * cc[1], wcolor[2] * cc[2], wcolor[3]]
    uv[1] += wstretch
    color = image(uv[0], uv[1])
    color = mixv(color, wcolor, pct)
    color = mixv(color, blurred, blurpct)
    for _, gc in g:
        color = [color[0] * gc[0], color[1] * gc[1], color[2] * gc[2], color[3]]
    return color


# ---------------- port, AGSL semantics: pixels, y down ----------------
def port(px, py, w, h, t, aspect_correct):
    a = (w / h) if aspect_correct else 1.0
    u, v = px / w, 1.0 - py / h  # same pixel in original coordinates

    def sample(u_, v_):
        return image(u_, v_)

    def glow(m):
        return smoothstep(0., .25, math.hypot((u - .5) * a, v - m))

    range_ = (1.0 + math.sin(t)) * 0.5
    pcts, cols = [], []
    for lo, hi, col in ((-.2, 1.6, (.8549, .9922, 1.)), (-.3, 1.5, (1., .9882, .8549)), (-.4, 1.4, (1., .8902, .8784))):
        p = glow(map_range(range_, 0., 1., lo, hi))
        pcts.append(p)
        cols.append(mixv(list(col), [1., 1., 1.], p))
    pct = pcts[0]
    blurpct = smoothstep(0., 1., v) * range_
    blurred = blur_stub(u, v)
    vv = v - ease_in_cubic(v) * .1 * range_
    range2 = 0. if math.sin(t) < 0. else math.sin(t)
    r = 5. * range2
    dx, dy = (u - .5) * a, vv - 1.
    d = (dx * dx + dy * dy) * 4.0
    circle = smoothstep(r - r * .5, r, d) * (1. - smoothstep(r, r + r * .5, d)) if r > 1e-4 else 0.
    wpct = 1. - circle
    cc = [c ** 8 for c in mixv([1.069, 1.077, 1.100], [1., 1., 1.], wpct)]
    wstretch = vv * .3 * range2 * (wpct - vv)
    wcolor = sample(u, vv + wstretch)
    wcolor = [wcolor[0] * cc[0], wcolor[1] * cc[1], wcolor[2] * cc[2], wcolor[3]]
    # The original says "remove the wstretch" but adds it back (uv.y += wStretch), so both samples share one position.
    color = sample(u, vv + wstretch)
    color = mixv(color, wcolor, pct)
    color = mixv(color, blurred, blurpct)
    for gc in cols:
        color = [color[0] * gc[0], color[1] * gc[1], color[2] * gc[2], color[3]]
    return color


random.seed(7)
worst = 0.0
for _ in range(100000):
    t = random.uniform(-PI / 2, 3 * PI / 2)
    px, py = random.uniform(0, 1000), random.uniform(0, 1000)
    o = original(px / 1000, 1.0 - py / 1000, t)
    p = port(px, py, 1000.0, 1000.0, t, aspect_correct=False)
    worst = max(worst, max(abs(x - y) for x, y in zip(o, p)))
assert worst < 1e-9, worst
print(f"1. same colours as the original on 100000 samples, largest difference {worst:.1e}")

# 2. round on a tall phone. The wave mask and the glow depend only on the distance to a centre, so two
#    points the same number of pixels from the centre must give the same value.
def wave_mask(u, v, radius, aspect):
    dx, dy = (u - .5) * aspect, v - 1.
    d = (dx * dx + dy * dy) * 4.0
    return smoothstep(radius - radius * .5, radius, d) * (1. - smoothstep(radius, radius + radius * .5, d))


def glow_value(u, v, centre_v, aspect):
    return smoothstep(0., .25, math.hypot((u - .5) * aspect, v - centre_v))


W, H = 1080.0, 2400.0
worst_fixed = worst_original = 0.0
for dist in (150.0, 300.0, 450.0, 520.0):
    for radius in (0.15, 0.3, 0.5):
        for aspect, store in ((W / H, "fixed"), (1.0, "original")):
            # the wave centre is the middle of the top edge, so go right and go down by the same pixels
            right = wave_mask(.5 + dist / W, 1.0, radius, aspect)
            below = wave_mask(.5, 1.0 - dist / H, radius, aspect)
            diff = abs(right - below)
            if store == "fixed":
                worst_fixed = max(worst_fixed, diff)
            else:
                worst_original = max(worst_original, diff)
    for aspect, store in ((W / H, "fixed"), (1.0, "original")):
        centre_v = 0.6
        right = glow_value(.5 + dist / W, centre_v, centre_v, aspect)
        below = glow_value(.5, centre_v - dist / H, centre_v, aspect)
        diff = abs(right - below)
        if store == "fixed":
            worst_fixed = max(worst_fixed, diff)
        else:
            worst_original = max(worst_original, diff)
assert worst_fixed < 1e-9, worst_fixed
assert worst_original > 0.2, worst_original
print(f"2. same pixel distance gives the same wave and glow: fixed {worst_fixed:.1e}, original differs by up to {worst_original:.2f}")

# 3. tap blur against the original blur on a hard edge
def edge(x, y):
    return 1.0 if x > 50 else 0.0


def original_blur(x, y):
    sigma = 35 * .25
    total = wsum = 0.0
    for i in range(64):
        s = 35 // 4
        d = ((i % s) * 4 - 17.5, (i // s) * 4 - 17.5)
        if i >= s * s:
            break
        w = math.exp(-.5 * (d[0] ** 2 + d[1] ** 2) / sigma ** 2)
        # a mip level 2 sample is the average of a 4 by 4 block
        v = sum(edge(x + d[0] + dx - 1.5, y + d[1]) for dx in range(4)) / 4
        total += w * v
        wsum += w
    return total / wsum


def tap_blur(x, y):
    total = wsum = 0.0
    for j in range(-3, 4):
        for i in range(-3, 4):
            d = (i * 4.0, j * 4.0)
            w = math.exp(-.5 * (d[0] ** 2 + d[1] ** 2) / 81.0)
            total += w * edge(x + d[0], y + d[1])
            wsum += w
    return total / wsum


rms = math.sqrt(sum((original_blur(x, 0) - tap_blur(x, 0)) ** 2 for x in range(20, 80)) / 60)
print(f"3. blur of a hard edge, root mean square difference to the original {rms:.3f}")
