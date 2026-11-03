---
source: releases.md
sourceHash: f3cd95eab5114b8df93a81a9b7fc426b7134ebddcaa7e183365d2084fd1b7dc4
---

# Bản phát hành

Một [phê duyệt](approvals.md) cho biết nội dung được phép chạy. Một **bản phát hành** (release) cho biết những phiên bản đã duyệt nào được các engine của một ứng dụng nạp, dưới dạng một tập có chữ ký. Một engine được tạo với `requireRelease` nạp đúng các phiên bản mà bản phát hành của nó liệt kê, lấy các phiên bản canary và bóng (shadow) từ đó, và từ chối một bản phát hành cũ hơn bản nó đã chấp nhận trước đó. Cùng với `requireApproved`, điều này lấp hai khoảng trống mà phê duyệt đơn thuần để lại: một tệp bị thiếu không còn có thể giữ một phiên bản cũ có hiệu lực, và một phiên bản đã bị huỷ dù vẫn còn phê duyệt hợp lệ cũng không thể được nạp lại.

Đây là bước 2 của [thiết kế quản trị](governance.md#11-kế-hoạch-triển-khai). Tính năng đã có trong bộ máy luật Java và bộ máy luật TypeScript, hai bộ máy báo cùng các vấn đề bằng cùng câu chữ. Bước này cũng thêm giao diện chỉ đọc `RuleRepository`, một kho luật trên một thư mục hoặc một bản checkout Git, và một công cụ hỗ trợ tạo lại engine khi có bản phát hành mới hơn.

- [Ví dụ](#ví-dụ)
- [Bản phát hành](#bản-phát-hành-1)
- [Engine kiểm tra những gì](#engine-kiểm-tra-những-gì)
- [Các vấn đề được báo](#các-vấn-đề-được-báo)
- [Kho luật](#kho-luật)
- [Nạp lại](#nạp-lại)
- [Cấp bản phát hành](#cấp-bản-phát-hành)
- [Giới hạn](#giới-hạn)

## Ví dụ

Thư mục luật chứa nội dung và các văn bản phê duyệt như với [phê duyệt](approvals.md#phê-duyệt-trong-tệp), và một bản phát hành có chữ ký cho mỗi đích (target):

```
rules/
  loan_eligibility.v3.stone.json
  loan_eligibility.v4.stone.json
  loan_eligibility.v5.stone.json
  retail_credit.v8.params.json
  reason-codes.json
  approvals/
    CHG-2026-0042.approval.jws
  releases/
    retail-credit-prod.release.jws
```

Một **đích** là một ứng dụng hoặc một dịch vụ quyết định đã triển khai; mỗi đích có các bản phát hành riêng, để các ứng dụng khác nhau có thể chạy các tập phiên bản đã duyệt khác nhau. Engine đọc bản phát hành của đích từ một kho luật và kiểm tra nó:

```java
RuleRepository repository = FileRuleRepository.of(Path.of("rules"));

RuleStoneEngine engine = RuleStone.builder()
        .snapshot(repository.load("retail-credit-prod"))   // the release, the versions it lists, the approvals
        .requireApproved(verifier)                         // every version approved; the release signed
        .requireRelease("retail-credit-prod")              // exactly the listed versions
        .build();
```

Trong TypeScript:

```ts
import { createEngine } from '@dxa/rulestone-engine'

const engine = await createEngine({
  snapshot: await repository.load('retail-credit-prod'),
  requireApproved: verifier,
  requireRelease: 'retail-credit-prod',
})
```

Nội dung cũng có thể được đăng ký từng phần như trước, còn bản phát hành được truyền dưới dạng văn bản: `.release(name, jws)` trong Java, `release: { name, jws }` trong TypeScript. Một engine được tạo không có `requireRelease` bỏ qua bản phát hành, giống như engine không có `requireApproved` bỏ qua các phê duyệt.

## Bản phát hành

```json
{
  "type": "rulestone.release",
  "target": "retail-credit-prod",
  "sequence": 118,
  "previous": "sha256:c41d…",
  "artifacts": [
    { "kind": "table", "id": "loan_eligibility", "version": "3@sha256:0a7e…", "state": "RETIRED" },
    { "kind": "table", "id": "loan_eligibility", "version": "4@sha256:9f2c…", "state": "ACTIVE" },
    { "kind": "table", "id": "loan_eligibility", "version": "5@sha256:2b9e…", "state": "ACTIVE" },
    { "kind": "params", "id": "retail_credit", "version": "8@sha256:41d8…", "state": "ACTIVE" },
    { "kind": "catalog", "id": "reason-codes", "version": "5@sha256:fccd…", "state": "ACTIVE" }
  ],
  "routing": [
    { "decision": "loan_eligibility", "kind": "SHADOW", "version": "5@sha256:2b9e…" }
  ],
  "issuedAt": "2026-11-20T07:45:00Z"
}
```

| Trường | Bắt buộc | Nội dung |
|---|---|---|
| `type` | có | Luôn là `rulestone.release`. |
| `target` | có | Ứng dụng có các engine nạp bản phát hành này. |
| `sequence` | có | Số thứ tự của bản phát hành trong đích của nó: một số nguyên từ 1 đến 2<sup>53</sup> − 1, tăng dần qua mỗi bản phát hành. |
| `previous` | từ bản thứ hai | `sha256:<hex>` của dạng chuẩn tắc của bản phát hành `sequence − 1`, để các bản phát hành tạo thành một chuỗi. Vắng mặt đúng ở bản phát hành 1. |
| `artifacts` | có, ít nhất một | Các phiên bản chính xác mà engine nạp: `kind`, `id`, `version` dạng `<nhãn>@sha256:<64 chữ số hex>`, và `state`. Mỗi nhãn của một thành phần chỉ được liệt kê một lần, và nhiều nhất một danh mục mã lý do, vì mỗi engine có một danh mục. |
| `routing` | không | Các phiên bản canary và bóng: `decision` (id của một bảng hoặc luồng), `kind` (`CANARY` hoặc `SHADOW`), `version` chính xác, và với canary là tỉ lệ lưu lượng `share`, lớn hơn 0 và không quá 1, nhiều nhất 6 chữ số thập phân, như [`canary`](java-api.md#phiên-bản-canary-và-phiên-bản-bóng) nhận. Mỗi quyết định có nhiều nhất một canary và một phiên bản bóng. Vắng mặt khi không có. |
| `issuedAt` | có | Thời điểm ký, một thời điểm UTC. |

**Trạng thái.** Cả hai trạng thái đều được nạp. Phiên bản `ACTIVE` là các phiên bản hiện hành: một phiên bản có thể ở trạng thái active nhiều tuần trước `effectiveFrom` của nó, và ngày hiệu lực vẫn quyết định, theo từng yêu cầu, phiên bản đã nạp nào được áp dụng. Phiên bản `RETIRED` không còn có thể có hiệu lực từ hôm nay trở đi, nhưng vẫn được nạp, để các yêu cầu có ngày lùi về quá khứ và các lần chạy lại vẫn chọn được chúng, và để việc gỡ bỏ một phiên bản không kéo dài phiên bản đứng trước nó (xem [phiên bản và ngày hiệu lực](decision-tables.md#phiên-bản-và-ngày-hiệu-lực)). Chỉ phiên bản `ACTIVE` mới được định tuyến tới.

**Phiên bản canary và phiên bản bóng được quản trị.** Một phiên bản được định tuyến tới được liệt kê và nạp như mọi phiên bản khác, nên phải được duyệt; việc chuyển lưu lượng tới một phiên bản là một phần của bản phát hành có chữ ký, không phải của mã ứng dụng.

**Chữ ký.** Bản phát hành được ký đúng như một văn bản phê duyệt (xem [Chữ ký](approvals.md#chữ-ký)): tuần tự hoá JWS dạng compact, header gồm đúng `alg`, `kid` và `typ`, với `typ` là `rulestone-release+jws`, ký bằng `EdDSA` hoặc `ES256` với một khoá mà bộ kiểm chứng tin cậy, và payload là bản phát hành ở dạng JSON chuẩn tắc: gọn, khoá được sắp xếp, bỏ `previous` và `routing` khi vắng mặt, tỉ lệ được viết dạng số thập phân thường như `0.05` hoặc `1`. Trường typ ngăn một văn bản phê duyệt bị nhầm thành bản phát hành và ngược lại. Cùng một `ApprovalVerifier` kiểm chứng cả hai: `verifyRelease(jws)` trong cả hai bộ máy. `ReleaseJson` trong Java, và `parseRelease`, `writeRelease`, `releaseHash` trong TypeScript đọc và ghi bản phát hành; mã băm là trường `previous` của bản phát hành kế tiếp.

## Engine kiểm tra những gì

Khi được tạo, một engine có `requireApproved` và `requireRelease(target)`:

1. kiểm tra phê duyệt của mọi phiên bản đã đăng ký như [trước đây](approvals.md#engine-kiểm-tra-những-gì);
2. **kiểm chứng bản phát hành**: header, chữ ký và payload chuẩn tắc, bằng các khoá của bộ kiểm chứng. Nếu bản phát hành không kiểm chứng được, các phần khác của nó không được kiểm tra nữa;
3. kiểm tra rằng đó là bản phát hành **của đích của nó**;
4. nếu được cho biết **bản phát hành đã chấp nhận gần nhất**, tức bản mà engine trước đó chạy hoặc bản mà bộ đệm giữ lại (`lastAcceptedRelease` trong cả hai bộ máy), từ chối một bản phát hành có **số thứ tự thấp hơn** (quay lui), một bản phát hành có **cùng số thứ tự nhưng nội dung khác**, và, khi bản phát hành nối tiếp ngay sau bản đã chấp nhận, một trường `previous` không phải mã băm của bản đã chấp nhận. Một bản phát hành vượt lên nhiều số vẫn được chấp nhận: một engine có thể bỏ lỡ một số bản phát hành;
5. kiểm tra rằng bản phát hành liệt kê **đúng các phiên bản đã đăng ký**: mọi bảng, luồng, bộ tham số và danh mục đã đăng ký ở phiên bản chính xác mà engine tự tính, và mọi phiên bản được liệt kê đều đã được đăng ký. Một tệp bị thiếu, bị thêm hoặc bị sửa là một vấn đề;
6. kiểm tra **định tuyến**: mỗi phiên bản canary hoặc bóng là một phiên bản bảng hoặc luồng `ACTIVE` được liệt kê. Sau đó engine định tuyến tới chúng như `canary` và `shadow` trong Java; hai phương thức này không thể gọi trên builder cùng với `requireRelease`.

`requireRelease` cần `requireApproved`, vì các khoá của nó kiểm chứng bản phát hành. Như với phê duyệt, mọi kiểm tra diễn ra một lần, khi engine được tạo.

## Các vấn đề được báo

Các vấn đề của bản phát hành đứng sau mọi vấn đề khác của các định nghĩa, trong một `InvalidDecisionException` ở Java và một `InvalidDecisionError` ở TypeScript. Chúng gọi bản phát hành theo tên tệp khi có, cùng các phiên bản và mã băm, không bao giờ nêu nội dung luật hay giá trị đầu vào:

```text
invalid decision definition (3 problems):
  - loan_eligibility@5: no approval covers 5@sha256:4884…
  - retail_credit@2 (parameter set): release 117 lists 2@sha256:65c6…, which is not loaded
  - release retail-credit-prod.release.jws: shadow loan_eligibility@3 is RETIRED; only an ACTIVE version can be routed to
```

| Vấn đề | Ý nghĩa |
|---|---|
| `release: required for target 't', but none is registered` | Không có bản phát hành nào được cung cấp. |
| `release <name>: <signature problem>` | Bản phát hành không kiểm chứng được; các vấn đề giống như của [phê duyệt](approvals.md#các-vấn-đề-được-báo), với `typ` là `rulestone-release+jws`, cùng các vấn đề của các trường, chẳng hạn `$.previous: required after the first release: the hash of release 117`. |
| `release <name>: target is 'a', but this engine loads 'b'` | Bản phát hành của một ứng dụng khác. |
| `release <name>: sequence 117 is older than release 118, which this engine already accepted` | Một lần quay lui. |
| `release <name>: sequence 118 was already accepted with other content` | Hai bản phát hành khác nhau có cùng một số. |
| `release <name>: previous is sha256:…, but release 117 accepted before is sha256:…` | Chuỗi bị đứt. |
| `<artifact>: <exact version> is not in release 118` | Một phiên bản được nạp mà bản phát hành không liệt kê; nếu nhãn đó được liệt kê với nội dung khác, nội dung đó được hiển thị. |
| `<artifact>: release 118 lists <exact version>, which is not loaded` | Thiếu một phiên bản được liệt kê, chẳng hạn một phiên bản đã nghỉ hưu mà tệp đã bị xoá. |
| `release <name>: canary <decision>@<label> is not listed: …` | Định tuyến tới một phiên bản mà bản phát hành không liệt kê. |
| `release <name>: shadow <decision>@<label> is RETIRED; only an ACTIVE version can be routed to` | Định tuyến tới một phiên bản đã nghỉ hưu. |

## Kho luật

`RuleRepository`, trong `rulestone-api`, là giao diện chỉ đọc mà engine nạp từ đó. Nó trả về nội dung thô và văn bản có chữ ký, không bao giờ trả về đối tượng đã phân tích hay mã băm được tin cậy, để engine tự tính mọi mã băm và tự kiểm chứng mọi chữ ký; kho luật không được tin cậy.

| Phương thức | Trả về |
|---|---|
| `load(target)` | Một `Snapshot`: bản phát hành có chữ ký của đích, nội dung của mọi phiên bản nó liệt kê, và các văn bản phê duyệt. |
| `find(kind, id, exactVersion)` | Một phiên bản chính xác, để chạy lại, kể cả phiên bản đã nghỉ hưu hoặc không có trong bản phát hành hiện tại. |
| `latestSequence(target)` | Số thứ tự của bản phát hành hiện tại theo kho luật, để quyết định nhanh có cần nạp hay không; mặc định nó nạp cả snapshot. |
| `watch(target, listener)` | Báo các bản phát hành mới hơn, nếu kho luật làm được; mặc định là không, và bên gọi tự hỏi định kỳ. |

**Tệp và Git.** `FileRuleRepository.of(dir)` trong `rulestone-core` đọc cấu trúc của [ví dụ](#ví-dụ): bảng, luồng và bộ tham số ở bất kỳ đâu trong thư mục, đặt tên tuỳ ý; danh mục `reason-codes.json`, có tên `reason-codes`, hoặc `<id>.reason-codes.json`, có tên `<id>`, để nhiều danh mục có thể được quản trị song song; mọi `approvals/*.approval.jws`; và `releases/<target>.release.jws`. Nó đọc danh sách của bản phát hành mà không tin cậy nó, để chọn các tệp có `id` và `version` được nêu, và bỏ qua các tệp không được liệt kê hoặc không phải JSON hợp lệ, cùng các thư mục có tên bắt đầu bằng dấu chấm, như `.git`. Với Git, hãy trỏ nó tới một bản checkout mà một job triển khai hoặc chính ứng dụng cập nhật; nó không dùng thư viện Git nào. Khi đó một pull request mang theo nội dung mới, văn bản phê duyệt và bản phát hành mới, và engine từ chối mọi thứ mà chữ ký không bao phủ, kể cả khi cơ chế bảo vệ nhánh bị vượt qua.

**TypeScript.** `RuleRepository` là cùng giao diện đó với promise, để một kho luật có thể được đọc qua HTTP từ trình duyệt, edge worker hoặc Node.js. `InMemoryRuleRepository` giữ các snapshot trong bộ nhớ, cho kiểm thử và cho nội dung đến bằng cách khác. Một kho luật qua HTTP chỉ cần vài dòng:

```ts
const repository: RuleRepository = {
  async load(target) {
    const response = await fetch(`https://rules.bank.example/targets/${target}/snapshot`)
    return (await response.json()) as RepositorySnapshot      // verified by the engine, not trusted
  },
  async find() {
    return undefined
  },
}
```

## Nạp lại

`RepositoryReloader` tạo một engine từ bản phát hành hiện tại của một đích, và tạo engine mới mỗi khi có bản phát hành mới hơn, chỉ thay vào nếu engine mới được tạo thành công. Một bản phát hành không qua được kiểm chứng hoặc kiểm tra hợp lệ sẽ không bao giờ được thay vào; engine trước đó tiếp tục chạy và lỗi được báo lại.

```java
AtomicReference<RuleStoneEngine> engine = new AtomicReference<>();
RepositoryReloader reloader = RepositoryReloader.builder(repository, "retail-credit-prod", verifier)
        .engine(builder -> builder.auditSink(auditSink).clock(clock))       // everything but content
        .cache(Path.of("/var/lib/loans/retail-credit-prod.release.jws"))   // the last release accepted
        .onReload(engine::set)
        .onFailure((sequence, e) -> log.error("release {} not loaded", sequence, e))
        .build();
reloader.reloadIfNewer();                                     // at start-up
reloader.pollEvery(Duration.ofSeconds(30), scheduler);        // or call reloadIfNewer() yourself
```

```ts
const reloader = new RepositoryReloader({
  repository, target: 'retail-credit-prod', verifier,
  engine: { auditSinks: [sink], timeZone: 'Asia/Ho_Chi_Minh' },
  store,                                                      // a ReleaseStore: the last release accepted
  onReload: (engine) => { current = engine },
  onFailure: (sequence, error) => console.error(`release ${sequence} not loaded`, error),
})
await reloader.reloadIfNewer()
reloader.pollEvery(30_000)
```

- `reloadIfNewer()` hỏi kho luật số thứ tự mới nhất và, nếu nó mới hơn bản phát hành đang chạy hoặc chưa có gì chạy, nạp snapshot và tạo một engine với `requireApproved`, `requireRelease` và, làm bản phát hành đã chấp nhận gần nhất, bản đang chạy. Phương thức trả về việc có engine mới được thay vào hay không. Các lần nạp lại không bao giờ chạy đồng thời.
- Công cụ nạp lại không tự khởi tạo luồng hay bộ hẹn giờ nào. `pollEvery` lên lịch `reloadIfNewer` trên một `ScheduledExecutorService` mà bạn cung cấp trong Java, hoặc bằng `setInterval` trong TypeScript; `watch()` nạp lại theo thông báo của kho luật; `close()` và `stop()` dừng cả hai.
- **Bộ đệm từ chối quay lui sau khi khởi động lại.** Bản phát hành được chấp nhận gần nhất được giữ lại, kèm chữ ký: trong một tệp ở Java, được ghi nguyên tử, hoặc trong một `ReleaseStore` ở TypeScript (`MemoryReleaseStore`, hoặc store của riêng bạn trên `localStorage`, IndexedDB hay một kho key-value). Khi khởi động, nó được kiểm chứng và dùng làm bản phát hành đã chấp nhận gần nhất, nên một kho luật bị quay lui về bản phát hành cũ hơn vẫn bị từ chối, kể cả với một tiến trình mới. Một bộ đệm không kiểm chứng được là một lỗi, không phải lý do để chấp nhận bất kỳ bản phát hành nào.

## Cấp bản phát hành

`ApprovalIssuer` trong `rulestone-test` cấp bản phát hành cho kiểm thử và job CI, với các khoá của văn bản phê duyệt:

```java
Release release = ApprovalIssuer.release("retail-credit-prod", 118)
        .previous(release117)                    // or .previous("sha256:…")
        .retired(loanEligibilityV3)
        .active(loanEligibilityV4)
        .active(loanEligibilityV5)
        .active(retailCreditV8)
        .active(catalog)
        .shadow(loanEligibilityV5)               // or .canary(loanEligibilityV5, 0.05)
        .build();
String jws = issuer.issue(release);
```

Trong TypeScript, `issuer.issueRelease(release)` ký một đối tượng `Release`, và `releaseEntries({ tables, flows, paramSets, reasonCodeCatalog }, state)` từ `@dxa/rulestone-engine/testing` liệt kê các phiên bản chính xác cho nó.

Trước khi có dịch vụ quản trị, job CI [cấp văn bản phê duyệt](approvals.md#cấp-văn-bản-phê-duyệt-từ-ci) cũng có thể cấp bản phát hành: sau khi một pull request được duyệt, job liệt kê các phiên bản của đích cùng trạng thái của chúng, đánh số bản phát hành lớn hơn bản hiện tại một đơn vị, ghi mã băm của bản hiện tại vào `previous`, ký nó và commit `releases/<target>.release.jws`.

## Giới hạn

- **Không đệm nội dung.** Công cụ nạp lại đệm bản phát hành chứ không đệm nội dung, nên engine vẫn cần kho luật khi khởi động. Một bản checkout Git hoặc một thư mục là cục bộ; một kho luật qua mạng cần chịu được sự cố khi khởi động thuộc về các kho JDBC và HTTP ở các bước sau.
- **Bộ máy luật TypeScript không định tuyến.** Nó kiểm chứng phần định tuyến của bản phát hành giống hệt bộ máy Java, nhưng không hỗ trợ phiên bản canary và bóng, nên mọi yêu cầu đều được trả lời bằng phiên bản đang có hiệu lực.
- **Engine chỉ kiểm tra chuỗi khi biết bản đứng trước.** Một bản phát hành vượt lên nhiều số so với bản đã chấp nhận gần nhất vẫn được chấp nhận; kiểm tra toàn bộ chuỗi là việc của công cụ `verify` của nhật ký quản trị, ở một bước sau.
- **Mỗi engine một bản phát hành.** Một đích là một ứng dụng đã triển khai; một engine cần hai tập luật thì là hai đích.
