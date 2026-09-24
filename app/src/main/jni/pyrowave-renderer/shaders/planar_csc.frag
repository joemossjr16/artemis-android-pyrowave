#version 450
// PyroWave decodes to three planes (Y, Cb, Cr). The host encodes 8-bit BT.709
// limited range with left-cosited 4:2:0 chroma, so shift chroma sampling by a
// quarter chroma texel to line it up with centre-sampled luma.
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

    float y = (texture(u_y, v_uv).r - 16.0 / 255.0) * (255.0 / 219.0);
    float cb = (texture(u_cb, cuv).r - 128.0 / 255.0) * (255.0 / 224.0);
    float cr = (texture(u_cr, cuv).r - 128.0 / 255.0) * (255.0 / 224.0);

    vec3 rgb = vec3(
        y + 1.5748 * cr,
        y - 0.1873 * cb - 0.4681 * cr,
        y + 1.8556 * cb
    );
    frag = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
