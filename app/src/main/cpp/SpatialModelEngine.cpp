#include "SpatialModelEngine.h"
#include "PatternAnimations.h"
#include <algorithm>
#include <cmath>

namespace spatial {

namespace {

inline float computeDepthScale(float intensity) {
    float clamped = std::clamp(intensity, 0.0f, 1.25f);
    return 0.55f + 0.55f * clamped;
}

} // namespace

void SpatialModelEngine::initialize(
    const uint8_t* depthBytes,
    int depthWidth,
    int depthHeight,
    float imageAspect,
    int gridSize
) {
    mesh_ = AdaptiveMeshBuilder::build(depthBytes, depthWidth, depthHeight, imageAspect, gridSize);

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
    if (intensity_ != intensity || rInverted_ != rInverted) {
        intensity_ = std::clamp(intensity, 0.0f, 1.25f);
        rInverted_ = rInverted;
        depthDirty_ = true;
    }
    depthCenter_ = std::clamp(depthCenter, 0.0f, 1.0f);
    zoom_ = std::clamp(zoom, -1.0f, 1.0f);
    zoom3D_ = std::clamp(zoom3D, 0.0f, 1.0f);
    elasticity_ = std::clamp(elasticity, 0.0f, 1.0f);
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

void SpatialModelEngine::writeParallaxVertices(
    float* outVertices,
    float shiftX,
    float shiftY,
    float effectiveDepthCenter,
    float zoomOverride,
    float panX,
    float panY
) {
    if (!outVertices) return;
    updateCurvedDepths();

    const float effZoom = (zoomOverride < 0.0f) ? (zoomOverride * 0.5f) : zoomOverride;
    const float baseScale = 1.0f + effZoom * 0.45f;
    const float z3dActive = (std::fabs(zoomOverride) >= 0.1f) ? zoom3D_ : 0.0f;
    // Soft-capped amplitude by intensity so high intensity increases parallax without warping
    const float normInt = std::clamp(intensity_ / 1.25f, 0.0f, 1.0f);
    const float ampFactor = 0.18f + 0.82f * std::sqrt(normInt);
    const float maxShift = 0.042f * ampFactor;

    const size_t n = mesh_.vertices.size();
    for (size_t i = 0; i < n; ++i) {
        const MeshVertex& mv = mesh_.vertices[i];
        const float cd = nodes_[i].curvedDepth;
        const float s0 = cd - effectiveDepthCenter;

        // Parallax shift (edges stay anchored with edgeWeight == 0)
        float px = mv.baseX + shiftX * s0 * maxShift * mv.edgeWeight;
        float py = mv.baseY + shiftY * s0 * maxShift * mv.edgeWeight;

        // Continuous zoom + optional 3D depth zoom
        float zZoomScale = baseScale + effZoom * z3dActive * s0 * 0.35f * mv.edgeWeight;
        px = px * zZoomScale + panX;
        py = py * zZoomScale + panY;

        float pz = cd * 0.023f;

        const size_t baseIdx = i * 5;
        outVertices[baseIdx + 0] = px;
        outVertices[baseIdx + 1] = py;
        outVertices[baseIdx + 2] = pz;
        outVertices[baseIdx + 3] = mv.u;
        outVertices[baseIdx + 4] = mv.v;
    }
}

void SpatialModelEngine::updateRotate(float* outVertices, float tiltX, float tiltY) {
    float sx = std::clamp(tiltX, -1.0f, 1.0f);
    float sy = std::clamp(tiltY, -1.0f, 1.0f);
    writeParallaxVertices(outVertices, sx, sy, depthCenter_, zoom_, 0.0f, 0.0f);
}

void SpatialModelEngine::updateAnimated(float* outVertices, float progress, int patternId) {
    float sx = 0.0f, sy = 0.0f;
    PatternAnimations::sample(patternId, progress, sx, sy);
    writeParallaxVertices(outVertices, sx, sy, depthCenter_, zoom_, 0.0f, 0.0f);
}

void SpatialModelEngine::updateZoomPan(float* outVertices, float zoom, float panX, float panY) {
    writeParallaxVertices(outVertices, 0.0f, 0.0f, depthCenter_, zoom, panX, panY);
}

void SpatialModelEngine::applyImpulse(float impulseX, float impulseY) {
    updateCurvedDepths();
    const size_t n = mesh_.vertices.size();
    for (size_t i = 0; i < n; ++i) {
        const MeshVertex& mv = mesh_.vertices[i];
        if (mv.isBorder) continue;
        float factor = (nodes_[i].curvedDepth - depthCenter_) * mv.edgeWeight;
        nodes_[i].velX += impulseX * factor;
        nodes_[i].velY += impulseY * factor;
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

void SpatialModelEngine::updateShake(float* outVertices, float dt, float tiltX, float tiltY) {
    if (!outVertices) return;
    updateCurvedDepths();

    float clampedDt = std::clamp(dt, 0.0005f, 0.04f);
    int subSteps = std::clamp(static_cast<int>(std::ceil(clampedDt / 0.008f)), 1, 4);
    float subDt = clampedDt / static_cast<float>(subSteps);

    const float maxShift = 0.036f * depthScale_;
    const float sx = std::clamp(tiltX, -1.0f, 1.0f);
    const float sy = std::clamp(tiltY, -1.0f, 1.0f);

    // Elasticity [0..1]: higher elasticity -> bouncier (lower damping, lively spring)
    const float kStab = 95.0f - 45.0f * elasticity_;   // 95 -> 50
    const float cDamp = 6.5f - 4.3f * elasticity_;     // 6.5 -> 2.2
    const float kStruct = 28.0f + 24.0f * elasticity_; // neighbor coupling

    const size_t n = mesh_.vertices.size();
    for (size_t i = 0; i < n; ++i) {
        const MeshVertex& mv = mesh_.vertices[i];
        float s0 = nodes_[i].curvedDepth - depthCenter_;
        nodes_[i].anchorX = mv.baseX + sx * s0 * maxShift * mv.edgeWeight;
        nodes_[i].anchorY = mv.baseY + sy * s0 * maxShift * mv.edgeWeight;
    }

    for (int step = 0; step < subSteps; ++step) {
        for (size_t i = 0; i < n; ++i) {
            PhysicsNode& node = nodes_[i];
            if (mesh_.vertices[i].isBorder) {
                node.forceX = 0.0f;
                node.forceY = 0.0f;
                continue;
            }
            // Stabilizer spring pulling toward equilibrium anchor
            node.forceX = -kStab * (node.posX - node.anchorX) - cDamp * node.velX;
            node.forceY = -kStab * (node.posY - node.anchorY) - cDamp * node.velY;
        }

        // Structural springs propagate jelly ripples across adjacent triangles
        for (const SpringEdge& e : springs_) {
            PhysicsNode& na = nodes_[e.a];
            PhysicsNode& nb = nodes_[e.b];
            float dispAx = na.posX - na.anchorX;
            float dispAy = na.posY - na.anchorY;
            float dispBx = nb.posX - nb.anchorX;
            float dispBy = nb.posY - nb.anchorY;

            float fx = kStruct * e.stiffnessWeight * (dispBx - dispAx);
            float fy = kStruct * e.stiffnessWeight * (dispBy - dispAy);

            if (!mesh_.vertices[e.a].isBorder) {
                na.forceX += fx;
                na.forceY += fy;
            }
            if (!mesh_.vertices[e.b].isBorder) {
                nb.forceX -= fx;
                nb.forceY -= fy;
            }
        }

        for (size_t i = 0; i < n; ++i) {
            if (mesh_.vertices[i].isBorder) {
                nodes_[i].posX = mesh_.vertices[i].baseX;
                nodes_[i].posY = mesh_.vertices[i].baseY;
                nodes_[i].velX = 0.0f;
                nodes_[i].velY = 0.0f;
                continue;
            }
            PhysicsNode& node = nodes_[i];
            node.velX += node.forceX * subDt;
            node.velY += node.forceY * subDt;
            node.posX += node.velX * subDt;
            node.posY += node.velY * subDt;

            // Safety clamp against extreme numerical spikes
            float dx = node.posX - node.anchorX;
            float dy = node.posY - node.anchorY;
            float maxOffset = 0.14f;
            if (dx * dx + dy * dy > maxOffset * maxOffset) {
                float invLen = maxOffset / std::sqrt(dx * dx + dy * dy);
                node.posX = node.anchorX + dx * invLen;
                node.posY = node.anchorY + dy * invLen;
                node.velX *= 0.5f;
                node.velY *= 0.5f;
            }
        }
    }

    const float effZoom = (zoom_ < 0.0f) ? (zoom_ * 0.5f) : zoom_;
    const float baseScale = 1.0f + effZoom * 0.45f;
    const float z3dActive = (std::fabs(zoom_) >= 0.1f) ? zoom3D_ : 0.0f;

    for (size_t i = 0; i < n; ++i) {
        const MeshVertex& mv = mesh_.vertices[i];
        const float cd = nodes_[i].curvedDepth;
        const float s0 = cd - depthCenter_;
        float zZoomScale = baseScale + effZoom * z3dActive * s0 * 0.35f * mv.edgeWeight;

        const size_t baseIdx = i * 5;
        outVertices[baseIdx + 0] = nodes_[i].posX * zZoomScale;
        outVertices[baseIdx + 1] = nodes_[i].posY * zZoomScale;
        outVertices[baseIdx + 2] = cd * 0.023f * depthScale_;
        outVertices[baseIdx + 3] = mv.u;
        outVertices[baseIdx + 4] = mv.v;
    }
}

} // namespace spatial
