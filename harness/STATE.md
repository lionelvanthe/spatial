# Bàn giao hiện tại

- Ngày: 2026-11-01 (Asia/Saigon).
- Phạm vi: FIT-001 done, ứng dụng chỉ dùng Fit theo yêu cầu mới, thay thế lựa chọn Fill/Blurred background trước đó.
- Bỏ bộ chọn chế độ trong viewer, giữ màu nền. Default/parse/sanitize/JSON ghi luôn FIT, gồm scene thiếu trường hoặc lưu FILL/CENTER_CROP/BLURRED_BACKGROUND. Enum cũ giữ cho tương thích, không còn là lựa chọn ứng dụng.
- Preview/wallpaper dùng SpatialGLRenderer Fit; legacy fitImage=false vẫn về FIT. Export và shader applyEffects luôn dùng Fit; hệ số .88 giữ như chế độ Fit có sẵn. Zoom thường vẫn hoạt động sau framing Fit; Zoom3D tiếp tục bị bỏ theo ZOOM-002.
- Cập nhật test theo yêu cầu mới: ImageFitModeTest kiểm tra chuẩn hóa Fit/màu nền; persistence scene cũ thành Fit; UI không còn Fill/Blurred background; SurfaceCoverage xác nhận legacy crop request không đổi Fit; MP4 kiểm tra hệ số Fit. Các test shader cấp thấp cho mode cũ giữ làm coverage nội bộ, ứng dụng không chọn các mode đó.
- Init pass `20261101T112705696Z-474a56dd`.
- Verify cuối failed `20261101T112911913Z-58cf6bc2`: 41/43 unit tests pass, hai lỗi nền MotionLimitsTest.nonFiniteValuesFallBackToSafeDefaults và valuesInsideTheRangeAreUntouched. ImageFitModeTest 2/2 pass. Lần Verify đầu `20261101T112817366Z-57afe046` còn 2 test kỳ vọng Fill/Blurred trước yêu cầu mới; đã cập nhật hành vi mong muốn sang Fit, không sửa test nền MotionLimits.
- Device pass `20261101T112918389Z-c535940d`, Pixel_9_Pro AVD Android 16: 20 tests, 0 failed/skipped, gồm UI/GPU/persistence/MP4.
- `.\gradlew.bat :app:lintDebug :app:assembleDebug` pass bản cuối; git diff --check pass.
- Báo cáo: `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/lint-results-debug.html`, `app/build/reports/androidTests/connected/debug/index.html`; result harness trong `.harness/runs/` theo ID trên.
- Chưa kiểm tra trực quan mới, máy thật/cảm biến, live wallpaper hoặc phát video bằng app ngoài trong phiên này; UI đã được thao tác qua instrumentation. Thuật toán inpaint giữ nguyên.
- Giữ thay đổi người dùng `.idea/misc.xml`. Commit và push code cho các tính năng VIEWPORT-001, VIEWPORT-002, ZOOM-001, ZOOM-002, FIT-001 theo yêu cầu người dùng.
- Bước tiếp theo: BASELINE-001 xử lý hai lỗi MotionLimitsTest và chạy Verify toàn bộ; WALLPAPER-001 vẫn pending.
