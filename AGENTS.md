# Spatial agent harness

Trao đổi bằng tiếng Việt. Đây là điểm vào cho mọi phiên làm việc.

## Bắt đầu
1. Đọc `AGENT.md` (quy tắc kỹ thuật), `harness/STATE.md` (bàn giao), và `feature_list.json` (phạm vi/tiêu chí).
2. Chạy `git status --short`; giữ nguyên mọi thay đổi có sẵn của người dùng.
3. Chạy `./scripts/harness.ps1 -Mode Init`. Chọn một feature đang làm hoặc tạo mục cho yêu cầu mới; chỉ một mục `in_progress`.
4. Đọc thêm `TECHNOLOGIES.md` và code/test liên quan. `PROGRESS.md` là lịch sử, không phải bằng chứng hiện tại.

## Thực hiện và xác minh
- Tuân thủ `AGENT.md`; không nâng toolchain/dependency hoặc sửa assets ngoài yêu cầu.
- Quy trình, lệnh và ma trận kiểm tra nằm tại `harness/README.md`.
- Với thay đổi logic/build: chạy `./scripts/harness.ps1 -Mode Verify`.
- Với render/ONNX: thêm `-Mode Device` trên thiết bị phù hợp; xem cả checklist kiểm tra thủ công.
- Với thay đổi harness: chạy `-Mode Check` và kiểm tra hành vi script đã sửa.
- Lệnh lỗi hoặc kiểm tra bỏ qua không được tính là pass. Không suy ra chất lượng hình ảnh/cảm biến từ test JVM.

## Kết thúc
- Review diff, cập nhật feature với bằng chứng thực tế trước khi đặt `done`.
- Cập nhật `harness/STATE.md`: phạm vi, kết quả, lệnh đã chạy, đường dẫn báo cáo, blocker và bước tiếp theo.
- Không xóa/làm yếu tiêu chí để hợp thức hóa kết quả. Không tự commit, push hoặc cài app lên thiết bị ngoài phạm vi yêu cầu.
- Báo rõ kiểm tra chưa chạy. Không ghi secret, đường dẫn SDK cá nhân hoặc dữ liệu người dùng vào trạng thái.
