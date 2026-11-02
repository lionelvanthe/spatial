# Bàn giao hiện tại

- Ngày: 2026-11-02 (Asia/Saigon).
- Phạm vi: `LOADING-001` ("Hiển thị loading khi chọn ảnh mới thay vì ảnh cũ") `done`, chưa commit. Trước đó `TABS-002` (92d7820), `BGUI-001` (dc3adf0), `INTENSITY-001`/`EDGES-002` (7980a5c) `done`. Không còn mục `in_progress`.
- LOADING-001: `SpatialViewModel.isLoadingScene` + `SpatialGLRenderer.onSceneDrawn`; `MainScreen` che viewport bằng loader tới khi renderer vẽ xong frame đầu của ảnh mới. Test mới `SceneLoadingTest`. Verify pass `.harness/runs/20261102T045454966Z-abb1f285/result.json`; Device pass `.harness/runs/20261102T050028349Z-136a8c72/result.json` (23/23). Chưa xem trực quan.
- Lưu ý: `ZoomExportTest` treo không ổn định trên emulator Android 9 này (đã gặp 2 lần), chạy lại thì pass.
- TABS-002: gỡ tab ZoomPan trong `MainScreen.kt` (danh sách tab, nhánh index 3, điều kiện `!= 3` ở toolbar/bảng thông số). Code chế độ 3 trong `SpatialGLRenderer`/`ViewerFraming` và drawable `ic_zoom_pan_tab` vẫn còn nhưng không dùng từ UI. Verify pass `.harness/runs/20261102T045038593Z-b2745729/result.json`; Device pass `.harness/runs/20261102T045101442Z-9f8453ab/result.json` (22/22). Chưa xem trực quan.
- BGUI-001: gỡ hàng nút Background (Dark/Black/White/Blue) trong `MainScreen.kt`. Giữ `SpatialSceneConfig.backgroundColor` để đọc scene cũ; scene đã lưu màu khác mặc định vẫn dùng màu đó nhưng không còn UI để đổi. Verify pass `.harness/runs/20261102T044027701Z-89d61602/result.json`; Device pass `.harness/runs/20261102T044109139Z-3652e8ab/result.json` (22/22). Chưa xem trực quan.
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
