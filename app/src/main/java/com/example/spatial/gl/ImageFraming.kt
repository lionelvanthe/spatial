package com.example.spatial.gl

/** One source-coordinate transform for RGB, mask and all depth channels. */
internal object ImageFraming {
    const val GLSL = """
        vec2 aspectUV(vec2 screenUV, float crop) {
            float ratio = uAspectRatio / uImageAspect;
            vec2 span = crop > 0.5 ? vec2(min(ratio, 1.0), min(1.0 / ratio, 1.0))
                                  : vec2(max(ratio, 1.0), max(1.0 / ratio, 1.0));
            return (screenUV - 0.5) * span + 0.5;
        }

        vec2 photoUV(vec2 screenUV) {
            bool crop = uScaleType > 0.5 && uScaleType < 1.5;
            float scale = crop ? max(uFill * uZoom, 1.0) : min(uFill * uZoom, 1.0);
            vec2 uv = (aspectUV(screenUV, crop ? 1.0 : 0.0) - 0.5) / max(scale, 0.0001) + 0.5;
            // Fit modes retain the complete photo. Fill permits pan only within its crop,
            // so a gesture cannot accidentally expose empty space.
            if (crop) {
                vec2 span = (aspectUV(vec2(1.0), 1.0) - 0.5) * 2.0 / scale;
                vec2 allowance = max((1.0 - span) * 0.5, vec2(0.0));
                uv += clamp(vec2(-uPan.x, uPan.y), -allowance, allowance);
            }
            return uv;
        }

        // Extended backdrop is a center crop of the ORIGINAL RGB, not the disocclusion
        // inpaint texture. Only uncovered fit regions pay the 13-tap blur cost.
        vec4 blurredBackdrop(vec2 screenUV) {
            vec2 uv = aspectUV(screenUV, 1.0);
            vec2 radius = vec2(0.035, 0.035 * uImageAspect);
            vec4 sum = texture2D(image, uv) * 4.0;
            for (int i = 0; i < 8; i++) {
                float angle = float(i) * 0.785398163;
                vec2 offset = vec2(cos(angle), sin(angle)) * radius;
                sum += texture2D(image, clamp(uv + offset, 0.0, 1.0));
            }
            for (int i = 0; i < 4; i++) {
                float angle = float(i) * 1.570796327;
                vec2 offset = vec2(cos(angle), sin(angle)) * radius * 0.5;
                sum += texture2D(image, clamp(uv + offset, 0.0, 1.0));
            }
            return sum / 16.0;
        }
    """
}
