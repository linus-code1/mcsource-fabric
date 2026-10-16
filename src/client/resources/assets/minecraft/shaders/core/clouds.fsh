#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:oit.glsl>

// MC:Source: Minecraft's blocky clouds are not drawn; the sky shader paints a
// soft cloud layer instead.

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    discard;
}
