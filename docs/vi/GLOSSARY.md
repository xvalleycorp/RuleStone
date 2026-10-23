# Bảng thuật ngữ · Glossary

Terminology for the Vietnamese translation, so that every page uses the same words. Use the Vietnamese term below; where the table says to keep the English term, keep it, and explain it in Vietnamese the first time it appears on a page (in parentheses or in the sentence). Add new terms here in the same pull request as the translation that needs them, in alphabetical order of the English term.

Bảng thuật ngữ dùng cho bản dịch tiếng Việt, để mọi trang dùng thống nhất một cách gọi. Nếu cột "Tiếng Việt" ghi giữ nguyên, hãy giữ thuật ngữ tiếng Anh và giải thích bằng tiếng Việt ở lần xuất hiện đầu tiên trong trang.

**Never translated:** code, JSON, program output, identifiers and API names (`RuleStoneEngine`, `DecisionResult`), file names (`.stone.json`), JSON keys (`hitPolicy`, `reasonCode`), reason codes (`DTI_HIGH`), hit policy names (`FIRST`, `UNIQUE`, `COLLECT`), lifecycle states (`DRAFT`, `ACTIVE`), product and standard names (CEL, Maven, PCI DSS).

## Rules and decisions

| English | Tiếng Việt | Ghi chú |
|---|---|---|
| business rule | luật nghiệp vụ | |
| business rules engine, rules engine | bộ máy luật nghiệp vụ, bộ máy luật | Tên loại sản phẩm. Đối tượng `RuleStoneEngine` trong mã gọi là "engine". |
| business rules management | quản lý luật nghiệp vụ | |
| decision | quyết định | |
| decision graph | đồ thị quyết định | |
| decision table | bảng quyết định | |
| definition (of a decision) | định nghĩa (quyết định, luật) | |
| effective date; effective from / to | ngày hiệu lực; hiệu lực từ / đến | |
| effective dating | hiệu lực theo ngày | |
| business date | ngày nghiệp vụ | Ngày mà quyết định được xét theo, khác với ngày hệ thống. |
| evaluate | đánh giá | "Đánh giá một quyết định", "luật không được đánh giá". |
| hit policy | hit policy | Giữ nguyên; giải thích là "chính sách khớp luật": cách chọn kết quả khi nhiều luật cùng khớp. |
| match (a rule matches) | khớp (luật khớp) | |
| overlap (versions, rows) | chồng lấn | |
| reason code | mã lý do | Bản thân mã (`DTI_HIGH`) giữ nguyên. |
| rule | luật | Không dùng "quy tắc" cho rule của bảng quyết định. |
| rule set | tập luật | |
| rule version | phiên bản luật | |
| row (of a decision table) | dòng | |
| sub-decision | quyết định con | |
| trace | vết thực thi | Có thể ghi "(trace)" ở lần đầu. |

## Expressions and types

| English | Tiếng Việt | Ghi chú |
|---|---|---|
| expression | biểu thức | |
| expression language | ngôn ngữ biểu thức | |
| field | trường | |
| floating point, floating-point number | dấu phẩy động, số dấu phẩy động | |
| input / output | đầu vào / đầu ra | |
| missing field | trường bị thiếu | |
| normalization | chuẩn hoá | Chuẩn hoá tên trước khi so khớp. |
| overload | overload | Giữ nguyên trong thông báo lỗi; có thể giải thích "phiên bản nạp chồng của hàm". |
| pure function | hàm thuần (pure) | |
| round, round down / up | làm tròn, làm tròn xuống / lên | |
| similarity score | điểm tương đồng | |
| struct | struct | Giữ nguyên; giải thích "kiểu cấu trúc gồm các trường có kiểu". |
| threshold | ngưỡng | |
| type, typed | kiểu, có kiểu | "Đầu vào có kiểu". |
| type check, type-checked | kiểm tra kiểu, được kiểm tra kiểu | |
| type error | lỗi kiểu | |

## Engine and integration

| English | Tiếng Việt | Ghi chú |
|---|---|---|
| AI agent | AI agent | Giữ nguyên "agent". |
| assertion (fluent assertions) | assertion (khẳng định trong kiểm thử) | |
| builder | builder | Giữ nguyên (mẫu thiết kế). |
| compile | biên dịch | |
| dependency | thư viện phụ thuộc, phụ thuộc | |
| embed, embedded | nhúng, chế độ nhúng | |
| guardrail | rào chắn | |
| hot reload | nạp lại nóng (hot reload) | |
| immutable | bất biến | |
| load, load time | nạp, lúc nạp | "Bắt lỗi ngay lúc nạp luật". |
| module | mô-đun | Tên mô-đun (`rulestone-core`) giữ nguyên. |
| pre-release | bản tiền phát hành | |
| release, publish (a version) | phát hành | |
| redeploy | triển khai lại | |
| repository (rule repository) | kho luật | Kho mã nguồn Git: "repository" hoặc "kho mã". |
| roadmap | lộ trình | |
| shadow mode | chế độ bóng (shadow) | |
| SPI (service provider interface) | SPI (giao diện nhà cung cấp dịch vụ) | |
| test case | ca kiểm thử | |
| test coverage, rule coverage | độ bao phủ kiểm thử, độ bao phủ luật | |
| thread-safe | an toàn đa luồng | |
| timeout | thời gian chờ tối đa | |

## Banking and governance

| English | Tiếng Việt | Ghi chú |
|---|---|---|
| adverse-action notice | thông báo từ chối (adverse action) | Thông báo lý do từ chối gửi khách hàng. |
| AML (anti-money laundering) | phòng, chống rửa tiền (AML) | |
| alias | bí danh | |
| applicant | người vay | Trong ví dụ xét điều kiện vay. |
| audit, auditable | kiểm toán, kiểm toán được | |
| audit trail | dấu vết kiểm toán | |
| backtesting | kiểm thử ngược (backtesting) | |
| champion/challenger | champion/challenger | Giữ nguyên; giải thích "so sánh phiên bản đang chạy với phiên bản thử thách". |
| core banking | core banking | Giữ nguyên (hệ thống ngân hàng lõi). |
| credit scoring | chấm điểm tín dụng | |
| debt-to-income ratio (DTI) | tỷ lệ nợ trên thu nhập (DTI) | |
| dormant account | tài khoản ngủ đông | |
| enhanced due diligence | thẩm định tăng cường | |
| deposit rate | lãi suất tiền gửi | |
| deterministic | tất định | |
| eligibility | điều kiện; xét điều kiện (vay) | "Loan eligibility" → "xét điều kiện vay". |
| explainability | khả năng giải thích | |
| false positive / false negative | dương tính giả / âm tính giả | Cảnh báo sai / bỏ sót. |
| fraud | gian lận | |
| fuzzy matching | so khớp gần đúng | |
| KYC (know your customer) | KYC (nhận biết khách hàng) | |
| governance | quản trị | |
| limit (credit limit) | hạn mức | |
| large cash transaction report (CTR) | báo cáo giao dịch tiền mặt lớn | |
| maker-checker | maker-checker (người lập – người duyệt) | |
| mask (data masking) | che (che dữ liệu) | |
| PEP (politically exposed person) | người có ảnh hưởng chính trị (PEP) | |
| personal data protection | bảo vệ dữ liệu cá nhân | |
| policy (credit policy) | chính sách | |
| reproducible | tái lập được | |
| sanctions screening | sàng lọc danh sách cấm vận | |
| structuring | chia nhỏ giao dịch | Chia nhỏ để né ngưỡng báo cáo. |
| suspicious transaction report (STR) | báo cáo giao dịch đáng ngờ | |
| segregation of duties | phân tách nhiệm vụ | |
| tamper-evident | chống giả mạo | |
| transfer fee | phí chuyển tiền | |
| transliteration | chuyển tự | |
| underwriting | thẩm định tín dụng | |
| unsecured (consumer) loan | khoản vay (tiêu dùng) tín chấp | |
| value date | ngày giá trị | |
| watchlist | danh sách theo dõi | |

## Software supply chain and project

| English | Tiếng Việt | Ghi chú |
|---|---|---|
| artifact (Maven) | artifact | Giữ nguyên. |
| contribution, contributor | đóng góp, người đóng góp | |
| license, licensed under | giấy phép, được cấp phép theo | "Giấy phép Apache 2.0". |
| SBOM (software bill of materials) | danh mục thành phần phần mềm (SBOM) | |
| sign-off (DCO) | sign-off | Giữ nguyên. |
| supply chain | chuỗi cung ứng phần mềm | |
| vulnerability | lỗ hổng bảo mật | |
