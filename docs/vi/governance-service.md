---
source: governance-service.md
sourceHash: cf8b983d3edf6775b2f5fdff5cde53229208003eb99eb3749bea2e65f5310ae5
---

# Dịch vụ quản trị luật

`rulestone-governance` là dịch vụ quản trị của RuleStone dưới dạng thư viện Java: nó điều khiển **vòng đời** của các đối tượng luật trong các **bộ thay đổi**, áp dụng **maker-checker** với chính sách phê duyệt và thay đổi khẩn cấp, **ký các văn bản phê duyệt và bản phát hành** mà engine chấp nhận, và ghi mọi sự kiện vào một **nhật ký kiểm toán liên kết chuỗi băm** mà công cụ `verify` kiểm tra. Một engine được tạo với `requireApproved()` và `requireRelease()` từ những gì dịch vụ đã cấp sẽ nạp đúng những gì đã được duyệt và phát hành, không gì khác.

Đây là bước 3 của [thiết kế quản trị](governance.md#11-kế-hoạch-triển-khai). Dịch vụ chỉ có bằng Java, như thiết kế đã nêu ([bộ máy luật TypeScript tương đương](governance.md#7-bộ-máy-luật-typescript-tương-đương)); bộ máy luật TypeScript kiểm chứng những gì dịch vụ cấp, giống bộ máy luật Java. Bước này có một kho trong bộ nhớ; kho JDBC, việc neo chuỗi, SPI `ApprovalWorkflow` với Camunda và các màn hình trình soạn thảo sẽ đến ở các bước sau.

- [Ví dụ](#ví-dụ)
- [Cài đặt](#cài-đặt)
- [Vòng đời và các thao tác](#vòng-đời-và-các-thao-tác)
- [Bộ thay đổi, người lập và nhãn](#bộ-thay-đổi-người-lập-và-nhãn)
- [Chính sách và cấp rủi ro](#chính-sách-và-cấp-rủi-ro)
- [Ai được làm gì](#ai-được-làm-gì)
- [Thay đổi khẩn cấp](#thay-đổi-khẩn-cấp)
- [Bản phát hành](#bản-phát-hành)
- [Những gì engine nạp](#những-gì-engine-nạp)
- [Nhật ký kiểm toán](#nhật-ký-kiểm-toán)
- [Kiểm chứng](#kiểm-chứng)
- [Các SPI](#các-spi)
- [Lỗi](#lỗi)
- [Khác biệt so với thiết kế](#khác-biệt-so-với-thiết-kế)
- [Giới hạn](#giới-hạn)

## Ví dụ

Một người lập lưu và trình duyệt một bảng, một người duyệt phê duyệt, một người quản lý phát hành kích hoạt, và một engine được tạo từ những gì dịch vụ đã cấp:

```java
GovernancePolicy policy = GovernancePolicy.builder()
        .tier(GovernancePolicy.Tier.of("standard", 1, "RULE_CHECKER"))
        .assign("*", "*", "standard")
        .makerRoles("RULE_MAKER")
        .releaseRoles("RELEASE_MANAGER")
        .build();

InMemoryPrincipalResolver who = new InMemoryPrincipalResolver();   // the bank's SSO, in a real service
GovernanceService service = GovernanceService.builder()
        .store(new InMemoryGovernanceStore())
        .principals(who)
        .signer(PrivateKeySigner.of("gov-2026", privateKey))        // an HSM or KMS in production
        .policy(policy)
        .build();

who.actAs(Principal.of("u:nguyen.van.a", "RULE_MAKER"));
service.createChangeSet("CHG-2026-0042", "Raise the score gate");
service.saveDraft("CHG-2026-0042", ArtifactKind.TABLE, tableJson);
service.submit("CHG-2026-0042");

who.actAs(Principal.of("u:tran.thi.b", "RULE_CHECKER"));
service.approve("CHG-2026-0042", "RULE_CHECKER", "Matches the committee minutes");

who.actAs(Principal.of("u:rm.one", "RELEASE_MANAGER"));
service.activate("CHG-2026-0042", "retail-credit-prod");

RuleStoneEngine engine = RuleStone.builder()
        .snapshot(service.repository().load("retail-credit-prod"))
        .requireApproved(ApprovalVerifier.builder().trustedKey("gov-2026", publicKey).build())
        .requireRelease("retail-credit-prod")
        .build();
```

`u:nguyen.van.a` không thể tự duyệt bộ thay đổi của mình: anh ấy đã lưu và trình duyệt nó, nên anh ấy là một người lập của bộ thay đổi, và `approve` sẽ thất bại với `FOUR_EYES`. Dịch vụ đã ghi các mục sau vào nhật ký, mỗi mục liên kết với mục trước nó:

```text
1  POLICY_CHANGED   svc:rulestone-governance
2  DRAFT_CREATED    u:nguyen.van.a   CHG-2026-0042
3  DRAFT_SAVED      u:nguyen.van.a   CHG-2026-0042  table gate_decision@1
4  SUBMITTED        u:nguyen.van.a   CHG-2026-0042
5  APPROVED         u:tran.thi.b     CHG-2026-0042
6  APPROVAL_ISSUED  svc:rulestone-governance   (the statement's logEntry is this entry's hash)
7  RELEASED         u:rm.one         release 1 of retail-credit-prod
8  ACTIVATED        u:rm.one         CHG-2026-0042
```

## Cài đặt

Thêm mô-đun; nó chỉ phụ thuộc vào `rulestone-core` và JDK:

```xml
<dependency>
    <groupId>vn.io.dxa.rulestone</groupId>
    <artifactId>rulestone-governance</artifactId>
</dependency>
```

`GovernanceService.builder()` nhận:

| Cài đặt | Ý nghĩa |
|---|---|
| `store` | Một [`GovernanceStore`](#các-spi). Bắt buộc. Bước này có `InMemoryGovernanceStore`. |
| `principals` | Một [`PrincipalResolver`](#các-spi): ai đang gọi. Bắt buộc. |
| `signer` | Một [`Signer`](#các-spi) ký văn bản phê duyệt và bản phát hành. Bắt buộc. |
| `policy` | [Chính sách](#chính-sách-và-cấp-rủi-ro). Bắt buộc. Một kho chưa có chính sách sẽ nhận chính sách này, cùng một mục `POLICY_CHANGED`. Một kho đã có chính sách phải giữ đúng chính sách này, bất kể phiên bản: chính sách chỉ đổi qua `changePolicy`, cần quản trị viên và được ghi nhật ký. |
| `clock` | Cho các mục nhật ký, văn bản phê duyệt và bản phát hành, và cho ngày nghiệp vụ của việc [ngừng sử dụng](#bản-phát-hành). Mặc định là giờ hệ thống UTC; đồng hồ cố định giúp kiểm thử lặp lại được. |
| `idGenerator` | Nguồn của mã sự kiện và mã văn bản phê duyệt. Mặc định là UUID ngẫu nhiên; một bộ đếm giúp chúng lặp lại được. |
| `issuer` | Ghi vào văn bản phê duyệt làm `issuer`, và làm `svc:<issuer>` cho các mục do chính dịch vụ ghi. Mặc định là `rulestone-governance`. |
| `listener` | Nhận mọi mục nhật ký sau khi được ghi, ví dụ cảnh báo cho một [phê duyệt bổ sung quá hạn](#thay-đổi-khẩn-cấp). Listener ném ngoại lệ sẽ bị bỏ qua: nhật ký mới là bản ghi. |

Dịch vụ thực hiện các thao tác tuần tự, mỗi lúc một bên ghi, như nhật ký đòi hỏi. Việc đọc đi qua `service.changeSet(id)`, `changeSets()`, `stateOf(kind, id, label)`, `policy()` và kho.

## Vòng đời và các thao tác

Một phiên bản đi qua các trạng thái của [thiết kế](governance.md#22-vòng-đời); `LifecycleState` liệt kê các bước chuyển dịch vụ cho phép, và mọi bước khác bị từ chối với `ILLEGAL_STATE`:

```text
            submit            approve (policy met)          activate
  DRAFT ─────────────▶ SUBMITTED ─────────────▶ APPROVED ─────────────▶ ACTIVE ─────▶ RETIRED
    ▲                     │
    │  withdraw, edit     │  reject (with a reason)
    ├─────────────────────┤
    │  revise             ▼
    └──────────────── REJECTED
```

| Thao tác | Ai | Trạng thái cần có | Mục nhật ký |
|---|---|---|---|
| `createChangeSet(id, title)` | vai trò người lập | không | `DRAFT_CREATED` |
| `saveDraft(changeSet, kind, content)` | vai trò người lập | `DRAFT`, `SUBMITTED` hoặc `REJECTED` | `DRAFT_SAVED` |
| `removeDraft(changeSet, kind, id, label)` | vai trò người lập | như `saveDraft` | `DRAFT_SAVED` kèm `removed` |
| `submit(changeSet)` | vai trò người lập | `DRAFT` | `SUBMITTED` |
| `withdraw(changeSet, reason)` | một người lập của bộ thay đổi | `SUBMITTED` | `WITHDRAWN` |
| `comment(changeSet, location, text)` | mọi chủ thể đã xác thực | bất kỳ | `COMMENTED` |
| `approve(changeSet, role, comment)` | một người duyệt | `SUBMITTED` | `APPROVED` hoặc `EMERGENCY_APPROVED`, rồi `APPROVAL_ISSUED` khi đạt chính sách |
| `reject(changeSet, reason)` | một người duyệt | `SUBMITTED` | `REJECTED` |
| `declareEmergency(changeSet, reason)` | vai trò người lập | `DRAFT` hoặc `SUBMITTED` | `EMERGENCY_DECLARED` |
| `postApprove(changeSet, role, comment)` | một người duyệt | thay đổi khẩn cấp đang chờ phê duyệt bổ sung | `APPROVED`, rồi `POST_APPROVED` khi đạt chính sách đầy đủ |
| `rejectPostApproval(changeSet, reason)` | một người duyệt | thay đổi khẩn cấp đang chờ phê duyệt bổ sung | `POST_APPROVAL_REJECTED` |
| `checkOverdue()` | ứng dụng chủ, theo bộ hẹn giờ | không | `POST_APPROVAL_OVERDUE` |
| `activate(changeSet, target[, routing])` | vai trò phát hành | `APPROVED`, hoặc `ACTIVE` trong một target khác | `RELEASED`, `ACTIVATED`, `RETIRED` |
| `retireSuperseded(target)` | vai trò phát hành | không | `RELEASED`, `RETIRED` |
| `changePolicy(policy)` | quản trị viên | không | `POLICY_CHANGED` |

Một thao tác vi phạm quy tắc sẽ ném `GovernanceException` và không thay đổi gì: kho áp dụng bộ thay đổi, văn bản phê duyệt, bản phát hành và các mục nhật ký của một thao tác trong một lần cập nhật nguyên tử duy nhất, hoặc không áp dụng gì cả.

## Bộ thay đổi, người lập và nhãn

Một **bộ thay đổi** (change set) gom các phiên bản đối tượng được trình duyệt, xem xét, phê duyệt và kích hoạt cùng nhau: một lần xem xét mọi mục, một văn bản phê duyệt nêu mã băm của từng mục, một lần kích hoạt, để engine không bao giờ nạp một nửa thay đổi. `saveDraft` nhận nội dung dưới dạng văn bản JSON và đọc từ đó danh tính của đối tượng theo từng loại: `id` và nhãn `version` của bảng, luồng hay bộ tham số, và phiên bản của danh mục, vốn không có id và được đặt tên `reason-codes` trừ khi `saveDraft(changeSet, ArtifactKind.CATALOG, name, content)` đặt tên khác. Dịch vụ đọc nội dung bằng chính các bộ đọc mà engine dùng, nên nội dung không hợp lệ bị từ chối ngay khi lưu (`INVALID_CONTENT`), và tự tính **phiên bản chính xác** `<label>@sha256:<hash>`.

**Người lập là một sự thật của nhật ký.** Mọi chủ thể đã lưu một bản sửa của bất kỳ mục nào, đã xoá một mục, hoặc đã trình duyệt bộ thay đổi đều là người lập của nó, cùng với người mà một chủ thể AI đã làm thay (`Principal.actingFor`). Mỗi lần lưu là một mục `DRAFT_SAVED` với người lưu là actor, nên bất kỳ ai cũng suy ra được tập người lập từ nhật ký, và `verify` làm đúng như vậy.

**Phiên bản được thay thế, phê duyệt được gắn chặt.** Trước khi duyệt, một nhãn chỉ là đề xuất: một nhãn bị từ chối hoặc rút lại có thể được trình duyệt lại với nội dung khác, mỗi lần trình duyệt có mã băm riêng, và phê duyệt của nội dung trước không chuyển sang. Sửa một bộ thay đổi đã trình duyệt sẽ đưa nó về `DRAFT` và vô hiệu các phê duyệt của nó. **Khi duyệt, `id@label` được gắn với một mã băm nội dung mãi mãi**: kho từ chối mọi nội dung khác dưới nhãn đó, kể cả sau khi ngừng sử dụng, và dịch vụ từ chối lưu, trình duyệt hay duyệt nó (`LABEL_BOUND`). Hai bộ thay đổi có thể cùng soạn một nhãn; bộ nào được duyệt trước sẽ thắng, bộ còn lại phải dùng nhãn mới, và `removeDraft` bỏ nhãn cũ ra. Nội dung của một phiên bản đã duyệt được giữ trong kho, không bao giờ bị sửa, và tìm lại bằng `repository().find(kind, id, exactVersion)`.

## Chính sách và cấp rủi ro

Chính sách cho biết một thay đổi cần bao nhiêu phê duyệt, và từ ai. Nó là một đối tượng Java hoặc một tệp JSON:

```json
{
  "version": 1,
  "tiers": {
    "standard":      { "approvals": 1, "roles": ["RULE_CHECKER"] },
    "credit-policy": { "approvals": 2, "roles": ["CREDIT_RISK", "COMPLIANCE"], "distinctUnits": true }
  },
  "assign": [
    { "kind": "params", "id": "retail_credit", "tier": "credit-policy" },
    { "kind": "*", "id": "*", "tier": "standard" }
  ],
  "roles": {
    "maker": ["RULE_MAKER"],
    "emergency": ["EMERGENCY_APPROVER"],
    "release": ["RELEASE_MANAGER"],
    "administrator": ["GOVERNANCE_ADMIN"]
  },
  "emergency": { "postApprovalBusinessDays": 1, "zone": "Asia/Ho_Chi_Minh" }
}
```

`GovernancePolicyJson.read` và `write` chuyển đổi qua lại; việc đọc nghiêm ngặt, nên một khoá viết sai là lỗi. Trong Java:

```java
GovernancePolicy policy = GovernancePolicy.builder()
        .tier(GovernancePolicy.Tier.of("standard", 1, "RULE_CHECKER"))
        .tier(GovernancePolicy.Tier.of("credit-policy", 2, "CREDIT_RISK", "COMPLIANCE").withDistinctUnits())
        .assign(ArtifactKind.PARAMS, "retail_credit", "credit-policy")
        .assign("*", "*", "standard")
        .makerRoles("RULE_MAKER")
        .emergencyRoles("EMERGENCY_APPROVER")
        .releaseRoles("RELEASE_MANAGER")
        .administratorRoles("GOVERNANCE_ADMIN")
        .postApprovalDue(1, ZoneId.of("Asia/Ho_Chi_Minh"))
        .build();
```

- Một **cấp rủi ro** (tier) cần `approvals` người duyệt khác nhau, ít nhất bằng số vai trò của nó, và **một người duyệt cho mỗi vai trò nó liệt kê**: người duyệt nêu vai trò mà họ duyệt, phải là vai trò họ đang giữ. Phê duyệt theo các vai trò khác không được tính cho cấp đó.
- `distinctUnits` đòi hỏi người duyệt thuộc các đơn vị tổ chức khác nhau (`Principal.unit`). Người duyệt không có đơn vị, hoặc thuộc đơn vị đã duyệt rồi, bị từ chối với `UNIT_NOT_DISTINCT`.
- **Các phép gán** được thử theo thứ tự, và phép gán đầu tiên khớp loại và mẫu id (`*` khớp mọi ký tự) cho biết cấp của một đối tượng. Một đối tượng không có phép gán nào khớp là lỗi chính sách, nên hãy kết thúc danh sách bằng `{ "kind": "*", "id": "*" }`.
- Một bộ thay đổi lấy **cấp nghiêm ngặt nhất** trong các mục của nó: cấp cần nhiều phê duyệt nhất. Nếu nhiều cấp cần số phê duyệt bằng nhau, yêu cầu là hợp của chúng, đặt tên `a+b`, với vai trò của tất cả. Do đó, một thông điệp danh mục thay đổi cùng với một tham số tín dụng chỉ cần người duyệt của chính sách tín dụng, và trường `policy` của văn bản phê duyệt cho biết cấp nào đã áp dụng, ví dụ `credit-policy@3`: cấp và phiên bản chính sách.
- `roles` liệt kê ai được lập (`maker`; để trống nghĩa là mọi chủ thể đã xác thực), duyệt một thay đổi khẩn cấp (`emergency`; để trống nghĩa là tắt thay đổi khẩn cấp), kích hoạt (`release`; để trống nghĩa là mọi chủ thể trừ AI) và đổi chính sách (`administrator`; để trống nghĩa là không ai). Vai trò đến từ nhà cung cấp định danh của ngân hàng.

**Đổi chính sách** là một sự kiện: `changePolicy(policy)` cần một quản trị viên, lưu chính sách với số phiên bản kế tiếp, và ghi `POLICY_CHANGED` kèm phiên bản cũ và mới, mã băm của chính sách và tên các cấp. Các bộ thay đổi đã trình duyệt được xét theo chính sách có hiệu lực lúc chúng được quyết định.

## Ai được làm gì

Các quy tắc [phân tách nhiệm vụ](governance.md#33-phân-tách-nhiệm-vụ), như dịch vụ áp dụng:

| Quy tắc | Bị từ chối với |
|---|---|
| Người duyệt hay người từ chối không được là người lập của bộ thay đổi. | `FOUR_EYES` |
| Một chủ thể AI (tiền tố `ai:`) có thể lập thay đổi, không bao giờ kiểm tra hay phát hành. | `FORBIDDEN` |
| Quản trị viên quản trị không được duyệt hay từ chối thay đổi luật. | `FORBIDDEN` |
| Người duyệt giữ vai trò mà họ duyệt. | `FORBIDDEN` |
| Vai trò đó là vai trò mà cấp của bộ thay đổi liệt kê (hoặc, với thay đổi khẩn cấp, một vai trò khẩn cấp). | `ROLE_NOT_REQUIRED` |
| Mỗi chủ thể một phê duyệt. | `DUPLICATE_APPROVAL` |
| Từ chối cần một lý do, và một người duyệt có vai trò của cấp. | `INVALID_REQUEST`, `FORBIDDEN` |
| Rút lại dành cho một người lập của bộ thay đổi. | `FORBIDDEN` |
| Lập cần vai trò người lập, kích hoạt cần vai trò phát hành, đổi chính sách cần vai trò quản trị viên, khi chính sách nêu các vai trò này. | `FORBIDDEN` |

Khi đạt cấp rủi ro, dịch vụ trong một lần cập nhật nguyên tử gắn từng nhãn với mã băm của nó, ký văn bản phê duyệt và ghi `APPROVAL_ISSUED`. Văn bản phê duyệt có định dạng như trong [Phê duyệt](approvals.md#văn-bản-phê-duyệt), với `makers` lấy từ nhật ký, mỗi phê duyệt một người duyệt kèm vai trò và thời điểm họ duyệt, `policy` là `tier@version`, và `logEntry`: mã băm của mục `APPROVAL_ISSUED`, gắn văn bản với [chuỗi](#nhật-ký-kiểm-toán). Văn bản được lưu thành `<change set>.approval.jws`.

## Thay đổi khẩn cấp

Khi một luật phải đổi ngay, một người lập khai báo bộ thay đổi là khẩn cấp (`declareEmergency`), nếu chính sách có nêu vai trò khẩn cấp. [Thiết kế](governance.md#35-thay-đổi-khẩn-cấp) giảm bớt nguyên tắc bốn mắt nhưng không bao giờ bỏ nó:

- **Một người duyệt** giữ vai trò khẩn cấp và không phải người lập là đủ, thay cho cấp rủi ro. Phê duyệt theo các vai trò của cấp vẫn được tính cho cấp: nếu chính sách đầy đủ duyệt trước, văn bản là văn bản thông thường.
- Văn bản ghi `"emergency": true`, nêu `postApprovalDue` và có `policy` là `emergency@<version>`. Mặc định hạn là cuối ngày (23:59:59) của ngày làm việc kế tiếp, từ thứ Hai đến thứ Sáu, theo múi giờ của chính sách; `postApprovalDue(days, zone)` đặt cả hai.
- Bộ thay đổi có thể được kích hoạt ngay. Nhật ký ghi `EMERGENCY_DECLARED`, `EMERGENCY_APPROVED`, `APPROVAL_ISSUED` và `ACTIVATED`.
- **Chính sách đầy đủ sau đó phê duyệt bổ sung** cho cùng các mã băm bằng `postApprove` (một người duyệt, không phải người lập, theo một vai trò của cấp, không phải vai trò khẩn cấp). Khi đạt, một văn bản thứ hai không có cờ khẩn cấp được cấp thành `<change set>.post.approval.jws`, và nhật ký ghi `POST_APPROVED`. Một engine đòi nhiều người duyệt hơn văn bản khẩn cấp có sẽ được văn bản này thoả mãn.
- `rejectPostApproval(changeSet, reason)` ghi `POST_APPROVAL_REJECTED`. Phiên bản khi đó phải được thay thế hoặc huỷ, và các quyết định đã đưa ra dưới nó tìm được qua `version` của bản ghi và [chấm điểm lại](replay.md#chấm-điểm-lại-một-quyết-định). Dịch vụ từ chối kích hoạt nó trong các target khác.
- **Phê duyệt bổ sung quá hạn là một cảnh báo và một mục nhật ký, không phải lỗi build.** `checkOverdue()`, gọi theo bộ hẹn giờ, ghi `POST_APPROVAL_OVERDUE` một lần cho mỗi thay đổi khẩn cấp đã quá hạn, và chuyển mục đó cho `listener`. Engine vẫn tạo được, vì một engine đang chạy dù sao cũng giữ phiên bản đó, và việc từ chối build chỉ chặn các lần nạp lại không liên quan. Phê duyệt bổ sung muộn vẫn được chấp nhận.

## Bản phát hành

`activate(changeSet, target)` cấp bản phát hành có chữ ký kế tiếp của target: [định dạng bản phát hành](releases.md#bản-phát-hành) với `sequence` lớn hơn bản mới nhất của target một đơn vị (1 cho bản đầu), `previous` là mã băm của bản đó, các phiên bản của bản phát hành trước và các mục của bộ thay đổi ở trạng thái `ACTIVE`, và `issuedAt`. Bộ thay đổi trở thành `ACTIVE`. Nó cũng có thể vào bản phát hành của các target khác, mỗi target một bản. Kích hoạt một bộ thay đổi chưa được duyệt, đã ngừng sử dụng, hoặc hai lần trong cùng một target đều bị từ chối (`ILLEGAL_STATE`).

- **Ngừng sử dụng chỉ là sổ sách.** Một phiên bản trở thành `RETIRED` khi nó không thể có hiệu lực vào ngày hôm nay: `effectiveTo` của nó đã qua (`"reason": "expired"`), hoặc một phiên bản kế nhiệm đang active đã có hiệu lực (`"reason": "superseded"`). Nó vẫn được liệt kê, để các yêu cầu lùi ngày và chạy lại vẫn chọn được nó và để việc bỏ nó không kéo dài phiên bản trước. Mỗi lần `activate` đều áp dụng điều này, và `retireSuperseded(target)` cấp một bản phát hành chỉ cho việc đó, nếu có gì thay đổi; hãy gọi nó theo bộ hẹn giờ hoặc khi qua ngày. "Hôm nay" là ngày của đồng hồ dịch vụ theo múi giờ của nó. Các phiên bản ngừng sử dụng xuất hiện thành sự kiện `RETIRED`, và trạng thái của mục và của bộ thay đổi đi theo (`RETIRED` khi mọi mục đều vậy).
- **Danh mục được thay, không phải ngừng sử dụng.** Một engine có một danh mục mã lý do, nên một bản phát hành liệt kê nhiều nhất một: kích hoạt một phiên bản danh mục mới sẽ bỏ phiên bản cũ khỏi bản phát hành (`RETIRED` với `"reason": "replaced"`), và nó vẫn nằm trong kho.
- **Định tuyến.** `activate(changeSet, target, routing)` đặt các phiên bản canary và bóng của bản phát hành mới, mỗi phiên bản là một bảng hoặc luồng mà bản phát hành liệt kê là active. Định tuyến là một phần của bản phát hành có chữ ký, nên engine chỉ định tuyến như bản phát hành nói; đặt nó cần một trong các vai trò phát hành của chính sách. Nếu không đặt, định tuyến của bản phát hành trước được giữ lại chừng nào các phiên bản của nó còn active.
- **Quay lui.** Số thứ tự chỉ tăng và kho từ chối một bản phát hành không nối ngay sau bản mới nhất. Một engine được đưa bản phát hành cũ hơn bản nó đã chấp nhận sẽ từ chối nó (`lastAcceptedRelease`, hoặc bộ đệm của `RepositoryReloader`).

## Những gì engine nạp

`service.repository()` là một `StoreRuleRepository`, một [`RuleRepository`](releases.md#kho-luật) chỉ đọc trên kho: `load(target)` trả về bản phát hành có chữ ký mới nhất, nội dung của mọi phiên bản nó liệt kê và mọi văn bản phê duyệt. Nó không tin gì cả và engine kiểm chứng mọi thứ, nên bạn cũng có thể đưa nó cho một `RepositoryReloader`:

```java
RepositoryReloader reloader = RepositoryReloader.builder(service.repository(), "retail-credit-prod", verifier)
        .onReload(engine::set)
        .build();
```

`GovernanceExport.write(store, directory)` ghi cùng trạng thái đó thành tệp theo bố cục của `FileRuleRepository`, cho engine đọc tệp và cho kiểm toán:

```text
export/
  gate_decision.v1.stone.json                         the versions the latest release of each target lists
  reason-codes.json
  approvals/CHG-2026-0042.approval.jws                every statement
  releases/retail-credit-prod.release.jws             the latest release of each target
  releases/history/retail-credit-prod.1.release.jws   every release, for the chain
  governance-log.jsonl                                the log, one entry per line
  governance-policy.json                              the policy in force
```

## Nhật ký kiểm toán

Mỗi sự kiện là một mục của nhật ký chỉ thêm vào, ở dạng JSON chuẩn tắc (gọn, khoá được sắp xếp ở mọi cấp):

```json
{
  "seq": 5,
  "eventId": "00000000-0000-4000-8000-000000000005",
  "at": "2026-11-20T03:12:00.481Z",
  "type": "APPROVED",
  "actor": { "principal": "u:tran.thi.b", "via": "camunda8:rule-change/2251799813685321" },
  "changeSet": "CHG-2026-0042",
  "subjects": [ { "kind": "table", "id": "gate_decision", "version": "1@sha256:9f2c…" } ],
  "details": { "role": "RULE_CHECKER", "comment": "Matches the committee minutes" },
  "prev": "sha256:8c1e…",
  "hash": "sha256:5e0b…"
}
```

- `hash` là SHA-256 của dạng chuẩn tắc của mục không có `hash`; `prev` là `hash` của mục `seq - 1`; `prev` của mục đầu tiên là `sha256:` theo sau là 64 chữ số 0 (`LogEntry.GENESIS`). `seq` không có khoảng trống. `LogEntryJson` ghi và đọc các mục.
- `via` là điều `PrincipalResolver` báo là hệ thống nơi hành động xuất phát. Các mục do chính dịch vụ ghi có actor là `svc:<issuer>`.
- Nhật ký chứa mã, phiên bản, mã băm, chủ thể và văn bản tự do của bình luận, lý do và tiêu đề. Nó **không chứa nội dung luật và không chứa dữ liệu khách hàng**. Bình luận là văn bản tự do mà nhật ký giữ mãi mãi, và RuleStone không phát hiện được dữ liệu khách hàng trong đó: hãy nhắc những người viết chúng.
- `details` chỉ chứa chuỗi, số nguyên, boolean, danh sách và map, không gì khác, để một mục chỉ có một dạng văn bản.

| Sự kiện | Ghi khi | Chi tiết |
|---|---|---|
| `POLICY_CHANGED` | Dịch vụ khởi động trên một kho trống, hoặc `changePolicy` | `fromVersion`, `toVersion`, `policyHash`, `tiers` |
| `DRAFT_CREATED` | `createChangeSet` | `title` |
| `DRAFT_SAVED` | `saveDraft`, `removeDraft` | `submission`, `voidedApprovals`, `onBehalfOf`, `removed` |
| `SUBMITTED` | `submit` | `submission`, `tier`, `tiers`, `requiredApprovals`, `emergency` |
| `WITHDRAWN`, `REJECTED` | `withdraw`, `reject` | `reason`, `voidedApprovals` |
| `COMMENTED` | `comment` | `text`, `location` |
| `APPROVED` | Một phê duyệt, hoặc một phê duyệt bổ sung | `role`, `comment`, `post` |
| `EMERGENCY_DECLARED` | `declareEmergency` | `reason` |
| `EMERGENCY_APPROVED` | Một phê duyệt theo vai trò khẩn cấp | `role`, `comment` |
| `APPROVAL_ISSUED` | Đạt chính sách hoặc phê duyệt khẩn cấp | `approvalId`, `policy`, `emergency`, `approvers`, `postApprovalDue` |
| `POST_APPROVED` | Chính sách đầy đủ đã phê duyệt bổ sung | như `APPROVAL_ISSUED` |
| `POST_APPROVAL_REJECTED` | `rejectPostApproval` | `reason` |
| `POST_APPROVAL_OVERDUE` | `checkOverdue` | `due`, `overdueSeconds` |
| `RELEASED` | Một bản phát hành được ký | `target`, `sequence`, `releaseHash`, `previous`, `changeSet` |
| `ACTIVATED` | Một bộ thay đổi vào bản phát hành | `target`, `sequence` |
| `RETIRED` | Một phiên bản rời tập active của bản phát hành | `target`, `reason` |

`CANCELLED`, `KEY_ADDED`, `KEY_RETIRED` và `ANCHORED` đã được định nghĩa nhưng chưa được ghi.

## Kiểm chứng

`GovernanceVerifier` kiểm tra một nhật ký cùng các văn bản phê duyệt và bản phát hành nó đã cấp, và báo mọi vấn đề; `VerifyTool` là giao diện dòng lệnh của nó.

```java
GovernanceVerifier verifier = GovernanceVerifier.builder()
        .verifier(ApprovalVerifier.builder().trustedKey("gov-2026", publicKey).build())
        .build();
VerificationReport report = verifier.verify(store);          // or verify(directory), or verify(log, approvals, releases)
System.out.print(report.format());
```

```sh
java -cp "$CLASSPATH" vn.io.dxa.rulestone.governance.VerifyTool verify ./export \
    --key gov-2026=gov-2026-public.pem --min-approvers 1
```

Các tệp khoá chứa khoá công khai, Ed25519 hoặc P-256, ở dạng X.509 như PEM hoặc base64 thuần. Hãy giữ các khoá đã ngừng dùng trong cấu hình: một văn bản phê duyệt vẫn hợp lệ cho nội dung nó bao phủ. Mã thoát là 0 khi mọi thứ đều đúng, 1 khi tìm thấy vấn đề và 2 khi tham số hoặc tệp không dùng được. Nó kiểm tra:

- **Chuỗi**: `seq` không có khoảng trống, mọi `prev` là mã băm của mục trước, mọi `hash` được tính lại. Mục đầu tiên mà một phép kiểm tra thất bại được báo cùng `seq` của nó, và các phép kiểm tra dựa vào nhật ký bị bỏ qua. Một mục bị sửa, bị xoá, bị thêm hay bị đổi thứ tự sẽ bị phát hiện ở đây.
- **Các điểm neo**, qua một `AnchorCheck` (xem bên dưới).
- **Mọi văn bản phê duyệt**: chữ ký và dạng chuẩn tắc (như engine kiểm tra), một `logEntry` có tồn tại, là mục `APPROVAL_ISSUED` hoặc `POST_APPROVED` của cùng bộ thay đổi và nêu cùng mã văn bản và phiên bản; `makers` gồm mọi người mà nhật ký cho thấy đã lưu hoặc trình duyệt bộ thay đổi; người duyệt không phải người lập và được nhật ký cho thấy đã duyệt trước đó; và một văn bản cho mỗi mục cấp văn bản của nhật ký.
- **Mọi bản phát hành**: chữ ký, số thứ tự từ 1 không có khoảng trống, `previous` là mã băm của bản phát hành trước, một mục `RELEASED` cho nó, và mọi phiên bản được liệt kê đều được nêu trong một mục `APPROVAL_ISSUED` trước đó và trong một mục `RELEASED` của target.
- **Nội dung**: với kho hoặc các tệp đã xuất, nội dung của mọi phiên bản mà bản phát hành mới nhất của mỗi target liệt kê vẫn băm ra đúng mã băm đó.

```text
log entries: 8
approval statements: 1
releases: 1
FAILED: 2 problems
  - entry 5: hash does not match the entry's content: the entry was edited
  - the checks of statements and releases need an intact log, and were skipped
```

Báo cáo chứa vị trí, bộ thay đổi, văn bản phê duyệt, bản phát hành, phiên bản và chủ thể, không bao giờ chứa nội dung luật, nên kiểm toán viên có thể lưu giữ nó.

**Neo chuỗi là bước kế tiếp.** Một chuỗi chỉ chứng minh các mục không bị sửa đối với người giữ một mã băm muộn hơn: một người trong cuộc có thể ghi lại cả nhật ký thì dựng lại được cả chuỗi, và một nhật ký bị mất các mục cuối vẫn là một chuỗi hợp lệ. `AnchorCheck` là điểm mở rộng: `GovernanceVerifier.builder().anchors(check)` gọi nó với nhật ký sau khi chuỗi đã được kiểm chứng, và nó so đầu chuỗi với điểm neo mới nhất. Các điểm kiểm có chữ ký, dấu thời gian RFC 3161 và sự kiện `ANCHORED` hiện thực hoá nó là bước 5; trước đó `AnchorCheck.NONE` không kiểm tra gì.

## Các SPI

| Giao diện | Bước này đã có | Dùng cho |
|---|---|---|
| `PrincipalResolver` | `InMemoryPrincipalResolver` | `current()` trả về `Principal` của người gọi: một mã ổn định như `u:tran.thi.b`, các vai trò, đơn vị tuỳ chọn, và với chủ thể AI là người nó làm thay. `via()` nêu hệ thống nơi cuộc gọi xuất phát. Ứng dụng chủ ánh xạ claim hoặc nhóm SSO của mình sang vai trò quản trị; RuleStone lưu mã và kiểm tra quy tắc, không bao giờ lưu mật khẩu. |
| `Signer` | `PrivateKeySigner` | `keyId()`, `algorithm()` (`EdDSA` hoặc `ES256`) và `sign(signingInput)`: dịch vụ dựng JWS và xin chữ ký của chuỗi cần ký, để khoá có thể nằm trong HSM hoặc KMS. Với `ES256` chữ ký là R và S, mỗi phần 32 byte. `PrivateKeySigner` ký bằng một `PrivateKey` của JDK, cho kiểm thử và job CI. |
| `GovernanceStore` | `InMemoryGovernanceStore` | Bộ thay đổi và bản nháp, nội dung các phiên bản đã duyệt, văn bản phê duyệt, bản phát hành, chính sách và nhật ký, chỉ đổi qua `commit(Update)`: nguyên tử, nối chuỗi nhật ký, gắn nhãn đúng một lần và từ chối bản phát hành không nối ngay sau bản mới nhất. Kho JDBC sẽ đến sau. |
| `AnchorCheck` | `AnchorCheck.NONE` | So đầu nhật ký với một điểm neo. |
| `RuleRepository` | `StoreRuleRepository` | Những gì engine đọc. |

`JwsWriter` trong `rulestone-core` ghi chuỗi JWS compact mà cả `PrivateKeySigner` lẫn `ApprovalIssuer` trong `rulestone-test` dùng, nên văn bản phê duyệt và bản phát hành giống nhau bất kể ai cấp.

## Lỗi

`GovernanceException.code()` cho biết quy tắc nào đã từ chối, để các nơi gọi phản ứng được:

| Mã | Ý nghĩa |
|---|---|
| `UNAUTHENTICATED` | `PrincipalResolver` không có người gọi. |
| `NOT_FOUND`, `ALREADY_EXISTS` | Bộ thay đổi, target hoặc bản phát hành không tồn tại, hoặc đã tồn tại. |
| `ILLEGAL_STATE` | Vòng đời không cho phép thao tác này lúc này. |
| `FORBIDDEN` | Người gọi thiếu vai trò, hoặc là chủ thể AI hay quản trị viên ở nơi không được phép. |
| `FOUR_EYES`, `DUPLICATE_APPROVAL` | Người gọi là người lập, hoặc đã duyệt rồi. |
| `ROLE_NOT_REQUIRED`, `UNIT_NOT_DISTINCT` | Vai trò không phải vai trò mà cấp liệt kê, hoặc các đơn vị không khác nhau. |
| `LABEL_BOUND` | Nhãn phiên bản đã được một phê duyệt gắn với một mã băm. |
| `INVALID_CONTENT`, `INVALID_POLICY`, `INVALID_REQUEST` | Nội dung, chính sách hoặc yêu cầu sai. |
| `CONFLICT` | Kho đã đổi trong lúc đó, hoặc từ chối một lần cập nhật làm vỡ các ràng buộc của nó. |
| `SIGNING_FAILED` | `Signer` không ký được, hoặc trả về chữ ký không phải 64 byte. Không có gì bị thay đổi. |

Thông báo nêu bộ thay đổi, phiên bản, chủ thể và vai trò. Chúng không bao giờ chứa nội dung luật hay giá trị đầu vào.

## Khác biệt so với thiết kế

- **Cấp nghiêm ngặt nhất.** Thiết kế nói một bộ thay đổi lấy cấp nghiêm ngặt nhất trong các mục của nó. Dịch vụ lấy cấp cần nhiều phê duyệt nhất, và gộp các cấp bằng nhau, để vai trò của một cấp nào đó không bị bỏ qua một cách âm thầm.
- **Chính sách tại lúc quyết định.** Bộ thay đổi được xét theo chính sách có hiệu lực lúc chúng được quyết định, không phải chính sách lúc chúng được trình duyệt.
- **Văn bản phê duyệt bổ sung.** Thiết kế nói chính sách đầy đủ phê duyệt bổ sung cho cùng các mã băm. Dịch vụ cấp một văn bản thứ hai cho việc đó, để phê duyệt bổ sung nằm trong kho luật và kiểm chứng được như mọi văn bản khác.
- **Ngừng sử dụng và huỷ.** Phiên bản được ngừng sử dụng tự động như thiết kế mô tả. Việc huỷ tường minh một phiên bản active chưa từng có hiệu lực, vốn cần phê duyệt, và sự kiện `CANCELLED`, chưa có trong bước này.
- **Xem xét.** Bản so sánh khác biệt, các luồng bị ảnh hưởng bởi một thay đổi bảng và các kiểm thử đính kèm lần trình duyệt, mà thiết kế cho người duyệt xem, thuộc về các màn hình trình soạn thảo. Bình luận ở đây là sự kiện; kiểm thử ngược bắt buộc chưa được mô hình hoá, nên thay đổi khẩn cấp chỉ có thể hoãn người duyệt thứ hai.
- **`GENESIS`.** `prev` của mục đầu tiên là `sha256:` và 64 chữ số 0, theo dạng của mọi mã băm khác.

## Giới hạn

- **Chỉ trong bộ nhớ.** `InMemoryGovernanceStore` mất cùng tiến trình; `GovernanceExport` ghi các tệp có chữ ký và nhật ký. Lưu trữ bền vững là bước JDBC.
- **Chưa neo chuỗi**: một nhật ký bị cắt cụt hoặc bị dựng lại hoàn toàn chỉ phát hiện được bằng một `AnchorCheck`.
- **Một chữ ký cho mỗi văn bản.** Dịch vụ ký thay cho những người duyệt, bằng một khoá; chữ ký của từng người duyệt là tuỳ chọn cho sau này.
- **Phê duyệt từ hệ thống khác** (`ApprovalWorkflow`, Camunda, hệ thống ticket của ngân hàng) và các màn hình trình soạn thảo là các bước sau. Hiện nay người gọi `approve` là chủ thể đã xác thực.
- **Định tuyến do người phát hành đặt**, với vai trò phát hành, và không được phê duyệt như một thay đổi riêng.
- **Luồng quyết định không bị đụng tới.** Đánh giá một quyết định không đọc kho quản trị nào và không kiểm chứng chữ ký nào; các kiểm tra xảy ra khi engine được tạo.
