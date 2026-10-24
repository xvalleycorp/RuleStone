# Bảng thuật ngữ · Glossary

Terminology for the Vietnamese translation, so that every page uses the same words. Use the Vietnamese term below; where the table says to keep the English term, keep it, and explain it in Vietnamese the first time it appears on a page (in parentheses or in the sentence). Add new terms here in the same pull request as the translation that needs them, in alphabetical order of the English term.

Bảng thuật ngữ dùng cho bản dịch tiếng Việt, để mọi trang dùng thống nhất một cách gọi. Nếu cột "Tiếng Việt" ghi giữ nguyên, hãy giữ thuật ngữ tiếng Anh và giải thích bằng tiếng Việt ở lần xuất hiện đầu tiên trong trang.

**Never translated:** code, JSON, program output, identifiers and API names (`RuleStoneEngine`, `DecisionResult`), file names (`.stone.json`), JSON keys (`hitPolicy`, `reasonCode`), reason codes (`DTI_HIGH`), hit policy names (`FIRST`, `UNIQUE`, `COLLECT`), lifecycle states (`DRAFT`, `ACTIVE`), product and standard names (CEL, Maven, PCI DSS).

## Rules and decisions

| English | Tiếng Việt | Ghi chú |
|---|---|---|
| business date | ngày nghiệp vụ | Ngày mà quyết định được xét theo, khác với ngày hệ thống. |
| business rule | luật nghiệp vụ | |
| business rules engine, rules engine | bộ máy luật nghiệp vụ, bộ máy luật | Tên loại sản phẩm. Đối tượng `RuleStoneEngine` trong mã gọi là "engine". |
| business rules management | quản lý luật nghiệp vụ | |
| decision | quyết định | |
| decision graph | đồ thị quyết định | |
| decision table | bảng quyết định | |
| definition (of a decision) | định nghĩa (quyết định, luật) | |
| effective date; effective from / to | ngày hiệu lực; hiệu lực từ / đến | |
| effective dating | hiệu lực theo ngày | |
| evaluate | đánh giá | "Đánh giá một quyết định", "luật không được đánh giá". |
| hit policy | hit policy | Giữ nguyên; giải thích là "chính sách khớp luật": cách chọn kết quả khi nhiều luật cùng khớp. |
| match (a rule matches) | khớp (luật khớp) | |
| overlap (versions, rows) | chồng lấn | |
| reason code | mã lý do | Bản thân mã (`DTI_HIGH`) giữ nguyên. |
| row (of a decision table) | dòng | |
| rule | luật | Không dùng "quy tắc" cho rule của bảng quyết định. |
| rule set | tập luật | |
| rule version | phiên bản luật | |
| sub-decision | quyết định con | |
| supersede (a version) | thay thế (phiên bản) | Phiên bản mới thay thế phiên bản trước từ `effectiveFrom` của nó. |
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
| audit sink | audit sink (nơi nhận bản ghi kiểm toán) | Giữ nguyên; `AuditSink`. |
| builder | builder | Giữ nguyên (mẫu thiết kế). |
| compile | biên dịch | |
| correlation id | mã tương quan (correlation id) | Mã giao dịch nghiệp vụ của bên gọi, ví dụ số hồ sơ vay. |
| dependency | thư viện phụ thuộc, phụ thuộc | |
| embed, embedded | nhúng, chế độ nhúng | |
| facts hash | hash của đầu vào | Giá trị `factsHash`. |
| guardrail | rào chắn | |
| HMAC key | khoá HMAC | |
| hot reload | nạp lại nóng (hot reload) | |
| immutable | bất biến | |
| load, load time | nạp, lúc nạp | "Bắt lỗi ngay lúc nạp luật". |
| module | mô-đun | Tên mô-đun (`rulestone-core`) giữ nguyên. |
| pre-release | bản tiền phát hành | |
| redeploy | triển khai lại | |
| release, publish (a version) | phát hành | |
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

## Decision flows, AI and orchestration

| English | Tiếng Việt | Ghi chú |
|---|---|---|
| activity (Temporal) | activity | Giữ nguyên; một bước công việc do worker thực thi. |
| attribution (model) | attribution (mức đóng góp của đặc trưng) | |
| attested input | đầu vào có chứng thực | |
| BPMN error | BPMN error | Giữ nguyên. |
| boundary event | boundary event | Giữ nguyên. |
| champion/challenger | champion/challenger | Xem bảng "Banking and governance". Nhóm: bucket → nhóm. |
| decision flow | luồng quyết định | |
| decision record | bản ghi quyết định | |
| delegate (Camunda 7) | delegate | Giữ nguyên (`JavaDelegate`). |
| element template | element template | Giữ nguyên. |
| feature (model) | đặc trưng | |
| hybrid AI decisioning | ra quyết định AI lai | |
| incident (Camunda) | incident | Giữ nguyên; sự cố cần người vận hành xử lý. |
| job worker | job worker | Giữ nguyên. |
| machine-learning model | mô hình học máy (ML) | |
| node | nút | |
| payload | payload | Giữ nguyên. |
| pin (a version) | ghim (phiên bản) | |
| process variable | biến quy trình | |
| replay (decision) | replay, chạy lại | "Replay" giữ nguyên khi nói về Temporal. |
| re-score | chấm điểm lại | |
| search attribute | search attribute | Giữ nguyên. |
| shadow model | mô hình chạy bóng (shadow) | |
| side effect (Temporal) | side effect | Giữ nguyên (`Workflow.sideEffect`). |
| strategy tree | cây chiến lược | |
| web editor | trình soạn thảo web | |
| workflow | workflow | Giữ nguyên. |
| workflow history | history của workflow | |
| workflow replay | replay workflow | |
| workflow task | workflow task | Giữ nguyên. |

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
