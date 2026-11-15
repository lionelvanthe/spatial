#include "PatternAnimations.h"
#include <cmath>
#include <algorithm>

namespace spatial {

namespace {
constexpr float PI = 3.14159265358979323846f;
constexpr float TWO_PI = 2.0f * PI;

inline float wrapProgress(float p) {
    float r = std::fmod(p, 1.0f);
    return (r < 0.0f) ? (r + 1.0f) : r;
}

inline float smoothCos(float t) {
    return 0.5f * (1.0f - std::cos(PI * t));
}

inline void sampleSquareCW(float p, float& outX, float& outY) {
    // 4 edges around [-1, 1] x [-1, 1] clockwise starting from top-left (-1, 1)
    float s = p * 4.0f;
    if (s < 1.0f) {
        float t = smoothCos(s);
        outX = -1.0f + 2.0f * t;
        outY = 1.0f;
    } else if (s < 2.0f) {
        float t = smoothCos(s - 1.0f);
        outX = 1.0f;
        outY = 1.0f - 2.0f * t;
    } else if (s < 3.0f) {
        float t = smoothCos(s - 2.0f);
        outX = 1.0f - 2.0f * t;
        outY = -1.0f;
    } else {
        float t = smoothCos(s - 3.0f);
        outX = -1.0f;
        outY = -1.0f + 2.0f * t;
    }
}

inline void sampleTriangleCW(float p, float& outX, float& outY) {
    // Equilateral triangle vertices: (0, 1), (sqrt(3)/2, -0.5), (-sqrt(3)/2, -0.5)
    constexpr float vx[3] = { 0.0f, 0.8660254f, -0.8660254f };
    constexpr float vy[3] = { 1.0f, -0.5f, -0.5f };
    float s = p * 3.0f;
    int idx = std::min(2, static_cast<int>(s));
    int next = (idx + 1) % 3;
    float t = smoothCos(s - static_cast<float>(idx));
    outX = vx[idx] + (vx[next] - vx[idx]) * t;
    outY = vy[idx] + (vy[next] - vy[idx]) * t;
}

inline float triangleWave4(float p) {
    // 0 -> 1 -> 0 -> -1 -> 0 over [0, 1)
    float s = p * 4.0f;
    if (s < 1.0f) return s;
    if (s < 2.0f) return 2.0f - s;
    if (s < 3.0f) return -(s - 2.0f);
    return s - 4.0f;
}

} // namespace

void PatternAnimations::sample(int patternId, float progress, float& outX, float& outY) {
    float p = wrapProgress(progress);
    float angle = p * TWO_PI;

    switch (static_cast<PatternId>(patternId)) {
        case PatternId::HORIZONTAL:
            outX = std::sin(angle);
            outY = 0.0f;
            break;
        case PatternId::VERTICAL:
            outX = 0.0f;
            outY = std::sin(angle);
            break;
        case PatternId::DIAGONAL_BT:
            outX = std::sin(angle);
            outY = std::sin(angle);
            break;
        case PatternId::DIAGONAL_TB:
            outX = std::sin(angle);
            outY = -std::sin(angle);
            break;
        case PatternId::HALF_CIRCLE_B: {
            // Oscillate along bottom arc
            float theta = PI + PI * 0.5f * (1.0f - std::cos(angle));
            outX = std::cos(theta);
            outY = std::sin(theta);
            break;
        }
        case PatternId::HALF_CIRCLE_T: {
            float theta = PI * 0.5f * (1.0f - std::cos(angle));
            outX = std::cos(theta);
            outY = std::sin(theta);
            break;
        }
        case PatternId::CIRCULAR_CW:
            outX = std::sin(angle);
            outY = std::cos(angle);
            break;
        case PatternId::CIRCULAR_CCW:
            outX = -std::sin(angle);
            outY = std::cos(angle);
            break;
        case PatternId::TRIANGULAR_CW:
            sampleTriangleCW(p, outX, outY);
            break;
        case PatternId::SQUARE_CW:
            sampleSquareCW(p, outX, outY);
            break;
        case PatternId::SQUARE_CCW:
            sampleSquareCW(p, outX, outY);
            outX = -outX;
            break;
        case PatternId::EIGHT_HORIZONTAL:
            outX = std::sin(angle);
            outY = 2.0f * std::sin(angle) * std::cos(angle);
            break;
        case PatternId::EIGHT_VERTICAL:
            outX = 2.0f * std::sin(angle) * std::cos(angle);
            outY = std::sin(angle);
            break;
        case PatternId::SPIRAL_CW: {
            // 4 revolutions (8*PI) with envelope 0 -> 1 -> 0
            float env = 0.5f * (1.0f - std::cos(angle));
            float spiralAngle = p * 4.0f * TWO_PI;
            outX = env * std::sin(spiralAngle);
            outY = env * std::cos(spiralAngle);
            break;
        }
        case PatternId::HORIZONTAL_RAPID:
            outX = triangleWave4(wrapProgress(p * 2.0f));
            outY = 0.0f;
            break;
        case PatternId::VERTICAL_RAPID:
            outX = 0.0f;
            outY = triangleWave4(wrapProgress(p * 2.0f));
            break;
        case PatternId::NONE:
        default:
            outX = 0.0f;
            outY = 0.0f;
            break;
    }
}

} // namespace spatial
