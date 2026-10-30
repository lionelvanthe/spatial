package com.example.spatial.gl

/**
 * Per-pixel "steep parallax" shaders (GLSL ES 1.00 so they run on GLES 2.0).
 *
 * One full-screen rectangle is drawn. For every pixel the fragment shader walks a ray through
 * [STEPS] depth planes of the (heavily smoothed) depth map, finds where the ray meets the surface
 * and samples the photo there. Near surfaces slide along the tilt, far surfaces slide against it,
 * and the plane at `uNeutral` stays put.
 */
internal object ParallaxShaders {
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
        uniform sampler2D uDepthMap;
        uniform sampler2D uInpaint;        // background with foreground removed and filled
        uniform sampler2D uForegroundMask; // alpha is 1 only for the near layer
        uniform vec2 uOffset;        // tilt, -1..1 on each axis (positive: near surfaces move right/up)
        uniform float uDepthHeight;  // parallax size as a fraction of the image
        uniform float uNeutral;      // depth (0 far .. 1 near) that stays still
        uniform float uAspectRatio;  // viewport width / height
        uniform float uImageAspect;  // image width / height
        uniform vec4 uBgColor;
        uniform float uScaleType;    // 0 = fit (letterbox), 1 = crop (fill the screen)
        uniform float uFill;         // share of the screen the photo takes
        uniform float uZoom;         // user zoom
        uniform vec2 uPan;           // pan, in image widths / heights
        uniform float uZoom3D;       // how much near surfaces are magnified
        uniform float uStrength;     // 0 = show the untouched photo
        varying vec2 v_TexCoord;

        // Fixed loop bounds keep this valid on GLES 2 while providing a stable intersection.
        const int STEPS = 32;

        void main() {
            float ratio = uAspectRatio / uImageAspect;
            vec2 uv = v_TexCoord;
            if (uScaleType < 0.5) {
                if (ratio > 1.0) { uv.x = (uv.x - 0.5) * ratio + 0.5; }
                else             { uv.y = (uv.y - 0.5) / ratio + 0.5; }
            } else {
                if (ratio > 1.0) { uv.y = (uv.y - 0.5) / ratio + 0.5; }
                else             { uv.x = (uv.x - 0.5) * ratio + 0.5; }
            }
            uv = (uv - 0.5) / (uFill * uZoom) + 0.5;
            uv += vec2(-uPan.x, uPan.y);
            if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                gl_FragColor = uBgColor;
                return;
            }

            vec2 finalUV = uv;
            if (uStrength > 0.0) {
                // Equal tilt gives equal on-screen displacement on both axes (y scaled by the aspect).
                vec2 parallax = vec2(uOffset.x, -uOffset.y * uImageAspect) * uDepthHeight * uStrength;
                float stepDepth = 1.0 / float(STEPS);
                vec2 delta = parallax / float(STEPS);
                vec2 currentUV = uv - parallax * (1.0 - uNeutral);
                float currentLayerDepth = 1.0;
                float hitDepth = 0.0;
                bool intersected = false;
                finalUV = currentUV;

                for (int i = 0; i < STEPS; i++) {
                    float depthMapVal = texture2D(uDepthMap, currentUV).r;
                    if (currentLayerDepth <= depthMapVal) {
                        // Linear interpolation between this and the previous plane
                        float prevDepthMapVal = texture2D(uDepthMap, currentUV - delta).r;
                        float prevLayerDepth = currentLayerDepth + stepDepth;
                        float dist1 = prevLayerDepth - prevDepthMapVal;
                        float dist2 = depthMapVal - currentLayerDepth;
                        float weight = dist1 / (dist1 + dist2 + 0.0001);
                        finalUV = (currentUV - delta) * (1.0 - weight) + currentUV * weight;
                        hitDepth = currentLayerDepth;
                        intersected = true;
                        break;
                    }
                    currentUV += delta;
                    currentLayerDepth -= stepDepth;
                }
                if (!intersected) {
                    finalUV = currentUV;
                    hitDepth = 0.0;
                }
                // 3D zoom: surfaces in front of the still plane are magnified about the centre.
                float zoom3d = 1.0 + uZoom3D * max(hitDepth - uNeutral, 0.0);
                finalUV = (finalUV - 0.5) / zoom3d + 0.5;
            }
            // The source photo cannot contain pixels hidden behind a foreground object.  Sampling
            // it directly at a newly exposed coordinate creates a bright copy of the silhouette.
            // Compose the ray-marched foreground over a pre-filled background instead.
            vec4 foreground = texture2D(image, finalUV);
            vec4 background = texture2D(uInpaint, finalUV);
            float foregroundAlpha = texture2D(uForegroundMask, finalUV).a;
            gl_FragColor = mix(background, foreground, foregroundAlpha);
        }
    """
}
