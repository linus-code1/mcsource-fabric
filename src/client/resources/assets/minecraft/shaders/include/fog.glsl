#ifndef MINECRAFT_FOG_GLSL
#define MINECRAFT_FOG_GLSL

// MC:Source fog. On top of Minecraft's own fog (water, lava, blindness and the
// edge of the render distance) there is Half-Life 2 style distance haze: a
// linear fog that starts a short way out and thickens towards the horizon,
// up to a maximum density, so distant hills fade into the sky colour.
// Written for this mod; no Valve code is used.

layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

// Haze starts this many blocks out and reaches MCSOURCE_HAZE_NEAR at the
// fog's start (the render distance), then thickens on to full at the fog's
// end. Without distant terrain the end is the render distance itself; with
// it the end is the far edge of the distant terrain, so the land beyond the
// loaded chunks fades slowly into the sky instead of stopping.
const float MCSOURCE_HAZE_START = 16.0;
const float MCSOURCE_HAZE_NEAR = 0.6;

float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (vertexDistance <= fogStart) {
        return 0.0;
    } else if (vertexDistance >= fogEnd) {
        return 1.0;
    }
    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

float total_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmentalEnd, float renderDistanceStart, float renderDistanceEnd) {
    float environment = linear_fog_value(sphericalVertexDistance, environmentalStart, environmentalEnd);
    // The sky passes the same value for start and end; it gets no haze.
    if (renderDistanceStart >= renderDistanceEnd) {
        return max(environment, linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
    }
    float nearHaze = linear_fog_value(sphericalVertexDistance, MCSOURCE_HAZE_START, renderDistanceStart) * MCSOURCE_HAZE_NEAR;
    float farHaze = linear_fog_value(sphericalVertexDistance, renderDistanceStart, renderDistanceEnd);
    return max(environment, nearHaze + (1.0 - MCSOURCE_HAZE_NEAR) * farHaze);
}

// Colour grading towards the look of Half-Life 2, applied once to the lit
// colour of everything drawn in the world (blocks, mobs, the hands) but not
// to menus, which have no fog: colours well less saturated than Minecraft's,
// a gentle S-shaped contrast, shadows leaning cool blue-green and
// highlights only a touch warm. (Textures used to be graded warm and yellow
// on their own, before lighting, which read as Half-Life 1, not 2.)
const float MCSOURCE_SATURATION = 0.75;
const float MCSOURCE_CONTRAST = 0.3;
const vec3 MCSOURCE_SHADOW_TINT = vec3(-0.012, 0.004, 0.02);
const vec3 MCSOURCE_HIGHLIGHT_TINT = vec3(1.03, 1.0, 0.95);

vec3 mcsource_grade(vec3 color) {
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(luma), color, MCSOURCE_SATURATION);
    color = mix(color, color * color * (3.0 - 2.0 * color), MCSOURCE_CONTRAST);
    float shadow = (1.0 - luma) * (1.0 - luma);
    color += MCSOURCE_SHADOW_TINT * shadow;
    color *= mix(vec3(1.0), MCSOURCE_HIGHLIGHT_TINT, luma * luma);
    return clamp(color, 0.0, 1.0);
}

vec4 apply_fog(vec4 inColor, float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmentalEnd, float renderDistanceStart, float renderDistanceEnd, vec4 fogColor) {
    float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd);
    vec3 color = inColor.rgb;
    if (renderDistanceStart < renderDistanceEnd) {
        color = mcsource_grade(color);
    }
    return vec4(mix(color, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

float fog_spherical_distance(vec3 pos) {
    return length(pos);
}

float fog_cylindrical_distance(vec3 pos) {
    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return max(distXZ, distY);
}

#endif
