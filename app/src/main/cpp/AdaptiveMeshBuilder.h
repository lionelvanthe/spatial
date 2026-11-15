#pragma once

#include <cstdint>
#include <vector>

namespace spatial {

struct MeshVertex {
    float baseX;
    float baseY;
    float u;
    float v;
    float rawDepth;
    float edgeWeight;
    bool isBorder;
};

struct AdaptiveMeshData {
    std::vector<MeshVertex> vertices;
    std::vector<uint16_t> indices;
    int gridSize = 0;
};

class AdaptiveMeshBuilder {
public:
    // Builds a watertight adaptive triangle mesh from a grayscale depth buffer [0..255].
    // gridSize must be a multiple of 4 (e.g. 96).
    static AdaptiveMeshData build(
        const uint8_t* depthBytes,
        int depthWidth,
        int depthHeight,
        float imageAspect,
        int gridSize = 96
    );
};

} // namespace spatial
