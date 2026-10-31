# Hướng dẫn làm việc với Spatial

## Tổng quan

Spatial là ứng dụng Android tạo hiệu ứng ảnh 3D/parallax từ ảnh và bản đồ chiều sâu. Ứng dụng có giao diện Jetpack Compose, renderer OpenGL, điều khiển bằng cảm biến/cử chỉ, các mẫu chuyển động, xuất video và live wallpaper. Model ONNX đi kèm phục vụ ước lượng depth trên thiết bị.

Trao đổi và báo cáo kết quả bằng tiếng Việt, trừ khi người dùng yêu cầu khác. Giữ tên class, API và thuật ngữ trong code bằng tiếng Anh theo quy ước hiện có.

## Cấu trúc mã nguồn

- `app/src/main/java/com/example/spatial/MainActivity.kt`: điểm vào ứng dụng.
- `ui/`: màn hình Compose, `SpatialViewModel` và theme.
- `data/`: tải assets, dữ liệu depth, scene và cấu hình `SpatialSceneConfig`.
- `ai/`: tiền xử lý ảnh/depth và chạy suy luận ONNX.
- `gl/`: renderer, mesh, projection, shader, dựng layer, mẫu chuyển động và xuất video.
- `sensor/`: theo dõi cảm biến và tính độ nghiêng tương đối.
- `wallpaper/`: `SpatialWallpaperService`.
- Các đường dẫn thư mục package ở trên tính từ `app/src/main/java/com/example/spatial/`.
- `app/src/main/assets/`: shader, model `d_models/model_256.onnx`, scene mẫu và wallpaper.
- `app/src/test/`: unit test cho projection, giới hạn chuyển động, cấu hình, layer, vòng lặp và tiền xử lý depth.
- `app/src/androidTest/`: kiểm thử trên Android.
- `PROGRESS.md`: ghi chép tiến độ và phân tích trước đây. Đối chiếu với code và test trước khi dùng; thông số và kết luận trong tài liệu có thể đã cũ.

## Môi trường và lệnh kiểm tra

Project có một module `:app`, dùng Gradle Kotlin DSL và version catalog tại `gradle/libs.versions.toml`. Dùng Gradle Wrapper của repository, không tự đổi phiên bản để khắc phục lỗi môi trường.

Cấu hình hiện tại: Gradle 9.6.0, AGP 9.4.1, Kotlin Compose plugin 2.2.10, compile/target SDK 37, min SDK 24. Gradle daemon yêu cầu toolchain Java 25 theo `gradle/gradle-daemon-jvm.properties`; Java source/target compatibility của app là 11. Hai cấu hình này có mục đích khác nhau.

Chạy từ thư mục gốc bằng PowerShell:

```powershell
# Build APK debug
.\gradlew.bat :app:assembleDebug

# Unit test JVM
.\gradlew.bat :app:testDebugUnitTest

# Android Lint
.\gradlew.bat :app:lintDebug

# Một nhóm test cụ thể
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.spatial.SpatialProjectionTest"

# Instrumentation test, cần thiết bị hoặc emulator đang kết nối
.\gradlew.bat :app:connectedDebugAndroidTest
```

Trên Linux/macOS dùng `./gradlew` thay cho `.\gradlew.bat`. Android SDK được cấu hình theo máy qua `local.properties` hoặc biến môi trường; không commit đường dẫn SDK cục bộ. Lần chạy đầu có thể cần mạng để tải Gradle, toolchain và dependencies.

Chọn kiểm tra theo phạm vi thay đổi. Thay đổi tài liệu không cần build app. Nếu không chạy được kiểm tra, báo rõ lệnh đã thử và nguyên nhân; không khẳng định build/test đã thành công.

## Quy tắc sửa code

1. Đọc code liên quan và kiểm tra `git status` trước khi sửa. Giữ nguyên thay đổi có sẵn của người dùng, kể cả file đã được stage hoặc đánh dấu xóa.
2. Giữ thay đổi tập trung vào yêu cầu. Theo Kotlin official style và cách đặt tên, tổ chức code hiện tại; tránh refactor hoặc nâng dependency ngoài phạm vi.
3. UI và xử lý sự kiện thuộc `ui/`; logic depth, projection và chuyển động nên ở lớp chuyên trách để có thể kiểm thử độc lập.
4. Không chạy suy luận ONNX, giải mã ảnh, I/O hoặc tác vụ nặng trên main thread. Tôn trọng coroutine scope, cancellation và vòng đời hiện có.
5. Thao tác tài nguyên OpenGL trên đúng GL thread/context. Giải phóng texture, buffer, encoder và tài nguyên ONNX theo vòng đời; tránh tạo bitmap hoặc buffer lớn mỗi frame.
6. Quản lý đăng ký cảm biến, render loop và wallpaper theo trạng thái hiển thị/vòng đời để tránh rò rỉ và hao pin.
7. Không hardcode lại giới hạn hoặc giá trị mặc định đã có trong `MotionLimits` và `SpatialSceneConfig`. Giữ bước chuẩn hóa dữ liệu, kiểm tra giá trị hữu hạn và tính tương thích khi đọc cấu hình cũ.
8. Khi đổi định dạng JSON, scene hoặc assets, kiểm tra cả đường đọc và ghi, cùng tác động đến scene đã lưu, preview, wallpaper và export.
9. Không thêm model, thư viện hay quyền Android mới nếu chức năng hiện có đã đáp ứng yêu cầu. Nếu cần thêm, giải thích tác động về kích thước, hiệu năng và tương thích.

## Lưu ý về depth và hiển thị

- Xác nhận quy ước trục, thứ tự dữ liệu depth, chiều gần/xa, aspect ratio và phép biến đổi tọa độ trước khi sửa thuật toán. Không suy luận chỉ từ tên biến hoặc phần mở rộng `.dat`.
- Giữ sự khớp nhau giữa ảnh màu và depth qua letterbox, crop, resize và projection. Kiểm tra ảnh ngang, dọc, vuông và depth đồng nhất khi thay đổi phần này.
- Khi sửa parallax, mesh hoặc layer, kiểm tra lộ viền đen, rách biên chủ thể, biến dạng và hành vi ở giới hạn chuyển động/zoom.
- Các đường preview, live wallpaper và video export cần được đối chiếu khi thay đổi logic render dùng chung.
- Emulator có thể thiếu cảm biến vật lý. Không kết luận cảm biến hoạt động đúng chỉ từ thao tác kéo trên emulator; dùng thiết bị thật khi cần xác minh độ nghiêng.
- Với export, kiểm tra thời lượng, độ liền mạch của vòng lặp, file đầu ra và giải phóng tài nguyên khi hoàn tất hoặc lỗi.

## Assets và vệ sinh repository

- Giữ nguyên cấu trúc và tên bộ scene `*_img.dat`, `*_depth.dat`, `*_thumb.dat`, `*_desc.dat` trừ khi đang thay đổi có chủ đích cả loader và dữ liệu.
- Không chỉnh sửa hàng loạt hoặc tái sinh model ONNX/assets nhị phân để xử lý một lỗi code.
- Không commit thư mục build/cache như `.gradle/`, `.gradle-user/`, `.kotlin/`, `build/`, `app/build/`, APK, log, screenshot thử nghiệm hoặc cấu hình IDE cá nhân.
- Không đưa khóa ký, thông tin bí mật hay đường dẫn máy cá nhân vào code hoặc tài liệu.

## Hoàn tất công việc

Với thay đổi logic, bổ sung hoặc cập nhật test hồi quy có ý nghĩa trong nhóm liên quan. Với thay đổi hình ảnh, kiểm tra trực quan trên emulator/thiết bị khi có sẵn; test JVM không xác nhận được chất lượng render.

Trước khi bàn giao, xem lại diff để loại bỏ sửa đổi ngoài ý muốn. Báo ngắn gọn: đã đổi gì, đã kiểm tra gì, và còn giới hạn hoặc việc nào chưa xác minh. Chỉ cập nhật `PROGRESS.md` khi có tiến triển đáng ghi nhận hoặc người dùng yêu cầu; phân biệt kết quả đã kiểm chứng với giả thuyết.
