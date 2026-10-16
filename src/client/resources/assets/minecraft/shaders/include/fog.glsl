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

// Haze starts this many blocks out and reaches its maximum at the render distance.
const float MCSOURCE_HAZE_START = 16.0;
const float MCSOURCE_HAZE_MAX_DENSITY = 0.6;

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
    float edge = linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd);
    float fog = max(environment, edge);
    // The sky passes the same value for start and end; it gets no haze.
    if (renderDistanceStart < renderDistanceEnd) {
        float haze = linear_fog_value(sphericalVertexDistance, MCSOURCE_HAZE_START, renderDistanceEnd) * MCSOURCE_HAZE_MAX_DENSITY;
        fog = max(fog, haze);
    }
    return fog;
}

vec4 apply_fog(vec4 inColor, float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmentalEnd, float renderDistanceStart, float renderDistanceEnd, vec4 fogColor) {
    float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd);
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
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
