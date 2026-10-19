#version 330
#extension GL_ARB_separate_shader_objects : require

// MC:Source lightmap. Builds the 16x16 light colour table that every block and
// mob is lit through, styled after the look of Source engine maps: warm lamps,
// daylight made of a warm sun and a cool blue sky fill, and soft highlights
// instead of hard clipping. The two inputs are not Minecraft's light levels:
// SourceLight works out how much lamp light reaches a point (already fallen
// off with distance and shadowed by walls) and how much of the sky it sees,
// and this only turns those into colour. Sun direction and shadows are baked
// into block vertices by SunLight. Written for this mod from the published
// descriptions of Source lighting; no Valve code is used.

layout(std140) uniform LightmapInfo {
    float SkyFactor;
    float BlockFactor;
    float NightVisionFactor;
    float DarknessScale;
    float BossOverlayWorldDarkeningFactor;
    float BrightnessFactor;
    vec3 BlockLightTint;
    vec3 SkyLightColor;
    vec3 AmbientColor;
    vec3 NightVisionColor;
} lightmapInfo;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// Lamp colours: the bright middle of a lamp's pool is near white, the dim edge
// a deeper orange, like bounced incandescent light.
const vec3 LAMP_HOT = vec3(1.00, 0.86, 0.68);
const vec3 LAMP_EDGE = vec3(1.00, 0.58, 0.30);
// Lamps are slightly overbright right next to the source.
const float LAMP_INTENSITY = 1.35;

// Daylight under open sky: a cool sky fill plus a warm sun. SunLight uses the
// same numbers to work out how much is left in shadow.
const vec3 SUN_COLOR = vec3(1.06, 0.98, 0.86);
const vec3 SKY_FILL_COLOR = vec3(0.78, 0.87, 1.06);
const float SUN_STRENGTH = 0.6;
const float SKY_FILL_STRENGTH = 0.7;
// Overall daylight level, scaled so open ground in full sun lands just under
// white after the soft clip.
const float DAYLIGHT = 0.9;


// Keeps colours linear up to the knee and rolls brighter light off smoothly
// towards white, standing in for Source's HDR tone mapping.
vec3 soft_clip(vec3 color) {
    const float knee = 0.78;
    vec3 over = max(color - knee, 0.0);
    vec3 rolled = knee + (1.0 - knee) * (1.0 - exp(-over / (1.0 - knee)));
    return mix(color, rolled, step(knee, color));
}

vec3 brighten(vec3 color) {
    float maxComponent = max(max(color.x, color.y), color.z);
    if (maxComponent <= 0.0) {
        return color;
    }
    float inverted = 1.0 - maxComponent;
    float raised = 1.0 - inverted * inverted * inverted * inverted;
    return color * (raised / maxComponent);
}

void main() {
    float blockLevel = floor(texCoord.x * 16.0) / 15.0;
    float skyLevel = floor(texCoord.y * 16.0) / 15.0;

    vec3 nightVision = lightmapInfo.NightVisionColor * lightmapInfo.NightVisionFactor;
    vec3 color = max(lightmapInfo.AmbientColor, nightVision);

    // Daylight in proportion to how much sky is seen (and bounced in).
    vec3 daylight = (SKY_FILL_COLOR * SKY_FILL_STRENGTH + SUN_COLOR * SUN_STRENGTH) * DAYLIGHT * skyLevel;
    color += lightmapInfo.SkyLightColor * daylight * lightmapInfo.SkyFactor;

    // Lamps: whiter near the flame. The dimension's block light tint still
    // nudges the colour (the Nether stays redder).
    float lamp = blockLevel;
    vec3 tint = lightmapInfo.BlockLightTint / max(max(max(lightmapInfo.BlockLightTint.r, lightmapInfo.BlockLightTint.g), lightmapInfo.BlockLightTint.b), 0.001);
    vec3 lampColor = mix(LAMP_EDGE, LAMP_HOT, lamp) * mix(vec3(1.0), tint, 0.35);
    color += lampColor * lamp * LAMP_INTENSITY * lightmapInfo.BlockFactor;

    color = mix(color, color * vec3(0.7, 0.6, 0.6), lightmapInfo.BossOverlayWorldDarkeningFactor);
    color -= vec3(lightmapInfo.DarknessScale);

    color = clamp(soft_clip(max(color, 0.0)), 0.0, 1.0);
    color = mix(color, brighten(color), lightmapInfo.BrightnessFactor);

    fragColor = vec4(color, 1.0);
}
