package com.filmpilot.fx

/**
 * GLSL ES 3.00 port of [FilmPipeline], line for line. It runs on the camera stream for the
 * live viewfinder and on full-resolution captures for developing.
 *
 * Conventions:
 *  - `uImage` holds the input pixels; the shader reads the texel under each fragment
 *    (`texelFetch(uImage, ivec2(gl_FragCoord.xy))`), so the render target must be the same
 *    size as the input texture.
 *  - `uTileOrigin` is the position of that texture inside the full image, which lets a large
 *    photo be developed in tiles while vignette and grain stay continuous across tiles.
 *    Row 0 of the texture is image row `uTileOrigin.y`.
 *  - The LUT is a 3D texture (`size³`, RGB16F, linear filtering) laid out like the `.cube` data.
 *
 * Constants are injected from the Kotlin implementation, and `FilmUniforms` supplies the
 * uniform values.
 */
object FilmShaderSource {

    const val VERTEX = """#version 300 es
in vec4 aPosition;
void main() { gl_Position = aPosition; }
"""

    val FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;

uniform sampler2D uImage;
uniform sampler3D uLut;
uniform bool uUseLut;
uniform vec3 uLutMin;
uniform vec3 uLutInvRange;   // 1 / (domainMax - domainMin)
uniform float uLutSize;
uniform bool uUseExposure;
uniform float uExposureGain;
uniform vec2 uCenter;
uniform float uInvHalfDiagonal;
uniform float uVignetteAmount;
uniform float uGrainAmount;
uniform float uInvGrainSize;
uniform int uGrainSeed;
uniform ivec2 uTileOrigin;

out vec4 fragColor;

const float VIGNETTE_START = ${FilmPipeline.VIGNETTE_START};
const float GRAIN_FLOOR = ${FilmPipeline.GRAIN_FLOOR};

float srgbToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}
float linearToSrgb(float c) {
    c = clamp(c, 0.0, 1.0);
    return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
}

// Noise.hash (lowbias32): identical 32-bit wrap-around arithmetic.
uint hash(int x, int y, int seed) {
    uint h = (uint(x) * 0x27d4eb2du) ^ (uint(y) * 0x165667b1u) ^ (uint(seed) * 0x5bd1e995u);
    h ^= h >> 16u; h *= 0x7feb352du;
    h ^= h >> 15u; h *= 0x846ca68bu;
    h ^= h >> 16u;
    return h;
}
float signedNoise(int x, int y, int seed) {
    return float(hash(x, y, seed) >> 8u) * (2.0 / 16777215.0) - 1.0;
}
float valueNoise(float x, float y, int seed) {
    float xf = floor(x), yf = floor(y);
    int xi = int(xf), yi = int(yf);
    float tx = x - xf, ty = y - yf;
    tx = tx * tx * (3.0 - 2.0 * tx);
    ty = ty * ty * (3.0 - 2.0 * ty);
    float a = signedNoise(xi, yi, seed);
    float b = signedNoise(xi + 1, yi, seed);
    float c = signedNoise(xi, yi + 1, seed);
    float d = signedNoise(xi + 1, yi + 1, seed);
    return mix(mix(a, b, tx), mix(c, d, tx), ty);
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec3 rgb = texelFetch(uImage, texel, 0).rgb;
    ivec2 p = texel + uTileOrigin;
    float x = float(p.x), y = float(p.y);

    // 1. Exposure, in linear light.
    if (uUseExposure) {
        rgb = vec3(
            linearToSrgb(srgbToLinear(rgb.r) * uExposureGain),
            linearToSrgb(srgbToLinear(rgb.g) * uExposureGain),
            linearToSrgb(srgbToLinear(rgb.b) * uExposureGain));
    }

    // 2. Film LUT (trilinear via hardware filtering, sampling texel centres).
    if (uUseLut) {
        vec3 n = clamp((rgb - uLutMin) * uLutInvRange, 0.0, 1.0);
        vec3 coord = (n * (uLutSize - 1.0) + 0.5) / uLutSize;
        rgb = texture(uLut, coord).rgb;
    }

    // 3. Vignette.
    if (uVignetteAmount > 0.0) {
        float dist = length(vec2(x + 0.5, y + 0.5) - uCenter) * uInvHalfDiagonal;
        rgb *= 1.0 - uVignetteAmount * smoothstep(VIGNETTE_START, 1.0, dist);
    }

    // 4. Grain, weighted to the midtones.
    if (uGrainAmount > 0.0) {
        float g = valueNoise(x * uInvGrainSize, y * uInvGrainSize, uGrainSeed);
        float l = clamp(dot(rgb, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
        float weight = GRAIN_FLOOR + (1.0 - GRAIN_FLOOR) * 4.0 * l * (1.0 - l);
        rgb += g * uGrainAmount * weight;
    }

    fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""
}
