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
| aggregate function | hàm tổng hợp | `sum`, `min`, `max`, `avg` trên danh sách. |
| expression | biểu thức | |
| expression language | ngôn ngữ biểu thức | |
| field | trường | |
| floating point, floating-point number | dấu phẩy động, số dấu phẩy động | |
| input / output | đầu vào / đầu ra | |
| local value | giá trị cục bộ | Khai báo trong `let` của bảng quyết định. |
| mean | trung bình | |
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
| benchmark | benchmark (phép đo hiệu năng) | Giữ nguyên; JMH. |
| buffer (producer buffer) | bộ đệm (buffer) | Bộ đệm của Kafka producer. |
| builder | builder | Giữ nguyên (mẫu thiết kế). |
| canary, canary release | canary, phát hành canary | Giữ nguyên; một phần lưu lượng được chuyển sang phiên bản mới. |
| case management | quản lý case | Hàng đợi và xử lý các case cần người review; thuộc ứng dụng, không thuộc RuleStone. |
| compile | biên dịch | |
| correlation id | mã tương quan (correlation id) | Mã giao dịch nghiệp vụ của bên gọi, ví dụ số hồ sơ vay. |
| dependency | thư viện phụ thuộc, phụ thuộc | |
| embed, embedded | nhúng, chế độ nhúng | |
| fallback | phương án dự phòng | Chính sách quyết định thay khi không có kết quả. |
| inline path | luồng xử lý trực tiếp (inline) | Đường xử lý mà bên gọi chờ kết quả, ví dụ khi duyệt thanh toán. |
| facts hash | hash của đầu vào | Giá trị `factsHash`. |
| guardrail | rào chắn | |
| HMAC key | khoá HMAC | |
| hot reload | nạp lại nóng (hot reload) | |
| immutable | bất biến | |
| label (of a reviewed case) | nhãn | Kết luận của người review, dùng để huấn luyện mô hình. |
| load, load time | nạp, lúc nạp | "Bắt lỗi ngay lúc nạp luật". |
| module | mô-đun | Tên mô-đun (`rulestone-core`) giữ nguyên. |
| pre-release | bản tiền phát hành | |
| redeploy | triển khai lại | |
| release, publish (a version) | phát hành | |
| repository (rule repository) | kho luật | Kho mã nguồn Git: "repository" hoặc "kho mã". |
| roadmap | lộ trình | |
| shadow mode | chế độ bóng (shadow) | |
| shadow version | phiên bản bóng (shadow) | Được đánh giá chỉ để ghi lại, không trả kết quả cho bên gọi. |
| SPI (service provider interface) | SPI (giao diện nhà cung cấp dịch vụ) | |
| monotonic clock | đồng hồ đơn điệu | Đồng hồ đo thời gian trôi qua, không bị chỉnh giờ (`System.nanoTime`). |
| test case | ca kiểm thử | |
| test coverage, rule coverage | độ bao phủ kiểm thử, độ bao phủ luật | |
| thread | luồng thực thi (thread) | Để phân biệt với luồng quyết định. |
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
| adapter (model) | adapter | Giữ nguyên; mô-đun nối RuleStone với một loại máy chủ hoặc định dạng mô hình. |
| attribution (model) | attribution (mức đóng góp của đặc trưng) | |
| attested input | đầu vào có chứng thực | |
| BPMN error | BPMN error | Giữ nguyên. |
| boundary event | boundary event | Giữ nguyên. |
| branch (of a flow node) | nhánh | Một lối ra của nút có nhiều lối ra. |
| bucket (split node) | nhóm (bucket) | |
| champion/challenger | champion/challenger | Xem bảng "Banking and governance". Nhóm: bucket → nhóm. |
| confidence (model) | độ tin cậy | `minConfidence`. |
| contract (model node) | hợp đồng | "Hợp đồng có kiểu" của nút mô hình: phiên bản ghim, đầu ra và kiểu, điều kiện `valid`. |
| contribution (attribution) | mức đóng góp | |
| decision flow | luồng quyết định | |
| decision record | bản ghi quyết định | |
| delegate (Camunda 7) | delegate | Giữ nguyên (`JavaDelegate`). |
| derived value | giá trị dẫn xuất | Giá trị do nút `derive` tính. |
| element template | element template | Giữ nguyên. |
| failure path (`onFailure`) | đường xử lý lỗi | Đường mà nút mô hình đi theo khi mô hình lỗi. |
| feature (model) | đặc trưng | |
| hybrid AI decisioning | ra quyết định AI lai | |
| incident (Camunda) | incident | Giữ nguyên; sự cố cần người vận hành xử lý. |
| invoke mode, supplied mode | chế độ `invoke`, chế độ `supplied` | Giữ nguyên tên chế độ. |
| invoker (model) | invoker | Giữ nguyên (`ModelInvoker`): đối tượng gọi mô hình. |
| job worker | job worker | Giữ nguyên. |
| knock-out (policy rule) | luật loại trừ (knock-out) | Luật chính sách từ chối hồ sơ ngay. |
| latency | độ trễ | `latencyMs`. |
| machine-learning model | mô hình học máy (ML) | |
| model node | nút mô hình | Nút `model` của luồng quyết định. |
| node | nút | |
| node coverage | độ bao phủ nút | Mọi nút được đi qua và mọi nhánh được đi theo. |
| path (of a flow) | đường đi | Các nút mà một lần đánh giá đi qua. |
| payload | payload | Giữ nguyên. |
| pin (a version) | ghim (phiên bản) | |
| probability of default (PD) | xác suất vỡ nợ (PD) | |
| process variable | biến quy trình | |
| publish (node values) | công bố (giá trị của nút) | Nút công bố giá trị dưới id của nó hoặc tên `as`. |
| rank (attribution) | thứ hạng | Cùng với chiều (direction): tăng rủi ro, giảm rủi ro. |
| refer (to a person) | chuyển người xét duyệt, chuyển xem xét thủ công | Kết quả `REFER`. |
| replay (decision) | replay, chạy lại | "Replay" giữ nguyên khi nói về Temporal. |
| re-score | chấm điểm lại | |
| salt (split) | salt | Giữ nguyên. |
| scorecard | thẻ điểm | Mô hình chấm điểm dạng cộng điểm. |
| search attribute | search attribute | Giữ nguyên. |
| shadow model | mô hình chạy bóng (shadow) | |
| side effect (Temporal) | side effect | Giữ nguyên (`Workflow.sideEffect`). |
| split (node) | phép chia (nút `split`) | Chia theo tỷ lệ phần trăm. |
| start node, end node | nút bắt đầu, nút kết thúc | |
| strategy tree | cây chiến lược | |
| switch (node) | nút rẽ nhánh (`switch`) | |
| utilisation (credit) | mức sử dụng hạn mức | |
| web editor | trình soạn thảo web | |
| workflow | workflow | Giữ nguyên. |
| workflow history | history của workflow | |
| workflow replay | replay workflow | |
| workflow task | workflow task | Giữ nguyên. |

## Browser SDK and playground

| English | Tiếng Việt | Ghi chú |
|---|---|---|
| accessibility | trợ năng | |
| arrange (nodes) | sắp xếp lại (các nút) | Nút "Arrange" của vùng vẽ luồng. |
| attribute (HTML) | attribute | Giữ nguyên, để phân biệt với property ("thuộc tính"). |
| bundle size | kích thước gói | |
| bundler | bundler | Giữ nguyên (Vite, webpack, esbuild). |
| canvas (flow canvas) | vùng vẽ (vùng vẽ luồng) | `<rulestone-flow-canvas>`. |
| condition builder | trình tạo điều kiện | `<rulestone-condition-builder>`, đúng như chuỗi trong các thành phần. |
| conformance suite | bộ kiểm thử tương thích | Bộ kiểm thử so sánh bộ máy luật trên trình duyệt với bộ máy luật Java. |
| element (Web Component) | phần tử | "Phần tử `<rulestone-table-editor>`". |
| engine of record | bộ máy luật chính thức (engine of record) | Bộ máy luật Java, dùng cho quyết định trong môi trường production. |
| event | sự kiện | Tên sự kiện (`rulestone-change`) giữ nguyên. |
| export, import (a file) | xuất, nhập (tệp) | |
| fit (zoom to fit) | vừa khung | Nút "Fit" của vùng vẽ luồng. |
| hook (validator hook) | hook | Giữ nguyên. |
| host (page, application) | ứng dụng chủ, trang chủ quản (host) | Ứng dụng nhúng các phần tử. |
| method | phương thức | |
| palette (flow canvas) | bảng các nút | Chuỗi trong các thành phần: "Các nút". |
| pan, zoom | cuộn, thu phóng (phóng to, thu nhỏ) | |
| part (`::part`) | part | Giữ nguyên. |
| persistence | lưu trữ | |
| playground | sân thử | Trang thử các thành phần trên trang web. |
| property (of an element) | thuộc tính | |
| rules grid | lưới luật | `<rulestone-rules-grid>`. |
| sample facts | dữ liệu mẫu | |
| schema designer | trình thiết kế đầu vào và đầu ra | `<rulestone-schema-designer>`; chuỗi trong các thành phần: "Đầu vào và đầu ra". |
| share link | liên kết chia sẻ | |
| strict reader | trình đọc chặt chẽ | Trình đọc `.stone.json` báo lỗi với trường lạ và khoá trùng. |
| theme, theming | giao diện (theme) | Giao diện sáng, giao diện tối. |
| token (design token) | token | Giữ nguyên (`--rs-color-bg`). |
| Try it | Chạy thử | Tên bảng `<rulestone-try-it>`. |
| Web Component | Web Component | Giữ nguyên. |

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
