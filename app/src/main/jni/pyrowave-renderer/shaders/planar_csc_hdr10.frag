#version 450
// PyroWave HDR10 (P010-style R16_UNORM planes, BT.2020/PQ) -> tone-mapped SDR output.
//
// Ported from punktfunk's tonemap.glsl (crates/pf-client-core/shaders/tonemap.glsl):
// BT.2390 EETF applied in PQ from a peak*203-nit source onto 203-nit SDR white, then
// BT.2020->BT.709 primaries and the sRGB OETF. This client never creates a real HDR10
// Vulkan swapchain (device/compositor HDR10 surface format support and colorspace
// negotiation is inconsistent across Android devices) - instead it decodes the full
// HDR/PQ wire data and tone-maps down to the same plain SDR swapchain the SDR path
// uses. See planar_csc.frag for the SDR-only sibling shader.
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 frag;

layout(set = 0, binding = 0) uniform sampler2D u_y;
layout(set = 0, binding = 1) uniform sampler2D u_cb;
layout(set = 0, binding = 2) uniform sampler2D u_cr;

// SMPTE ST.2084 (PQ) EOTF: code value -> linear, 1.0 = 10000 nits.
vec3 pq_eotf(vec3 e) {
    const float m1 = 0.1593017578125;  // 2610/16384
    const float m2 = 78.84375;         // 2523/4096 * 128
    const float c1 = 0.8359375;        // 3424/4096
    const float c2 = 18.8515625;       // 2413/4096 * 32
    const float c3 = 18.6875;          // 2392/4096 * 32
    vec3 p = pow(max(e, vec3(0.0)), vec3(1.0 / m2));
    return pow(max(p - c1, vec3(0.0)) / (c2 - c3 * p), vec3(1.0 / m1));
}

// Inverse of pq_eotf for one channel: linear (1.0 = 10000 nits) -> code value.
float pq_oetf(float y) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    float p = pow(clamp(y, 0.0, 1.0), m1);
    return pow((c1 + c2 * p) / (1.0 + c3 * p), m2);
}

// BT.2020 -> BT.709 primaries (linear light).
vec3 bt2020_to_709(vec3 c) {
    return mat3(
         1.6605, -0.1246, -0.0182,
        -0.5876,  1.1329, -0.1006,
        -0.0728, -0.0083,  1.1187
    ) * c;
}

// Linear -> sRGB OETF.
vec3 srgb_oetf(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    bvec3 lo = lessThanEqual(c, vec3(0.0031308));
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return mix(hi, c * 12.92, vec3(lo));
}

// `pq`: full-range PQ R'G'B' (BT.2020). `peak`: source peak in 203-nit units.
vec3 pq_to_sdr(vec3 pq, float peak) {
    vec3 lin = max(bt2020_to_709(pq_eotf(clamp(pq, 0.0, 1.0))), vec3(0.0));
    float l = max(lin.r, max(lin.g, lin.b));
    if (l > 0.0) {
        const float white = 203.0 / 10000.0;
        float src = pq_oetf(max(peak, 1.0001) * white);
        float max_lum = pq_oetf(white) / src;
        float ks = 1.5 * max_lum - 0.5;
        float e = min(pq_oetf(l) / src, 1.0);
        if (e > ks) {
            float t = (e - ks) / (1.0 - ks);
            float t2 = t * t;
            float t3 = t2 * t;
            e = (2.0 * t3 - 3.0 * t2 + 1.0) * ks + (t3 - 2.0 * t2 + t) * (1.0 - ks)
                + (-2.0 * t3 + 3.0 * t2) * max_lum;
        }
        lin *= pq_eotf(vec3(e * src)).r / l;
    }
    return srgb_oetf(lin / (203.0 / 10000.0));
}

void main() {
    vec2 cuv = v_uv;
    float cw = float(textureSize(u_cb, 0).x);
    if (cw < float(textureSize(u_y, 0).x)) {
        cuv.x += 0.25 / cw;
    }
    // 10-bit studio-range BT.2020 PQ codes, MSB-packed into R16_UNORM (P010 convention).
    float y = (texture(u_y, v_uv).r - 64.0 / 1023.0) * (1023.0 / 876.0);
    float cb = (texture(u_cb, cuv).r - 512.0 / 1023.0) * (1023.0 / 896.0);
    float cr = (texture(u_cr, cuv).r - 512.0 / 1023.0) * (1023.0 / 896.0);
    vec3 pq_rgb = vec3(
        y + 1.4746 * cr,
        y - 0.1646 * cb - 0.5714 * cr,
        y + 1.8814 * cb
    );
    // 4.9 = ~1000 nits over the 203-nit reference, matching punktfunk's presenter default.
    vec3 rgb = pq_to_sdr(pq_rgb, 4.9);
    frag = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
