#version 450
// HDR10 variant of planar_csc.frag: the host's HDR10 capture/encode path
// (Vibepollo's src/platform/windows/pyrowave_d3d11.cpp and display_vram.cpp)
// produces studio-range BT.2020 codes carried as 10-bit values left-shifted
// into 16-bit planes (P010 convention: value16 = value10 << 6). Sampling an
// R16_UNORM texture normalizes against 65535, which lands within a fraction
// of an LSB of normalizing against 1023 for a value shifted that way - the
// same sampling trick moonlight-qt-pyrowave's PyrowaveHdrPresenter (Windows
// client) relies on for its own BT.2020 CSC pass. The PQ (SMPTE ST 2084)
// transfer characteristic does not enter this matrix at all: it is an
// orthogonal per-sample nonlinearity the host already encoded and this pass
// leaves untouched, exactly like existing HEVC/AV1 Main10 HDR decode paths.
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 frag;

layout(set = 0, binding = 0) uniform sampler2D u_y;
layout(set = 0, binding = 1) uniform sampler2D u_cb;
layout(set = 0, binding = 2) uniform sampler2D u_cr;

void main() {
    vec2 cuv = v_uv;
    float cw = float(textureSize(u_cb, 0).x);
    if (cw < float(textureSize(u_y, 0).x)) {
        cuv.x += 0.25 / cw;
    }

    // 10-bit limited range: yMin=64, yMax=940, uvMin=64, uvMax=960 (out of
    // 1023), matching getFramePremultipliedCscConstants()'s bitsPerChannel=10
    // branch in moonlight-qt-pyrowave's ffmpeg-renderers/renderer.h.
    float y = (texture(u_y, v_uv).r - 64.0 / 1023.0) * (1023.0 / 876.0);
    float cb = (texture(u_cb, cuv).r - 512.0 / 1023.0) * (1023.0 / 896.0);
    float cr = (texture(u_cr, cuv).r - 512.0 / 1023.0) * (1023.0 / 896.0);

    // BT.2020 NCL YCbCr -> RGB.
    vec3 rgb = vec3(
        y + 1.4746 * cr,
        y - 0.1646 * cb - 0.5714 * cr,
        y + 1.8814 * cb
    );
    frag = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
