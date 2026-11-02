# Bàn giao hiện tại

- Ngày: 2026-11-02 (Asia/Saigon).
- Phạm vi: Kích hoạt photo picker trong Gallery (`onAddNewPhoto`) và sửa import `PickVisualMediaRequest`.
- Thực hiện:
  - Bổ sung `import androidx.activity.result.PickVisualMediaRequest` trong `MainScreen.kt`.
  - Bỏ tiền tố lỗi `ActivityResultContracts.` trước `PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)` và bỏ comment lời gọi `imagePickerLauncher.launch(...)`.
  - Nhấn nút "Add photo" trong Gallery hiện đã khởi chạy Photo Picker của hệ thống.
- Kết quả kiểm tra:
  - Verify pass `.harness/runs/20261102T081630431Z-fd16276c/result.json`: 53/53 JVM unit tests pass, Android Lint `lintDebug`, `assembleDebug`.
- Chưa xác minh:
  - Thao tác chọn ảnh trực quan trên thiết bị thật / emulator.
- Bước tiếp theo:
  - Kiểm tra tương tác chọn ảnh từ thư viện trên máy thật và theo dõi quá trình sinh depth map cho ảnh tùy chỉnh.
