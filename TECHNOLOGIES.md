# Công nghệ sử dụng trong Spatial

Tài liệu mô tả stack đang được khai báo và sử dụng trong repository. Phiên bản bên dưới lấy từ cấu hình hiện tại; khi nâng cấp, đối chiếu lại `gradle/libs.versions.toml`, `app/build.gradle.kts` và cấu hình Gradle Wrapper.

## 1. Nền tảng và ngôn ngữ

Spatial là ứng dụng Android native, viết bằng Kotlin, có một module ứng dụng `:app` với package `com.example.spatial`.

| Thành phần | Cấu hình hiện tại | Vai trò |
| --- | --- | --- |
| Android SDK | min SDK 24, compile/target SDK 37 | Nền tảng chạy và biên dịch ứng dụng |
| Kotlin Compose plugin | 2.2.10 | Hỗ trợ biên dịch giao diện Compose |
| Java source/target | 11 | Mức tương thích mã Java của module app |
| JVM toolchain cho Gradle daemon | 25 | JVM chạy tiến trình build, khai báo trong `gradle/gradle-daemon-jvm.properties` |

Mức Java của mã ứng dụng và JVM chạy Gradle là hai cấu hình riêng.

## 2. Giao diện: Jetpack Compose và Material 3

Giao diện được xây dựng bằng Jetpack Compose, sử dụng Material 3 cho các thành phần UI và Material Icons Extended cho biểu tượng.

| Thư viện | Phiên bản | Mục đích |
| --- | --- | --- |
| Compose BOM | 2026.02.01 | Đồng bộ phiên bản các thư viện Compose thuộc BOM |
| Activity Compose | 1.13.0 | Tích hợp Compose với Android Activity |
| Material 3, Compose UI/Graphics | Theo BOM | Thành phần giao diện và đồ họa UI |
| Lifecycle Runtime KTX | 2.11.0 | Tích hợp vòng đời Android |
| Lifecycle ViewModel Compose | 2.8.7 | Sử dụng ViewModel trong Compose |
| Core KTX | 1.19.1 | Các extension Kotlin cho Android API |

Mã chính nằm trong `ui/MainScreen.kt`, `ui/SpatialViewModel.kt` và `ui/theme/`, tính từ `app/src/main/java/com/example/spatial/`. ViewModel quản lý trạng thái và điều phối các thao tác với scene; phần dựng ảnh không gian nằm trong lớp OpenGL riêng.

## 3. Đồ họa: OpenGL ES 2.0 và GLSL

Ứng dụng dùng `GLSurfaceView`, API `GLES20` và shader GLSL để hiển thị ảnh có chiều sâu. Ảnh màu cung cấp texture; depth map cung cấp thông tin chiều sâu để tạo độ lệch parallax và biến đổi hình học.

Các thành phần trong package `gl/`:

- `SpatialGLSurfaceView`: tích hợp vùng hiển thị OpenGL và tương tác.
- `SpatialGLRenderer`: điều phối render scene.
- `SpatialMesh`, `DepthProjection`, `LayerBuilder`: hình học, phép chiếu và dựng lớp theo depth.
- `ParallaxShaders`, `ShaderHelper`: shader và hỗ trợ biên dịch/liên kết chương trình GPU.
- `MotionPattern`: quỹ đạo chuyển động cho chế độ animation.

Repository còn có shader tại `app/src/main/assets/shaders/`. Khi sửa hiệu ứng cần kiểm tra đường render thực sự được gọi, vì shader cũng được khai báo trong Kotlin.

Chất lượng hiển thị phụ thuộc vào sự khớp nhau giữa texture và depth, quy ước tọa độ, giới hạn chuyển động và xử lý biên ảnh.

## 4. AI trên thiết bị: ONNX Runtime

Project dùng `com.microsoft.onnxruntime:onnxruntime-android:1.17.1` để chạy model ước lượng chiều sâu ngay trên Android.

- Model được đóng gói tại `app/src/main/assets/d_models/model_256.onnx`.
- `ai/DepthEstimationManager.kt` quản lý môi trường ONNX, session, tải model và suy luận.
- `ai/DepthPreprocess.kt` chứa các bước xử lý kích thước ảnh, letterbox, crop, chuẩn hóa và làm mượt depth.
- Manager có cơ chế ước lượng thay thế bằng heuristic khi không sử dụng được model.

Depth tạo ra được đưa vào pipeline render để biến ảnh 2D thành hiệu ứng không gian. Tên kiến trúc hoặc nguồn huấn luyện của model cần được xác minh từ metadata/tài liệu model; tên file và comment trong code chưa đủ để kết luận.

## 5. Xử lý bất đồng bộ: Kotlin Coroutines

Ứng dụng dùng `kotlinx-coroutines-android:1.8.1` cùng coroutine scope của ViewModel để điều phối công việc bất đồng bộ. Các thao tác như tải dữ liệu, xử lý ảnh và khởi tạo model sử dụng dispatcher phù hợp thay vì chặn UI.

Coroutine và GL thread có vai trò riêng: chuyển sang background dispatcher không đồng nghĩa đã có OpenGL context. Các thao tác GPU vẫn cần chạy trên thread/context tương ứng.

## 6. Cảm biến và tương tác Android

Package `sensor/` gồm `SpatialSensorTracker` và `RelativeTilt`, xử lý dữ liệu cảm biến và độ nghiêng tương đối để điều khiển góc nhìn. Vùng hiển thị cũng xử lý cử chỉ để tương tác với ảnh.

Hành vi cảm biến phụ thuộc phần cứng. Emulator hữu ích để kiểm tra UI và cử chỉ; việc xác minh phản ứng khi nghiêng máy cần thiết bị có cảm biến phù hợp.

## 7. Xuất video: EGL, MediaCodec và MediaMuxer

`gl/VideoExporter.kt` dùng các API Android để xuất animation thành video H.264 trong container MP4:

1. EGL tạo context và surface để render ngoài màn hình.
2. OpenGL dựng từng frame với tham số hiệu ứng.
3. `MediaCodec` mã hóa hình ảnh thành luồng H.264.
4. `MediaMuxer` ghi luồng đã mã hóa vào file MP4.

`LoopPlan` lập lịch ở 30 FPS theo số chu kỳ chuyển động nguyên, nhằm tạo video có thể lặp liền mạch. Khả năng mã hóa và thời gian xuất phụ thuộc thiết bị.

## 8. Live wallpaper

`wallpaper/SpatialWallpaperService.kt` sử dụng Android `WallpaperService` để đưa hiệu ứng không gian ra hình nền động. Cấu hình liên quan được khai báo trong manifest và `app/src/main/res/xml/wallpaper.xml`.

Service phải quản lý vòng đời surface, render và cảm biến theo trạng thái hiển thị của wallpaper.

## 9. Dữ liệu và tài nguyên

Scene được biểu diễn bằng các lớp trong package `data/`, bao gồm `SpatialSceneItem`, `SpatialSceneConfig`, `DepthMap` và `AssetDataLoader`.

- `SpatialSceneConfig` đọc/ghi cấu hình bằng Android `org.json.JSONObject` và chuẩn hóa các giá trị đầu vào.
- Scene mẫu và wallpaper nằm trong `assets/samples/` và `assets/wallpapers/`.
- Mỗi bộ assets sử dụng các tên `*_img.dat`, `*_depth.dat`, `*_thumb.dat`, `*_desc.dat` cho ảnh, depth, thumbnail và mô tả/cấu hình.
- Tài nguyên UI dùng cơ chế Android resources tại `res/`, gồm drawable, màu, chuỗi và theme.

Phần mở rộng `.dat` là quy ước đóng gói của project; cần đọc loader để biết chính xác cách giải mã từng loại dữ liệu.

## 10. Build và quản lý dependency

| Công cụ | Phiên bản | Vai trò |
| --- | --- | --- |
| Gradle Wrapper | 9.6.0 | Chạy build với phiên bản cố định |
| Android Gradle Plugin | 9.4.1 | Build, đóng gói và kiểm tra ứng dụng Android |
| Gradle Kotlin DSL | File `.gradle.kts` | Khai báo cấu hình build bằng Kotlin |
| Version catalog | `gradle/libs.versions.toml` | Quản lý alias và phiên bản nhiều dependency/plugin |
| Foojay resolver convention | 1.0.0 | Hỗ trợ phân giải Java toolchain |

Dependency được lấy từ Google Maven và Maven Central; plugin còn dùng Gradle Plugin Portal. Một số dependency vẫn khai báo phiên bản trực tiếp trong `app/build.gradle.kts`, nên cần kiểm tra cả file này lẫn version catalog.

## 11. Kiểm thử và công cụ kiểm tra

- **JUnit 4.13.2**: unit test JVM cho projection, depth preprocessing, cấu hình, giới hạn chuyển động, layer và kế hoạch vòng lặp video.
- **AndroidX Test JUnit 1.3.0 và Espresso 3.7.0**: dependency phục vụ kiểm thử trên thiết bị/emulator.
- **Compose UI Test**: dependency hỗ trợ kiểm thử giao diện Compose, phiên bản theo BOM.
- **Android Lint**: phân tích tĩnh thông qua Android Gradle Plugin.

Các dependency kiểm thử được khai báo không đồng nghĩa mọi luồng UI đều đã có test. Kiểm tra chất lượng parallax, cảm biến, video và wallpaper vẫn cần xác minh trực quan hoặc trên thiết bị.

Lệnh build/test và quy tắc làm việc được ghi trong `AGENT.md`.
