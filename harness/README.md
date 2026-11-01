# Harness cho Spatial

Thiết kế dựa trên [Learn Harness Engineering](https://github.com/walkinglabs/learn-harness-engineering): instructions, state, verification, scope và session lifecycle. Harness này điều phối quy trình coding agent trong repository; không thêm agent runtime vào ứng dụng.

## Sử dụng

Chạy từ PowerShell (Windows hoặc PowerShell 7 trên Linux/macOS):

```powershell
./scripts/harness.ps1 -Mode Init
./scripts/harness.ps1 -Mode Check
./scripts/harness.ps1 -Mode Verify
./scripts/harness.ps1 -Mode Device
```

`Init` kiểm tra cấu trúc, trạng thái feature, Java, SDK và Gradle Wrapper bằng `--version`. Gradle có thể tải distribution/JVM theo cấu hình hiện có; script không tự cài SDK, sửa PATH hoặc chấp nhận license. Daemon dùng Java 25 theo `gradle/gradle-daemon-jvm.properties`, khác source compatibility Java 11. SDK cần platform 37 và build tools phù hợp. Đọc phiên bản thực tế từ các file Gradle thay vì thay đổi phiên bản để né lỗi môi trường.

`Check` kiểm tra cấu trúc và trạng thái, không cần SDK. `Verify` chạy lần lượt `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, dừng khi lỗi. `Device` yêu cầu đúng một thiết bị adb ở trạng thái `device`, rồi chạy `:app:connectedDebugAndroidTest` (cài APK test/app). Nếu nhiều thiết bị, đặt `ANDROID_SERIAL` để chọn một thiết bị; script truyền cùng lựa chọn cho Gradle.

Mỗi lần chạy ghi log và `result.json` vào `.harness/runs/<run>/`; kết quả có mode, task, exit code và thời gian UTC. Chỉ `passed` khi toàn bộ bước của mode thành công. Báo cáo Gradle: `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/lint-results-debug.html`, `app/build/reports/androidTests/connected/`. Output APK: `app/build/outputs/apk/debug/`. Không commit các output này; nếu cần bằng chứng lâu dài, tóm tắt lệnh/kết quả/ngày trong feature và STATE hoặc dẫn tới CI artifact đã lưu.

## Chọn kiểm tra

| Thay đổi | Kiểm tra cần chạy |
| --- | --- |
| Chỉ tài liệu | Check; rà đường dẫn và nội dung |
| Harness/script | Check; Init; nhánh thành công/thất bại của script liên quan |
| Kotlin, cấu hình, build | Verify; test hồi quy có ý nghĩa cho logic |
| Depth, shader, framing, mesh | Verify + Device: DepthInference/Parallax/ImageFit/SurfaceCoverage; quan sát ngang/dọc/vuông, depth phẳng, giới hạn zoom/tilt |
| UI | Verify + thao tác luồng bị ảnh hưởng trên thiết bị/emulator |
| Sensor, wallpaper | Verify + Device + máy thật: ẩn/hiện, pause/resume, cảm biến |
| Export | Verify + Device + xuất MP4, phát lại, thời lượng, điểm nối vòng lặp và giải phóng tài nguyên |

Instrumentation hiện có không thay thế toàn bộ kiểm tra UI, wallpaper, export hay đánh giá hình ảnh. Không có thiết bị thì ghi chưa xác minh và bước tiếp theo; không đánh dấu feature cần thiết bị là done.

## Trạng thái và bàn giao

`feature_list.json` là phạm vi công việc, không phải danh sách tự động chứng nhận mọi tính năng sẵn có. Mục wallpaper kế thừa việc cần xác minh trong PROGRESS.md, không khẳng định đang lỗi. Trạng thái: `pending`, `in_progress`, `blocked`, `done`. Mỗi mục có ID duy nhất, dependency, acceptance và evidence. Không quá một mục in_progress. Mục done cần evidence không rỗng và tất cả dependency done; evidence phải mô tả kiểm tra có thật, không chỉ ghi "done".

Khi bắt đầu việc mới: thêm mục có tiêu chí cụ thể, chọn in_progress. Khi kết thúc: cập nhật evidence và STATE, nêu lỗi hoặc kiểm tra chưa chạy. Không đánh dấu done chỉ vì đã viết code. Giữ PROGRESS.md cho lịch sử kỹ thuật đáng ghi nhận; STATE là bản bàn giao ngắn hiện hành.
