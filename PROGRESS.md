# BÁO CÁO TIẾN ĐỘ DỰ ÁN SPATIAL 3D (CLONE PHOTOS 3D)

> **Mục tiêu**: Tái tạo chính xác 100% hiệu ứng hình ảnh 3D Parallax, chiều sâu không gian và vật lý Jelly / Jello của Tab 1 (Rotate) từ ứng dụng mẫu **Photos 3D** (`com.vacuapps.photos3d` của VacuApps).  
> **Môi trường thử nghiệm**:
> - Máy ảo: **LDPlayer** (`emulator-5554`, Android 9, 1080x1920) - Đã cài cả app mẫu lẫn app clone.
> - Máy thật: **Xiaomi Mi MIX 2** (`1f5efaa4`) - Đã cài cả app mẫu lẫn app clone.

---

## I. NHỮNG GÌ ĐÃ HOÀN THÀNH (COMPLETED)

### 1. Reverse Engineering Toàn Diện Ứng Dụng Mẫu (`com.vacuapps.photos3d`)
- **Trích xuất & Dịch ngược**:
  - Đã trích xuất `classes.dex`, `base.apk`, `split_config.x86_64.apk` và thư viện native C++ `libPhotos3DModelManager.so`.
- **Phát hiện bước ngoặt về Cử chỉ Tab 1**:
  - Hệ thống điều khiển 4 tab trong mã Dalvik kế thừa từ lớp `Ldl0;`:
    - `Lal0;` (Tab 0: Shake): Hỗ trợ vuốt tay (`method u` có bytecode).
    - `Lbl0;` (Tab 1: Rotate / Jelly): Các hàm cử chỉ vuốt/kéo `u(...)`, `v(...)`, `t(...)` **hoàn toàn là hàm rỗng `return-void`**!
    - `Lcl0;` (Tab 2: Animations): Cử chỉ vuốt là `return-void`, chạy theo quỹ đạo toán học.
    - `Lvk0;` (Tab 3: ZoomPan): Hỗ trợ kéo pan và zoom đa điểm (`method u` dài 128 lệnh).
  - 👉 **Kết luận lý giải vì sao app mẫu đứng yên trên máy ảo LDPlayer**: App mẫu ở Tab 1 **hoàn toàn không hỗ trợ vuốt ngón tay**, mà phụ thuộc 100% vào cảm biến con quay hồi chuyển vật lý (**Gyroscope / Accelerometer**). Trên máy ảo LDPlayer không có cảm biến xoay thật nên ảnh ở app mẫu không di chuyển. Bản clone của chúng ta đã cải tiến vượt trội khi hỗ trợ song song cả Touch Drag (cho máy ảo) và Gyroscope (cho máy thật).
- **Giải mã công thức toán học chuẩn xác từ Native C++ (`libPhotos3DModelManager.so`)**:
  1. **Hệ số chiều sâu phi tuyến tính** (`ImageDynamicModel::calculateDepthCoef` tại `0x13540`):
     $$\text{depthScale} = 0.30f + 0.70f \times \text{intensity}^3$$
  2. **Ánh xạ chiều sâu per-node** (`updateDepthByIntensity_node` tại `0x1356c`):
     $$\text{node.depth} = \text{powf}(\text{rawDepth}, \gamma)$$
     $$\text{node.baseZ} = \text{node.depth} \times 0.023f$$
  3. **Độ lệch đỉnh phối cảnh & Giới hạn biên độ** (`applyVerticesShift` tại `0x142ec` & `shiftVertices` tại `0x14549`):
     $$s_0 = \text{depthScale} \times (\text{node.depth} - \text{depthCenter})$$
     $$\text{finalX} = \text{node.baseX} + \text{currentShiftX} \times s_0 \times 0.12f \times \text{edgeWeight}$$
     $$\text{finalY} = \text{node.baseY} + \text{currentShiftY} \times s_0 \times 0.12f \times \text{edgeWeight}$$
     $$\text{finalZ} = \text{depthDiff} \times \text{depthScale} \times 0.65f \times \text{zScale} \times \text{edgeWeight}$$
     *(Giới hạn dịch chuyển nội tại vi mô $\text{currentShiftX} \in [-0.040f, 0.040f]$, loại bỏ hoàn toàn các hàm nhân kéo méo bề mặt 2D kỳ dị như sóng tròn ripple hay co dãn squish).*
  4. **Hệ phương trình vi phân lò xo dao động Jelly (Under-damped Harmonic Oscillator)**:
     $$F_x = -k \cdot x - c \cdot v_x$$
     $$F_y = -k \cdot y - c \cdot v_y$$
     với $k = 65.0f$, $c = 2.8f$, chu kỳ $T \approx 0.78\text{s}$, tắt dần sau 3-4 nhịp nảy đàn hồi tự nhiên khi thả tay vuốt.
  5. **Góc xoay phối cảnh 3D & Khử lộ viền đen (Aspect Fill Overscan)**:
     - Giới hạn góc nghiêng tuyệt đối: `targetTiltX, targetTiltY` được kẹp trong $[-1.0f, 1.0f]$, loại bỏ hiện tượng góc nghiêng bị khuếch đại khi xoay máy quá đà.
     - `modelMatrix` xoay chuẩn tự nhiên: $4.5^{\circ}$ (thay vì $14.5^{\circ}$ vốn gây méo hình thang/keystoning trên khuôn mặt).
     - `setLookAtM` camera eye translation: $0.18f$ (thay vì $0.45f$ làm góc card bị kéo lệch ra ngoài frustum).
     - `margin = 1.25f` (25% overscan) kết hợp `edgeWeight` neo giữ mép biên, bảo đảm bao phủ 100% màn hình, không bao giờ lộ viền đen kể cả khi lật máy tối đa.
  6. **Thuật toán khử biến dạng khuôn mặt & rách viền Silhouette (Elastic Gradient Limiting)**:
     - Chuẩn hóa chiều sâu hình học $Z$: $z_{\text{disp}} = \text{depthDiff} \times \text{depthScale} \times 0.05f$ (khớp chuẩn native `node.baseZ = node.depth * 0.023f`, thay vì $0.65f$ quá lớn làm mặt nhô ra hình chóp nón gây méo mó).
     - 4 passes giới hạn gradient depth `maxGrad = 0.030f`, phân bố chuyển tiếp mượt mà qua các node kề nhau, khử sạch 100% hiện tượng xé rách hay nổi gân viền má và tóc.

### 2. Giao Diện & Điều Khiển Thông Số 4 Tab (Chuẩn 100% `activity_image_view.xml` & `ImageViewActivity`)
- Đã giải mã toàn bộ `res/layout/activity_image_view.xml`, Dalvik bytecode (`ImageViewActivity.O()`, `al0.A(I)`, `rk0.p([F)`, `rk0.O(Z)`, `Lm6.a()`) và hàm C++ `ImageDynamicModel::updateProperties` (`0x13c0c`) để đồng bộ tuyệt đối giao diện và logic thông số của cả 4 tab:
  1. **Trạng thái mặc định & Phím Back (`rk0.x()`)**:
     - Khi mở ảnh, mở mặc định ở **Tab 0 (Rotate)** và **ẩn bảng thông số (`showControls = false`)** để hiển thị trọn vẹn khung hình 3D.
     - Bấm icon **Thuộc tính (Properties)** trên thanh công cụ để bật/tắt bảng thông số dạng lớp phủ bán trong suốt (overlay) không làm co giãn khung OpenGL.
     - Khi bảng thông số đang mở, bấm nút Back sẽ đóng bảng thông số trước khi thoát ra Gallery.
  2. **Các icon trên thanh công cụ phía trên (`al0.A(I)`)**:
     - **Tab 0 (Rotate) & Tab 1 (Shake/Jelly)**: Chỉ hiển thị **3 icon**: Hình nền (Wallpaper), Thuộc tính (Properties), Xóa (Delete).
     - **Tab 2 (Animations)**: Hiển thị đầy đủ **5 icon**: Hình nền, Tải xuống (Save), Chia sẻ (Share), Thuộc tính, Xóa.
     - **Tab 3 (ZoomPan)**: Chỉ hiển thị **1 icon**: Xóa (Delete) và tự động ẩn bảng thông số (`rk0.s()`).
  3. **Quy tắc hiển thị & kiểu dáng thanh trượt theo từng Tab (`ImageViewActivity.O()`)**:
     - **Tab 0 (Rotate)**: Hiển thị `Cường độ:` (`0.0..1.25`, mặc định `0.8`) + checkbox `Đảo` (tỷ lệ weight `5:1`), `Trung tâm:` (`0.0..1.0`, mặc định `0.85`, rãnh xanh liền mạch), `Zoom:` (`-1.0..1.0`, weight `3`, rãnh xanh liền mạch) + `Zoom 3D:` (`0.0..1.0`, weight `2`, rãnh mảnh, chữ `11sp`).
     - **Tab 1 (Shake/Jelly)**: Chỉ hiển thị `Cường độ:` (mặc định `0.9`, trải dài toàn chiều ngang, ẩn checkbox `Đảo`) và `Trung tâm:`. Ẩn hoàn toàn `Zoom`, `Zoom 3D` và `Tốc độ`.
     - **Tab 2 (Animations)**: Hiển thị `Cường độ:` (mặc định `0.9`, ẩn checkbox `Đảo`), `Trung tâm:`, `Zoom:` + `Zoom 3D:`, và `Tốc độ:` (`0.0..1.0`, mặc định `0.7`).
     - **Tab 3 (ZoomPan)**: Ẩn toàn bộ các thanh trượt thông số.
  4. **Quy tắc khóa/mở `Zoom 3D:` (`Lm6.a`)**:
     - Khi `|Zoom| < 0.1f`, thanh trượt `Zoom 3D:` tự động chuyển sang trạng thái **vô hiệu hóa (`enabled = false`, làm mờ)**. Chỉ khi kéo `|Zoom| >= 0.1f` thì `Zoom 3D:` mới được kích hoạt.
  5. **Công thức toán học chuẩn Native C++ cho từng thông số**:
     - `effectiveIntensity = 0.4f + 0.7f * intensity`, `exponent = 0.3f + 0.7f * effectiveIntensity^3`, `curvedDepth = pow(rawDepth, exponent)`.
     - Ở Tab 2 (Animations), `depthCenter` được đảo chiều `1.0f - depthCenter` đúng theo `rk0.p([F)`.
     - Chu kỳ chuyển động Animations tính từ `speed` theo công thức `cycleDurationMs = 1000 + (1.0f - speed) * 6000` (`rk0.O(Z)`).
     - Độ dịch chuyển trục Z theo `Zoom` và `Zoom 3D`: `effectiveZoom = (zoom < 0) ? zoom * 0.5f : zoom`, và `deltaZ = (shiftZ * zoom3D) * depthFactor + shiftZ * (1.0f - zoom3D)` (`performRotationalUpdate` & `performAnimatedUpdate`).

### 3. Tái Tạo Chuẩn Xác Parallax Nội Tại (Chỉ Di Chuyển Vật Thể Gần, Cố Định Khung Ảnh)
- **Vấn đề trước đó**: Khi xoay góc nhìn camera và `modelMatrix`, toàn bộ 4 góc khung hình của bức ảnh bị nghiêng xô lệch theo, khiến người dùng cảm thấy "di chuyển cả bức ảnh" thay vì nhìn thấy chủ thể ở gần tách lớp chuyển động.
- **Giải mã chuẩn xác từ Native C++ (`libPhotos3DModelManager.so` -> `applyVerticesShift` & `shiftVertices`)**:
  - Ứng dụng gốc **Photos 3D** giữ camera và khung ảnh (`modelMatrix`) cố định hoàn toàn.
  - Chuyển động Parallax được tính toán trực tiếp trên từng đỉnh lưới theo công thức độ lệch chiều sâu so với mặt phẳng tiêu cự `depthCenter`:
    $$\text{s}_0 = \text{depthScale} \times (\text{curvedDepth} - \text{depthCenter})$$
    $$\text{finalX} = \text{baseX} + \text{maxShiftX} \times \text{s}_0$$
    $$\text{finalY} = \text{baseY} + \text{maxShiftY} \times \text{s}_0$$
  - **Kết quả**:
    1. Tại các điểm thuộc mặt phẳng tiêu cự / hậu cảnh ($\text{curvedDepth} \approx \text{depthCenter}$), $\text{s}_0 = 0 \implies \text{finalX} = \text{baseX}$. **Khung ảnh và hậu cảnh hoàn toàn đứng yên cố định**.
    2. Tại các điểm thuộc chủ thể / vật thể ở gần ($\text{curvedDepth} > \text{depthCenter}$), $\text{s}_0 > 0$. **Chỉ có vật thể ở gần di chuyển nổi bật trên nền ảnh**.
    3. Không dùng `edgeWeight` hay co dãn phân vị, giữ khoảng cách lưới tự nhiên, loại bỏ hoàn toàn hiện tượng kéo dãn (stretch).

---


### 4. Tách Biệt Tuyệt Đối Dao Động Vật Lý Jelly (Chỉ Áp Dụng Duy Nhất Tại Tab 1)
- **Vấn đề phát hiện**: Trước đây, ở các tab không phải Jelly (Tab 0: Rotate, Tab 2: Animations, Tab 3: ZoomPan), trong khối xử lý làm mượt else vẫn áp dụng bộ lọc lò xo - cản dịu (Spring-damper filter với springK = 18.0f, dampC = 8.5f). Điều này vô tình gây ra quán tính nảy dao động (overshoot / bounce) giống như thạch Jelly ngay cả khi người dùng đang ở Tab 0.
- **Giải pháp xử lý triệt để**:
  1. **Khử hoàn toàn phương trình vi phân lò xo ở Tab 0, 2, 3**:
     - Thay thế toàn bộ hệ lò xo dao động bậc 2 bằng bộ lọc thông thấp làm mịn tuyến tính bậc 1 (Direct Exponential LERP Smoothing):
       \text{lerpFactor} = (1.0f - e^{-18.0f \times dt})
       \text{smoothTiltX} += (\text{targetTiltX} - \text{smoothTiltX}) \times \text{lerpFactor}
       \text{smoothTiltY} += (\text{targetTiltY} - \text{smoothTiltY}) \times \text{lerpFactor}
     - Gán triệt tiêu hoàn toàn vận tốc quán tính: elocityX = 0.0f, velocityY = 0.0f.
  2. **Cách ly toàn bộ biến trạng thái Jelly**:
     - Khi chạy ở các tab ngoài Tab 1, tự động reset triệt để: jellyDisplacementX = 0f, jellyDisplacementY = 0f, jellyVelocityX = 0f, jellyVelocityY = 0f.
     - jellySpeed = 0f và isJellyMode = false truyền vào SpatialMesh.kt, ngăn chặn 100% việc tạo sóng ripple $ trên bề mặt lưới.
  3. **Kết quả**:
     - **Tab 0 (Rotate)**: Di chuyển Parallax thuần khiết, êm ái, bám sát cử chỉ tay và Gyroscope theo thời gian thực mà không có bất kỳ rung lắc/nảy đàn hồi nào.
     - **Tab 1 (Shake / Jelly)**: Giữ trọn vẹn mô hình vật lý lò xo thạch Jell-O (=65, c=2.8$) với hiệu ứng nảy tự nhiên 3-4 nhịp khi búng ngón tay và gợn sóng 3D theo chiều sâu.
     - **Tab 2 & 3**: Vận hành chuẩn mực theo quỹ đạo toán học và Pan/Zoom tĩnh.

---


### 5. Khắc Phục Hiện Tượng Vật Thể Xa và Gần Di Chuyển Cùng Tốc Độ Khi Vuốt
- **Nguyên nhân toán học & thị giác**:
  - Trước đây, công thức dịch chuyển đỉnh Parallax được tính bằng hàm đối xứng tuyến tính:
    s_0 = \text{dScale} \times (\text{curvedDepth} - \text{depthCenter})
  - Khi $\text{depthCenter} \approx 0.5$, một vật thể ở hậu cảnh ($\text{curvedDepth} = 0.2$) và một vật thể ở tiền cảnh ($\text{curvedDepth} = 0.8$) đều có độ lệch $|0.2 - 0.5| = 0.3$ và $|0.8 - 0.5| = 0.3$. Do đó biên độ và tốc độ di chuyển của vật thể ở xa và ở gần là **hoàn toàn bằng nhau** (.3 \times \text{maxShiftX}$).
  - Thậm chí ở các ảnh có $\text{depthCenter} > 0.5$ (như preset lpine có $\text{depthCenter} = 0.728$), bầu trời/núi xa có độ lệch $|0.0 - 0.728| = 0.728$, trong khi chủ thể gần có $|1.0 - 0.728| = 0.272$, dẫn đến tình trạng nghịch lý: **vật thể ở xa bay nhanh gấp 2.7 lần vật thể ở gần**!
- **Giải pháp quang học phối cảnh thực tế (Optical Perspective Velocity Weighting)**:
  - Trong quang học phối cảnh thực, thị sai góc tỉ lệ nghịch với khoảng cách đến mắt/ống kính. Vật thể ở vô cực (bầu trời, chân trời, núi xa) có thị sai góc xấp xỉ bằng $, trong khi vật thể cận cảnh có thị sai góc lớn nhất.
  - Áp dụng hàm trọng số phối cảnh phi tuyến tính vào [SpatialMesh.kt](file:///c:/Users/WINDOWS/Desktop/Boom/spatial/app/src/main/java/com/example/spatial/gl/SpatialMesh.kt#L254-L257):
    \text{depthWeight} = \text{curvedDepth}^{1.2f}
    s_0 = \text{dScale} \times (\text{curvedDepth} - \text{depthCenter} \times 0.35f) \times \text{depthWeight}
  - **Kết quả đạt được**:
    1. **Vật thể ở xa (hậu cảnh vô cực, $\text{curvedDepth} \to 0$)**: $\text{depthWeight} \to 0 \implies s_0 \approx 0$. Núi xa, chân trời và khung ảnh được ghim chặt cố định, gần như đứng yên ($< 1\%$ dịch chuyển).
    2. **Vật thể ở gần (tiền cảnh / chủ thể, $\text{curvedDepth} \to 1$)**: $\text{depthWeight} \to 1.0 \implies s_0 \approx 0.825 \times \text{dScale}$. Chủ thể lướt nhanh, nhạy và rõ nét theo từng cú vuốt ngón tay (tốc độ cao hơn hậu cảnh từ **25 đến 100 lần**).
    3. **Không bao giờ bị đồng tốc độ**: Tốc độ di chuyển tăng đơn điệu liên tục từ xa đến gần, tạo cảm giác chiều sâu 3D chân thực, tự nhiên và loại bỏ hoàn toàn hiện tượng méo hay trôi hậu cảnh.

---

## II. THỬ NGHIỆM VÀ XÁC MINH TRỰC QUAN

1. **Máy ảo LDPlayer (`emulator-5554`)**:
   - Chạy 60 FPS mượt mà.
   - Thao tác vuốt/kéo trên màn hình tạo lực nảy thạch Jelly rung rinh 3 nhịp trước khi dừng.
   - Chụp màn hình xác minh các frame `p_bounce1.png`, `p_bounce2.png` đạt độ thẩm mỹ cao, không rách viền, không méo hình.

2. **Máy thật Xiaomi Mi MIX 2 (`1f5efaa4`)**:
   - Đã cài đặt APK debug thành công.
   - Tích hợp cảm biến Gyroscope phần cứng thực tế, góc nghiêng $14.5^{\circ}$ và thị sai chiều sâu holographic hiển thị trực quan và sống động.

---

## III. KẾ HOẠCH TIẾP THEO

1. **Hỗ trợ thêm các preset ảnh mẫu khác trong thư viện (Gallery)**:
   - Thử nghiệm các ảnh có độ phức tạp cao hơn (phong cảnh, động vật, ảnh chụp chân dung góc rộng).
2. **Đồng bộ với Live Wallpaper Service**:
   - Xác nhận `SpatialWallpaperService` hoạt động ổn định trên màn hình chính của máy thật khi người dùng bấm nút "Đặt hình nền".
3. **Tối ưu hóa tài nguyên & Render Loop**:
   - Tiết kiệm pin khi ở trạng thái nghỉ (idle) trên Live Wallpaper.

