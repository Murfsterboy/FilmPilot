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
 *  - The LUT is a 3D texture (`size³`, RGB32F, NEAREST) laid out like the `.cube` data
 *    (x = red, y = green, z = blue). Trilinear interpolation is done in the shader with
 *    `texelFetch`, in the same order as [Lut3D.sample], so it needs no float-texture filtering
 *    support and matches the CPU closely.
 *
 *  - `uGrainLattice` is [Noise.lattice] as an R32F texture; `uGrainOffset` comes from the seed.
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
uniform sampler2D uGrainLattice;
uniform ivec2 uGrainOffset;
uniform ivec2 uTileOrigin;

out vec4 fragColor;

const float VIGNETTE_START = ${FilmPipeline.VIGNETTE_START};
const float GRAIN_FLOOR = ${FilmPipeline.GRAIN_FLOOR};
const int LATTICE_MASK = ${Noise.LATTICE_SIZE - 1};

float srgbToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}
float linearToSrgb(float c) {
    c = clamp(c, 0.0, 1.0);
    return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
}

vec3 lutAt(int r, int g, int b) { return texelFetch(uLut, ivec3(r, g, b), 0).rgb; }

// Lut3D.sample: trilinear, interpolating red, then green, then blue.
vec3 sampleLut(vec3 c) {
    float n = uLutSize - 1.0;
    vec3 f = clamp((c - uLutMin) * uLutInvRange * n, 0.0, n);
    ivec3 i = min(ivec3(f), ivec3(int(n) - 1));
    vec3 d = f - vec3(i);
    vec3 c00 = mix(lutAt(i.x, i.y, i.z),         lutAt(i.x + 1, i.y, i.z),         d.x);
    vec3 c10 = mix(lutAt(i.x, i.y + 1, i.z),     lutAt(i.x + 1, i.y + 1, i.z),     d.x);
    vec3 c01 = mix(lutAt(i.x, i.y, i.z + 1),     lutAt(i.x + 1, i.y, i.z + 1),     d.x);
    vec3 c11 = mix(lutAt(i.x, i.y + 1, i.z + 1), lutAt(i.x + 1, i.y + 1, i.z + 1), d.x);
    return mix(mix(c00, c10, d.y), mix(c01, c11, d.y), d.z);
}

// Noise.value: grain lattice (R32F, LATTICE_SIZE², NEAREST) shared with the CPU.
float latticeAt(int x, int y) {
    return texelFetch(uGrainLattice, ivec2(x & LATTICE_MASK, y & LATTICE_MASK), 0).r;
}
float valueNoise(float x, float y) {
    float xf = floor(x), yf = floor(y);
    int xi = int(xf) + uGrainOffset.x, yi = int(yf) + uGrainOffset.y;
    float tx = x - xf, ty = y - yf;
    tx = tx * tx * (3.0 - 2.0 * tx);
    ty = ty * ty * (3.0 - 2.0 * ty);
    float a = latticeAt(xi, yi);
    float b = latticeAt(xi + 1, yi);
    float c = latticeAt(xi, yi + 1);
    float d = latticeAt(xi + 1, yi + 1);
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

    // 2. Film LUT.
    if (uUseLut) rgb = sampleLut(rgb);

    // 3. Vignette.
    if (uVignetteAmount > 0.0) {
        float dist = length(vec2(x + 0.5, y + 0.5) - uCenter) * uInvHalfDiagonal;
        rgb *= 1.0 - uVignetteAmount * smoothstep(VIGNETTE_START, 1.0, dist);
    }

    // 4. Grain, weighted to the midtones.
    if (uGrainAmount > 0.0) {
        float g = valueNoise(x * uInvGrainSize, y * uInvGrainSize);
        float l = clamp(dot(rgb, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
        float weight = GRAIN_FLOOR + (1.0 - GRAIN_FLOOR) * 4.0 * l * (1.0 - l);
        rgb += g * uGrainAmount * weight;
    }

    fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""
}
