# Bàn giao hiện tại

- Ngày: 2026-11-01 (Asia/Saigon).
- Phạm vi: TABS-001, đồng nhất khung ảnh và độ zoom của Shake, Rotate, Animations theo xác nhận người dùng; done trong phạm vi sửa lỗi này.
- Renderer dùng chung scene zoom, zoom3D và depthCenter cho ba tab. Chuyển giữa ba tab giữ pinch zoom, vẫn xóa độ lệch chuyển động. ZoomPan và thao tác reset giữ quy tắc riêng.
- Thêm ViewerFraming và 4 test hồi quy. Giữ nguyên thay đổi có sẵn tại .idea/misc.xml; không đổi dependency/assets.
- Init: pass, run `20261101T094619507Z-f263653a`. Lần chạy sandbox trước đó lỗi quyền cache Gradle; chạy lại với quyền truy cập cache thành công.
- Verify bản cuối: failed, run `20261101T094844494Z-32a6b420`; 42 tests, 40 pass, 2 lỗi nền tại MotionLimitsTest.nonFiniteValuesFallBackToSafeDefaults và valuesInsideTheRangeAreUntouched. Lint/build không chạy trong Verify do fail-fast.
- Xác minh riêng bản cuối: `.\gradlew.bat :app:testDebugUnitTest --tests com.example.spatial.ViewerFramingTest :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest` pass; 4 test mới pass, lint và APK build thành công.
- Device: pass trên Pixel_9_Pro AVD Android 16, run `20261101T094703621Z-0931d436`; đã chạy lại connectedDebugAndroidTest thành công sau thay đổi cuối.
- Trực quan: cài APK debug lên emulator sau khi instrumentation gỡ app; mở Cliffside Village với zoom/zoom3D khác 0, chuyển Shake → Rotate → Animations. Cùng khung ảnh/độ zoom cơ sở; Animations vẫn chuyển động riêng. Ảnh tại `.harness/tab-0.png`, `.harness/tab-1.png`, `.harness/tab-2.png`.
- Báo cáo: `app/build/reports/tests/testDebugUnitTest/` (lần chạy riêng 4 test), `app/build/reports/lint-results-debug.html`, `app/build/reports/androidTests/connected/`. Kết quả full Verify được ghi tại `.harness/runs/20261101T094844494Z-32a6b420/`.
- Chưa xác minh: thao tác pinch đa điểm trực tiếp, cảm biến máy thật, wallpaper và export; JVM test không chứng minh chất lượng hình ảnh/cảm biến.
- Bước tiếp theo: BASELINE-001 xử lý hai lỗi MotionLimitsTest rồi chạy lại Verify toàn bộ. WALLPAPER-001 vẫn pending. HARNESS-001 giữ nguyên done.
