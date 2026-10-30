---
source: README.md
sourceHash: 3da78efd20f2337845dc5294d6057f377ede14980707cbd90ce8e1df5781d7b1
---

# Tài liệu RuleStone

Đây là bản tiếng Việt của tài liệu RuleStone. Bản tiếng Anh là bản gốc; trang nào chưa được dịch sẽ được đánh dấu "(English)" và dẫn tới bản tiếng Anh. Thuật ngữ dùng trong bản dịch được thống nhất trong [bảng thuật ngữ](GLOSSARY.md).

## Hướng dẫn

| Trang | Đọc để |
|---|---|
| [Bắt đầu](getting-started.md) | Viết và đánh giá bảng quyết định đầu tiên của bạn trong mười phút. |
| [Bảng quyết định](decision-tables.md) | Nắm định dạng `.stone.json`, hit policy, phiên bản, ngày hiệu lực và cách kiểm tra hợp lệ. |
| [Ngôn ngữ biểu thức](expressions.md) | Viết điều kiện và đầu ra: toán tử, số thập phân, làm tròn, danh sách, trường bị thiếu, lỗi. |
| [So khớp gần đúng](fuzzy-matching.md) | So sánh tên khi sàng lọc AML/KYC: chuẩn hoá, điểm tương đồng, ngưỡng và các giới hạn. |
| [Java API](java-api.md) | Nhúng engine vào ứng dụng: đầu vào, kết quả, lỗi, nạp lại luật, đa luồng. |
| [Luồng quyết định](flows.md) | Nối các bảng quyết định thành một quyết định với nút rẽ nhánh, giá trị dẫn xuất, phép chia champion/challenger và nút kết thúc, theo định dạng `.flow.json`. |
| [Kiểm thử](testing.md) | Kiểm thử bảng quyết định và luồng quyết định: ca kiểm thử bằng JSON, tích hợp JUnit 5, độ bao phủ luật và nút, và assertion dạng fluent. |
| [Trình soạn thảo web](editor.md) | Tạo và sửa bảng quyết định trên trình duyệt, thử với các fact mẫu và lưu tệp `.stone.json`. |
| [SDK trình soạn thảo](editor-sdk.md) | Nhúng các trình soạn thảo trực quan vào ứng dụng web của bạn, với React, Angular, Vue hoặc HTML thuần, và chạy luật ngay trong trình duyệt. |
| [Tích hợp Temporal](temporal.md) | Đánh giá quyết định từ workflow Temporal, dưới dạng activity hoặc ngay trong mã workflow với phiên bản luật được ghim. |
| [Tích hợp Camunda](camunda.md) | Gọi quyết định từ quy trình BPMN bằng job worker của Camunda 8 hoặc delegate của Camunda 7. |
| [Ví dụ](examples.md) | Tìm hiểu các bảng quyết định hoàn chỉnh cho khoản vay, lãi suất tiền gửi, phí chuyển tiền, gian lận thẻ, thẩm định tín dụng và AML, và một luồng phát hành thẻ tín dụng. |

## Thiết kế

| Trang | Nội dung |
|---|---|
| [Kiến trúc](architecture.md) | Mục tiêu, nguyên tắc thiết kế, các mô-đun, các chế độ tích hợp, yêu cầu của ngân hàng, tích hợp AI và lộ trình. |
| [Ra quyết định AI lai](hybrid-ai.md) | Đề xuất thiết kế: điểm số của mô hình làm đầu vào cho luật, mô hình là nút trong luồng quyết định, và bản ghi quyết định giải thích mọi quyết định. |
| [Tham số, hàm tổng hợp và giá trị cục bộ](parameters.md) | Đề xuất thiết kế: bộ tham số cho các ngưỡng của chính sách, hàm tổng hợp trên danh sách, và giá trị cục bộ trong bảng. |

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
