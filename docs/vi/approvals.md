---
source: approvals.md
sourceHash: fcbbee2bbae36f3c927bc51bd3b2adf4a47550f5772cb3e953a8cdbb22090b1e
---

# Phê duyệt

Một engine được tạo với `requireApproved` chỉ nạp nội dung đã được một người khác với các tác giả của nó phê duyệt. Một phê duyệt là một **văn bản phê duyệt** (approval statement) có chữ ký, nêu chính xác mã băm nội dung của mọi bảng, luồng, bộ tham số và danh mục mã lý do mà nó bao phủ. Engine tự tính mọi mã băm từ nội dung nó nạp, kiểm chứng chữ ký bằng một khoá trong cấu hình của chính nó, và từ chối khởi tạo nếu có gì đó chưa được bao phủ, nếu một người duyệt đồng thời là người lập, hoặc nếu số người duyệt quá ít.

Đây là bước 1 của [thiết kế quản trị](governance.md#11-kế-hoạch-triển-khai). Tính năng đã có trong bộ máy luật Java và bộ máy luật TypeScript, hai bộ máy báo cùng các vấn đề bằng cùng câu chữ. Bản phát hành, giao diện kho luật và dịch vụ quản trị sẽ đến ở các bước sau; trong lúc chờ, một job CI có thể cấp các văn bản phê duyệt (xem [Cấp văn bản phê duyệt từ CI](#cấp-văn-bản-phê-duyệt-từ-ci)).

- [Ví dụ](#ví-dụ)
- [Engine kiểm tra những gì](#engine-kiểm-tra-những-gì)
- [Văn bản phê duyệt](#văn-bản-phê-duyệt)
- [Chữ ký](#chữ-ký)
- [Khoá](#khoá)
- [Phê duyệt trong tệp](#phê-duyệt-trong-tệp)
- [Các vấn đề được báo](#các-vấn-đề-được-báo)
- [Cấp văn bản phê duyệt trong kiểm thử](#cấp-văn-bản-phê-duyệt-trong-kiểm-thử)
- [Cấp văn bản phê duyệt từ CI](#cấp-văn-bản-phê-duyệt-từ-ci)
- [Giới hạn](#giới-hạn)

## Ví dụ

Thư mục luật chứa nội dung như hiện nay, cùng các văn bản phê duyệt có chữ ký đặt bên cạnh:

```
rules/
  loan_eligibility.v4.stone.json
  retail_credit.v8.params.json
  reason-codes.json
  approvals/
    CHG-2026-0042.approval.jws
```

Engine đọc các văn bản phê duyệt và được cho biết chữ ký của ai là đáng tin:

```java
PublicKey governanceKey = KeyFactory.getInstance("Ed25519")
        .generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(publicKeyBase64)));

RuleStoneEngine engine = RuleStone.builder()
        .decision(DecisionTableJson.read(Path.of("rules/loan_eligibility.v4.stone.json")))
        .paramSet(ParameterSetJson.read(Path.of("rules/retail_credit.v8.params.json")))
        .reasonCodeCatalog(ReasonCodeCatalogJson.read(Path.of("rules/reason-codes.json")))
        .approvals(Path.of("rules/approvals"))             // every *.approval.jws file
        .requireApproved(ApprovalVerifier.builder()
                .trustedKey("gov-2026", governanceKey)      // the signing key's id and public key
                .minApprovers(1)                            // a floor the engine checks itself
                .build())
        .build();
```

Trong TypeScript, `createEngine` nhận văn bản của các phê duyệt và bộ kiểm chứng:

```ts
import { ApprovalVerifier, createEngine } from '@dxa/rulestone-engine'

const governanceKey = await crypto.subtle.importKey('spki', publicKeyBytes, { name: 'Ed25519' }, false, ['verify'])

const engine = await createEngine({
  tables, paramSets, reasonCodeCatalog,
  approvals: [{ name: 'CHG-2026-0042.approval.jws', jws: approvalText }],
  requireApproved: new ApprovalVerifier({ trustedKeys: { 'gov-2026': governanceKey }, minApprovers: 1 }),
})
```

Nếu chỉ một ký tự của `loan_eligibility.v4.stone.json` bị đổi sau khi được duyệt, `build()` và `createEngine` sẽ thất bại:

```text
invalid decision definition (1 problem):
  - loan_eligibility@4: no approval covers 4@sha256:77ba…; the approved content is 4@sha256:9f2c…
```

Một engine được tạo không có `requireApproved` bỏ qua các phê duyệt và nạp mọi nội dung như trước đây, dùng cho phát triển và kiểm thử.

## Engine kiểm tra những gì

Khi được tạo, một engine có `requireApproved`:

1. **kiểm chứng mọi văn bản phê duyệt được đưa cho nó**: phần header, chữ ký với một khoá tin cậy và một thuật toán được phép, và phần payload, phải là một văn bản phê duyệt hợp lệ ở dạng chuẩn tắc. Một văn bản không kiểm chứng được là một vấn đề kể cả khi các văn bản khác đã bao phủ mọi thứ, vì một tệp bị sửa hoặc từ nơi khác trong thư mục phê duyệt là điều cần được biết;
2. **tính phiên bản chính xác** `<label>@sha256:<hash>` của mọi bảng, luồng, bộ tham số và danh mục mã lý do đã đăng ký từ dạng chuẩn tắc của nó, đúng như bản ghi quyết định nêu tên;
3. **yêu cầu mỗi đối tượng được bao phủ** bởi một văn bản đã kiểm chứng có một subject cùng loại, cùng id và cùng phiên bản chính xác. Danh mục mã lý do không có id riêng và được gọi là `reason-codes`;
4. **kiểm tra nguyên tắc bốn mắt và số tối thiểu** cho từng văn bản bao phủ: không người duyệt nào nằm trong `makers` của văn bản, và văn bản nêu ít nhất `minApprovers` người duyệt. Một đối tượng được chấp nhận khi có ít nhất một văn bản bao phủ nó đạt cả hai kiểm tra.

Engine không kiểm tra vai trò, cấp rủi ro hay đơn vị tổ chức: đó là việc của chính sách quản trị, do bên cấp văn bản áp dụng và xác nhận bằng chữ ký của mình. Mọi kiểm tra diễn ra một lần, khi engine được tạo; đánh giá một quyết định không kiểm chứng gì và không đọc tệp nào.

## Văn bản phê duyệt

```json
{
  "type": "rulestone.approval",
  "approvalId": "4a1f9c2e-7b3d-4e8a-b5c6-0d9e8f7a6b5c",
  "changeSet": "CHG-2026-0042",
  "subjects": [
    { "kind": "table", "id": "loan_eligibility", "version": "4@sha256:9f2c…" },
    { "kind": "params", "id": "retail_credit", "version": "8@sha256:41d8…" }
  ],
  "makers": ["u:nguyen.van.a"],
  "approvers": [
    { "principal": "u:tran.thi.b", "role": "CREDIT_RISK", "at": "2026-11-20T03:12:00Z" },
    { "principal": "u:le.van.c", "role": "COMPLIANCE", "at": "2026-11-20T07:41:00Z" }
  ],
  "policy": "credit-policy@3",
  "emergency": false,
  "issuer": "rulestone-governance@bank.example",
  "issuedAt": "2026-11-20T07:41:02Z",
  "logEntry": "sha256:5e0b…"
}
```

| Trường | Bắt buộc | Nội dung |
|---|---|---|
| `type` | có | Luôn là `rulestone.approval`. |
| `approvalId` | có | Id duy nhất của văn bản, chẳng hạn một UUID. |
| `changeSet` | có | Thay đổi được duyệt. Các vấn đề nêu tên văn bản bằng trường này. |
| `subjects` | có, ít nhất một | Các phiên bản chính xác được duyệt: `kind` (`table`, `flow`, `params` hoặc `catalog`), `id`, và `version` dạng `<label>@sha256:<64 chữ số hex>`. |
| `makers` | có, ít nhất một | Mọi chủ thể (principal) đã viết hoặc gửi duyệt thay đổi. |
| `approvers` | có, ít nhất một | Mỗi người là một `principal` riêng biệt, kèm `role` tuỳ chọn và thời điểm `at` họ phê duyệt. |
| `policy` | không | Chính sách quản trị được áp dụng. |
| `emergency` | có | `true` cho một [thay đổi khẩn cấp](governance.md#35-thay-đổi-khẩn-cấp), khi đó có thêm thời điểm `postApprovalDue`. |
| `issuer` | có | Bên ký: dịch vụ quản trị, hoặc một job CI. |
| `issuedAt` | có | Thời điểm ký. |
| `logEntry` | không | `sha256:<hex>` của mục nhật ký quản trị đã cấp văn bản, khi đã có dịch vụ quản trị. |

Thời điểm theo giờ UTC, viết dạng `2026-11-20T07:41:02Z`, có thể kèm phần lẻ của giây. Việc đọc là nghiêm ngặt: khoá không xác định và khoá trùng lặp đều là lỗi. Văn bản chứa mã định danh chủ thể, phiên bản và mã băm, không bao giờ chứa nội dung luật hay dữ liệu khách hàng. Cả hai bộ máy luật đều đọc và ghi được văn bản phê duyệt: `ApprovalStatementJson` trong Java, `parseApprovalStatement` và `writeApprovalStatement` trong TypeScript.

## Chữ ký

Một văn bản phê duyệt có chữ ký là một JWS ở dạng tuần tự hoá compact ([RFC 7515](https://www.rfc-editor.org/rfc/rfc7515)): ba phần base64url ngăn cách bởi dấu chấm, `header.payload.signature`.

- **Header** đúng là `{"alg":"EdDSA","kid":"gov-2026","typ":"rulestone-approval+jws"}`: một thuật toán, id của khoá ký và kiểu. Mọi tham số header khác đều bị từ chối, nên khoá không bao giờ có thể đến từ chính văn bản (`jwk`, `jku`, `x5u`, `x5c`), và `crit` không thể đòi các phần mở rộng mà engine không biết.
- **Payload** là văn bản phê duyệt ở dạng JSON chuẩn tắc của RuleStone: gọn, khoá được sắp xếp, các trường tuỳ chọn vắng mặt bị lược bỏ, cũng là dạng dùng để tính mã băm nội dung. Một payload đọc được nhưng không đúng chính xác dạng này bị từ chối, để mỗi phê duyệt chỉ có một văn bản.
- **Chữ ký** được tính trên `header.payload` với một trong hai thuật toán:

| `alg` | Khoá | Chữ ký |
|---|---|---|
| `EdDSA` | Ed25519 | 64 byte |
| `ES256` | ECDSA trên P-256 với SHA-256 | 64 byte, R và S mỗi phần 32 byte, như JWS viết |

`alg: none`, HMAC, RSA và mọi thuật toán khác đều bị từ chối. Một bộ kiểm chứng có thể cho phép ít hơn: `allowedAlgorithms("EdDSA")` trong Java, `allowedAlgorithms: ['EdDSA']` trong TypeScript. Cả hai bộ máy luật chỉ dùng mật mã của nền tảng: JDK trong Java, WebCrypto trong TypeScript, không có thư viện JOSE nào.

## Khoá

Engine tin các khoá công khai trong cấu hình của nó, mỗi khoá dưới `kid` mà văn bản phê duyệt nêu:

| Bộ máy luật | Khoá |
|---|---|
| Java | `ApprovalVerifier.builder().trustedKey(kid, publicKey)`, với một `PublicKey` Ed25519 hoặc một `PublicKey` EC trên P-256. Mọi khoá khác bị từ chối khi tạo bộ kiểm chứng. |
| TypeScript | `new ApprovalVerifier({ trustedKeys: { [kid]: key } })`, với một `CryptoKey` được import cho `Ed25519`, hoặc cho `ECDSA` với `namedCurve: 'P-256'`, có usage `verify`. |

Có thể tạo cặp khoá bằng OpenSSL. Khoá bí mật thuộc về HSM hoặc KMS của ngân hàng, hoặc kho bí mật của hệ thống CI; chỉ khoá công khai được đưa vào cấu hình của engine:

```sh
openssl genpkey -algorithm ed25519 -out approval-signing.pem
openssl pkey -in approval-signing.pem -pubout -out approval-verify.pem
```

`approval-verify.pem` chứa khoá công khai ở dạng X.509 SubjectPublicKeyInfo, mà Java đọc bằng `X509EncodedKeySpec` và WebCrypto đọc bằng `importKey('spki', …)` sau khi bỏ dòng đầu và dòng cuối của PEM và giải mã base64. Với `ES256`, dùng `-algorithm EC -pkeyopt ec_paramgen_curve:P-256`.

**Thay khoá.** Thêm khoá mới dưới một `kid` mới trước khi ngừng dùng khoá cũ, và giữ khoá cũ trong cấu hình: một văn bản đã ký bằng khoá cũ vẫn hợp lệ cho nội dung mà nó bao phủ, vì nội dung đó không bao giờ thay đổi.

## Phê duyệt trong tệp

| Bộ máy luật | Văn bản phê duyệt |
|---|---|
| Java | `.approvals(Path dir)` đọc mọi tệp `*.approval.jws` trong một thư mục, theo thứ tự tên tệp, không đọc thư mục con, và đặt tên mỗi văn bản theo tên tệp. `.approval(name, jws)` thêm một văn bản có tên; `.approval(jws)` thêm một văn bản được gọi là `approvals[i]` theo vị trí của nó. |
| TypeScript | `approvals` nhận nội dung các văn bản, dạng `{ name, jws }` hoặc chuỗi trần được gọi là `approvals[i]`. Engine không đọc tệp, để chạy được cả trong trình duyệt. |

Khoảng trắng và ký tự xuống dòng ở đầu và cuối, như dòng mới cuối tệp, được bỏ qua. Được phép có văn bản cho nội dung mà engine không nạp, nên thư mục phê duyệt có thể giữ văn bản của các phiên bản cũ.

## Các vấn đề được báo

Các vấn đề được báo cùng mọi vấn đề khác của định nghĩa, trong một `InvalidDecisionException` ở Java và một `InvalidDecisionError` ở TypeScript. Trước hết là các văn bản không kiểm chứng được, theo thứ tự được đưa vào, sau đó là các đối tượng chưa được bao phủ một cách hợp lệ, theo thứ tự bộ tham số, bảng, luồng, danh mục. Các vấn đề nêu tên tệp, khoá, phiên bản, bộ thay đổi và chủ thể, không bao giờ nêu nội dung luật hay giá trị đầu vào.

```text
invalid decision definition (4 problems):
  - approval CHG-2026-0041.approval.jws: signature does not verify with key 'gov-2026'
  - retail_credit@8 (parameter set): approval CHG-2026-0042 names approver u:tran.thi.b, who is also a maker
  - loan_eligibility@4: no approval covers 4@sha256:77ba…; the approved content is 4@sha256:9f2c…
  - reason-codes@5 (reason-code catalog): approval CHG-2026-0043 names 1 approver; at least 2 are required
```

| Vấn đề | Ý nghĩa |
|---|---|
| `not a JWS compact serialization (three base64url parts separated by '.')` | Văn bản không phải một văn bản phê duyệt có chữ ký. |
| `header is not a JSON object` | Phần đầu không giải mã ra một đối tượng JSON. |
| `header parameter 'jwk' is not allowed` | Header có tham số khác `alg`, `kid` và `typ`. |
| `header typ must be 'rulestone-approval+jws'` | Kiểu bị thiếu hoặc khác. |
| `header alg is required`, `header kid is required` | Thiếu một tham số header bắt buộc. |
| `algorithm 'none' is not allowed (allowed: EdDSA, ES256)` | Thuật toán không nằm trong danh sách cho phép của bộ kiểm chứng. |
| `unknown key 'gov-2025'` | Không khoá tin cậy nào có `kid` này. |
| `key 'gov-2026' is an EdDSA key, not ES256` | Header nêu một thuật toán không dành cho khoá đó. |
| `signature does not verify with key 'gov-2026'` | Văn bản đã bị sửa, hoặc được ký bằng một khoá khác. |
| `payload is not a JSON object`, `payload is not in canonical form` | Payload không phải một văn bản phê duyệt ở dạng chuẩn tắc. |
| `$.subjects[0].version: …` | Một trường của văn bản không hợp lệ, kèm vị trí. |
| `<artifact>: no approval covers <exact version>` | Không văn bản đã kiểm chứng nào nêu nội dung này; nếu có văn bản nêu một nội dung khác dưới cùng nhãn, nội dung đó được hiển thị. |
| `<artifact>: approval <change set> names approver <principal>, who is also a maker` | Không đạt nguyên tắc bốn mắt. |
| `<artifact>: approval <change set> names 1 approver; at least 2 are required` | Ít người duyệt hơn `minApprovers`. |

Một đối tượng được gọi là `id@label` nếu là bảng, kèm `(flow)`, `(parameter set)` hoặc `(reason-code catalog)` cho các loại khác.

## Cấp văn bản phê duyệt trong kiểm thử

`rulestone-test` có `ApprovalIssuer` để ký văn bản phê duyệt, nhờ đó các bài kiểm thử có thể tạo engine với `requireApproved`:

```java
ApprovalIssuer issuer = ApprovalIssuer.generate("test-key", ApprovalVerifier.EDDSA);
String jws = issuer.issue(ApprovalIssuer.statement("CHG-1")
        .covering(table)
        .covering(parameterSet)
        .maker("u:nguyen.van.a")
        .approver("u:tran.thi.b", "RULE_CHECKER")
        .build());

RuleStoneEngine engine = RuleStone.builder()
        .decision(table)
        .paramSet(parameterSet)
        .approval(jws)
        .requireApproved(issuer.verifier())
        .build();
```

`issuer.sign(header, payload)` ký mọi văn bản đúng như nó là, để kiểm thử cách engine từ chối một văn bản phê duyệt sai dạng. Bộ máy luật TypeScript có công cụ tương tự trong `@dxa/rulestone-engine/testing`:

```ts
import { ApprovalIssuer, approvalSubjects } from '@dxa/rulestone-engine/testing'

const issuer = await ApprovalIssuer.generate('test-key', 'EdDSA')
const jws = await issuer.issue({
  approvalId: 'a-1', changeSet: 'CHG-1', subjects: await approvalSubjects({ tables, paramSets }),
  makers: ['u:nguyen.van.a'], approvers: [{ principal: 'u:tran.thi.b', role: 'RULE_CHECKER', at: '2026-11-20T03:12:00Z' }],
  emergency: false, issuer: 'test', issuedAt: '2026-11-20T03:12:01Z',
})
const engine = await createEngine({ tables, paramSets, approvals: [jws], requireApproved: issuer.verifier() })
```

## Cấp văn bản phê duyệt từ CI

Trước khi có dịch vụ quản trị, một job CI có thể cấp văn bản phê duyệt cho một pull request: nó kiểm tra rằng những người review đã chấp thuận pull request không nằm trong số tác giả commit và có đúng vai trò, rồi ký một văn bản bằng khoá lấy từ kho bí mật của hệ thống CI và commit văn bản đó vào `rules/approvals/`. Vì engine kiểm chứng chữ ký, nội dung được đẩy lên mà không qua review sẽ bị từ chối kể cả khi cơ chế bảo vệ nhánh thất bại.

```java
PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(
        new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(System.getenv("APPROVAL_SIGNING_KEY"))));

ApprovalStatement statement = ApprovalIssuer.statement("PR-1234")
        .covering(DecisionTableJson.read(Path.of("rules/loan_eligibility.v4.stone.json")))
        .maker("u:nguyen.van.a")                       // the pull request's commit authors
        .approver("u:tran.thi.b", "RULE_CHECKER")      // its approving reviewers
        .issuer("ci@bank.example")
        .build();

Files.writeString(Path.of("rules/approvals/PR-1234.approval.jws"), ApprovalIssuer.of("ci-2026", key).issue(statement));
```

`APPROVAL_SIGNING_KEY` chứa phần thân base64 của khoá bí mật PKCS#8, chẳng hạn `approval-signing.pem` sau khi bỏ dòng đầu và dòng cuối. Chế độ Git chứng minh nguyên tắc bốn mắt kém chặt chẽ hơn một dịch vụ quản trị: job chỉ có thể so tác giả commit với người review (xem [Câu hỏi mở](governance.md#10-câu-hỏi-mở)).

## Giới hạn

- **Tính đầy đủ và chống quay lui cần bản phát hành.** `requireApproved` chứng minh rằng mọi phiên bản được nạp đều đã được duyệt, nhưng không chứng minh rằng engine nạp đủ mọi phiên bản cần nạp, hay rằng một phiên bản đã bị huỷ không được nạp lại. Bản phát hành có chữ ký với số thứ tự tăng dần sẽ lấp cả hai khoảng trống; đó là bước 2 của [thiết kế quản trị](governance.md#38-bản-phát-hành).
- **Mỗi văn bản một chữ ký.** Bên cấp, dịch vụ quản trị hoặc job CI, ký thay cho những người duyệt. Chữ ký riêng của từng người duyệt (tuần tự hoá JWS JSON) là một tuỳ chọn cho sau này.
- **Phiên bản canary và shadow** là các phiên bản đã đăng ký, nên cần được duyệt như mọi phiên bản khác.
- **Phê duyệt khẩn cấp** được kiểm tra như mọi phê duyệt khác: cần một người duyệt không phải người lập và ít nhất `minApprovers` người duyệt. Phê duyệt bổ sung sau đó là việc của dịch vụ quản trị.
- **Cấu hình của engine là trách nhiệm kiểm soát của ngân hàng.** Một ứng dụng được tạo không có `requireApproved` sẽ nạp nội dung chưa duyệt; quy trình kiểm soát triển khai lên production phải yêu cầu nó.
