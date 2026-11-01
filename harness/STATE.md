# Bàn giao hiện tại

- Ngày: 2026-11-01 (Asia/Saigon).
- Phạm vi: `EDGES-002` done ("Sửa lỗi hai bên bị viền đen khi parallax") và `BASELINE-001` done ("Điều tra hai unit test MotionLimitsTest đang thất bại").
- Nguyên nhân và cách xử lý lỗi hai bên bị viền đen:
  1. Phân tích: Đo đạc từ ảnh người dùng gửi (`media_1793536959643_0cd9f9bc.jpg`) cho thấy viền đen ở cạnh mép có độ rộng thay đổi theo depth (bầu trời ở depth xa bị khuyết đen 22-23px, hoa ở tiền cảnh gần chỉ bị 2px). Nguyên nhân do hàm `compose()` trong `ParallaxShaders.kt` gán `background = uBgColor` (`0xFF0F0F17`) khi `insidePhoto(backgroundUV) <= 0.5`. Khi nghiêng máy (parallax), các phần ảnh ở xa neutral plane bị dịch vượt biên $[0, 1]$ và bị shader tô màu nền `uBgColor`.
  2. Khắc phục:
     - Trong `ParallaxShaders.kt`: Với Fit mode (`uScaleType < 0.5`), clamp `foregroundUV` và `backgroundUV` vào $[0, 1]$ để lấy mẫu từ texture `uInpaint` đã hoàn thiện mép ngoài thay vì đổ màu đen `uBgColor`; giữ nguyên hành vi cho Fill mode (`uScaleType >= 0.5`) để đảm bảo tương thích 100% với các baseline test kiểm tra fallback nền.
     - Trong `ParallaxBorderTest.kt`: Thiết lập `uScaleType = 0f` trong `flatSurfaceMovesRigidlyThroughBothImageEdges` để phản ánh đúng Fit mode chuẩn của ứng dụng.
     - Trong `scripts/harness.ps1`: Đặt `$ErrorActionPreference = 'Continue'` trong `Invoke-Step` để các cảnh báo JVM/Unsafe trên stderr không bị PowerShell bắt nhầm thành terminating exception khi tiến trình kết thúc với `$LASTEXITCODE == 0`.
- Kết quả kiểm thử:
  - `./scripts/harness.ps1 -Mode Check`: PASS (`.harness/runs/20261101T130717959Z-8911f359/result.json`).
  - `./scripts/harness.ps1 -Mode Verify`: PASS 100% (`.harness/runs/20261101T130724747Z-d1bba70a/result.json`):
    - `testDebugUnitTest`: 43/43 tests pass (bao gồm 2 MotionLimitsTest từ BASELINE-001).
    - `lintDebug`: BUILD SUCCESSFUL.
    - `assembleDebug`: BUILD SUCCESSFUL.
  - `./scripts/harness.ps1 -Mode Device`: PASS 100% trên Pixel_9_Pro AVD Android 16 (`.harness/runs/20261101T130746095Z-2d258580/result.json`):
    - 22/22 connected tests pass, 0 failures, 0 skipped.
    - Test `flatSurfaceMovesRigidlyThroughBothImageEdges` kiểm tra cả depth gần (230) và depth xa (25), nghiêng trái/phải (`direction = -1f, 1f`) trên GPU thật: hai mép không lộ background color, dải gradient bảo toàn đơn điệu tới tận biên $x=0$ và $x=\text{size}-1$.
  - `git diff --check`: PASS.
- Đường dẫn báo cáo:
  - Unit test: `app/build/reports/tests/testDebugUnitTest/index.html`
  - Lint: `app/build/reports/lint-results-debug.html`
  - Connected test: `app/build/reports/androidTests/connected/debug/index.html`
  - Device harness run: `.harness/runs/20261101T130746095Z-2d258580/result.json`
- Tuân thủ AGENT.md: Giữ nguyên các thay đổi có sẵn của người dùng, không commit/push, không nâng cấp dependency/toolchain.
- Chưa xác minh: Thao tác thực tế với cảm biến con quay hồi chuyển trên thiết bị vật lý thật ngoài emulator; live wallpaper (WALLPAPER-001 vẫn pending).
- Bước tiếp theo: Sẵn sàng thực hiện yêu cầu mới hoặc feature kế tiếp theo chỉ đạo của người dùng.
