---
source: README.md
sourceHash: e416af542e0b1a52d7c438e8828f90fff90f6918b0c66e5805f3c1da095cc31e
---

# Tài liệu RuleStone

Đây là bản tiếng Việt của tài liệu RuleStone. Bản tiếng Anh là bản gốc; các trang chưa được dịch được đánh dấu "(English)" và dẫn tới bản tiếng Anh. Thuật ngữ dùng trong bản dịch được thống nhất trong [bảng thuật ngữ](GLOSSARY.md).

## Hướng dẫn

| Trang | Đọc để |
|---|---|
| [Bắt đầu](getting-started.md) | Viết và đánh giá bảng quyết định đầu tiên của bạn trong mười phút. |
| [Bảng quyết định](../decision-tables.md) (English) | Nắm định dạng `.stone.json`, hit policy, phiên bản, ngày hiệu lực và cách kiểm tra hợp lệ. |
| [Ngôn ngữ biểu thức](../expressions.md) (English) | Viết điều kiện và đầu ra: toán tử, số thập phân, làm tròn, danh sách, trường bị thiếu, lỗi. |
| [So khớp gần đúng](../fuzzy-matching.md) (English) | So sánh tên khi sàng lọc AML/KYC: chuẩn hoá, điểm tương đồng, ngưỡng và các giới hạn. |
| [Java API](../java-api.md) (English) | Nhúng engine vào ứng dụng: đầu vào, kết quả, lỗi, nạp lại luật, đa luồng. |
| [Kiểm thử](../testing.md) (English) | Kiểm thử bảng quyết định: ca kiểm thử bằng JSON, tích hợp JUnit 5, độ bao phủ luật và assertion dạng fluent. |
| [Ví dụ](../examples.md) (English) | Tìm hiểu các bảng quyết định hoàn chỉnh cho khoản vay, lãi suất tiền gửi và phí chuyển tiền. |

## Thiết kế

| Trang | Nội dung |
|---|---|
| [Kiến trúc](architecture.md) | Mục tiêu, nguyên tắc thiết kế, các mô-đun, các chế độ tích hợp, yêu cầu của ngân hàng, tích hợp AI và lộ trình. |

## Dự án

| Trang | Nội dung |
|---|---|
| [Đóng góp](../../CONTRIBUTING.md) (English) | Thiết lập môi trường phát triển, các bước kiểm tra, sign-off theo DCO và danh mục kiểm tra cho pull request. |
| [Chính sách bảo mật](../../SECURITY.md) (English) | Cách báo cáo một lỗ hổng bảo mật. |
| [Nhật ký thay đổi](../../CHANGELOG.md) (English) | Các thay đổi trong từng bản phát hành. |
| [Giấy phép](../../LICENSE) (English) | Giấy phép Apache 2.0. |
| [Dịch tài liệu](README.md) | Quy trình dịch và cập nhật bản tiếng Việt. |

## Quy ước trong các trang này

- Chữ `monospace` dùng cho mã nguồn, tên tệp và giá trị được viết đúng như trong mã.
- Số tiền trong ví dụ tính bằng đồng Việt Nam và không có phần thập phân, trừ khi ví dụ nói về làm tròn. Trong lời văn, số được viết theo cách của tiếng Việt (25.750.000,50); trong mã nguồn và JSON, số giữ nguyên cách viết của mã (25750000.50).
- Mã nguồn, JSON, kết quả in ra, tên định danh, tên tệp, mã lý do và tên API được giữ nguyên tiếng Anh.
- Mọi ví dụ trong [`examples/`](../../examples/) và mọi đoạn mã Java trong *Bắt đầu* và *Java API* đều được bộ kiểm thử chạy thật. Nếu bạn thấy các trang này khác với hành vi của RuleStone, xin hãy [báo cho chúng tôi](../../CONTRIBUTING.md#ways-to-contribute). Nếu bản dịch khác với bản tiếng Anh, bản tiếng Anh là bản đúng.
