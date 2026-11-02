package com.flick.app.ui.effects

/**
 * The AirDrop effect, ported from GLSL to AGSL: the background blurs and stretches upward, a wave
 * crosses the screen, and three soft coloured glows pass by.
 *
 * Original: https://www.shadertoy.com/view/dlXczX (Shadertoy). Its author is named on that page and
 * its license is not stated in the code. Shadertoy's default is Creative Commons Attribution
 * NonCommercial ShareAlike 3.0, which does not allow commercial use. See `NOTICE.md`.
 *
 * Changes from the original, each checked by `scripts/check_airdrop_port.py`:
 * - Same maths otherwise: on 100000 sample points the colours are identical.
 * - The original counts y upward and Android downward, so y is flipped on entry and on every read.
 * - The original measures distances in screen fractions, so on a tall phone the wave and glows are
 *   stretched. Here x is scaled by the aspect ratio, so they are round on every screen.
 * - The original blurs with a 35 sample kernel on a mipmap, which AGSL cannot sample. A 7 by 7
 *   gaussian with 4 pixel spacing replaces it, scaled with the screen width. On a hard edge the two
 *   differ by 0.038 on a scale of 0 to 1.
 * - At radius 0 the original divides by zero in `smoothstep`, so the wave mask is 0 there.
 */
internal const val AIRDROP_AGSL = """
uniform shader image;
uniform float2 size;
uniform float time;

const float PI = 3.14159265359;

float mapRange(float value, float min1, float max1, float min2, float max2) {
    return min2 + (value - min1) * (max2 - min2) / (max1 - min1);
}

float easeInCubic(float x) {
    return 1.0 - cos((x * PI) / 2.0);
}

// 0 inside the ring and outside it, 1 on the ring. x is scaled by the aspect ratio so the ring is round.
float circleMask(float2 st, float radius, float aspect) {
    if (radius < 0.0001) {
        return 0.0;
    }
    float2 dist = float2((st.x - 0.5) * aspect, st.y - 1.0);
    float d = dot(dist, dist) * 4.0;
    float c1 = smoothstep(radius - radius * 0.5, radius, d);
    float c2 = 1.0 - smoothstep(radius, radius + radius * 0.5, d);
    return c1 * c2;
}

float glow(float2 uv, float centreY, float aspect) {
    return smoothstep(0.0, 0.25, distance(float2(uv.x * aspect, uv.y), float2(0.5 * aspect, centreY)));
}

// Reads the screen at a point given in the original's coordinates: 0 to 1, y upward.
float4 sampleUv(float2 uv) {
    float2 px = float2(uv.x * size.x, (1.0 - uv.y) * size.y);
    return image.eval(clamp(px, float2(0.0), size - 1.0));
}

float4 blurred(float2 px) {
    float scale = size.x / 1080.0;
    float4 sum = float4(0.0);
    float weights = 0.0;
    for (int j = -3; j <= 3; j++) {
        for (int i = -3; i <= 3; i++) {
            float2 d = float2(float(i), float(j)) * 4.0 * scale;
            float w = exp(-0.5 * dot(d, d) / (81.0 * scale * scale));
            sum += w * image.eval(clamp(px + d, float2(0.0), size - 1.0));
            weights += w;
        }
    }
    return sum / weights;
}

half4 main(float2 fragCoord) {
    float aspect = size.x / size.y;
    float2 uv = float2(fragCoord.x / size.x, 1.0 - fragCoord.y / size.y);
    float range = (1.0 + sin(time)) * 0.5;

    // glows
    float pct = glow(uv, mapRange(range, 0.0, 1.0, -0.2, 1.6), aspect);
    float3 gcircle = mix(float3(0.8549, 0.9922, 1.0), float3(1.0), pct);
    float pct2 = glow(uv, mapRange(range, 0.0, 1.0, -0.3, 1.5), aspect);
    float3 gcircle2 = mix(float3(1.0, 0.9882, 0.8549), float3(1.0), pct2);
    float pct3 = glow(uv, mapRange(range, 0.0, 1.0, -0.4, 1.4), aspect);
    float3 gcircle3 = mix(float3(1.0, 0.8902, 0.8784), float3(1.0), pct3);

    // blur, stronger toward the top and as the effect peaks
    float blurpct = smoothstep(0.0, 1.0, uv.y) * range;
    float4 blurredPixel = blurred(fragCoord);

    // stretch upward
    float yStretch = easeInCubic(uv.y) * 0.1 * range;
    uv.y -= yStretch;

    // wave
    float range2 = sin(time) < 0.0 ? 0.0 : sin(time);
    float2 st2 = uv;
    float r = 5.0 * range2;
    float wpct = 1.0 - circleMask(st2, r, aspect);
    float3 cc = mix(float3(1.069, 1.077, 1.100), float3(1.0), wpct);
    cc = pow(cc, float3(8.0));
    float wStretch = st2.y * 0.3 * range2 * (wpct - st2.y);
    st2.y += wStretch;
    float4 wcolor = sampleUv(st2);
    wcolor *= float4(cc, 1.0);

    // The original comments "remove the wstretch" but adds it back, so both reads share one position.
    uv.y += wStretch;
    float4 color = sampleUv(uv);

    color = mix(color, wcolor, pct);
    color = mix(color, blurredPixel, blurpct);
    color *= float4(gcircle, 1.0);
    color *= float4(gcircle2, 1.0);
    color *= float4(gcircle3, 1.0);
    return half4(color);
}
"""
