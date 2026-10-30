with open('PROGRESS.md', 'r', encoding='utf-8') as f:
    text = f.read()

target = """### 3. Khắc Phục Triệt Để Hiện Tượng Kéo Dãn (Stretch) Thay Vì Có Chiều Sâu 3D
- **Nguyên nhân gốc rễ**:
  1. `modelMatrix` trước đó bị cố định (`setIdentityM`), không hề xoay theo không gian 3D khi nghiêng máy hay vuốt tay.
  2. Mã cũ tự ý can thiệp sai lệch vào tọa độ mặt phẳng 2D: cộng thêm `shiftX * parallax * 0.08f * edgeWeight` trực tiếp vào $(X, Y)$, đồng thời neo cứng các cạnh ngoài viền (`edgeWeight = 0`). Vì viền ngoài bị ghim còn các điểm bên trong bị kéo xô lệch, bức ảnh bị co dãn như một tấm cao su (rubber-sheet distortion) thay vì chuyển động hình học.
  3. Bộ lọc phân vị `percentile (depthLow, depthRange)` nén và cắt cụt các giá trị độ sâu trên khuôn mặt thành mặt phẳng `1.0`, triệt tiêu toàn bộ hình khối 3D chi tiết (mũi, má, cằm).
- **Giải pháp chuẩn xác**:
  1. **Khôi phục xoay phối cảnh 3D thực thụ (`modelMatrix`)**: Áp dụng góc xoay nghiêng tự nhiên $8.0^{\circ} \sim 8.5^{\circ}$ quanh trục $Y$ và $X$ kết hợp dịch chuyển góc nhìn camera (`eyeX, eyeY`), tạo thị sai 3D thị giác chân thực theo đúng nguyên lý Perspective Projection.
  2. **Giải phóng lưới đỉnh 2D**: Loại bỏ hoàn toàn phép biến dạng 2D nhân tạo và hệ số khóa viền `edgeWeight`. Giữ cấu trúc đỉnh $(X, Y)$ cứng cáp và chuẩn hóa theo tia chiếu camera (`rayScale`), chuyển toàn bộ thị sai sang trục $Z$ hình học không gian.
  3. **Khôi phục dải chiều sâu thực**: Dùng trực tiếp giá trị `smoothedDepth` chuẩn hóa `[0.0, 1.0]` tương thích hoàn toàn với thông số `depthCenter` trong `_desc.dat`, bảo toàn trọn vẹn đường nét 3D của chủ thể và các tầng lớp hậu cảnh."""

replacement = """### 3. Tái Tạo Chuẩn Xác Parallax Nội Tại (Chỉ Di Chuyển Vật Thể Gần, Cố Định Khung Ảnh)
- **Vấn đề trước đó**: Khi xoay góc nhìn camera và `modelMatrix`, toàn bộ 4 góc khung hình của bức ảnh bị nghiêng xô lệch theo, khiến người dùng cảm thấy "di chuyển cả bức ảnh" thay vì nhìn thấy chủ thể ở gần tách lớp chuyển động.
- **Giải mã chuẩn xác từ Native C++ (`libPhotos3DModelManager.so` -> `applyVerticesShift` & `shiftVertices`)**:
  - Ứng dụng gốc **Photos 3D** giữ camera và khung ảnh (`modelMatrix`) cố định hoàn toàn.
  - Chuyển động Parallax được tính toán trực tiếp trên từng đỉnh lưới theo công thức độ lệch chiều sâu so với mặt phẳng tiêu cự `depthCenter`:
    $$\\text{s}_0 = \\text{depthScale} \\times (\\text{curvedDepth} - \\text{depthCenter})$$
    $$\\text{finalX} = \\text{baseX} + \\text{maxShiftX} \\times \\text{s}_0$$
    $$\\text{finalY} = \\text{baseY} + \\text{maxShiftY} \\times \\text{s}_0$$
  - **Kết quả**:
    1. Tại các điểm thuộc mặt phẳng tiêu cự / hậu cảnh ($\\text{curvedDepth} \\approx \\text{depthCenter}$), $\\text{s}_0 = 0 \\implies \\text{finalX} = \\text{baseX}$. **Khung ảnh và hậu cảnh hoàn toàn đứng yên cố định**.
    2. Tại các điểm thuộc chủ thể / vật thể ở gần ($\\text{curvedDepth} > \\text{depthCenter}$), $\\text{s}_0 > 0$. **Chỉ có vật thể ở gần di chuyển nổi bật trên nền ảnh**.
    3. Không dùng `edgeWeight` hay co dãn phân vị, giữ khoảng cách lưới tự nhiên, loại bỏ hoàn toàn hiện tượng kéo dãn (stretch)."""

text = text.replace('\r\n', '\n')
target = target.replace('\r\n', '\n')
replacement = replacement.replace('\r\n', '\n')

if target in text:
    text = text.replace(target, replacement)
    with open('PROGRESS.md', 'w', encoding='utf-8') as f:
        f.write(text)
    print('PROGRESS.md updated!')
else:
    print('Target not found in PROGRESS.md')

