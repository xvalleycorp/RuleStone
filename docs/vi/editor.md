---
source: editor.md
sourceHash: 3b502f84aa422b2d5d25c24a2012a7d0955f3f39109ded36a9f97ace6f91bee3
---

# Trình soạn thảo web

Trình soạn thảo RuleStone là một ứng dụng web nhỏ để tạo và sửa bảng quyết định mà không phải gõ JSON. Chuyên viên phân tích nghiệp vụ sửa luật trên một lưới, dựng điều kiện từ các danh sách thả xuống, kiểm thử bảng với dữ liệu mẫu và thấy lỗi ngay tại ô liên quan trong lúc gõ. Lập trình viên vẫn nhận được các tệp `.stone.json` như trước, được lưu theo một định dạng ổn định để diff gọn gàng trong Git.

- [Chạy trình soạn thảo](#chạy-trình-soạn-thảo)
- [Sử dụng trình soạn thảo](#sử-dụng-trình-soạn-thảo)
- [Mô hình bảo mật](#mô-hình-bảo-mật)
- [Cách lưu tệp](#cách-lưu-tệp)
- [Trình dựng điều kiện và CEL](#trình-dựng-điều-kiện-và-cel)
- [Tham chiếu API](#tham-chiếu-api)
- [Nhúng trong Java](#nhúng-trong-java)
- [Hạn chế và lộ trình](#hạn-chế-và-lộ-trình)

## Chạy trình soạn thảo

Trình soạn thảo là một tệp jar thực thi, không cần gì khác ngoài Java 17 trở lên. Build từ thư mục gốc của repository:

```
mvn -B verify
```

Sau đó khởi động nó trên thư mục chứa các luật cần sửa, thường là một bản clone của repository Git chứa các tệp `.stone.json`:

```
java -jar rulestone-editor/target/rulestone-editor-0.1.0-SNAPSHOT-all.jar --rules-dir ./rules
```

và mở `http://127.0.0.1:8080/`.

| Tuỳ chọn | Mặc định | Ý nghĩa |
|---|---|---|
| `--rules-dir <dir>` | bắt buộc | Thư mục chứa các tệp `.stone.json`, được tìm đệ quy. Các thư mục ẩn như `.git` được bỏ qua. |
| `--port <n>` | `8080` | Cổng lắng nghe; `0` chọn một cổng còn trống. |
| `--host <address>` | `127.0.0.1` | Địa chỉ lắng nghe. Hãy đọc [Mô hình bảo mật](#mô-hình-bảo-mật) trước khi thay đổi. |
| `--allowed-host <name>` | không có | Một header `Host` khác được chấp nhận, như tên của reverse proxy, ví dụ `rules.bank.example`. Có thể lặp lại. |
| `--max-body-bytes <n>` | `1048576` | Kích thước body tối đa của yêu cầu được chấp nhận. |

Tệp jar chứa sẵn giao diện người dùng; không có gì được tải về lúc chạy và không cần truy cập Internet.

## Sử dụng trình soạn thảo

**Không gian làm việc.** Trang đầu liệt kê mọi quyết định trong thư mục, nhóm theo `id`, với thời gian hiệu lực, hit policy, số luật và số lỗi của từng phiên bản. Phiên bản có hiệu lực hôm nay được đánh dấu. **New decision table** mở một trình hướng dẫn để nhập id, phiên bản, hit policy và ngày hiệu lực, rồi tạo một tệp với một luật hứng mọi trường hợp.

**Luật.** Mỗi luật là một dòng: id, mô tả, điều kiện (*when*), một cột cho mỗi đầu ra, và mã lý do. Có thể thêm, nhân bản, xoá và sắp xếp lại các dòng bằng các nút, bằng cách kéo tay nắm, hoặc bằng <kbd>Alt</kbd>+<kbd>↑</kbd>/<kbd>↓</kbd>. Lưới điều hướng được bằng bàn phím: phím mũi tên di chuyển giữa các ô, <kbd>Enter</kbd> sửa một ô và <kbd>Escape</kbd> quay về lưới.

- Ô *when* hiển thị điều kiện dưới dạng các mệnh đề dễ đọc khi [trình dựng điều kiện](#trình-dựng-điều-kiện-và-cel) biểu diễn được, nếu không thì hiển thị CEL có tô sáng cú pháp. **Builder…** mở trình dựng điều kiện; **CEL** chuyển sang biểu thức với tính năng tự hoàn thành đường dẫn đầu vào (`applicant.monthlyIncome`) và tên hàm.
- Ô đầu ra hiển thị một trường có kiểu cho giá trị hằng: văn bản cho `string`, số cho `int` và `decimal`, số tiền và loại tiền cho `money`, bộ chọn ngày cho `date`, true/false cho `bool`, và thẻ (tag) cho danh sách. **ƒx** chuyển ô sang biểu thức CEL. Ô trống nghĩa là luật không đặt đầu ra đó.

**Đầu vào và đầu ra.** Khai báo đầu vào và đầu ra của bảng với bộ chọn kiểu, cấu trúc lồng nhau và danh sách. Danh sách kiểu lấy từ máy chủ, nên các kiểu mới thêm vào RuleStone sẽ xuất hiện mà không cần sửa trình soạn thảo.

**Kiểm tra hợp lệ.** Mọi thay đổi đều được máy chủ kiểm tra trong vòng nửa giây, đúng như `RuleStone.builder().build()` sẽ làm, cộng thêm việc đối chiếu với các phiên bản khác của cùng quyết định trong thư mục: phiên bản trùng lặp, hai phiên bản có hiệu lực cùng một ngày, và một `effectiveTo` nằm sau ngày bắt đầu của phiên bản kế tiếp (xem [Phiên bản và ngày hiệu lực](decision-tables.md#phiên-bản-và-ngày-hiệu-lực)). Lỗi xuất hiện ngay tại ô liên quan, với phần sai của biểu thức được gạch chân, và trong bảng lỗi. **Save** bị vô hiệu khi còn lỗi; **Save anyway** lưu một bảng không hợp lệ sau khi xác nhận, ví dụ để chia sẻ công việc đang làm dở trong một nhánh.

**Kiểm thử.** Đánh giá bảng đúng như trong trình soạn thảo, kể cả các thay đổi chưa lưu, với các fact nhập trong một biểu mẫu sinh ra từ các đầu vào hoặc dưới dạng JSON, và một ngày nghiệp vụ (`asOf`). Kết quả hiển thị các đầu ra, mã lý do, các luật khớp và phiên bản luật, còn thẻ Rules tô sáng các dòng khớp, không khớp và không được đánh giá.

**JSON và thay đổi.** Tệp đúng như sẽ được lưu, có nút sao chép, và một bản diff theo dòng so với tệp lúc được nạp.

**Save as new version** chép các luật, kể cả thay đổi chưa lưu, vào một tệp mới với số phiên bản tiếp theo và một `effectiveFrom` mới. Tệp của phiên bản hiện tại không bị sửa: phiên bản mới thay thế nó từ ngày đó, nên phiên bản luật (mã băm) của phiên bản hiện tại giữ nguyên.

Giao diện người dùng có tiếng Anh và tiếng Việt.

## Mô hình bảo mật

**Trình soạn thảo trong phiên bản này không có xác thực và không có phân quyền.** Bất kỳ ai gửi được yêu cầu tới cổng của nó đều có thể đọc và sửa mọi luật trong thư mục. Hãy chạy nó theo một trong hai cách:

1. **Trên máy của chính người dùng**, cách mặc định: nó chỉ lắng nghe trên `127.0.0.1`, nên các máy khác không truy cập được. Mỗi người dùng sửa trên một bản clone của repository luật và phát hành thay đổi qua Git cùng quy trình rà soát thông thường của bạn.
2. **Phía sau reverse proxy đăng nhập một lần (SSO) của tổ chức bạn**, proxy này xác thực người dùng và chỉ cho phép những người được sửa luật. Hãy gắn trình soạn thảo vào một địa chỉ mà chỉ proxy truy cập được, và khai báo tên host của proxy:

   ```
   java -jar rulestone-editor-0.1.0-SNAPSHOT-all.jar --rules-dir /srv/rules --host 10.0.0.5 --allowed-host rules.bank.example
   ```

   Trình soạn thảo phục vụ các trang với URL tương đối, nên cũng chạy được dưới một đường dẫn con của proxy.

Không bao giờ để nó truy cập trực tiếp từ mạng. Trong cả hai cách, việc phê duyệt thay đổi luật (maker-checker) diễn ra bên ngoài trình soạn thảo, trong Git: hãy rà soát và merge các commit trước khi luật lên production.

Trình soạn thảo tự bảo vệ trước các tấn công từ trang web khác và các yêu cầu không hợp lệ:

| Mối đe doạ | Biện pháp bảo vệ |
|---|---|
| Một trang web khác điều khiển trình duyệt của người dùng (CSRF) | Yêu cầu có `Origin` khác với của trình soạn thảo bị từ chối (403). Các yêu cầu API đánh dấu `Sec-Fetch-Site: cross-site` cũng vậy. Body của yêu cầu phải là `application/json`, thứ mà một form HTML không gửi được, và các yêu cầu CORS preflight không được trả lời. |
| DNS rebinding | Header `Host` phải là `localhost`, `127.0.0.1` hoặc `[::1]` với cổng của trình soạn thảo, địa chỉ đang gắn, hoặc một `--allowed-host`; nếu không sẽ trả về 421. |
| Đọc hoặc ghi các tệp khác | Tên tệp phải là đường dẫn tương đối gồm chữ cái, chữ số, `_`, `.` và `-`, kết thúc bằng `.stone.json`, sâu tối đa 8 cấp thư mục. `..`, đường dẫn tuyệt đối, tệp ẩn, dấu gạch chéo ngược, dấu phân cách đã mã hoá và liên kết tượng trưng đều bị từ chối. |
| Yêu cầu quá lớn | Body lớn hơn `--max-body-bytes` bị từ chối (413); tệp lớn hơn 4 MiB không được đọc. |
| Chèn script, nhúng khung (framing) | `Content-Security-Policy: default-src 'self'; script-src 'self'; ... frame-ancestors 'none'`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`. |
| Dữ liệu khách hàng trong log | Các fact nhập trong bảng kiểm thử không bao giờ được ghi log hay trả lại: thông báo lỗi nêu tên đầu vào và kiểu mong đợi, không bao giờ nêu giá trị. Log của máy chủ chỉ ghi lúc khởi động và các lỗi không lường trước, không có nội dung yêu cầu. |
| Mất cập nhật | Khi lưu, trình soạn thảo gửi `ETag` của tệp lúc được nạp trong `If-Match`; nếu trong lúc đó tệp trên đĩa đã thay đổi (người dùng khác, `git pull`), việc lưu bị từ chối (412) và người dùng nạp lại. Tệp được ghi một cách nguyên tử (tệp tạm, rồi đổi tên). |

## Cách lưu tệp

Trình soạn thảo ghi tệp theo một bố cục cố định, dễ diff, để thay đổi một luật chỉ hiện ra thành thay đổi ở vài dòng trong Git:

- các khoá theo thứ tự cố định: với bảng là `id`, `version`, `description`, `effectiveFrom`, `effectiveTo`, `hitPolicy`, `inputs`, `outputs`, `rules`; với luật là `id`, `description`, `when`, `then`, `reasonCode`;
- giữ thứ tự của tác giả cho đầu vào, đầu ra, trường struct và `then`, vì thứ tự đó có ý nghĩa (đầu ra được báo cáo theo thứ tự khai báo);
- thụt lề hai khoảng trắng, mỗi khoá một dòng, đối tượng chỉ có một giá trị ngắn nằm trên một dòng (`"then": { "decision": "'REJECT'" }`), danh sách ngắn nằm trên một dòng (`["decimal"]`), UTF-8 không escape văn bản tiếng Việt, và một dòng trống ở cuối tệp.

Việc định dạng không bao giờ làm thay đổi [mã băm nội dung](decision-tables.md#mã-băm-nội-dung) của bảng: mã băm được tính trên dạng chuẩn tắc, vốn bỏ qua khoảng trắng và thứ tự khoá. Mở rồi lưu một tệp viết tay không thay đổi gì sẽ định dạng lại tệp nhưng giữ nguyên `ruleVersion`.

Một quy trình làm việc điển hình:

1. `git pull` repository luật, hoặc tạo một nhánh.
2. Sửa và kiểm thử trong trình soạn thảo; lưu lại.
3. Rà soát diff (`git diff`, hoặc thẻ **JSON & changes**), chạy các kiểm thử quyết định (`.stone-test.json`, xem [Kiểm thử](testing.md)), commit và mở một pull request.
4. Sau khi rà soát và merge, triển khai luật như thường lệ.

Hãy tạo một phiên bản mới, thay vì sửa một phiên bản đang có hiệu lực, mỗi khi thay đổi phải áp dụng từ một ngày nhất định: nhờ đó các quyết định trong quá khứ vẫn chạy lại được với phiên bản đã tạo ra chúng.

## Trình dựng điều kiện và CEL

Trình dựng điều kiện sửa `when` của một luật dưới dạng một danh sách mệnh đề, tất cả nối bằng AND hoặc tất cả nối bằng OR. Mỗi mệnh đề gồm một đường dẫn đầu vào, một toán tử và một giá trị. Nó sinh ra CEL thông thường, nên tệp không chứa gì riêng của trình soạn thảo:

| Trình dựng | CEL được sinh ra |
|---|---|
| `applicant.age` *is at least* `18` | `applicant.age >= 18` |
| `applicant.monthlyIncome` *is greater than* `0.5` | `applicant.monthlyIncome > decimal('0.5')` |
| `applicant.segment` *equals* `SME` | `applicant.segment == 'SME'` |
| `payment.amount` *is at least* `1500000 VND` | `payment.amount >= money('1500000', 'VND')` |
| `applicant.birthDate` *is less than* `2008-11-01` | `applicant.birthDate < date('2008-11-01')` |
| `applicant.blacklisted` *is true* / *is false* | `applicant.blacklisted` / `!applicant.blacklisted` |
| `applicant.segment` *is one of* `RETAIL`, `SME` | `applicant.segment in ['RETAIL', 'SME']` |
| `applicant.segment` *is not one of* `VIP` | `!(applicant.segment in ['VIP'])` |
| `customer.tags` *contains* `PEP` | `'PEP' in customer.tags` |
| `applicant.segment` *is provided* / *is not provided* | `has(applicant.segment)` / `!has(applicant.segment)` |
| `amount` *is at most* (một đầu vào khác) `applicant.limit` | `amount <= applicant.limit` |
| `amount` *is at most* (một biểu thức) `applicant.monthlyIncome * 12` | `amount <= applicant.monthlyIncome * 12` |
| không có mệnh đề nào | `true` |

Các toán tử được đề xuất tuỳ theo kiểu của đầu vào: đầu vào `bool` có *is true* và *is false*; `int`, `decimal`, `money` và `date` có sáu phép so sánh và *is one of*; `string` có phép bằng và *is one of*; danh sách có *contains*; trường struct có thêm *is provided*. Giá trị được kiểm tra ngay khi gõ: `decimal` phải viết theo cách thông thường (`1234.50`), `money` gồm số tiền và mã tiền tệ viết hoa, `date` phải là ngày có thật.

Trình dựng điều kiện đọc lại được các biểu thức có sẵn, kể cả biểu thức viết tay: `decimal(5)`, `money(2000, 'VND')` và chuỗi trong nháy kép đều được nhận ra, và dấu ngoặc quanh một giá trị cũng được hiểu. Một biểu thức mà trình dựng không biểu diễn được, như `applicant.monthlyDebt / applicant.monthlyIncome > decimal('0.5')` (có phép tính ở vế trái), một tổ hợp AND và OR, hay các hàm trên danh sách như `exists`, được hiển thị ở chế độ chỉ đọc và giữ nguyên đúng như đã viết; hãy sửa nó dưới dạng CEL. Bạn có thể chuyển bất kỳ điều kiện nào sang CEL vào bất kỳ lúc nào.

## Tham chiếu API

Giao diện người dùng dùng một JSON API mà bạn cũng có thể gọi từ script. Mọi yêu cầu và phản hồi đều là JSON UTF-8. Yêu cầu có body phải gửi `Content-Type: application/json`. Lỗi được trả lời dưới dạng `{"error": {"code": "...", "message": "..."}}` kèm một mã trạng thái HTTP: 400 yêu cầu sai định dạng, 403 khác nguồn gốc (cross-origin), 404 không tìm thấy, 405 sai phương thức, 409 tệp đã tồn tại, 412 tệp đã thay đổi, 413 quá lớn, 415 không phải JSON, 421 host không xác định, 422 bảng hoặc fact không hợp lệ.

Các ví dụ dưới đây chạy trên một thư mục chứa bản sao của `deposit_rate`, `loan_eligibility` phiên bản 2 và 3, và `transfer_fees` từ [`examples/`](../../examples/), theo thứ tự này. `…` thay cho phần văn bản được lược bớt. Bộ kiểm thử chạy mọi ví dụ trong trang này và so sánh các phản hồi.

### Danh sách lỗi

Kết quả kiểm tra hợp lệ là danh sách các lỗi (problem). Mỗi lỗi có một `message` một dòng, `detail` đúng như RuleStone báo cáo, và một `location` với những trường áp dụng được:

| Trường | Ý nghĩa |
|---|---|
| `path` | Đường dẫn JSON trong bảng, ví dụ `$.rules[0].when` |
| `rule`, `ruleIndex` | Id của luật và vị trí của nó, đếm từ 0 |
| `field` | Trường của luật (`id`, `when`, `then`, `reasonCode`) khi có `rule`; nếu không thì là trường của bảng (`id`, `version`, `effectiveFrom`, `inputs`, …) |
| `output` | Đầu ra, với lỗi trong `then` và `outputs` |
| `input` | Đường dẫn đầu vào, với lỗi trong `inputs`, ví dụ `applicant.age` |
| `file` | Tệp còn lại liên quan trong một xung đột phiên bản |
| `line`, `column` | Vị trí trong biểu thức, đếm từ 1, với lỗi biểu thức |

### `GET /api/meta`

Các kiểu, hit policy và hàm biểu thức dành cho giao diện người dùng. `types` liệt kê các kiểu nguyên thuỷ của engine; `implicitInputs` liệt kê các đầu vào mà mọi luật đọc được mà không cần khai báo.

```http
GET /api/meta
```

```http
HTTP/1.1 200 OK

{
  "editorVersion": "…",
  "types": [
    { "name": "bool", "description": "true or false" },
    { "name": "int", "description": "64-bit integer" },
    { "name": "string", "description": "text" },
    { "name": "decimal", "description": "exact decimal number, for amounts and rates" },
    { "name": "money", "description": "amount with a currency" },
    { "name": "date", "description": "calendar date (yyyy-MM-dd)" },
    "…"
  ],
  "hitPolicies": [
    { "name": "UNIQUE", "description": "At most one rule may match; several matches fail the evaluation." },
    "…"
  ],
  "functions": [
    { "name": "decimal", "kind": "function", "signature": "decimal(string) -> decimal", "insert": "decimal('${}')",
      "description": "Exact decimal from text in plain notation, e.g. decimal('0.05')." },
    "…"
  ],
  "implicitInputs": [{ "name": "businessDate", "type": "date" }],
  "limits": { "maxBodyBytes": 1048576, "maxFileBytes": 4194304 }
}
```

### `GET /api/tables`

Mọi tệp `.stone.json` trong thư mục, sắp xếp theo id quyết định và ngày hiệu lực, kèm các lỗi của chúng: lỗi trong chính tệp và xung đột với các phiên bản khác của cùng quyết định. Một tệp không đọc được chỉ có `file` và `problems`.

```http
GET /api/tables
```

```http
HTTP/1.1 200 OK

{
  "directory": "rules",
  "tables": [
    {
      "file": "deposit_rate.stone.json",
      "id": "deposit_rate",
      "version": "1",
      "description": "Term deposit interest rate by term and balance",
      "hitPolicy": "UNIQUE",
      "ruleCount": 4,
      "ruleVersion": "1@sha256:…",
      "problems": []
    },
    {
      "file": "loan_eligibility.v2.stone.json",
      "id": "loan_eligibility",
      "version": "2",
      "effectiveFrom": "2026-01-01",
      "effectiveTo": "2026-11-01",
      "hitPolicy": "FIRST",
      "ruleCount": 3,
      "ruleVersion": "2@sha256:…",
      "problems": []
    },
    "…"
  ]
}
```

### `GET /api/tables/{file}`

Một tệp: văn bản đúng như được lưu (`content`), `table` đã phân tích (`null` nếu tệp không phải JSON), các lỗi của nó, và một `etag` (cũng được gửi trong header `ETag`) để truyền lại khi lưu. `{file}` là đường dẫn tương đối so với thư mục luật, ví dụ `loans/loan_eligibility.v3.stone.json`.

```http
GET /api/tables/loan_eligibility.v3.stone.json
```

```http
HTTP/1.1 200 OK

{
  "file": "loan_eligibility.v3.stone.json",
  "etag": "…",
  "content": "{\n  \"id\": \"loan_eligibility\",\n  \"version\": \"3\",…",
  "table": { "id": "loan_eligibility", "version": "3", "effectiveFrom": "2026-11-01", "hitPolicy": "FIRST" },
  "valid": true,
  "problems": [],
  "ruleVersion": "3@sha256:…"
}
```

### `POST /api/validate`

Kiểm tra một bảng, gửi trong body, như engine sẽ làm, và đối chiếu với các phiên bản khác của cùng quyết định trong thư mục. Hãy truyền `?file=` với tên tệp mà bảng đang (hoặc sẽ) được lưu, để nó không bị so sánh với chính bản đã lưu của nó. `ruleVersion` là mã băm nội dung mà engine sẽ báo cáo, có mặt khi bản thân bảng hợp lệ.

```http
POST /api/validate?file=loan_eligibility.v3.stone.json
Content-Type: application/json

{
  "id": "loan_eligibility", "version": "3", "effectiveFrom": "2026-11-01", "hitPolicy": "FIRST",
  "inputs": { "applicant": { "age": "int" } },
  "outputs": { "decision": "string" },
  "rules": [
    { "id": "R1", "when": "applicant.agee < 18", "then": { "decision": "'REJECT'" }, "reasonCode": "UNDERAGE" }
  ]
}
```

```http
HTTP/1.1 200 OK

{
  "valid": false,
  "problems": [
    {
      "message": "undefined field 'agee'",
      "detail": "loan_eligibility@3 rules[R1].when: ERROR: <input>:1:10: undefined field 'agee'\n | applicant.agee < 18\n | .........^",
      "location": { "path": "$.rules[0].when", "rule": "R1", "ruleIndex": 0, "field": "when", "line": 1, "column": 10 }
    }
  ],
  "ruleVersion": null
}
```

Một phiên bản có hiệu lực muộn hơn phiên bản 3, ví dụ từ 2026-12-01, là hợp lệ: nó thay thế phiên bản 3 từ ngày đó. Một phiên bản có hiệu lực cùng ngày với phiên bản 3 thì mơ hồ:

```http
POST /api/validate
Content-Type: application/json

{
  "id": "loan_eligibility", "version": "4", "effectiveFrom": "2026-11-01", "hitPolicy": "FIRST",
  "inputs": {}, "outputs": {}, "rules": [{ "id": "R1", "when": "true" }]
}
```

```http
HTTP/1.1 200 OK

{
  "valid": false,
  "problems": [
    {
      "message": "loan_eligibility@4 and loan_eligibility@3: both take effect on 2026-11-01, so neither supersedes the other; give the later version a later effectiveFrom (version 3 is in loan_eligibility.v3.stone.json)",
      "location": { "path": "$.effectiveFrom", "field": "effectiveFrom", "file": "loan_eligibility.v3.stone.json" }
    }
  ],
  "ruleVersion": "4@sha256:…"
}
```

### `POST /api/evaluate`

Đánh giá một bảng, không cần đã lưu, với `facts` và một ngày nghiệp vụ `asOf` tuỳ chọn (`yyyy-MM-dd`; mặc định: ngày hôm nay trên máy chủ). Phiên bản của chính bảng được dùng bất kể ngày hiệu lực của nó; `effectiveOnAsOf` cho biết nó có hiệu lực vào ngày `asOf` hay không.

Fact và đầu ra dùng các dạng JSON của [`FactsJson`](java-api.md): `decimal` là một số hoặc một chuỗi số, `money` là `"<amount> <currency>"`, `date` là `"yyyy-MM-dd"`. Số JSON được đọc chính xác, không bao giờ dưới dạng dấu phẩy động, và đầu ra thập phân được trả về dưới dạng chuỗi để không client JavaScript nào làm tròn chúng.

```http
POST /api/evaluate
Content-Type: application/json

{
  "table": {
    "id": "transfer_fee", "version": "1", "hitPolicy": "FIRST",
    "inputs": { "transfer": { "amount": "money", "valueDate": "date" } },
    "outputs": { "fee": "money", "settles": "date" },
    "rules": [
      { "id": "LARGE", "when": "transfer.amount > money(1000000, 'VND')",
        "then": { "fee": "(transfer.amount * decimal('0.0005')).round()", "settles": "transfer.valueDate.plusDays(1)" },
        "reasonCode": "LARGE_AMOUNT" },
      { "id": "SMALL", "when": "true", "then": { "fee": "money(2000, 'VND')", "settles": "transfer.valueDate" } }
    ]
  },
  "facts": { "transfer": { "amount": "150000000 VND", "valueDate": "2026-11-02" } },
  "asOf": "2026-11-02"
}
```

```http
HTTP/1.1 200 OK

{
  "decisionId": "transfer_fee",
  "ruleVersion": "1@sha256:…",
  "asOf": "2026-11-02",
  "effectiveOnAsOf": true,
  "matched": true,
  "output": { "fee": "75000 VND", "settles": "2026-11-03" },
  "reasonCodes": ["LARGE_AMOUNT"],
  "matchedRules": ["LARGE"],
  "trace": [{ "ruleId": "LARGE", "matched": true }]
}
```

Các fact không khớp với đầu vào bị từ chối kèm mọi lỗi, theo vị trí và dạng mong đợi, không bao giờ trích dẫn giá trị:

```http
POST /api/evaluate
Content-Type: application/json

{
  "table": {
    "id": "transfer_fee", "version": "1", "hitPolicy": "FIRST",
    "inputs": { "transfer": { "amount": "money", "valueDate": "date" } },
    "outputs": { "fee": "money" },
    "rules": [{ "id": "R1", "when": "true", "then": { "fee": "transfer.amount" } }]
  },
  "facts": { "transfer": { "amount": 150000000, "valueDate": "02/11/2026" } }
}
```

```http
HTTP/1.1 422 Unprocessable Entity

{
  "error": { "code": "invalid_facts", "message": "…" },
  "factProblems": [
    "transfer.amount: expected money as \"<amount> <CCY>\", e.g. \"1500000 VND\"",
    "transfer.valueDate: expected a date as \"yyyy-MM-dd\""
  ]
}
```

Một lần đánh giá thất bại, như chia cho không hoặc hai luật cùng khớp với `UNIQUE`, được trả lời bằng 422 và `"code": "evaluation_failed"`; một bảng không hợp lệ được trả lời bằng 422, `"code": "invalid_table"` và các `problems` của nó.

### `PUT /api/tables/{file}`

Lưu một bảng, gửi trong body, vào một tệp đã tồn tại theo [định dạng ổn định](#cách-lưu-tệp). Ví dụ này tăng lãi suất của luật `MID`. Một bảng có lỗi bị từ chối với 422 kèm các lỗi, và tệp giữ nguyên; thêm `?force=true` để vẫn lưu. Gửi `If-Match` với `etag` lấy được khi nạp tệp để từ chối việc lưu (412) nếu tệp đã thay đổi kể từ đó.

```http
PUT /api/tables/deposit_rate.stone.json
Content-Type: application/json

{
  "id": "deposit_rate", "version": "1", "description": "Term deposit interest rate by term and balance",
  "hitPolicy": "UNIQUE",
  "inputs": { "termMonths": "int", "balance": "decimal" },
  "outputs": { "rate": "decimal" },
  "rules": [
    { "id": "SHORT", "when": "termMonths < 6", "then": { "rate": "decimal('0.035')" } },
    { "id": "MID", "when": "termMonths >= 6 && termMonths < 12", "then": { "rate": "decimal('0.049')" } },
    { "id": "LONG", "when": "termMonths >= 12 && balance < 5000000000", "then": { "rate": "decimal('0.052')" } },
    { "id": "VIP_LONG", "when": "termMonths >= 12 && balance >= 5000000000", "then": { "rate": "decimal('0.055')" } }
  ]
}
```

```http
HTTP/1.1 200 OK

{
  "saved": true,
  "file": "deposit_rate.stone.json",
  "etag": "…",
  "valid": true,
  "problems": [],
  "ruleVersion": "1@sha256:…"
}
```

### `POST /api/tables`

Tạo một tệp: `{"table": {...}, "file": "optional/name.stone.json"}`. Nếu không có `file`, tên tệp là `<id>.v<version>.stone.json`. Một tệp đã tồn tại không bao giờ bị ghi đè (409), và không tệp nào khác bị sửa: phiên bản mới thay thế phiên bản trước từ `effectiveFrom` của nó. Lỗi và `?force=true` hoạt động như với `PUT`.

```http
POST /api/tables
Content-Type: application/json

{
  "table": {
    "id": "loan_eligibility", "version": "4", "effectiveFrom": "2027-01-01", "hitPolicy": "FIRST",
    "inputs": { "applicant": { "age": "int" } },
    "outputs": { "decision": "string" },
    "rules": [{ "id": "R1", "when": "applicant.age < 21", "then": { "decision": "'REJECT'" }, "reasonCode": "UNDERAGE" }]
  }
}
```

```http
HTTP/1.1 201 Created

{
  "saved": true,
  "file": "loan_eligibility.v4.stone.json",
  "etag": "…",
  "valid": true,
  "problems": [],
  "ruleVersion": "4@sha256:…"
}
```

### `POST /api/format`

Trả về văn bản mà việc lưu bảng sẽ ghi ra, mà không lưu; giao diện người dùng dùng nó cho chế độ xem JSON và bản diff.

```http
POST /api/format
Content-Type: application/json

{ "rules": [{ "when": "true", "id": "R1" }], "version": "1", "id": "t" }
```

```http
HTTP/1.1 200 OK

{
  "content": "{\n  \"id\": \"t\",\n  \"version\": \"1\",\n  \"rules\": [\n    {\n      \"id\": \"R1\",\n      \"when\": \"true\"\n    }\n  ]\n}\n"
}
```

### Yêu cầu bị từ chối

Một yêu cầu từ trang của một web site khác mang `Origin` của site đó và bị từ chối:

```http
POST /api/validate
Content-Type: application/json
Origin: https://attacker.example

{}
```

```http
HTTP/1.1 403 Forbidden

{ "error": { "code": "cross_origin", "message": "cross-origin requests are not allowed" } }
```

## Nhúng trong Java

`rulestone-editor` cũng là một thư viện, ví dụ để khởi động trình soạn thảo từ một công cụ nội bộ hoặc từ một bài kiểm thử:

```java
try (EditorServer editor = EditorServer.builder()
        .rulesDir(Path.of("rules"))
        .port(0)                                   // any free port
        .start()) {
    System.out.println("Open " + editor.uri());
    // ...
}
```

| Phương thức | Mặc định | Mục đích |
|---|---|---|
| `rulesDir(Path)` | bắt buộc | Thư mục chứa các tệp `.stone.json`. |
| `host(String)` | `127.0.0.1` | Địa chỉ lắng nghe. |
| `port(int)` | `8080` | Cổng; `0` cho một cổng bất kỳ còn trống, sau đó đọc `port()` hoặc `uri()`. |
| `allowedHost(String)` | không có | Header `Host` khác được chấp nhận. |
| `maxBodyBytes(long)` | 1 MiB | Kích thước body tối đa của yêu cầu. |
| `threads(int)` | 8 | Số luồng xử lý yêu cầu. |

Máy chủ chỉ dùng HTTP server có sẵn của JDK và Jackson. `close()` dừng máy chủ.

## Hạn chế và lộ trình

- **Không có người dùng, vai trò hay phê duyệt.** Xác thực, phân quyền theo từng quyết định và quy trình maker-checker ngay trong trình soạn thảo (nháp, gửi duyệt, phê duyệt) nằm trong lộ trình. Cho tới lúc đó, hãy dựa vào reverse proxy để kiểm soát truy cập và vào việc rà soát trong Git để phê duyệt.
- **Không có dấu vết kiểm toán riêng.** Ai đã thay đổi gì được ghi lại bởi các commit Git, không phải bởi trình soạn thảo.
- **Không tích hợp Git.** Trình soạn thảo đọc và ghi tệp; việc commit, tạo nhánh và push diễn ra bên ngoài nó.
- **Mỗi máy chủ một thư mục.** Để sửa nhiều repository, hãy chạy nhiều trình soạn thảo trên các cổng khác nhau.
- **Trình dựng điều kiện hỗ trợ danh sách AND/OR phẳng** gồm các phép so sánh trên đường dẫn đầu vào. Phép tính ở vế trái, nhóm lồng nhau và hàm trên danh sách vẫn viết bằng CEL.
- **Đổi tên một đầu vào không cập nhật các luật** đang dùng nó; việc kiểm tra hợp lệ sẽ chỉ ra các luật đó.
- **Chỉ dành cho máy tính để bàn.** Giao diện được thiết kế cho màn hình rộng từ 1280 điểm ảnh trở lên.
