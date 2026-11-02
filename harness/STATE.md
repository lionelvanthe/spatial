# Bàn giao hiện tại

- Ngày: 2026-11-02 (Asia/Saigon).
- Phạm vi: `INTENSITY-001` ("Sửa lỗi thay đổi cường độ nhưng không có tác dụng") `done`; `EDGES-002` `done`. Không còn mục `in_progress`.
- Nguyên nhân INTENSITY-001: `intensity` trong `_desc.dat` là tham số Photos 3D thang 0..1.25 (ảnh mẫu 0.42-0.98; native `depthScale = 0.30 + 0.70 * i^3`, xem `PROGRESS.md`). Code kẹp về `MotionLimits.MAX_INTENSITY = 0.15` và render tuyến tính `0.045 + 0.115 * i`, nên slider 0..1.25 gần như vô tác dụng và mọi ảnh mẫu luôn ở max.
- Khắc phục:
  - `MotionLimits`: `MAX_INTENSITY = 1.25`, `DEFAULT_INTENSITY = 0.8`; slider `0..MAX_INTENSITY`; `SpatialViewModel.updateIntensity` sanitize; scene custom dùng `DEFAULT_INTENSITY`.
  - `DepthProjection.depthHeight(i) = 0.06225 * depthScale(i) / depthScale(1.25)`; dùng cho `SpatialGLRenderer` (preview, wallpaper) và `VideoExporter`. Max parallax giữ 0.06225 (mức đã kiểm chứng).
  - Chưa áp đường cong depth native (`curvedDepth`) vào shader; người dùng chọn chỉ dùng depthScale.
- EDGES-002: `ParallaxBorderTest.flatSurfaceMovesRigidlyThroughBothImageEdges` thiếu `uScaleType = 0f` (fixture mặc định Fill) nên fail trên HEAD 36f0f51; đã thêm, không đổi assertion.
- Kết quả:
  - Verify pass `.harness/runs/20261102T043112998Z-e89a9a41/result.json`: 45/45 unit test, lintDebug, assembleDebug.
  - Device pass `.harness/runs/20261102T043152738Z-42b8f73e/result.json` trên PGT-AN10 (Android 9, Adreno 750): 22/22.
  - Lưu ý: một lượt Device trước (`20261102T040207048Z-3ba31590`) treo >10 phút ở `ZoomExportTest` (encoder H.264 phần mềm của emulator); chạy lại thì pass.
- Chưa xác minh: độ mạnh parallax của ảnh mẫu bằng mắt (yếu hơn trước: astronaut ~21%, unicorn ~57% max), so sánh với app gốc, cảm biến máy thật, wallpaper.
- Bước tiếp theo: Xem trực quan; nếu ảnh mẫu quá yếu thì cân nhắc nâng `DepthProjection.MAX_DEPTH_HEIGHT` sau khi kiểm tra mép ảnh, hoặc áp thêm `curvedDepth` vào shader.
