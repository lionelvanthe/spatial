#include "SpatialModelEngine.h"
#include "PatternAnimations.h"
#include <algorithm>
#include <cmath>

namespace spatial {

namespace {

constexpr float TWO_PI = 6.28318530717958647692f;

inline float safeFinite(float v, float fallback) {
    return std::isfinite(v) ? v : fallback;
}

inline float computeInternalIntensity(float uiIntensity) {
    float clamped = std::clamp(safeFinite(uiIntensity, 0.8f), 0.0f, 1.25f);
    return 0.40f + 0.70f * clamped;
}

inline float computeDepthScale(float uiIntensity) {
    float internal = computeInternalIntensity(uiIntensity);
    return 0.30f + 0.70f * internal * internal * internal;
}

} // namespace

void SpatialModelEngine::initialize(
    const uint8_t* depthBytes,
    int depthWidth,
    int depthHeight,
    float imageAspect,
    int gridSize
) {
    float safeAspect = (std::isfinite(imageAspect) && imageAspect > 0.01f) ? imageAspect : 1.0f;
    mesh_ = AdaptiveMeshBuilder::build(depthBytes, depthWidth, depthHeight, safeAspect, gridSize);

    const size_t n = mesh_.vertices.size();
    nodes_.resize(n);
    for (size_t i = 0; i < n; ++i) {
        nodes_[i].posX = mesh_.vertices[i].baseX;
        nodes_[i].posY = mesh_.vertices[i].baseY;
        nodes_[i].anchorX = mesh_.vertices[i].baseX;
        nodes_[i].anchorY = mesh_.vertices[i].baseY;
        nodes_[i].velX = 0.0f;
        nodes_[i].velY = 0.0f;
    }

    rebuildSprings();
    depthDirty_ = true;
    hasLastParallax_ = false;
    updateCurvedDepths();
}

void SpatialModelEngine::rebuildSprings() {
    springs_.clear();
    const size_t triCount = mesh_.indices.size() / 3;
    std::vector<uint32_t> edgeKeys;
    edgeKeys.reserve(triCount * 3);

    auto pushEdge = [&](uint16_t u, uint16_t v) {
        if (u == v) return;
        uint16_t a = std::min(u, v);
        uint16_t b = std::max(u, v);
        edgeKeys.push_back((static_cast<uint32_t>(a) << 16) | static_cast<uint32_t>(b));
    };

    for (size_t i = 0; i + 2 < mesh_.indices.size(); i += 3) {
        uint16_t i0 = mesh_.indices[i];
        uint16_t i1 = mesh_.indices[i + 1];
        uint16_t i2 = mesh_.indices[i + 2];
        pushEdge(i0, i1);
        pushEdge(i1, i2);
        pushEdge(i2, i0);
    }

    std::sort(edgeKeys.begin(), edgeKeys.end());
    edgeKeys.erase(std::unique(edgeKeys.begin(), edgeKeys.end()), edgeKeys.end());

    springs_.reserve(edgeKeys.size());
    for (uint32_t key : edgeKeys) {
        SpringEdge e;
        e.a = static_cast<uint16_t>(key >> 16);
        e.b = static_cast<uint16_t>(key & 0xFFFFu);
        float dDiff = std::fabs(mesh_.vertices[e.a].rawDepth - mesh_.vertices[e.b].rawDepth);
        // Stronger coupling on smooth regions, softer across depth silhouettes
        e.stiffnessWeight = std::clamp(1.0f - dDiff * 2.0f, 0.25f, 1.0f);
        springs_.push_back(e);
    }
}

void SpatialModelEngine::setProperties(
    float intensity,
    float depthCenter,
    bool rInverted,
    float zoom,
    float zoom3D,
    float elasticity
) {
    float safeIntensity = std::clamp(safeFinite(intensity, 0.8f), 0.0f, 1.25f);
    if (intensity_ != safeIntensity || rInverted_ != rInverted) {
        intensity_ = safeIntensity;
        rInverted_ = rInverted;
        depthDirty_ = true;
        hasLastParallax_ = false;
    }
    depthCenter_ = std::clamp(safeFinite(depthCenter, 0.5f), 0.0f, 1.0f);
    zoom_ = std::clamp(safeFinite(zoom, 0.0f), -1.0f, 1.0f);
    zoom3D_ = std::clamp(safeFinite(zoom3D, 0.0f), 0.0f, 1.0f);
    elasticity_ = std::clamp(safeFinite(elasticity, 0.5f), 0.0f, 1.0f);
}

void SpatialModelEngine::updateCurvedDepths() {
    if (!depthDirty_) return;
    depthScale_ = computeDepthScale(intensity_);
    const size_t n = mesh_.vertices.size();
    for (size_t i = 0; i < n; ++i) {
        float d = mesh_.vertices[i].rawDepth;
        if (rInverted_) d = 1.0f - d;
        nodes_[i].curvedDepth = std::pow(std::clamp(d, 0.0f, 1.0f), depthScale_);
    }
    depthDirty_ = false;
}

bool SpatialModelEngine::writeParallaxVertices(
    float* outVertices,
    float shiftX,
    float shiftY,
    float effectiveDepthCenter,
    float zoomOverride,
    float panX,
    float panY
) {
    if (!outVertices) return false;
    updateCurvedDepths();

    const float safeShiftX = std::clamp(safeFinite(shiftX, 0.0f), -1.0f, 1.0f);
    const float safeShiftY = std::clamp(safeFinite(shiftY, 0.0f), -1.0f, 1.0f);
    const float safeCenter = std::clamp(safeFinite(effectiveDepthCenter, 0.5f), 0.0f, 1.0f);
    const float safeZoom = std::clamp(safeFinite(zoomOverride, 0.0f), -1.0f, 1.0f);
    const float safePanX = std::clamp(safeFinite(panX, 0.0f), -2.0f, 2.0f);
    const float safePanY = std::clamp(safeFinite(panY, 0.0f), -2.0f, 2.0f);

    // Dirty-check matching Photos 3D ImageDynamicModel (1e-4 threshold) to skip redundant vertex writes & VBO uploads.
    constexpr float kDirtyEps = 0.0001f;
    if (hasLastParallax_ &&
        lastOutVertices_ == outVertices &&
        lastRInverted_ == rInverted_ &&
        std::fabs(safeShiftX - lastShiftX_) <= kDirtyEps &&
        std::fabs(safeShiftY - lastShiftY_) <= kDirtyEps &&
        std::fabs(safeCenter - lastCenter_) <= kDirtyEps &&
        std::fabs(safeZoom - lastZoom_) <= kDirtyEps &&
        std::fabs(zoom3D_ - lastZoom3D_) <= kDirtyEps &&
        std::fabs(safePanX - lastPanX_) <= kDirtyEps &&
        std::fabs(safePanY - lastPanY_) <= kDirtyEps &&
        std::fabs(intensity_ - lastIntensity_) <= kDirtyEps) {
        return false;
    }

    const float effZoom = (safeZoom < 0.0f) ? (safeZoom * 0.5f) : safeZoom;
    // Exact Photos 3D 1.1.6 proportions from ImageDynamicModel::updateProperties (0x25890) & shiftVertices (0x262a8):
    // baseZ = cd * 0.048f (0.023f scaled 0.95->2.0) prevents subject bulging when changing intensity.
    const float internalIntensity = computeInternalIntensity(intensity_);
    const float maxShift = 0.088f * internalIntensity;
    const float z3d = zoom3D_;
    const float flatZoomTerm = effZoom * (1.0f - z3d);
    const float depthZoomWeight = effZoom * z3d;

    const size_t n = mesh_.vertices.size();
    for (size_t i = 0; i < n; ++i) {
        const MeshVertex& mv = mesh_.vertices[i];
        const float cd = nodes_[i].curvedDepth;
        const float s0 = cd - safeCenter;

        // Clean linear parallax shift: keeps architectural lines straight and preserves
        // exact subject proportions across all intensity/zoom values (edges anchored at border).
        float px = mv.baseX + safeShiftX * s0 * maxShift * mv.edgeWeight + safePanX;
        float py = mv.baseY + safeShiftY * s0 * maxShift * mv.edgeWeight + safePanY;

        // Depth-weighted 3D zoom matching Photos 3D 1.1.6 visual magnification on screen:
        // +15-16% at zoom=+1.0 and -6.5% at zoom=-1.0 (stays inside 1.075x overscan, zero black borders).
        const float depthTerm = (effZoom > 0.0f) ? cd : (1.0f - cd);
        const float zoomDeltaZ = (flatZoomTerm + depthZoomWeight * depthTerm) * 0.26f;
        float pz = cd * 0.048f + zoomDeltaZ;

        const size_t baseIdx = i * 5;
        outVertices[baseIdx + 0] = px;
        outVertices[baseIdx + 1] = py;
        outVertices[baseIdx + 2] = pz;
        outVertices[baseIdx + 3] = mv.u;
        outVertices[baseIdx + 4] = mv.v;
    }

    hasLastParallax_ = true;
    lastOutVertices_ = outVertices;
    lastShiftX_ = safeShiftX;
    lastShiftY_ = safeShiftY;
    lastCenter_ = safeCenter;
    lastZoom_ = safeZoom;
    lastZoom3D_ = zoom3D_;
    lastPanX_ = safePanX;
    lastPanY_ = safePanY;
    lastIntensity_ = intensity_;
    lastRInverted_ = rInverted_;
    return true;
}

bool SpatialModelEngine::updateRotate(float* outVertices, float tiltX, float tiltY) {
    float sx = std::clamp(safeFinite(tiltX, 0.0f), -1.0f, 1.0f);
    float sy = std::clamp(safeFinite(tiltY, 0.0f), -1.0f, 1.0f);
    return writeParallaxVertices(outVertices, sx, sy, depthCenter_, zoom_, 0.0f, 0.0f);
}

bool SpatialModelEngine::updateAnimated(float* outVertices, float progress, int patternId) {
    float safeProgress = safeFinite(progress, 0.0f);
    float p = std::fmod(safeProgress, 1.0f);
    if (p < 0.0f) p += 1.0f;
    // Match Photos 3D 1.1.6 ImageDynamicModel::performAnimatedUpdate (0x260e0):
    // zoom breathes smoothly from 0 -> zoom_ -> 0 over each loop cycle via 0.5 * (1 - cos(2*pi*p)).
    float breathZoom = zoom_ * (0.5f * (1.0f - std::cos(p * TWO_PI)));
    if (patternId == static_cast<int>(PatternId::ZOOM)) {
        float animZoom = std::sin(p * TWO_PI) * 0.25f * std::clamp(intensity_, 0.0f, 1.25f);
        return writeParallaxVertices(outVertices, 0.0f, 0.0f, depthCenter_, breathZoom + animZoom, 0.0f, 0.0f);
    }
    float sx = 0.0f, sy = 0.0f;
    PatternAnimations::sample(patternId, p, sx, sy);
    return writeParallaxVertices(outVertices, sx, sy, depthCenter_, breathZoom, 0.0f, 0.0f);
}

bool SpatialModelEngine::updateZoomPan(float* outVertices, float zoom, float panX, float panY) {
    return writeParallaxVertices(outVertices, 0.0f, 0.0f, depthCenter_, zoom, panX, panY);
}

void SpatialModelEngine::applyImpulse(float impulseX, float impulseY) {
    updateCurvedDepths();
    const float ix = std::clamp(safeFinite(impulseX, 0.0f), -12.0f, 12.0f);
    const float iy = std::clamp(safeFinite(impulseY, 0.0f), -12.0f, 12.0f);
    const float internalIntensity = computeInternalIntensity(intensity_);
    // Match Photos 3D 1.1.6 sk0.p (1 - depthCenter in shake mode) & reSetupNodes (0x25d78):
    // signed accelWeight = 1.45f * internalIntensity * (cd - shakeCenter) makes foreground and
    // background jiggle in opposite directions around the focal depth plane.
    const float shakeCenter = 1.0f - depthCenter_;
    const size_t n = mesh_.vertices.size();
    for (size_t i = 0; i < n; ++i) {
        const MeshVertex& mv = mesh_.vertices[i];
        if (mv.isBorder) continue;
        const float signedDepth = internalIntensity * (nodes_[i].curvedDepth - shakeCenter);
        const float accelWeight = signedDepth * 1.45f * mv.edgeWeight * 0.045f;
        nodes_[i].velX += ix * accelWeight;
        nodes_[i].velY += iy * accelWeight;
    }
}

void SpatialModelEngine::resetPhysics() {
    const size_t n = mesh_.vertices.size();
    for (size_t i = 0; i < n; ++i) {
        nodes_[i].posX = mesh_.vertices[i].baseX;
        nodes_[i].posY = mesh_.vertices[i].baseY;
        nodes_[i].velX = 0.0f;
        nodes_[i].velY = 0.0f;
        nodes_[i].forceX = 0.0f;
        nodes_[i].forceY = 0.0f;
    }
}

void SpatialModelEngine::updateShake(float* outVertices, float dt, float dragX, float dragY, bool isTouching) {
    if (!outVertices) return;
    updateCurvedDepths();

    const float internalIntensity = computeInternalIntensity(intensity_);
    const float shakeCenter = 1.0f - depthCenter_;
    const size_t n = mesh_.vertices.size();

    if (isTouching) {
        // Match Photos 3D 1.1.6 updateDynamicNative (0x28a2c..0x28a58):
        // while touching, shift vertices directly with finger drag so releasing springs them back like jelly.
        const float sx = std::clamp(safeFinite(dragX, 0.0f), -1.0f, 1.0f);
        const float sy = std::clamp(safeFinite(dragY, 0.0f), -1.0f, 1.0f);
        const float maxShift = 0.088f * internalIntensity;
        for (size_t i = 0; i < n; ++i) {
            const MeshVertex& mv = mesh_.vertices[i];
            const float cd = nodes_[i].curvedDepth;
            const float s0 = cd - shakeCenter;
            const float px = mv.baseX + sx * s0 * maxShift * mv.edgeWeight;
            const float py = mv.baseY + sy * s0 * maxShift * mv.edgeWeight;
            nodes_[i].posX = px;
            nodes_[i].posY = py;
            nodes_[i].velX = 0.0f;
            nodes_[i].velY = 0.0f;

            const size_t baseIdx = i * 5;
            outVertices[baseIdx + 0] = px;
            outVertices[baseIdx + 1] = py;
            outVertices[baseIdx + 2] = cd * 0.048f;
            outVertices[baseIdx + 3] = mv.u;
            outVertices[baseIdx + 4] = mv.v;
        }
        hasLastParallax_ = false;
        return;
    }

    // Exact Photos 3D 1.1.6 ImageDynamicModel::performUpdateStep (0x25468) + reSetupNodes (0x25d04):
    // 1 native physics tick = 1/30s with mass = 20 + 10*|sd|, kStab = 8 + 1.5*(1 - |sd|),
    // kStruct = 3 + 1.5*(1 - |cdA - cdB|), and per-tick damping 0.85^h anchored at basePos.
    const float clampedDt = std::clamp(safeFinite(dt, 0.016f), 0.0005f, 0.04f);
    const int subSteps = std::clamp(static_cast<int>(std::ceil(clampedDt / 0.0167f)), 1, 3);
    const float h = (clampedDt * 30.0f) / static_cast<float>(subSteps);
    const float dampFactor = std::pow(0.85f, h);

    for (int step = 0; step < subSteps; ++step) {
        for (size_t i = 0; i < n; ++i) {
            PhysicsNode& node = nodes_[i];
            const MeshVertex& mv = mesh_.vertices[i];
            if (mv.isBorder) {
                node.forceX = 0.0f;
                node.forceY = 0.0f;
                continue;
            }
            const float absDepth = std::min(std::fabs(internalIntensity * (node.curvedDepth - shakeCenter)), 1.0f);
            const float kStab = 8.0f + (1.0f - absDepth) * 1.5f;
            node.forceX = kStab * (mv.baseX - node.posX);
            node.forceY = kStab * (mv.baseY - node.posY);
        }

        for (const SpringEdge& e : springs_) {
            PhysicsNode& na = nodes_[e.a];
            PhysicsNode& nb = nodes_[e.b];
            const MeshVertex& mva = mesh_.vertices[e.a];
            const MeshVertex& mvb = mesh_.vertices[e.b];
            const float dispAx = na.posX - mva.baseX;
            const float dispAy = na.posY - mva.baseY;
            const float dispBx = nb.posX - mvb.baseX;
            const float dispBy = nb.posY - mvb.baseY;

            const float kStruct = 3.0f + (1.0f - std::fabs(na.curvedDepth - nb.curvedDepth)) * 1.5f;
            const float fx = kStruct * (dispBx - dispAx);
            const float fy = kStruct * (dispBy - dispAy);

            if (!mva.isBorder) {
                na.forceX += fx;
                na.forceY += fy;
            }
            if (!mvb.isBorder) {
                nb.forceX -= fx;
                nb.forceY -= fy;
            }
        }

        for (size_t i = 0; i < n; ++i) {
            const MeshVertex& mv = mesh_.vertices[i];
            if (mv.isBorder) {
                nodes_[i].posX = mv.baseX;
                nodes_[i].posY = mv.baseY;
                nodes_[i].velX = 0.0f;
                nodes_[i].velY = 0.0f;
                continue;
            }
            PhysicsNode& node = nodes_[i];
            const float absDepth = std::fabs(internalIntensity * (node.curvedDepth - shakeCenter));
            const float mass = 20.0f + absDepth * 10.0f;

            node.velX += (node.forceX / mass) * h;
            node.velY += (node.forceY / mass) * h;
            node.posX += node.velX * h;
            node.posY += node.velY * h;
            node.velX *= dampFactor;
            node.velY *= dampFactor;

            // Safety clamp against extreme numerical spikes
            const float dx = node.posX - mv.baseX;
            const float dy = node.posY - mv.baseY;
            constexpr float maxOffset = 0.16f;
            if (dx * dx + dy * dy > maxOffset * maxOffset) {
                const float invLen = maxOffset / std::sqrt(dx * dx + dy * dy);
                node.posX = mv.baseX + dx * invLen;
                node.posY = mv.baseY + dy * invLen;
                node.velX *= 0.5f;
                node.velY *= 0.5f;
            }
        }
    }

    for (size_t i = 0; i < n; ++i) {
        const MeshVertex& mv = mesh_.vertices[i];
        const size_t baseIdx = i * 5;
        outVertices[baseIdx + 0] = nodes_[i].posX;
        outVertices[baseIdx + 1] = nodes_[i].posY;
        outVertices[baseIdx + 2] = nodes_[i].curvedDepth * 0.048f;
        outVertices[baseIdx + 3] = mv.u;
        outVertices[baseIdx + 4] = mv.v;
    }
    hasLastParallax_ = false;
}

} // namespace spatial
