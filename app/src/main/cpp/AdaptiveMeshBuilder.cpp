#include "AdaptiveMeshBuilder.h"
#include <algorithm>
#include <cmath>
#include <vector>

namespace spatial {

namespace {

inline void addTri(std::vector<uint32_t>& out, uint32_t a, uint32_t b, uint32_t c) {
    out.push_back(a);
    out.push_back(b);
    out.push_back(c);
}

// Tessellate a square [gx, gx+step] x [gy, gy+step] with optional midpoints on L/R/T/B edges
void tessellateSquareWithMidpoints(
    std::vector<uint32_t>& outIndices,
    int stride,
    int gx,
    int gy,
    int step,
    bool midL,
    bool midR,
    bool midT,
    bool midB
) {
    auto vid = [stride](int x, int y) -> uint32_t {
        return static_cast<uint32_t>(y * stride + x);
    };

    uint32_t tl = vid(gx, gy);
    uint32_t tr = vid(gx + step, gy);
    uint32_t bl = vid(gx, gy + step);
    uint32_t br = vid(gx + step, gy + step);

    if (!midL && !midR && !midT && !midB) {
        addTri(outIndices, tl, bl, tr);
        addTri(outIndices, tr, bl, br);
        return;
    }

    int half = step / 2;
    uint32_t center = vid(gx + half, gy + half);

    // Top edge
    if (midT) {
        uint32_t mt = vid(gx + half, gy);
        addTri(outIndices, tl, center, mt);
        addTri(outIndices, mt, center, tr);
    } else {
        addTri(outIndices, tl, center, tr);
    }

    // Right edge
    if (midR) {
        uint32_t mr = vid(gx + step, gy + half);
        addTri(outIndices, tr, center, mr);
        addTri(outIndices, mr, center, br);
    } else {
        addTri(outIndices, tr, center, br);
    }

    // Bottom edge
    if (midB) {
        uint32_t mb = vid(gx + half, gy + step);
        addTri(outIndices, br, center, mb);
        addTri(outIndices, mb, center, bl);
    } else {
        addTri(outIndices, br, center, bl);
    }

    // Left edge
    if (midL) {
        uint32_t ml = vid(gx, gy + half);
        addTri(outIndices, bl, center, ml);
        addTri(outIndices, ml, center, tl);
    } else {
        addTri(outIndices, bl, center, tl);
    }
}

} // namespace

AdaptiveMeshData AdaptiveMeshBuilder::build(
    const uint8_t* depthBytes,
    int depthWidth,
    int depthHeight,
    float imageAspect,
    int gridSize
) {
    if (gridSize < 8) gridSize = 8;
    // Ensure multiple of 4
    gridSize = (gridSize / 4) * 4;
    if (gridSize > 128) gridSize = 128;

    const int stride = gridSize + 1;
    const int totalGridVerts = stride * stride;

    // 1. Sample depth at full grid resolution matching PreInitializationModelData::calculateDepthValue
    // Window half-size from initializeVertices (0x21980): 0.0185 * 0.5 = 0.00925 of image size
    std::vector<float> smoothDepth(totalGridVerts, 0.5f);
    if (depthBytes && depthWidth > 0 && depthHeight > 0) {
        const float cellW = static_cast<float>(depthWidth - 1) / static_cast<float>(gridSize);
        const float cellH = static_cast<float>(depthHeight - 1) / static_cast<float>(gridSize);
        const float halfWinX = std::max(1.0f, 0.00925f * static_cast<float>(depthWidth));
        const float halfWinY = std::max(1.0f, 0.00925f * static_cast<float>(depthHeight));

        for (int gy = 0; gy <= gridSize; ++gy) {
            float cy = static_cast<float>(gy) * cellH;
            int y0 = std::clamp(static_cast<int>(std::lround(cy - halfWinY)), 0, depthHeight - 1);
            int y1 = std::clamp(static_cast<int>(std::lround(cy + halfWinY)), 0, depthHeight - 1);
            if (y1 < y0) y1 = y0;
            int ciy = std::clamp(static_cast<int>(std::lround(cy)), 0, depthHeight - 1);

            for (int gx = 0; gx <= gridSize; ++gx) {
                float cx = static_cast<float>(gx) * cellW;
                int x0 = std::clamp(static_cast<int>(std::lround(cx - halfWinX)), 0, depthWidth - 1);
                int x1 = std::clamp(static_cast<int>(std::lround(cx + halfWinX)), 0, depthWidth - 1);
                if (x1 < x0) x1 = x0;
                int cix = std::clamp(static_cast<int>(std::lround(cx)), 0, depthWidth - 1);

                float sum = 0.0f;
                float maxVal = 0.0f;
                int count = 0;
                for (int py = y0; py <= y1; ++py) {
                    const uint8_t* row = depthBytes + py * depthWidth;
                    for (int px = x0; px <= x1; ++px) {
                        float d = static_cast<float>(row[px]) / 255.0f;
                        sum += d;
                        if (d > maxVal) maxVal = d;
                        ++count;
                    }
                }
                float avg = (count > 0) ? (sum / static_cast<float>(count)) : 0.5f;
                float center = static_cast<float>(depthBytes[ciy * depthWidth + cix]) / 255.0f;
                float val = (avg < center) ? (0.4f * avg + 0.6f * maxVal) : (0.7f * avg + 0.3f * maxVal);
                smoothDepth[gy * stride + gx] = std::clamp(val, 0.0f, 1.0f);
            }
        }
    }

    // 2. Compute block subdivision level on 4x4 coarse blocks (1 = fine 1x1, 2 = medium 2x2, 4 = coarse 4x4)
    const int coarseW = gridSize / 4;
    const int coarseH = gridSize / 4;
    std::vector<int> blockLevel(coarseW * coarseH, 4);

    constexpr float THRESHOLD_4 = 0.004f;
    constexpr float THRESHOLD_2 = 0.012f;

    for (int by = 0; by < coarseH; ++by) {
        for (int bx = 0; bx < coarseW; ++bx) {
            float minD = 1.0f, maxD = 0.0f;
            int gx0 = bx * 4;
            int gy0 = by * 4;
            for (int dy = 0; dy <= 4; ++dy) {
                for (int dx = 0; dx <= 4; ++dx) {
                    float d = smoothDepth[(gy0 + dy) * stride + (gx0 + dx)];
                    minD = std::min(minD, d);
                    maxD = std::max(maxD, d);
                }
            }
            float range = maxD - minD;
            if (range > THRESHOLD_2) {
                blockLevel[by * coarseW + bx] = 1;
            } else if (range > THRESHOLD_4) {
                blockLevel[by * coarseW + bx] = 2;
            } else {
                blockLevel[by * coarseW + bx] = 4;
            }
        }
    }

    // Enforce 2:1 balance across adjacent coarse blocks (no 4 next to 1)
    bool changed = true;
    while (changed) {
        changed = false;
        for (int by = 0; by < coarseH; ++by) {
            for (int bx = 0; bx < coarseW; ++bx) {
                int& lvl = blockLevel[by * coarseW + bx];
                if (lvl == 4) {
                    bool adjFine =
                        (bx > 0 && blockLevel[by * coarseW + (bx - 1)] == 1) ||
                        (bx + 1 < coarseW && blockLevel[by * coarseW + (bx + 1)] == 1) ||
                        (by > 0 && blockLevel[(by - 1) * coarseW + bx] == 1) ||
                        (by + 1 < coarseH && blockLevel[(by + 1) * coarseW + bx] == 1);
                    if (adjFine) {
                        lvl = 2;
                        changed = true;
                    }
                }
            }
        }
    }

    // 3. Emit triangles
    std::vector<uint32_t> rawIndices;
    rawIndices.reserve(gridSize * gridSize * 6);

    auto getBlockLevel = [&](int bx, int by) -> int {
        if (bx < 0 || bx >= coarseW || by < 0 || by >= coarseH) return 4;
        return blockLevel[by * coarseW + bx];
    };

    for (int by = 0; by < coarseH; ++by) {
        for (int bx = 0; bx < coarseW; ++bx) {
            int lvl = blockLevel[by * coarseW + bx];
            int gx0 = bx * 4;
            int gy0 = by * 4;

            if (lvl == 4) {
                bool midL = getBlockLevel(bx - 1, by) < 4;
                bool midR = getBlockLevel(bx + 1, by) < 4;
                bool midT = getBlockLevel(bx, by - 1) < 4;
                bool midB = getBlockLevel(bx, by + 1) < 4;
                tessellateSquareWithMidpoints(rawIndices, stride, gx0, gy0, 4, midL, midR, midT, midB);
            } else if (lvl == 2) {
                bool fineL = getBlockLevel(bx - 1, by) == 1;
                bool fineR = getBlockLevel(bx + 1, by) == 1;
                bool fineT = getBlockLevel(bx, by - 1) == 1;
                bool fineB = getBlockLevel(bx, by + 1) == 1;
                for (int sy = 0; sy < 2; ++sy) {
                    for (int sx = 0; sx < 2; ++sx) {
                        tessellateSquareWithMidpoints(
                            rawIndices,
                            stride,
                            gx0 + sx * 2,
                            gy0 + sy * 2,
                            2,
                            (sx == 0) && fineL,
                            (sx == 1) && fineR,
                            (sy == 0) && fineT,
                            (sy == 1) && fineB
                        );
                    }
                }
            } else {
                for (int sy = 0; sy < 4; ++sy) {
                    for (int sx = 0; sx < 4; ++sx) {
                        tessellateSquareWithMidpoints(
                            rawIndices,
                            stride,
                            gx0 + sx,
                            gy0 + sy,
                            1,
                            false, false, false, false
                        );
                    }
                }
            }
        }
    }

    // 4. Compact used vertices and remap indices
    std::vector<int32_t> remap(totalGridVerts, -1);
    AdaptiveMeshData result;
    result.gridSize = gridSize;
    result.indices.reserve(rawIndices.size());

    const float halfW = (imageAspect > 0.0f) ? imageAspect : 1.0f;
    const float halfH = 1.0f;

    for (uint32_t idx : rawIndices) {
        if (remap[idx] < 0) {
            int gx = static_cast<int>(idx % stride);
            int gy = static_cast<int>(idx / stride);
            float u = static_cast<float>(gx) / static_cast<float>(gridSize);
            float v = static_cast<float>(gy) / static_cast<float>(gridSize);

            bool isBorder = (gx == 0 || gx == gridSize || gy == 0 || gy == gridSize);
            float edgeWeight = isBorder ? 0.0f : 1.0f;

            MeshVertex mv;
            mv.baseX = (u * 2.0f - 1.0f) * halfW;
            mv.baseY = (1.0f - v * 2.0f) * halfH;
            mv.u = u;
            mv.v = v;
            mv.rawDepth = smoothDepth[idx];
            mv.edgeWeight = edgeWeight;
            mv.isBorder = isBorder;

            remap[idx] = static_cast<int32_t>(result.vertices.size());
            result.vertices.push_back(mv);
        }
        result.indices.push_back(static_cast<uint16_t>(remap[idx]));
    }

    return result;
}

} // namespace spatial
