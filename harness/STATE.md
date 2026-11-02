# Bàn giao hiện tại

- Ngày: 2026-11-02 (Asia/Saigon).
- Phạm vi: `MOTION-002` ("Bỏ mẫu chuyển động none khỏi danh sách lựa chọn Animations") `done`, chưa commit. Trước đó `MOTION-001` (triangular_cw và square_cw), `LOADING-001` (d02a0a2), `TABS-002` (92d7820), `BGUI-001` (dc3adf0), `INTENSITY-001`/`EDGES-002` (7980a5c) `done`. Không còn mục `in_progress`.
- Thực hiện:
  - Gỡ bỏ `MotionPatternItem("none", "Sensor Only", R.drawable.ic_speed)` khỏi danh sách `ALL_MOTION_PATTERNS` trong `MainScreen.kt`.
  - Thanh chọn mẫu của tab Animations không còn nút `none`/`Sensor Only`.
  - Giữ nguyên logic nội bộ khi người dùng ở Tab 0 (Rotate) và Tab 1 (Shake) đặt pattern là `"none"` (để cảm biến tự do điều khiển); khi người dùng bấm sang Tab 2 (Animations), code tự động fallback sang `"horizontal"` nếu cấu hình đang là `"none"`.
  - Thêm unit test `allMotionPatternsExcludesNone` trong `LoopPlanTest.kt` kiểm tra `ALL_MOTION_PATTERNS` không chứa item nào có id `"none"`.
- Kết quả kiểm tra:
  - Verify pass `.harness/runs/20261102T064124306Z-a89fd6f2/result.json`: 49/49 JVM unit tests pass, Android Lint `lintDebug`, `assembleDebug`.
  - Device pass `.harness/runs/20261102T064238181Z-e2023c9d/result.json` trên emulator-5554 (PGT-AN10 Android 9): 23/23 connected instrumentation tests passed, 0 failed, 0 skipped.
- Chưa xác minh:
  - Quan sát trực quan thanh cuộn mẫu Animations bằng mắt trên màn hình.
- Bước tiếp theo:
  - Kiểm tra trực quan thanh mẫu tab Animations trên thiết bị.
