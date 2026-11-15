#pragma once

#include "AdaptiveMeshBuilder.h"
#include <cstdint>
#include <vector>

namespace spatial {

struct SpringEdge {
    uint16_t a;
    uint16_t b;
    float stiffnessWeight;
};

struct PhysicsNode {
    float posX = 0.0f;
    float posY = 0.0f;
    float velX = 0.0f;
    float velY = 0.0f;
    float forceX = 0.0f;
    float forceY = 0.0f;
    float anchorX = 0.0f;
    float anchorY = 0.0f;
    float curvedDepth = 0.5f;
};

class SpatialModelEngine {
public:
    SpatialModelEngine() = default;

    void initialize(
        const uint8_t* depthBytes,
        int depthWidth,
        int depthHeight,
        float imageAspect,
        int gridSize = 96
    );

    int getVertexCount() const { return static_cast<int>(mesh_.vertices.size()); }
    int getIndexCount() const { return static_cast<int>(mesh_.indices.size()); }
    const uint16_t* getIndices() const { return mesh_.indices.data(); }

    void setProperties(
        float intensity,
        float depthCenter,
        bool rInverted,
        float zoom,
        float zoom3D,
        float elasticity
    );

    void updateRotate(float* outVertices, float tiltX, float tiltY);
    void updateShake(float* outVertices, float dt, float tiltX, float tiltY);
    void updateAnimated(float* outVertices, float progress, int patternId);
    void updateZoomPan(float* outVertices, float zoom, float panX, float panY);

    void applyImpulse(float impulseX, float impulseY);
    void resetPhysics();

private:
    AdaptiveMeshData mesh_;
    std::vector<SpringEdge> springs_;
    std::vector<PhysicsNode> nodes_;

    float intensity_ = 0.8f;
    float depthCenter_ = 0.5f;
    bool rInverted_ = false;
    float zoom_ = 0.0f;
    float zoom3D_ = 0.0f;
    float elasticity_ = 0.5f;

    float depthScale_ = 1.0f;
    bool depthDirty_ = true;

    void rebuildSprings();
    void updateCurvedDepths();
    void writeParallaxVertices(
        float* outVertices,
        float shiftX,
        float shiftY,
        float effectiveDepthCenter,
        float zoomOverride,
        float panX,
        float panY
    );
};

} // namespace spatial
