# Bàn giao hiện tại

- Ngày: 2026-11-02 (Asia/Saigon).
- Phạm vi: `SENSOR-005` ("Tinh chỉnh tăng nhẹ tốc độ tự hồi tâm của parallax cảm biến") `done`, chưa commit. Trước đó `SENSOR-004`, `SENSOR-003`, `SENSOR-002`, `SENSOR-001` (parallax nghiêng và lắc), `TABS-003` (độ đàn hồi Tab 2), `MOTION-002` (bỏ none), `MOTION-001` (triangular_cw và square_cw), `LOADING-001` (d02a0a2), `TABS-002` (92d7820), `BGUI-001` (dc3adf0), `INTENSITY-001`/`EDGES-002` (7980a5c) `done`. Không còn mục `in_progress`.
- Thực hiện:
  - Tinh chỉnh tăng nhẹ tốc độ tự hồi tâm trong `SpatialGLRenderer.kt`:
    - Nâng `recenterRate` lên `1.95f`.
    - Đặt `restingBoost = 1.65f` khi cảm biến giữ ổn định (`motionSpeed < 0.32f`), cho tốc độ hồi tâm hiệu dụng khoảng `~3.22f/s`.
    - Điều chỉnh trần `recenterAlpha` lên `0.22f`.
    - Thời gian ảnh tự động trôi về trung tâm khi dừng nghiêng máy đạt khoảng **~0.55 – 0.60 giây**, phản hồi nhanh nhẹn và tự nhiên theo đúng yêu cầu.
- Kết quả kiểm tra:
  - Verify pass `.harness/runs/20261102T075104610Z-4c2c6e8c/result.json`: 53/53 JVM unit tests pass, Android Lint `lintDebug`, `assembleDebug`.
  - Device pass `.harness/runs/20261102T075446513Z-c1db39a2/result.json` trên emulator-5554 (PGT-AN10 Android 9): 24/24 connected instrumentation tests passed, 0 failed, 0 skipped.
- Chưa xác minh:
  - Trải nghiệm trực tiếp trên tay máy thật Xiaomi Mi MIX 2.
- Bước tiếp theo:
  - Trải nghiệm trực quan thao tác trên máy thật để xác nhận nhịp độ hồi tâm vừa ý.
