#include "AdaptiveMeshBuilder.h"
#include <algorithm>
#include <cmath>
#include <vector>

namespace spatial {

namespace {

inline float sampleBilinear(
    const uint8_t* bytes,
    int w,
    int h,
    float u,
    float v
) {
    if (!bytes || w <= 0 || h <= 0) return 0.5f;
    float x = std::clamp(u, 0.0f, 1.0f) * static_cast<float>(w - 1);
    float y = std::clamp(v, 0.0f, 1.0f) * static_cast<float>(h - 1);
    int x0 = static_cast<int>(x);
    int y0 = static_cast<int>(y);
    int x1 = std::min(x0 + 1, w - 1);
    int y1 = std::min(y0 + 1, h - 1);
    float fx = x - static_cast<float>(x0);
    float fy = y - static_cast<float>(y0);

    float d00 = static_cast<float>(bytes[y0 * w + x0]) / 255.0f;
    float d10 = static_cast<float>(bytes[y0 * w + x1]) / 255.0f;
    float d01 = static_cast<float>(bytes[y1 * w + x0]) / 255.0f;
    float d11 = static_cast<float>(bytes[y1 * w + x1]) / 255.0f;

    float d0 = d00 + (d10 - d00) * fx;
    float d1 = d01 + (d11 - d01) * fx;
    return d0 + (d1 - d0) * fy;
}

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

    // 1. Sample depth at full grid resolution
    std::vector<float> gridDepth(totalGridVerts, 0.5f);
    for (int gy = 0; gy <= gridSize; ++gy) {
        float v = static_cast<float>(gy) / static_cast<float>(gridSize);
        for (int gx = 0; gx <= gridSize; ++gx) {
            float u = static_cast<float>(gx) / static_cast<float>(gridSize);
            gridDepth[gy * stride + gx] = sampleBilinear(depthBytes, depthWidth, depthHeight, u, v);
        }
    }

    // Edge-preserving bilateral filter: smooths internal facial/body depth variations
    // so foreground objects move as a rigid volume, while preserving sharp silhouette separation.
    std::vector<float> smoothDepth = gridDepth;
    std::vector<float> tempDepth(totalGridVerts);
    constexpr int BILATERAL_RADIUS = 3;
    constexpr float INV_TWO_SIGMA_S2 = 1.0f / (2.0f * 2.5f * 2.5f);
    constexpr float INV_TWO_SIGMA_R2 = 1.0f / (2.0f * 0.12f * 0.12f);

    for (int pass = 0; pass < 2; ++pass) {
        for (int gy = 0; gy <= gridSize; ++gy) {
            for (int gx = 0; gx <= gridSize; ++gx) {
                float centerD = smoothDepth[gy * stride + gx];
                float wSum = 0.0f;
                float dSum = 0.0f;
                for (int dy = -BILATERAL_RADIUS; dy <= BILATERAL_RADIUS; ++dy) {
                    int ny = std::clamp(gy + dy, 0, gridSize);
                    for (int dx = -BILATERAL_RADIUS; dx <= BILATERAL_RADIUS; ++dx) {
                        int nx = std::clamp(gx + dx, 0, gridSize);
                        float nd = smoothDepth[ny * stride + nx];
                        float dist2 = static_cast<float>(dx * dx + dy * dy);
                        float range2 = (nd - centerD) * (nd - centerD);
                        float w = std::exp(-dist2 * INV_TWO_SIGMA_S2 - range2 * INV_TWO_SIGMA_R2);
                        wSum += w;
                        dSum += nd * w;
                    }
                }
                tempDepth[gy * stride + gx] = (wSum > 1e-6f) ? (dSum / wSum) : centerD;
            }
        }
        smoothDepth.swap(tempDepth);
    }

    // Light single-pass gradient guard at extreme cliffs to avoid tearing
    constexpr float MAX_DEPTH_GRAD = 0.065f;
    tempDepth = smoothDepth;
    for (int gy = 1; gy < gridSize; ++gy) {
        for (int gx = 1; gx < gridSize; ++gx) {
            int idx = gy * stride + gx;
            float c = smoothDepth[idx];
            float avg = 0.25f * (
                smoothDepth[idx - 1] +
                smoothDepth[idx + 1] +
                smoothDepth[idx - stride] +
                smoothDepth[idx + stride]
            );
            float diff = avg - c;
            if (std::fabs(diff) > MAX_DEPTH_GRAD) {
                tempDepth[idx] = c + diff * 0.45f;
            } else {
                tempDepth[idx] = c;
            }
        }
    }
    smoothDepth.swap(tempDepth);

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
            float distEdge = std::min(std::min(u, 1.0f - u), std::min(v, 1.0f - v));
            float edgeWeight = isBorder ? 0.0f : std::clamp(distEdge / 0.025f, 0.0f, 1.0f);

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
