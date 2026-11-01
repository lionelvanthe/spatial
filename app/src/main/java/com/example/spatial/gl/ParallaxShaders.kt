package com.example.spatial.gl

import android.opengl.GLES20
import com.example.spatial.data.SpatialSceneConfig

/**
 * Per-pixel "steep parallax" shaders (GLSL ES 1.00 so they run on GLES 2.0).
 *
 * One full-screen rectangle is drawn. For every pixel the fragment shader walks a ray through
 * fixed depth planes of an extended foreground surface and samples the photo/mask there.
 * A reconstructed far surface is projected independently into the inpainted background.
 * Near surfaces slide along the tilt, far surfaces slide against it; `uNeutral` stays put.
 */
internal object ParallaxShaders {
    /** Sets the look uniforms (blur, vignette, colour grade) of [program], which must be in use. */
    fun applyEffects(program: Int, config: SpatialSceneConfig) {
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uScaleType"), 0f)
        val color = config.backgroundColor
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uBgColor"),
            ((color shr 16) and 255) / 255f, ((color shr 8) and 255) / 255f,
            (color and 255) / 255f, (color ushr 24) / 255f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uBlur"), config.blur)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uVignette"), config.vignette)
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(program, "uColor"),
            config.saturation, config.contrast, config.brightness, config.sepia
        )
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uCamera"), 1.0f - config.isometric, config.dolly)
    }

    const val VERTEX = """
        attribute vec4 position;
        attribute vec2 textureST;
        varying vec2 v_TexCoord;
        void main() {
            v_TexCoord = textureST;
            gl_Position = vec4(position.xy, 0.0, 1.0);
        }
    """

    const val FRAGMENT = """
        precision highp float;
        uniform sampler2D image;
        uniform sampler2D uDepthMap; // R=original, G=reconstructed far field, B=extended near field
        uniform vec2 uDepthTexel; // reciprocal depth texture dimensions
        uniform float uLayered; // B has an independently extended near surface
        uniform sampler2D uInpaint;        // background with foreground removed and filled
        uniform sampler2D uForegroundMask; // alpha is 1 only for the near layer
        uniform vec2 uOffset;        // tilt, -1..1 on each axis (positive: near surfaces move right/up)
        uniform float uDepthHeight;  // parallax size as a fraction of the image
        uniform float uNeutral;      // depth (0 far .. 1 near) that stays still
        uniform float uAspectRatio;  // viewport width / height
        uniform float uImageAspect;  // image width / height
        uniform vec4 uBgColor;
        uniform float uScaleType;    // 0 = fit, 1 = fill, 2 = fit over a blurred original backdrop
        uniform float uFill;         // share of the screen the photo takes
        uniform float uZoom;         // user zoom
        uniform vec2 uPan;           // pan, in image widths / heights
        uniform float uStrength;     // 0 = show the untouched photo
        uniform float uBlur;         // depth-of-field strength, 0 = off
        uniform float uVignette;     // edge darkening, 0 = off
        uniform vec4 uColor;         // saturation, contrast, brightness, sepia
        uniform vec2 uCamera;        // perspective (1 - isometric), dolly
        varying vec2 v_TexCoord;

        // Fixed loop bounds keep this valid on GLES 2 while providing a stable intersection.
        const int STEPS = 32;
        const float PERSPECTIVE = 0.25;
        const float DOLLY = 0.2;

        ${ImageFraming.GLSL}

        // The ray-marched foreground composed over the pre-filled background.
        float insidePhoto(vec2 uv) {
            return step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
        }

        vec4 compose(vec2 foregroundUV, vec2 backgroundUV, float visible) {
            vec4 foreground = texture2D(image, foregroundUV);
            vec4 background;
            if (insidePhoto(backgroundUV) > 0.5) {
                background = texture2D(uInpaint, backgroundUV);
            } else {
                background = uScaleType > 1.5 ? blurredBackdrop(v_TexCoord) : uBgColor;
            }
            return mix(background, foreground,
                texture2D(uForegroundMask, foregroundUV).a * visible * insidePhoto(foregroundUV));
        }

        float rayLimit(float origin, float travel) {
            return travel > 0.0 ? (1.0 - origin) / max(travel, 0.000001)
                                : origin / max(-travel, 0.000001);
        }

        vec2 projectDepth(vec2 uv, vec2 parallax, float depth) {
            float relativeDepth = depth - uNeutral;
            return uv - parallax * relativeDepth;
        }

        void main() {
            vec2 uv = photoUV(v_TexCoord);
            if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                gl_FragColor = uScaleType > 1.5 ? blurredBackdrop(v_TexCoord) : uBgColor;
                return;
            }

            // Original-photo mode is a separate path: no depth/mask/background reads and no
            // grading or depth blur. This also avoids precision noise for effectively zero strength.
            if (uStrength <= 0.0001) {
                gl_FragColor = texture2D(image, uv);
                return;
            }

            vec2 finalUV = uv;
            vec2 backgroundUV = uv;
            float hasSurface = 1.0;
            if (uStrength > 0.0) {
                // Equal tilt gives equal on-screen displacement on both axes (y scaled by the aspect).
                vec2 parallax = vec2(uOffset.x, -uOffset.y * uImageAspect) * uDepthHeight * uStrength;
                // Rays that diverge from a point (perspective, or a dollied camera) add an outward
                // component that grows with the distance from the centre; parallel rays add none.
                float spread = (uCamera.x * PERSPECTIVE + uCamera.y * DOLLY) * uStrength;
                // Magnify slightly so the diverging rays never leave the photo and smear its edge.
                uv = (uv - 0.5) / max(1.0, max(1.0 + spread * uNeutral, 1.0 - spread * (1.0 - uNeutral))) + 0.5;
                parallax += (uv - 0.5) * spread;
                vec2 nearTravel = -parallax * (1.0 - uNeutral);
                vec2 farTravel = parallax * uNeutral;
                float limit = min(min(rayLimit(uv.x, nearTravel.x), rayLimit(uv.y, nearTravel.y)),
                                  min(rayLimit(uv.x, farTravel.x), rayLimit(uv.y, farTravel.y)));
                // Approaches the image border smoothly; hard clamping creates a repeated-texel strip.
                limit = max(limit, 0.0);
                parallax *= 1.0 - exp(-limit * limit);
                // Independent inverse projection of the reconstructed far surface. Its depth
                // contains no foreground, so revealing a hole supplies actual filled RGB data.
                backgroundUV = uv;
                for (int j = 0; j < 4; j++) {
                    float farDepth = texture2D(uDepthMap, backgroundUV).g;
                    backgroundUV = projectDepth(uv, parallax, farDepth);
                }
                float stepDepth = 1.0 / float(STEPS);
                vec2 currentUV = projectDepth(uv, parallax, 1.0);
                float currentLayerDepth = 1.0;
                bool intersected = false;
                float previousSample = texture2D(uDepthMap, currentUV).b;
                float previousGap = 1.0 - previousSample;
                finalUV = currentUV;

                for (int i = 0; i <= STEPS; i++) {
                    float depthMapVal = texture2D(uDepthMap, currentUV).b;
                    float gap = currentLayerDepth - depthMapVal;
                    if (gap <= 0.0 && (i == 0 || previousGap > 0.0)) {
                        // A sign change caused by crossing a silhouette is a vertical side wall,
                        // not a visible RGB surface. Inspect one depth texel on either side;
                        // accept the real near/far surface, never an intermediate wall depth.
                        bool wall = false;
                        if (uLayered < 0.5) {
                            float left = texture2D(uDepthMap, currentUV - vec2(uDepthTexel.x, 0.0)).b;
                            float right = texture2D(uDepthMap, currentUV + vec2(uDepthTexel.x, 0.0)).b;
                            float top = texture2D(uDepthMap, currentUV - vec2(0.0, uDepthTexel.y)).b;
                            float bottom = texture2D(uDepthMap, currentUV + vec2(0.0, uDepthTexel.y)).b;
                            float low = min(min(left, right), min(top, bottom));
                            float high = max(max(left, right), max(top, bottom));
                            wall = high - low > 0.12 &&
                                min(abs(currentLayerDepth - low), abs(currentLayerDepth - high)) > stepDepth;
                        }
                        if (!wall && (uLayered > 0.5 || abs(depthMapVal - previousSample) <= 0.12)) {
                            vec2 beforeUV = projectDepth(uv, parallax, currentLayerDepth + stepDepth);
                            float beforeDepth = currentLayerDepth + stepDepth;
                            if (i > 0) {
                                for (int j = 0; j < 5; j++) {
                                    vec2 midUV = (beforeUV + currentUV) * 0.5;
                                    float midDepth = (beforeDepth + currentLayerDepth) * 0.5;
                                    if (midDepth <= texture2D(uDepthMap, midUV).b) {
                                        currentUV = midUV;
                                        currentLayerDepth = midDepth;
                                    } else {
                                        beforeUV = midUV;
                                        beforeDepth = midDepth;
                                    }
                                }
                            }
                            finalUV = currentUV;
                            intersected = true;
                            break;
                        }
                    }
                    previousSample = depthMapVal;
                    previousGap = gap;
                    currentLayerDepth -= stepDepth;
                    currentUV = projectDepth(uv, parallax, currentLayerDepth);
                }
                if (!intersected) {
                    // No RGB surface was visible on this ray. Use the filled far layer, not
                    // a foreground texel stretched across the rejected depth wall.
                    finalUV = projectDepth(uv, parallax, 0.0);
                    hasSurface = 0.0;
                }
            }
            // The source photo cannot contain pixels hidden behind a foreground object.  Sampling
            // it directly at a newly exposed coordinate creates a bright copy of the silhouette.
            // Compose the ray-marched foreground over a pre-filled background instead.
            vec4 color = compose(finalUV, backgroundUV, hasSurface);

            // Depth of field: blur grows with the distance from the neutral (in-focus) plane.
            if (uBlur > 0.0) {
                float away = abs(texture2D(uDepthMap, finalUV).r - uNeutral);
                float radius = uBlur * smoothstep(0.0, 0.6, away) * 0.012;
                if (radius > 0.0004) {
                    vec2 stretch = vec2(1.0, uImageAspect);
                    vec4 sum = color;
                    for (int i = 0; i < 8; i++) {
                        float a = float(i) * 0.785398;
                        vec2 dir = vec2(cos(a), sin(a)) * stretch;
                        sum += compose(clamp(finalUV + dir * radius, 0.0, 1.0),
                            clamp(backgroundUV + dir * radius, 0.0, 1.0), hasSurface);
                        sum += compose(clamp(finalUV + dir * radius * 0.5, 0.0, 1.0),
                            clamp(backgroundUV + dir * radius * 0.5, 0.0, 1.0), hasSurface);
                    }
                    color = sum / 17.0;
                }
            }

            // Colour grade.
            vec3 rgb = color.rgb * uColor.z;
            rgb = (rgb - 0.5) * uColor.y + 0.5;
            float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
            rgb = mix(vec3(luma), rgb, uColor.x);
            vec3 tint = vec3(dot(rgb, vec3(0.393, 0.769, 0.189)),
                             dot(rgb, vec3(0.349, 0.686, 0.168)),
                             dot(rgb, vec3(0.272, 0.534, 0.131)));
            rgb = mix(rgb, tint, uColor.w);

            // Vignette, measured on the screen so it hugs the frame whatever the zoom.
            vec2 fromCentre = v_TexCoord - 0.5;
            rgb *= 1.0 - uVignette * smoothstep(0.25, 0.75, length(fromCentre) * 1.2);

            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), color.a);
        }
    """
}
