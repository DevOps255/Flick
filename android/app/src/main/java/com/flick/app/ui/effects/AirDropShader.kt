package com.flick.app.ui.effects

/**
 * The AirDrop effect, written for Flick in AGSL after the iPhone effect. It has four moves:
 *
 * 1. A coloured glow rises from the bottom of the screen. The picture blurs and bends toward it as it rises.
 * 2. The glow gathers at the notch and keeps getting brighter and blurrier.
 * 3. A wave strikes down from the notch. Where it passes the picture is bent by the wave, so the
 *    distortion travels with the front. A thin bright band marks the front.
 * 4. Behind the front the screen is clean again. The effect is wiped away by the wave itself, so it
 *    needs no fade at the end, and nothing ever moves back up.
 *
 * It replaces the earlier port of a Shadertoy shader, which blurred and stretched the whole screen at
 * once and then undid it, and so looked like a stretched, fading copy instead of a wave.
 *
 * The four numbers come from `airDropFrame` in `AirDropEffect.kt`, which sets the timing:
 * [rise] moves the glow up, [energy] makes it brighter and blurrier, [accumulate] lights the notch, and
 * [wave] is how far the front has gone, from 0 at the notch to 1 when it has left the screen.
 * y points up inside the shader's maths and x is scaled by the aspect ratio, so shapes are round.
 *
 * The preview that tuned the numbers is `scripts/preview_airdrop.py`.
 */
internal const val AIRDROP_AGSL = """
uniform shader image;
uniform float2 size;
uniform float rise;
uniform float energy;
uniform float accumulate;
uniform float wave;

float sq(float x) {
    return x * x;
}

// Reads the screen at a point given as 0 to 1, y upward. Outside the screen the nearest edge is read.
float4 sampleUv(float2 uv) {
    float2 px = float2(clamp(uv.x, 0.0, 1.0) * size.x, (1.0 - uv.y) * size.y);
    return image.eval(clamp(px, float2(0.0), size - 1.0));
}

// A 7 by 7 gaussian blur, about 20 pixels wide on a 1080 pixel screen.
float4 blurred(float2 px) {
    float scale = size.x / 1080.0;
    float4 sum = float4(0.0);
    float weights = 0.0;
    for (int j = -3; j <= 3; j++) {
        for (int i = -3; i <= 3; i++) {
            float2 d = float2(float(i), float(j)) * 10.0 * scale;
            float w = exp(-0.5 * dot(d, d) / (400.0 * scale * scale));
            sum += w * image.eval(clamp(px + d, float2(0.0), size - 1.0));
            weights += w;
        }
    }
    return sum / weights;
}

// The blur is read where the picture is read, after it has been bent. Reading it anywhere else
// shows a second copy of the screen under the first.
float4 blurredAt(float2 uv) {
    return blurred(float2(clamp(uv.x, 0.0, 1.0) * size.x, (1.0 - uv.y) * size.y));
}

half4 main(float2 fragCoord) {
    float aspect = size.x / size.y;
    float2 st = float2(fragCoord.x / size.x, 1.0 - fragCoord.y / size.y);
    float px = (st.x - 0.5) * aspect;

    // The wave starts at the notch, at the top middle. d is the distance to it.
    float d = length(float2(px, st.y - 1.0));
    float farthest = sqrt(0.25 * aspect * aspect + 1.0);
    float front = wave * (farthest + 0.45);

    // 1 ahead of the front, where the effect is still on. 0 behind it, where the screen is clean.
    float ahead = smoothstep(front - 0.30, front, d);

    // The glow rises from below the screen to just under the notch.
    float glowY = -0.35 + 1.31 * rise;

    // Before the strike the picture bends toward the glow.
    float bend = exp(-sq((st.y - glowY) / 0.30));
    float v = st.y + (glowY - st.y) * 0.10 * bend * energy * ahead;
    float u = st.x;

    // At the strike the picture is pushed outward from the notch, in a band just behind the front.
    float ring = 0.0;
    if (wave > 0.0) {
        ring = exp(-sq((d - (front - 0.10)) / 0.11)) * (1.0 - wave * 0.3);
    }
    float2 outward = float2(px, st.y - 1.0) / max(d, 0.0001);
    u -= outward.x * 0.05 * ring / aspect;
    v -= outward.y * 0.05 * ring;

    float2 where = float2(u, v);
    float4 sharp = sampleUv(where);
    float4 soft = blurredAt(where);

    // Blur grows with the energy and is stronger toward the top.
    float blurAmount = energy * (0.35 + 0.65 * smoothstep(0.0, 1.0, st.y)) * ahead;
    float3 colour = mix(sharp.rgb, soft.rgb, blurAmount);

    // The glow goes from pink on the left to blue on the right.
    float side = smoothstep(-0.4, 0.4, px);
    float3 glowColour = float3(1.0 - 0.55 * side, 0.45 + 0.20 * side, 0.70 + 0.30 * side);
    float body = exp(-(sq(px / 0.62) + sq((st.y - glowY) / 0.38)));
    float notch = exp(-(sq(px / 0.40) + sq((st.y - 1.0) / 0.14))) * accumulate;
    float light = (body * energy * 0.9 + notch * 1.2) * ahead;
    colour = mix(colour, glowColour, clamp(light, 0.0, 1.0) * 0.85);

    // The bright band at the front of the wave.
    colour += float3(ring * 0.18);

    return half4(half3(clamp(colour, float3(0.0), float3(1.0))), sharp.a);
}
"""
