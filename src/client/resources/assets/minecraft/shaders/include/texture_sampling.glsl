#ifndef MINECRAFT_TEXTURE_SAMPLING_GLSL
#define MINECRAFT_TEXTURE_SAMPLING_GLSL

// MC:Source block texture sampling, for a Half-Life 1 look. Up close, textures
// are smoothly filtered between texels the way the GoldSrc OpenGL renderer
// drew its textures, instead of Minecraft's sharp pixels. Each texel is
// first graded towards the muted, warm colours of Half-Life's textures. Far
// away the normal mipmapped texture is used. Half-Life 2 textures read from
// the player's game (see BlockTextures) are larger than 16x16; filtering
// still stays inside 16-texel cells, which only shows as a slightly sharper
// step every 16 texels.
// Written for this mod; no Valve code or textures are used.

// Block textures are 16x16; filtering stays inside the sprite a sample falls
// in so neighbouring sprites in the atlas don't bleed in.
const float MCSOURCE_SPRITE = 16.0;

vec3 mcsource_grade(vec3 color) {
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(luma), color, 0.72);
    color *= vec3(1.0, 0.96, 0.88);
    color = (color - 0.5) * 1.08 + 0.5;
    return clamp(color, 0.0, 1.0);
}

vec4 mcsource_texel(sampler2D source, ivec2 texel) {
    vec4 color = texelFetch(source, texel, 0);
    return vec4(mcsource_grade(color.rgb), color.a);
}

vec4 mcsource_filtered(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 texel = uv / pixelSize;
    vec2 spriteMin = floor(texel / MCSOURCE_SPRITE) * MCSOURCE_SPRITE;
    vec2 spriteMax = spriteMin + (MCSOURCE_SPRITE - 1.0);
    vec2 p = texel - 0.5;
    vec2 base = floor(p);
    vec2 f = p - base;
    ivec2 lo = ivec2(clamp(base, spriteMin, spriteMax));
    ivec2 hi = ivec2(clamp(base + 1.0, spriteMin, spriteMax));
    vec4 c00 = mcsource_texel(source, lo);
    vec4 c10 = mcsource_texel(source, ivec2(hi.x, lo.y));
    vec4 c01 = mcsource_texel(source, ivec2(lo.x, hi.y));
    vec4 c11 = mcsource_texel(source, hi);
    // Weight colours by alpha so cut-out edges (leaves, plants) don't pick up
    // the dark colour of their transparent texels.
    vec4 w = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
    vec4 a = vec4(c00.a, c10.a, c01.a, c11.a) * w;
    float alpha = a.x + a.y + a.z + a.w;
    vec3 rgb = c00.rgb * a.x + c10.rgb * a.y + c01.rgb * a.z + c11.rgb * a.w;
    rgb = alpha > 0.0 ? rgb / alpha : c00.rgb;
    return vec4(rgb, alpha);
}

vec4 mcsource_sample(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float texelsPerPixel = max(texelScreenSize.x / pixelSize.x, texelScreenSize.y / pixelSize.y);
    float far = smoothstep(0.7, 1.5, texelsPerPixel);
    vec4 near = far < 1.0 ? mcsource_filtered(source, uv, pixelSize) : vec4(0.0);
    vec4 mipped = textureGrad(source, uv, du, dv);
    mipped.rgb = mcsource_grade(mipped.rgb);
    return mix(near, mipped, far);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    return mcsource_sample(source, uv, pixelSize);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    return mcsource_sample(source, uv, pixelSize);
}

vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    return mcsource_sample(source, uv, pixelSize);
}

#endif
