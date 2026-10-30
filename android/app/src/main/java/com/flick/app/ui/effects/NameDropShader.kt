package com.flick.app.ui.effects

/**
 * The water wave of NameDrop, ported from SpriteKit GLSL to AGSL.
 *
 * Original: https://github.com/cyrilzakka/NameDrop (MIT, Copyright (c) 2023 Cyril Zakka),
 * itself converted from https://www.shadertoy.com/view/llj3Dz. The license is kept in
 * `third_party/namedrop/LICENSE`.
 *
 * The maths is unchanged. Two things differ because of the platform:
 * - SpriteKit counts y upward from the bottom, AGSL downward from the top, so the wave
 *   centre (0.5, 1.05) becomes (0.5, -0.05). It is still just above the top edge.
 * - AGSL samples the screen content in pixels, so coordinates are scaled by `size`.
 *
 * `scripts/check_namedrop_port.py` compares both versions on 200000 random pixels.
 */
internal const val NAME_DROP_AGSL = """
uniform shader image;
uniform float2 size;
uniform float time;
uniform float waveScale;
uniform float sharpness;
uniform float spread;

half4 main(float2 fragCoord) {
    // The wave front travels from 0 to 0.5 once per second, then starts again.
    float currentTime = fract(time) * 0.5;
    float2 waveCenter = float2(0.5, -0.05);

    float2 coord = fragCoord / size;
    float dist = distance(coord, waveCenter);
    half4 color = image.eval(fragCoord);

    // Only distort the pixels within `spread` of the wave front.
    if (currentTime > 0.0001 && dist <= currentTime + spread && dist >= currentTime - spread) {
        float diff = dist - currentTime;
        float scaleDiff = 1.0 - pow(abs(diff * waveScale), sharpness);
        float diffTime = diff * scaleDiff;
        float2 direction = normalize(coord - waveCenter);
        coord += (direction * diffTime) / (currentTime * dist * 10.0);
        color = image.eval(coord * size);
        color += (color * scaleDiff) / (currentTime * dist * 40.0);
    }
    return color;
}
"""
