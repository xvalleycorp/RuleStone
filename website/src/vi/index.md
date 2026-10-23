---
layout: home
title: RuleStone
titleTemplate: Bộ máy luật nghiệp vụ cho ngân hàng và tài chính
source: index.md
sourceHash: 0c70866531801734d81d0683b890d5c8eed9a65d9fbcf6d90a51a0cfb4f54969

hero:
  name: RuleStone
  text: Luật nghiệp vụ ngân hàng, khắc vào đá.
  tagline: Bộ máy luật mã nguồn mở viết bằng Java, dành cho bảng quyết định có phiên bản, có ngày hiệu lực và tính toán thập phân chính xác. Mỗi phiên bản khắc vào đá. Mỗi thay đổi chỉ mất vài giây.
  image:
    light: /logo.svg
    dark: /logo-dark.svg
    alt: Biểu trưng RuleStone, một vòm đá xếp được khoá bằng viên đá đỉnh vòm màu vàng
  actions:
    - theme: brand
      text: Bắt đầu
      link: /vi/docs/getting-started
    - theme: alt
      text: Đọc tài liệu
      link: /vi/docs/
    - theme: alt
      text: Xem trên GitHub
      link: https://github.com/xvalleycorp/RuleStone

features:
  - icon: 🪙
    title: Tiền tệ chính xác
    details: Kiểu thập phân dựa trên BigDecimal. Số dấu phẩy động bị từ chối cả trong luật lẫn trong dữ liệu đầu vào, nên 0.1 + 0.2 đúng bằng 0.3.
  - icon: 🛑
    title: Bắt lỗi ngay lúc nạp luật
    details: Mọi luật đều được kiểm tra kiểu khi nạp. Một tên trường gõ sai hay một số decimal đem so với 1.5 sẽ làm việc nạp dừng lại với thông báo lỗi chỉ rõ vị trí, thay vì làm hỏng giao dịch của khách hàng.
  - icon: 📅
    title: Phiên bản và ngày hiệu lực
    details: Đăng ký mọi phiên bản của một chính sách. Engine áp dụng phiên bản có hiệu lực vào ngày nghiệp vụ và từ chối các phiên bản có thời gian hiệu lực chồng lấn.
  - icon: 🔏
    title: Tái lập và kiểm toán được
    details: Mỗi kết quả ghi rõ phiên bản luật đã dùng kèm mã băm nội dung SHA-256, các mã lý do và vết các luật đã xét, không chứa giá trị đầu vào nào.
  - icon: 🧱
    title: Biểu thức an toàn
    details: Luật được viết bằng CEL, ngôn ngữ biểu thức dùng trong chính sách của Kubernetes và Envoy. Không có script, không có I/O, và mọi biểu thức đều chắc chắn kết thúc.
  - icon: ☕
    title: Thư viện Java nhúng được
    details: Engine bất biến, an toàn đa luồng, cho Java 17 trở lên. Không cần máy chủ hay framework, và chỉ dùng thư viện phụ thuộc có giấy phép dễ dãi (permissive).
---

<div class="home-section">

## Một bảng quyết định

<div class="home-columns">
<div>

### Viết chính sách

Luật nằm trong các tệp `.stone.json` có phiên bản, tách khỏi mã nguồn ứng dụng. Dưới đây là bản rút gọn của [ví dụ xét điều kiện vay](https://github.com/xvalleycorp/RuleStone/blob/main/examples/loan_eligibility.v3.stone.json):

```json
{
  "id": "loan_eligibility",
  "version": "3",
  "effectiveFrom": "2026-11-01",
  "hitPolicy": "FIRST",
  "inputs": {
    "applicant": {
      "age": "int",
      "monthlyIncome": "decimal",
      "monthlyDebt": "decimal"
    },
    "amount": "decimal"
  },
  "outputs": { "decision": "string", "limit": "decimal" },
  "rules": [
    { "id": "R1", "when": "applicant.age < 18",
      "then": { "decision": "'REJECT'" },
      "reasonCode": "UNDERAGE" },
    { "id": "R3",
      "when": "applicant.monthlyDebt / applicant.monthlyIncome > decimal('0.5')",
      "then": { "decision": "'REJECT'" },
      "reasonCode": "DTI_HIGH" },
    { "id": "R4", "when": "amount <= applicant.monthlyIncome * 12",
      "then": { "decision": "'APPROVE'",
        "limit": "(applicant.monthlyIncome * 12 / 1000000).round(0, 'FLOOR') * 1000000" } },
    { "id": "R5", "when": "true",
      "then": { "decision": "'REVIEW'" },
      "reasonCode": "AMOUNT_ABOVE_LIMIT" }
  ]
}
```

</div>
<div>

### Đánh giá từ Java

```java
RuleStoneEngine engine = RuleStone.builder()
        .clock(Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")))
        .decision(DecisionTableJson.read(
                Path.of("rules/loan_eligibility.stone.json")))
        .build();   // type-checks every rule, or fails listing all problems

DecisionResult result = engine.evaluate(
        DecisionRef.of("loan_eligibility")
                .asOf(LocalDate.of(2026, 12, 1)),
        Facts.of("applicant", applicant,
                 "amount", new BigDecimal("300000000")));

result.output();        // {decision=APPROVE, limit=309000000}
result.reasonCodes();   // []
result.matchedRules();  // [R4]
result.ruleVersion();   // 3@sha256:…
```

Engine chọn phiên bản có hiệu lực vào ngày nghiệp vụ, nên một năm sau chạy lại quyết định này vẫn cho đúng kết quả cũ. Hãy lưu `ruleVersion()` cùng với mỗi quyết định: giá trị này xác định chính xác nội dung luật đến từng byte.

[Xem từng bước của ví dụ này →](/vi/docs/getting-started)

</div>
</div>

## Vì sao là "RuleStone"?

Bộ luật Hammurabi, được khắc trên một tấm bia đá bazan vào khoảng năm 1750 trước Công nguyên, là một trong những bộ luật thành văn đầu tiên của loài người, và trong đó đã có những điều luật về cho vay, lãi suất và tiền gửi. RuleStone đối xử với mọi phiên bản chính sách của bạn theo cùng tinh thần ấy: khắc vào đá, có định danh và kiểm toán được. Còn việc phát hành phiên bản tiếp theo chỉ mất vài giây và không cần triển khai lại ứng dụng.

## Mã nguồn mở

RuleStone được cấp phép theo [Giấy phép Apache 2.0](https://github.com/xvalleycorp/RuleStone/blob/main/LICENSE): bạn được tự do sử dụng, kể cả trong sản phẩm thương mại và sản phẩm mã nguồn đóng. Mọi thư viện phụ thuộc lúc chạy đều theo giấy phép Apache-2.0 hoặc BSD-3-Clause, và quá trình build sẽ thất bại nếu điều này thay đổi. Mỗi tệp jar đều kèm giấy phép, tệp NOTICE, danh sách giấy phép của bên thứ ba và một danh mục thành phần phần mềm (SBOM) theo chuẩn CycloneDX.

Chúng tôi hoan nghênh mọi đóng góp theo [Developer Certificate of Origin](https://github.com/xvalleycorp/RuleStone/blob/main/CONTRIBUTING.md) (English).

<div class="status-note">

**Bản tiền phát hành.** RuleStone 0.1 đang được tích cực phát triển: engine lõi đã chạy được và đã được kiểm thử, nhưng API vẫn có thể thay đổi và các artifact chưa được phát hành lên Maven Central. Xem [lộ trình](/vi/docs/architecture#_9-lo-trinh).

</div>

</div>
