package com.filmpilot.fx

/**
 * GLSL port of [LensOptics]. The maths is written once with a `SAMPLE(uv)` macro so it can
 * run both in the GLSL ES 3.00 developing pass (sampling the photo) and in the GLSL ES 1.00
 * live-preview pass (sampling the camera's external texture through CameraX's transform).
 *
 * Uniforms: `uDistortion`, `uChromatic`, `uBlur` (= [LensParams.blur]).
 */
object LensShaderSource {

    /** `vec3 lensSample(vec2 p, vec2 size)`: lens-processed colour at output pixel centre [p]. */
    const val COMMON = """
uniform float uDistortion;
uniform float uChromatic;
uniform float uBlur;

vec3 lensSample(vec2 p, vec2 size) {
    vec2 c = size * 0.5;
    float hd = length(c);
    vec2 q = (p - c) / hd;
    float r2 = dot(q, q);
    float s = (1.0 + uDistortion * r2) / (1.0 + max(uDistortion, 0.0));
    int n = uBlur > 0.0 ? 5 : 1;
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 5; i++) {
        if (i >= n) break;
        float t = n == 1 ? 0.0 : -1.0 + 0.5 * float(i);
        float f = s * (1.0 + uBlur * r2 * t);
        float fr = f * (1.0 + uChromatic * r2);
        float fb = f * (1.0 - uChromatic * r2);
        acc.r += SAMPLE((c + q * fr * hd) / size).r;
        acc.g += SAMPLE((c + q * f * hd) / size).g;
        acc.b += SAMPLE((c + q * fb * hd) / size).b;
    }
    return acc / float(n);
}
"""

    /** Developing pass: full photo in `uSource` (GL_LINEAR, CLAMP_TO_EDGE), rendered in tiles. */
    val DEVELOP_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uSource;
uniform vec2 uImageSize;
uniform ivec2 uTileOrigin;
out vec4 fragColor;
#define SAMPLE(uv) texture(uSource, uv)
$COMMON
void main() {
    vec2 p = vec2(ivec2(gl_FragCoord.xy) + uTileOrigin) + 0.5;
    fragColor = vec4(clamp(lensSample(p, uImageSize), 0.0, 1.0), 1.0);
}
"""

    /** Live-preview pass: camera frame (external OES texture) → RGBA texture of size `uOutSize`. */
    val PREVIEW_VERTEX = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vUv;
void main() { gl_Position = aPosition; vUv = aTexCoord.xy; }
"""

    val PREVIEW_FRAGMENT = """#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES uCamera;
uniform mat4 uTexMatrix;
uniform vec2 uOutSize;
varying vec2 vUv;
#define SAMPLE(uv) texture2D(uCamera, (uTexMatrix * vec4((uv), 0.0, 1.0)).xy)
$COMMON
void main() {
    gl_FragColor = vec4(lensSample(vUv * uOutSize, uOutSize), 1.0);
}
"""
}
