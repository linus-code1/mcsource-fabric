#ifndef MINECRAFT_LIGHT_GLSL
#define MINECRAFT_LIGHT_GLSL

// MC:Source directional light for mobs, players and held items. Uses the
// half-Lambert term Valve described in "Shading in Valve's Source Engine"
// (SIGGRAPH 2006): the usual N.L is remapped from [-1, 1] to [0, 1] and
// squared, so the light wraps around the model and the dark side keeps its
// shape instead of going flat. Written for this mod; no Valve code is used.

#define MINECRAFT_LIGHT_POWER   (0.6)
#define MINECRAFT_AMBIENT_LIGHT (0.4)

#define MCSOURCE_DIRECT_LIGHT  (0.5)
#define MCSOURCE_AMBIENT_LIGHT (0.3)

layout(std140) uniform Lighting {
    vec3 Light0_Direction;
    vec3 Light1_Direction;
};

vec2 minecraft_compute_light(vec3 lightDir0, vec3 lightDir1, vec3 normal) {
    return vec2(dot(lightDir0, normal), dot(lightDir1, normal));
}

vec2 mcsource_half_lambert(vec2 nDotL) {
    vec2 wrapped = clamp(nDotL * 0.5 + 0.5, 0.0, 1.0);
    return wrapped * wrapped;
}

vec4 minecraft_mix_light_separate(vec2 light, vec4 color) {
    vec2 lit = mcsource_half_lambert(light);
    float lightAccum = min(1.0, (lit.x + lit.y) * MCSOURCE_DIRECT_LIGHT + MCSOURCE_AMBIENT_LIGHT);
    return vec4(color.rgb * lightAccum, color.a);
}

vec4 minecraft_mix_light(vec3 lightDir0, vec3 lightDir1, vec3 normal, vec4 color) {
    vec2 light = minecraft_compute_light(lightDir0, lightDir1, normal);
    return minecraft_mix_light_separate(light, color);
}

#endif
