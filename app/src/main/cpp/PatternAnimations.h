#pragma once

namespace spatial {

enum class PatternId : int {
    NONE = 0,
    HORIZONTAL = 1,
    VERTICAL = 2,
    DIAGONAL_BT = 3,
    DIAGONAL_TB = 4,
    HALF_CIRCLE_B = 5,
    HALF_CIRCLE_T = 6,
    CIRCULAR_CW = 7,
    CIRCULAR_CCW = 8,
    TRIANGULAR_CW = 9,
    SQUARE_CW = 10,
    SQUARE_CCW = 11,
    EIGHT_HORIZONTAL = 12,
    EIGHT_VERTICAL = 13,
    SPIRAL_CW = 14,
    HORIZONTAL_RAPID = 15,
    VERTICAL_RAPID = 16
};

class PatternAnimations {
public:
    static void sample(int patternId, float progress, float& outX, float& outY);
};

} // namespace spatial
