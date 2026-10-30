---
source: editor-sdk.md
sourceHash: b65106555d46bd52b475414b6eb3aa9ecedd7ab1a50a78d7ae3c8c7f5eb4099a
---

# SDK trình soạn thảo

SDK trình soạn thảo đưa các trình soạn thảo trực quan của RuleStone vào bên trong ứng dụng web của chính ngân hàng. Chuyên viên phân tích dựng bảng quyết định, luật và điều kiện bằng một lưới và các khối kéo thả thay vì gõ JSON, thấy mọi lỗi ngay tại ô liên quan trong lúc gõ, và chạy thử các luật với dữ liệu mẫu, tất cả trong trình duyệt. Ứng dụng của ngân hàng vẫn nắm quyền kiểm soát: nó nạp và lưu các bảng, và có thể bổ sung các bước kiểm tra từ dịch vụ RuleStone của chính mình.

Hãy thử các thành phần này trong [sân thử](https://rulestone.dxa.io.vn/vi/playground) trên trang web.

- [SDK là gì](#sdk-là-gì)
- [Cài đặt và build](#cài-đặt-và-build)
- [Dùng trong ứng dụng của bạn](#dùng-trong-ứng-dụng-của-bạn)
- [Các phần tử](#các-phần-tử)
- [Kiểm tra phía máy chủ: hook validator](#kiểm-tra-phía-máy-chủ-hook-validator)
- [Lưu trữ](#lưu-trữ)
- [Giao diện](#giao-diện)
- [Ngôn ngữ](#ngôn-ngữ)
- [Trợ năng và bàn phím](#trợ-năng-và-bàn-phím)
- [Content Security Policy](#content-security-policy)
- [Kích thước gói](#kích-thước-gói)
- [Bộ máy luật trên trình duyệt và bộ máy luật Java](#bộ-máy-luật-trên-trình-duyệt-và-bộ-máy-luật-java)
- [Hạn chế](#hạn-chế)

## SDK là gì

SDK gồm hai gói npm trong thư mục [`sdk/`](../../sdk/) của repository:

| Gói | Nội dung |
|---|---|
| `@rulestone/engine` | Bộ máy luật viết bằng TypeScript: trình đọc `.stone.json` chặt chẽ, CEL được kiểm tra kiểu với các kiểu chính xác `decimal`, `money` và `date`, đánh giá theo phiên bản và bản ghi quyết định. Gói không có thư viện phụ thuộc lúc chạy. |
| `@rulestone/editor` | Các trình soạn thảo dưới dạng Web Component, xây dựng bằng [Lit](https://lit.dev): trình soạn thảo bảng, lưới luật, trình tạo điều kiện, trình thiết kế đầu vào và đầu ra, bảng Chạy thử, ô nhập CEL và vùng vẽ luồng quyết định. Gói dùng bộ máy luật để kiểm tra kiểu và để Chạy thử. |

Web Component dùng được trong mọi framework (React, Angular, Vue) và cả khi không dùng framework. Các phần tử không gọi mạng: trang chủ quản (host) đưa dữ liệu cho chúng qua thuộc tính (property) và nhận mọi thay đổi qua sự kiện.

SDK dùng để nhúng. [Trình soạn thảo web](editor.md) (`rulestone-editor`) là một ứng dụng hoàn chỉnh riêng, có máy chủ Java của riêng nó, dùng để sửa các tệp `.stone.json` trong một thư mục. Hãy dùng trình soạn thảo web để sửa trực tiếp một kho luật; dùng SDK để đưa chức năng soạn luật vào một hệ thống sẵn có, chẳng hạn ứng dụng khởi tạo khoản vay hoặc cấu hình sản phẩm, vốn có cách lưu trữ, đăng nhập và quy trình phê duyệt riêng.

Cả hai gói được cấp phép theo Apache-2.0. Hiện chúng chưa công khai, và tên gói là tạm thời cho tới khi được phát hành lên npm.

## Cài đặt và build

Cho tới khi các gói được phát hành, hãy build chúng từ repository. Bạn cần Node.js 22 trở lên.

```sh
git clone https://github.com/xvalleycorp/RuleStone.git
cd RuleStone/sdk
npm ci
npm run build    # the engine, then the editor, into packages/*/dist
npm pack --workspace @rulestone/engine --workspace @rulestone/editor
```

`npm pack` tạo ra `rulestone-engine-0.1.0.tgz` và `rulestone-editor-0.1.0.tgz`. Cài cả hai vào ứng dụng của bạn trong cùng một lệnh, để trình soạn thảo tìm thấy bộ máy luật:

```sh
npm install ../RuleStone/sdk/rulestone-engine-0.1.0.tgz ../RuleStone/sdk/rulestone-editor-0.1.0.tgz
```

Các gói là ES module kèm kiểu TypeScript, dành cho một bundler như Vite, webpack, esbuild hoặc Angular CLI. Mỗi phần tử có điểm vào riêng, nên một ứng dụng chỉ cần trình tạo điều kiện sẽ không phải nạp phần còn lại:

| Import | Định nghĩa |
|---|---|
| `@rulestone/editor` | Mọi phần tử; xuất các lớp, mô hình và chuỗi giao diện |
| `@rulestone/editor/table-editor` | `<rulestone-table-editor>` và các phần tử bên trong nó |
| `@rulestone/editor/rules-grid` | `<rulestone-rules-grid>` |
| `@rulestone/editor/condition-builder` | `<rulestone-condition-builder>` |
| `@rulestone/editor/schema-designer` | `<rulestone-schema-designer>` |
| `@rulestone/editor/try-it` | `<rulestone-try-it>` |
| `@rulestone/editor/cel-input` | `<rs-cel-input>` |
| `@rulestone/editor/flow-canvas` | `<rulestone-flow-canvas>` |
| `@rulestone/editor/model` | Không có phần tử nào: các thao tác trên bảng, đầu vào và đầu ra, điều kiện và luồng, cùng việc kiểm tra hợp lệ, để bạn tự làm giao diện hoặc viết kiểm thử |

## Dùng trong ứng dụng của bạn

Mỗi ví dụ dưới đây hiển thị trình soạn thảo bảng với một bảng mà ứng dụng chủ đã nạp, và lưu bảng sau mỗi thay đổi.

### HTML thuần

```html
<rulestone-table-editor locale="vi"></rulestone-table-editor>

<script type="module">
  import '@rulestone/editor/table-editor'

  const editor = document.querySelector('rulestone-table-editor')
  editor.table = await (await fetch('/rules/loan_eligibility.stone.json')).json()
  editor.addEventListener('rulestone-change', (e) => {
    if (e.detail.valid) save(e.detail.table, e.detail.ruleVersion)
  })
</script>
```

`save` là mã của chính ứng dụng bạn.

### React

React 19 gán thuộc tính cho custom element và lắng nghe sự kiện của chúng:

```tsx
import '@rulestone/editor/table-editor'

export function RuleEditor({ table, onSave }: Props) {
  return (
    <rulestone-table-editor
      table={table}
      locale="vi"
      onrulestone-change={(e: CustomEvent) => onSave(e.detail.table)}
    />
  )
}
```

Với React 18, gán thuộc tính và trình lắng nghe qua một ref:

```tsx
import { useEffect, useRef } from 'react'
import type { RulestoneTableEditor } from '@rulestone/editor'
import '@rulestone/editor/table-editor'

export function RuleEditor({ table, onSave }: Props) {
  const ref = useRef<RulestoneTableEditor>(null)
  useEffect(() => {
    const el = ref.current!
    el.table = table
    const listener = (e: Event) => onSave((e as CustomEvent).detail.table)
    el.addEventListener('rulestone-change', listener)
    return () => el.removeEventListener('rulestone-change', listener)
  }, [table])
  return <rulestone-table-editor ref={ref} locale="vi" />
}
```

Trong TypeScript, hãy khai báo các thẻ bạn dùng trong `JSX.IntrinsicElements`.

### Angular

```ts
import { CUSTOM_ELEMENTS_SCHEMA, Component } from '@angular/core'
import '@rulestone/editor/table-editor'

@Component({
  selector: 'app-rule-editor',
  standalone: true,
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `<rulestone-table-editor [table]="table" locale="vi"
    (rulestone-change)="save($event.detail.table)"></rulestone-table-editor>`,
})
export class RuleEditorComponent {
  table: object = { /* loaded by the host */ }
  save(table: object) { /* stored by the host */ }
}
```

Angular gắn `[table]` vào thuộc tính của phần tử, và `(rulestone-change)` vào sự kiện của nó.

### Vue

Hãy cho Vue biết các thẻ `rulestone-` và `rs-` là custom element, trong `vite.config.ts`, hoặc trong mục `vue` của cấu hình một framework xây dựng trên Vite (với VitePress là `.vitepress/config.ts`):

```ts
vue({
  template: { compilerOptions: { isCustomElement: (tag) => tag.startsWith('rulestone-') || tag.startsWith('rs-') } },
})
```

Truyền đối tượng dưới dạng thuộc tính bằng `.prop`. Khi có render phía máy chủ (Nuxt, VitePress), chỉ import các phần tử ở phía trình duyệt, sau khi component được gắn vào trang:

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
const table = ref({ /* loaded by the host */ })
onMounted(() => import('@rulestone/editor/table-editor'))
</script>

<template>
  <rulestone-table-editor :table.prop="table" locale="vi"
    @rulestone-change="(e) => (table = e.detail.table)" />
</template>
```

[Sân thử](https://rulestone.dxa.io.vn/vi/playground) của trang web là một trang VitePress làm theo cách này, và các phần tử chỉ được nạp trên trang đó.

## Các phần tử

Mọi phần tử đều có các attribute và thuộc tính sau:

| Attribute / thuộc tính | Ý nghĩa |
|---|---|
| `locale` | `en` hoặc `vi`. Mặc định là `lang` của phần tử tổ tiên gần nhất có thuộc tính này, nếu không thì tiếng Anh. |
| `readonly` | Hiển thị mọi thứ nhưng không cho thay đổi. |
| `theme` | `light` hoặc `dark` để chọn giao diện sáng hoặc tối; mặc định phần tử theo `prefers-color-scheme`. Phần tử truyền giá trị này xuống các phần tử bên trong nó. |
| `messages` (thuộc tính) | Ghi đè các chuỗi có sẵn, dưới dạng một đối tượng một phần lồng nhau, ví dụ `{ grid: { addRule: 'New rule' } }`. |

Sự kiện là các `CustomEvent` nổi bọt (bubble) và đi xuyên shadow root (`composed`). Những thuộc tính nhận đối tượng (`table`, `inputs`, `flow`) không có attribute: hãy gán chúng bằng script hoặc qua cách gắn thuộc tính của framework bạn dùng.

### Trình soạn thảo bảng

`<rulestone-table-editor>` là trình soạn thảo bảng quyết định đầy đủ: thông tin chung của bảng, lưới luật, trình thiết kế đầu vào và đầu ra, chế độ xem JSON, bảng các lỗi và bảng Chạy thử.

| Thành phần | Loại | Mô tả |
|---|---|---|
| `table` | thuộc tính | Bảng dưới dạng `.stone.json` đã phân tích, một đối tượng thuần. Trình soạn thảo làm việc trên một bản sao và không bao giờ thay đổi đối tượng của ứng dụng chủ. |
| `validator` | thuộc tính | Hàm tuỳ chọn `async (table, localResult) => HostProblem[]` cho các bước kiểm tra phía máy chủ. Xem [hook validator](#kiểm-tra-phía-máy-chủ-hook-validator). |
| `asOf` / `as-of` | thuộc tính / attribute | Ngày nghiệp vụ mà bảng Chạy thử bắt đầu (`yyyy-MM-dd`). Mặc định: hôm nay. |
| `facts` | thuộc tính / attribute | Dữ liệu đầu vào cho bảng Chạy thử dưới dạng văn bản JSON, theo các dạng của một ca kiểm thử `.stone-test.json`. |
| `getTable()` | phương thức | Bản sao của bảng đang sửa. |
| `setTable(table)` | phương thức | Thay bảng đang sửa. |
| `validate()` | phương thức | Kiểm tra ngay; trả về (resolve) `{ valid, problems, canonical, ruleVersion }`. |
| `run()` | phương thức | Chạy bảng trong bảng Chạy thử với dữ liệu đầu vào hiện có; trả về `detail` của sự kiện `rulestone-run`. |
| `rulestone-change` | sự kiện | Sau mỗi lần sửa, khi đã kiểm tra xong. `detail`: `{ table, canonical, problems, ruleVersion, valid }`. |
| `rulestone-validate` | sự kiện | Sau mỗi lần kiểm tra, kể cả khi ứng dụng chủ gán `table`. `detail`: `{ valid, problems, ruleVersion, canonical }`. |
| `rulestone-run` | sự kiện | Sau mỗi lần Chạy thử; xem bảng Chạy thử. |

`ruleVersion` là phiên bản luật mà bộ máy luật sẽ báo cho bảng, dạng `<version>@sha256:<hash>`, được tính trên `canonical`, tức JSON chuẩn tắc của bảng, đúng như cách bộ máy luật Java tính. Cả hai vắng mặt khi trình đọc chặt chẽ chưa đọc được bảng.

Các lỗi đến từ bộ máy luật: trình đọc tệp chặt chẽ (trường lạ, sai kiểu, ngày), trình biên dịch (trùng mã luật, định dạng mã lý do, đầu ra chưa khai báo, phiên bản) và bộ kiểm tra kiểu của từng biểu thức. Mỗi lỗi hiện ngay tại ô của nó, đúng dòng và cột mà bộ máy luật báo. Một cảnh báo đánh dấu các luật nằm sau một luật bắt mọi trường hợp dưới `FIRST`, vì chúng không bao giờ khớp.

### Lưới luật

`<rulestone-rules-grid>` hiển thị các luật dưới dạng một lưới giống bảng tính, mỗi luật một dòng, với các cột: kết quả của lần chạy gần nhất, mã luật, điều kiện, từng đầu ra, mã lý do, mô tả và các thao tác.

| Thành phần | Loại | Mô tả |
|---|---|---|
| `table` | thuộc tính | Bảng; lưới sửa các luật của nó. |
| `problems` | thuộc tính | Các lỗi tìm thấy ở nơi khác, ví dụ bởi trình soạn thảo bảng hoặc ứng dụng chủ. Lưới tự kiểm tra kiểu mọi ô biểu thức. |
| `matchedRules`, `evaluatedRules` | thuộc tính | Mã luật từ một lần Chạy thử. Các dòng được đánh dấu là khớp, không khớp hoặc không được đánh giá, bằng ký hiệu và chữ chứ không chỉ bằng màu. |
| `focusCell(rule, field, output?, index?)` | phương thức | Chuyển tiêu điểm tới một ô. Với một biểu thức có vị trí, bắt đầu sửa với con trỏ ở vị trí đó. |
| `move(from, to)` | phương thức | Di chuyển một luật và thông báo vị trí mới của nó. |
| `rulestone-change` | sự kiện | `detail`: `{ table }` sau mỗi lần sửa. |

Có thể thêm, nhân bản, xoá và sắp xếp lại các luật bằng kéo thả hoặc bằng bàn phím. Thao tác ⊞ mở trình tạo điều kiện bên dưới một luật. Điều kiện dài được xuống dòng ở dấu cách và quanh toán tử; một tên chỉ bị ngắt khi nó rộng hơn cột chứa nó.

### Trình tạo điều kiện

`<rulestone-condition-builder>` hiển thị một điều kiện dưới dạng các khối AND/OR lồng nhau, hoặc dưới dạng văn bản CEL.

| Thành phần | Loại | Mô tả |
|---|---|---|
| `value` | thuộc tính / attribute | Điều kiện dưới dạng CEL. |
| `inputs` | thuộc tính | Các đầu vào của bảng, như trong `.stone.json`, cho bộ chọn trường. |
| `mode` | thuộc tính / attribute | `blocks` hoặc `cel`. |
| `label` | thuộc tính / attribute | Tên truy cập (accessible name) của trình tạo điều kiện. |
| `rulestone-change` | sự kiện | `detail`: `{ value }`, đoạn CEL mới. |

Bộ chọn trường liệt kê các đầu vào và các trường của struct. Danh sách được kiểm tra bằng các khối `exists`, `all` và `exists_one`, với các trường riêng của phần tử trong danh sách. Toán tử tuỳ theo kiểu: so sánh cho `int`, `decimal`, `money` và `date`; `in`; `startsWith`, `endsWith`, `contains` và `matches` cho chuỗi; `is set` (`has()`) cho trường của struct; `includes` cho danh sách. Giá trị có kiểu (tiền kèm đơn vị tiền tệ, ngày, số thập phân, danh sách), và nút ƒx biến một giá trị thành biểu thức.

Đoạn CEL không bị sửa được giữ nguyên đúng như khi viết, kể cả khoảng trắng và chú thích. Những phần đã sửa được in ra dưới dạng CEL chuẩn tắc, chỉ với các dấu ngoặc mà thứ tự ưu tiên đòi hỏi. Đoạn CEL mà các khối không thể hiện được (một phép tính ở vế trái của phép so sánh, `filter` hoặc `map`, toán tử điều kiện, một lỗi cú pháp) được giữ dưới dạng văn bản trong một khối CEL, kèm lý do. Nó không bao giờ bị viết lại.

### Trình thiết kế đầu vào và đầu ra

`<rulestone-schema-designer>` hiển thị đầu vào và đầu ra của một bảng dưới dạng cây với mọi kiểu: `bool`, `int`, `string`, `decimal`, `money`, `date`, `struct`, `list` và các kiểu lồng nhau. Đầu ra không thể là struct.

| Thành phần | Loại | Mô tả |
|---|---|---|
| `table` | thuộc tính | Bảng. Cần các luật để đổi tên trường ở mọi nơi trường đó được dùng. |
| `rulestone-change` | sự kiện | `detail`: `{ table, rename? }`. `rename` cho biết một lần đổi tên đã sửa những biểu thức nào, và biểu thức nào không cập nhật được. |

Đổi tên một trường sẽ cập nhật mọi biểu thức luật dùng nó thông qua cây cú pháp đã kiểm tra kiểu, không bao giờ bằng cách thay thế văn bản. Một biến lặp, một trường cùng tên của struct khác, hay cái tên nằm trong một chuỗi đều được giữ nguyên. Trình thiết kế từ chối những tên không hợp lệ, là từ khoá dành riêng, đã được dùng, hoặc sẽ bị một biến lặp che khuất.

### Bảng Chạy thử

`<rulestone-try-it>` chạy một bảng ngay trong trình duyệt bằng `@rulestone/engine`.

| Thành phần | Loại | Mô tả |
|---|---|---|
| `table` | thuộc tính | Bảng cần chạy. |
| `asOf` / `as-of` | thuộc tính / attribute | Ngày nghiệp vụ. Mặc định: hôm nay, theo múi giờ của trình duyệt. |
| `facts` | thuộc tính / attribute | Dữ liệu đầu vào ban đầu dưới dạng văn bản JSON. |
| `factsText` | thuộc tính (chỉ đọc) | Dữ liệu đầu vào sẽ được chạy, dưới dạng văn bản JSON. |
| `run()` | phương thức | Chạy bảng; trả về `detail` của sự kiện. |
| `rulestone-run` | sự kiện | `detail`: `{ ok, result?, record?, recordJson?, error?, problems?, matchedRules, evaluatedRules }`. |

Biểu mẫu được tạo từ các đầu vào, với ô nhập cho tiền (số tiền và đơn vị tiền tệ), ngày, số thập phân, struct và danh sách. Chế độ JSON nhận dữ liệu đầu vào theo cùng các dạng như tệp `.stone-test.json`. Kết quả hiển thị các đầu ra, mã lý do, vết thực thi (trace), phiên bản luật và bản ghi quyết định.

### Ô nhập CEL

`<rs-cel-input>` là ô nhập văn bản CEL có tô màu cú pháp, kiểm tra kiểu và tự động gợi ý.

| Thành phần | Loại | Mô tả |
|---|---|---|
| `value` | thuộc tính / attribute | Biểu thức. |
| `variables` | thuộc tính | Các biến có kiểu trong phạm vi. Khi được gán, văn bản được kiểm tra kiểu và các trường được gợi ý. |
| `expected` | thuộc tính | Kiểu mà biểu thức phải trả về, ví dụ `bool` cho một điều kiện. |
| `multiline` | thuộc tính / attribute | Cho phép xuống dòng; khi đó Enter chèn một dòng mới và Ctrl+Enter xác nhận. |
| `label`, `placeholder` | thuộc tính / attribute | Tên truy cập và chữ gợi ý. |
| `issues` | thuộc tính | Các lỗi cần đánh dấu thay cho những lỗi phần tử tự tìm thấy. |
| `showProblems` / `show-problems` | thuộc tính / attribute | Liệt kê các lỗi bên dưới ô nhập. |
| `focusAt(index)` | phương thức | Đưa tiêu điểm vào ô nhập với con trỏ ở một chỉ số UTF-16. |
| `rulestone-input`, `rulestone-commit`, `rulestone-cancel` | sự kiện | Sau mỗi lần sửa và khi xác nhận (`detail`: `{ value }`), và khi nhấn Escape. |

### Vùng vẽ luồng

`<rulestone-flow-canvas>` thiết kế một luồng quyết định (`.flow.json`, đề xuất trong [Ra quyết định AI lai](hybrid-ai.md#3-mẫu-b-mô-hình-là-nút-trong-luồng-quyết-định)) trên một vùng vẽ, với bảng các loại nút, các cạnh, cuộn và thu phóng, bảng thuộc tính và những bước kiểm tra mà trình soạn thảo làm được khi không có bộ máy luật.

| Thành phần | Loại | Mô tả |
|---|---|---|
| `flow` | thuộc tính | Luồng, dưới dạng `.flow.json` đã phân tích. |
| `layout` | thuộc tính | Vị trí các nút theo mã nút. Định dạng tệp không có bố cục, nên bố cục được giữ riêng. Những vị trí còn thiếu được tự động bố trí. |
| `tables` | thuộc tính | Các mã quyết định đã biết; nút bảng nào dùng mã khác sẽ bị báo. |
| `problems` | thuộc tính (chỉ đọc) | Kết quả kiểm tra của luồng hiện tại. |
| `fit()` | phương thức | Hiển thị luồng ở cỡ đọc được: toàn bộ nếu vừa, nếu không thì từ góc trên bên trái, nơi có nút bắt đầu. |
| `arrange()` | phương thức | Bố trí lại mọi nút cho vừa kích thước vùng vẽ. |
| `toJson()`, `importText(text)` | phương thức | Luồng dưới dạng văn bản `.flow.json`; thay luồng bằng một luồng đọc từ văn bản (trả về `false` và hiện lỗi nếu không đọc được). |
| `addAt(type, at?)`, `connectNodes(from, to, failure?)` | phương thức | Thêm một nút (trả về mã nút); nối hai nút, hoặc đặt `onFailure` cho một nút mô hình. |
| `rulestone-change` | sự kiện | `detail`: `{ flow, layout, problems }`. |
| `rulestone-export` | sự kiện | `detail`: `{ flow, json }` khi người dùng xuất luồng. Gọi `preventDefault()` để tự xử lý tệp; nếu không, trình duyệt sẽ tải tệp về. |

Bảng các nút có các loại `table`, `switch`, `derive`, `model`, `split`, `end` và `flow`. Các bước kiểm tra tìm ra chu trình, nút mà nút bắt đầu không tới được, đường đi không bao giờ tới nút kết thúc, nút mô hình không có `onFailure` hoặc không ghim phiên bản, tỷ lệ phần trăm của `split` không cộng lại bằng 100, tham chiếu tới bảng không xác định, `switch` thiếu nhánh cuối, và nút `end` không gán mọi đầu ra.

Cho tới khi có người di chuyển một nút, vùng vẽ tự bố trí luồng theo kích thước của chính nó: một chuỗi nút dài được xuống hàng, như các dòng chữ, để toàn bộ luồng đọc được mà không cần cuộn nếu vừa. Khi vừa khung, vùng vẽ không bao giờ thu nhỏ quá mức mà chữ trên nút còn đọc được (80%); khi đó phần còn lại của một luồng lớn được xem bằng cách kéo nền, dùng phím mũi tên hoặc các nút thu phóng.

**Trên trình duyệt, luồng chỉ để thiết kế.** Bộ máy luật Java đã chạy được [luồng quyết định](flows.md), nhưng bộ máy luật trên trình duyệt (`@rulestone/engine`) chưa chạy được luồng, và vùng vẽ có ghi rõ điều đó.

## Kiểm tra phía máy chủ: hook validator

Trình soạn thảo bảng kiểm tra mọi thứ mà bộ máy luật trên trình duyệt kiểm tra được. Dịch vụ RuleStone của chính ngân hàng có thể kiểm tra nhiều hơn: ví dụ, một phiên bản mới không chồng lấn các phiên bản đã lưu, hay các quy định riêng của ngân hàng. Thuộc tính `validator` kết nối những bước kiểm tra đó:

```ts
type TableValidator = (table: TableJson, local: ValidationResult) => Promise<readonly HostProblem[]>

interface HostProblem {
  message: string
  severity?: 'error' | 'warning'     // default 'error'
  target?: ProblemTarget             // default: the table as a whole
}

type ProblemTarget =
  | { kind: 'table'; field?: string }
  | { kind: 'schema'; side: 'inputs' | 'outputs'; name?: string }
  | { kind: 'rule'; rule: number; field: 'id' | 'when' | 'then' | 'reasonCode' | 'description' | 'rule'; output?: string }
```

Trình soạn thảo gọi validator sau các bước kiểm tra của chính nó, mỗi lần kiểm tra: khi ứng dụng chủ gán một bảng, và 150 ms sau lần sửa cuối cùng. `local` là kết quả của chính trình soạn thảo, nên validator có thể bỏ qua một bảng vốn đã không hợp lệ. Trình soạn thảo hiển thị các lỗi của ứng dụng chủ cùng với lỗi của nó, có ghi là đến từ ứng dụng chủ, và đưa chúng vào `rulestone-change` và `rulestone-validate`. Kết quả nào về sau một lần sửa mới hơn sẽ bị bỏ qua. Nếu validator ném lỗi, thông báo lỗi được hiện dưới dạng cảnh báo, và việc soạn thảo vẫn tiếp tục.

Ví dụ này gọi [API của trình soạn thảo web](editor.md#post-apivalidate), một dịch vụ RuleStone viết bằng Java, qua một đường dẫn của ứng dụng chủ. Nó chỉ giữ lại những lỗi liên quan tới một tệp khác đã lưu, vì trình duyệt đã báo các lỗi còn lại:

```ts
editor.validator = async (table, local) => {
  if (local.ruleVersion === undefined) return []    // not readable yet
  const response = await fetch('/rules-api/validate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(table),
  })
  if (!response.ok) throw new Error(`Rule service: HTTP ${response.status}`)
  const result = await response.json()
  return result.problems
    .filter((p) => p.location?.file)
    .map((p) => ({ message: `${p.message} (${p.location.file})` }))
}
```

## Lưu trữ

Ứng dụng chủ chịu trách nhiệm lưu trữ. Các phần tử không bao giờ tự tải hay lưu thứ gì:

- **Nạp:** gán `table` (hoặc `flow` và `layout`) bằng dữ liệu mà ứng dụng của bạn đã nạp. Trình soạn thảo làm việc trên một bản sao.
- **Lưu:** lắng nghe `rulestone-change`. `detail.table` là bảng đã sửa dưới dạng đối tượng thuần, sẵn sàng cho `JSON.stringify`; `detail.valid` cho biết bộ máy luật có chấp nhận bảng hay không, và `detail.ruleVersion` là phiên bản mà bộ máy luật sẽ báo cho bảng. Chỉ lưu bảng hợp lệ, hoặc lưu bản nháp và chỉ công bố bảng hợp lệ, tuỳ quy trình của bạn.
- **Đọc trạng thái hiện tại:** gọi `getTable()` bất cứ lúc nào, và `validate()` để chờ một kết quả mới.
- **Luồng:** lưu `flow` thành tệp `.flow.json`, và lưu `layout` bên cạnh nếu muốn giữ vị trí các nút; `rulestone-change` mang cả hai.

Để ghi tệp, hãy dùng `tableText(table)` từ `@rulestone/editor/model`. Hàm này ghi các trường theo thứ tự của định dạng `.stone.json`, thụt lề hai dấu cách mỗi cấp, để các tệp đã lưu diff gọn gàng trong Git.

## Giao diện

Các phần tử dùng các thuộc tính tuỳ biến CSS (custom property), mà ứng dụng chủ đặt trên phần tử hoặc trên bất kỳ phần tử tổ tiên nào. Mỗi màu có một giá trị mặc định cho giao diện sáng và một cho giao diện tối. Attribute `theme` chọn sáng hoặc tối; nếu không có, các phần tử theo `prefers-color-scheme`. Để đổi màu cho riêng một giao diện, hãy đặt nó trong quy tắc `@media (prefers-color-scheme: dark)` của bạn hoặc cho `[theme='dark']`.

| Token | Sáng | Tối | Dùng cho |
|---|---|---|---|
| `--rs-color-bg` | `#ffffff` | `#0f1217` | Nền của mỗi phần tử |
| `--rs-color-surface` | `#f6f8fa` | `#161b22` | Bảng phụ, thanh công cụ, tiêu đề bảng |
| `--rs-color-surface-raised` | `#ffffff` | `#1c2129` | Ô nhập, khối, nút của luồng |
| `--rs-color-text` | `#1f2328` | `#e6edf3` | Chữ |
| `--rs-color-text-muted` | `#59636e` | `#9da7b3` | Chữ phụ, gợi ý |
| `--rs-color-border` | `#d1d9e0` | `#3d444d` | Viền nhạt, đường kẻ lưới |
| `--rs-color-border-strong` | `#818b98` | `#656c76` | Viền của các điều khiển |
| `--rs-color-accent` | `#0b5cad` | `#4493f8` | Nút chính, nhóm AND |
| `--rs-color-on-accent` | `#ffffff` | `#0f1217` | Chữ trên màu nhấn |
| `--rs-color-accent-subtle` | `#ddf4ff` | `#12263f` | Nút bật đang được nhấn, vùng chọn |
| `--rs-color-focus` | `#0969da` | `#58a6ff` | Viền tiêu điểm |
| `--rs-color-error` | `#b42318` | `#ff7b72` | Lỗi, ô không hợp lệ |
| `--rs-color-error-subtle` | `#ffebe9` | `#3a1517` | Nền của ô không hợp lệ |
| `--rs-color-warning` | `#7d4e00` | `#d29922` | Cảnh báo, thông báo chỉ để thiết kế |
| `--rs-color-warning-subtle` | `#fff8c5` | `#33270b` | Nền của cảnh báo |
| `--rs-color-success` | `#116329` | `#3fb950` | Trạng thái hợp lệ, dấu khớp, nút kết thúc |
| `--rs-color-match` | `#dafbe1` | `#12361f` | Dòng khớp khi Chạy thử |
| `--rs-color-nomatch` | `#f6f8fa` | `#161b22` | Dòng không khớp |
| `--rs-syntax-string`, `-number`, `-keyword`, `-function`, `-field`, `-variable`, `-operator`, `-comment` | | | Tô màu CEL |
| `--rs-font-family`, `--rs-font-family-mono` | phông hệ thống | | Chữ; CEL, mã và JSON |
| `--rs-font-size` | `14px` | | Cỡ chữ cơ sở |
| `--rs-radius`, `--rs-space`, `--rs-focus-width` | `6px`, `8px`, `2px` | | Góc bo, khoảng cách, viền tiêu điểm |
| `--rs-flow-canvas-height` | khoảng hai phần ba cửa sổ | | Chiều cao của vùng vẽ luồng |

Các giá trị mặc định đạt tỷ lệ tương phản WCAG 2.1 AA. Nếu đổi màu, hãy kiểm tra lại độ tương phản giữa chữ và nền. Ví dụ, sân thử dùng màu của trang web:

```css
.rule-editor {
  --rs-color-bg: var(--vp-c-bg);
  --rs-color-surface: var(--vp-c-bg-soft);
  --rs-color-text: var(--vp-c-text-1);
  --rs-color-text-muted: var(--vp-c-text-2);
  --rs-color-border: var(--vp-c-divider);
  --rs-color-accent: var(--vp-c-brand-1);
}
```

Để tinh chỉnh hơn, hãy định dạng các phần (part) mà phần tử cung cấp bằng `::part()`:

| Phần tử | Các part |
|---|---|
| Trình soạn thảo bảng | `metadata`, `tabs`, `problems`, `try-it` |
| Lưới luật | `toolbar`, `grid`, `row` (cả `row matched`, `row not-matched`, `row not-evaluated`), `cell` (cả `cell invalid`) |
| Trình tạo điều kiện | `toolbar`, `group`, `block` |
| Trình thiết kế đầu vào và đầu ra | `tree`, `item`, `report` |
| Bảng Chạy thử | `form`, `result` |
| Vùng vẽ luồng | `banner`, `palette`, `canvas`, `panel`, `problems` |
| Ô nhập CEL | `input` |

## Ngôn ngữ

Các phần tử có sẵn chuỗi tiếng Anh và tiếng Việt. `locale` chọn một trong hai; nếu không có, phần tử dùng attribute `lang` của phần tử tổ tiên gần nhất có nó, nên chỉ cần `<html lang="vi">` là đủ cho một trang tiếng Việt. Chuỗi tiếng Việt theo [bảng thuật ngữ](GLOSSARY.md) của dự án.

Ghi đè chuỗi cho cả trang bằng `registerMessages`, hoặc cho một phần tử bằng thuộc tính `messages` của nó:

```ts
import { registerMessages } from '@rulestone/editor'
registerMessages('vi', { grid: { addRule: 'Thêm luật mới' } })   // for every element on the page
editor.messages = { tryIt: { run: 'Chạy' } }                       // for one element
```

Các khoá là khoá của đối tượng `en` được xuất ra. Những lỗi đến từ bộ máy luật, như lỗi kiểu, là thông báo của chính bộ máy luật, bằng tiếng Anh, đúng như bộ máy luật Java báo.

## Trợ năng và bàn phím

Các phần tử được xây dựng theo WCAG 2.1 AA. Mọi thao tác kéo thả đều có cách làm tương đương bằng bàn phím, các lần di chuyển và thay đổi khác được thông báo qua một vùng thông báo lịch sự (polite live region), lưới và các cây dùng vai trò ARIA, tiêu điểm luôn nhìn thấy được, và kết quả một lần chạy được thể hiện bằng ký hiệu và chữ chứ không chỉ bằng màu.

| Ở đâu | Phím |
|---|---|
| Lưới luật | Phím mũi tên, Home, End, Page Up và Page Down để di chuyển giữa các ô; Enter hoặc F2 để sửa; Enter để lưu và Escape để huỷ; Alt+↑ và Alt+↓ để di chuyển luật; ở ô thao tác, Enter để vào các nút và Escape để quay ra. |
| Trình tạo điều kiện | Trên tay nắm của một khối: Alt+↑ và Alt+↓ di chuyển khối trong nhóm của nó, Alt+→ đưa khối vào nhóm phía trên, Alt+← đưa ra khỏi nhóm. Mỗi khối cũng có các nút di chuyển. |
| Trình thiết kế đầu vào và đầu ra | ↑ và ↓ để di chuyển giữa các trường, → và ← để hiện và ẩn trường lồng nhau, Alt+↑ và Alt+↓ để sắp xếp lại, Enter để sửa tên. |
| Ô nhập CEL | Ctrl+Space hiện gợi ý; ↑ và ↓ để chọn, Enter hoặc Tab để nhận, Escape để đóng. |
| Vùng vẽ luồng | Các nút trong bảng các nút để thêm một nút. Trên vùng vẽ: phím mũi tên để cuộn, + và − để thu phóng, 0 để vừa khung; Ctrl hoặc ⌘ cùng con lăn chuột cũng thu phóng, còn con lăn một mình thì cuộn trang. Trên màn hình cảm ứng, một ngón tay để cuộn, hai ngón tay chụm hoặc mở ra để thu phóng. Trên một nút: phím mũi tên để di chuyển (Shift để bước lớn hơn), C để bắt đầu nối (F cho `onFailure` của nút mô hình) và Enter trên nút đích để hoàn tất, Delete để xoá. Cũng có thể đặt và bỏ các cạnh trong bảng thuộc tính. |

## Content Security Policy

Các phần tử hoạt động dưới một chính sách bảo mật nội dung (CSP) chặt chẽ như:

```
default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'
```

- **Script:** không có script nội tuyến, `eval` hay `new Function`.
- **Style:** Lit áp dụng style của phần tử dưới dạng constructable stylesheet (`adoptedStyleSheets`), loại mà CSP không hạn chế, nên `style-src` không cần `'unsafe-inline'`. Các phần tử không dùng attribute `style`; vùng vẽ luồng đặt vị trí nút bằng attribute SVG. Trên trình duyệt không hỗ trợ constructable stylesheet, Lit quay về dùng thẻ `<style>`: khi đó hãy cho phép chúng bằng một nonce, bằng cách gán `window.litNonce` trước khi nạp các phần tử và thêm nonce đó vào `style-src`.
- **Nội dung:** văn bản của người dùng được Lit hiển thị dưới dạng văn bản, không bao giờ qua `innerHTML`.
- **Mạng:** không có. Xuất một luồng tạo ra một liên kết tải về dạng `blob:`.
- **Hash:** bộ máy luật tính phiên bản luật và hash của đầu vào bằng WebCrypto (`crypto.subtle`), mà trình duyệt chỉ cung cấp trên trang an toàn (HTTPS, hoặc `localhost` khi phát triển).

Bản demo của trình soạn thảo (`sdk/packages/editor/demo/`) chạy dưới chính sách này và báo mọi vi phạm.

## Kích thước gói

Kích thước sau khi rút gọn (minify), và sau khi rút gọn rồi nén gzip, bằng esbuild (`npm run size` trong mỗi gói). Đã gồm cả chuỗi tiếng Anh và tiếng Việt.

| Điểm vào | Gồm Lit và bộ máy luật | Chỉ mã của trình soạn thảo |
|---|---|---|
| `@rulestone/editor` (mọi phần tử) | 283.3 KiB, 83.3 KiB khi nén gzip | 193.7 KiB, 54.0 KiB khi nén gzip |
| `./table-editor` | 235.5 KiB, 69.9 KiB khi nén gzip | 145.9 KiB, 40.6 KiB khi nén gzip |
| `./rules-grid` | 154.5 KiB, 47.7 KiB khi nén gzip | 98.8 KiB, 28.3 KiB khi nén gzip |
| `./condition-builder` | 134.9 KiB, 42.6 KiB khi nén gzip | 78.9 KiB, 23.2 KiB khi nén gzip |
| `./schema-designer` | 111.7 KiB, 36.8 KiB khi nén gzip | 55.7 KiB, 17.5 KiB khi nén gzip |
| `./flow-canvas` | 97.7 KiB, 30.1 KiB khi nén gzip | 82.7 KiB, 24.5 KiB khi nén gzip |
| `./try-it` | 142.5 KiB, 45.8 KiB khi nén gzip | 53.3 KiB, 16.3 KiB khi nén gzip |
| `./cel-input` | 103.2 KiB, 34.3 KiB khi nén gzip | 47.7 KiB, 15.0 KiB khi nén gzip |

Riêng `@rulestone/engine` là 86.2 KiB, 28.1 KiB khi nén gzip. Vùng vẽ luồng không nạp bộ máy luật. Lit được dùng chung khi có nhiều phần tử trên một trang, và bundler tách ra những gì mỗi trang cần nạp.

## Bộ máy luật trên trình duyệt và bộ máy luật Java

Bộ máy luật Java vẫn là bộ máy luật chính thức (engine of record): hãy dùng nó để đánh giá các quyết định trong môi trường production. Bộ máy luật trên trình duyệt dùng để phản hồi tức thì trong lúc soạn và chạy thử luật, và được xây dựng để cho đúng cùng câu trả lời.

Một bộ kiểm thử tương thích trong `sdk/packages/engine/test/` so sánh hai bộ máy. Dữ liệu chuẩn (fixture) của nó do bộ máy luật Java ghi ra (`SdkFixturesTest` trong `rulestone-test`), và bản build Maven sẽ thất bại nếu chúng đã cũ:

| Fixture | Nội dung được kiểm tra |
|---|---|
| `java-hashes.json` | Dạng chuẩn tắc và phiên bản luật của mọi bảng trong `examples/` và trong các bảng kiểm thử. |
| `java-records.json` | Bản ghi quyết định và vết thực thi của mọi ca trong mọi tệp `examples/*.stone-test.json`. |
| `java-probes.json` | Kiểu và giá trị kết quả, hoặc chính xác lỗi, của mọi biểu thức trong `probes.json`: toán tử, macro, giới hạn số lần lặp, vị trí lỗi, số học thập phân, tiền, ngày và so khớp gần đúng. |
| `java-invalid-tables.json` | Các lỗi được báo cho mọi bảng không hợp lệ trong `invalid-tables.json`. |

Bộ kiểm thử còn kiểm tra mọi ví dụ `biểu thức` → kết quả trong [Ngôn ngữ biểu thức](expressions.md) và các ví dụ minh hoạ trong [So khớp gần đúng](fuzzy-matching.md). Số thập phân là chính xác: `Decimal` lưu một `BigInt` và một scale, và tuân theo các quy tắc của `BigDecimal` về scale, làm tròn và phép chia.

Các khác biệt đã biết:

- **Không hỗ trợ:** literal dạng map (`{'a': 1}`), `bytes`, `type()`, `timestamp()`, `duration()` và `string(double)`. Luật RuleStone không dùng chúng. Bộ máy luật trên trình duyệt từ chối chúng lúc biên dịch với thông báo nói rõ điều đó; Java thì chấp nhận.
- **Lỗi cú pháp** được báo ở cùng vị trí, nhưng bằng lời khác với ANTLR.
- **Biểu thức chính quy:** `matches()` chuyển cú pháp RE2 sang biểu thức chính quy của JavaScript. Cú pháp thông dụng cho kết quả như nhau; các cấu trúc RE2 hiếm gặp có thể khác.
- **Unicode:** `normalizeName` và các hàm so khớp gần đúng dùng bảng Unicode của trình duyệt, còn Java dùng bảng của JDK. Chúng chỉ khác nhau ở những ký tự mới được thêm trong các phiên bản Unicode gần đây.
- **Bản ghi quyết định** ghi tên bộ máy là `rulestone-js 0.1.0`, có độ chính xác tới mili giây ở `evaluatedAt`, và tính hash của đầu vào bằng SHA-256 thuần (không có tuỳ chọn HMAC như ở Java).
- **Hôm nay:** khi không có `asOf`, "hôm nay" là ngày theo múi giờ của trình duyệt. Hãy đặt `asOf` để có kết quả tái lập được.

[README của bộ máy luật](../../sdk/packages/engine/README.md) (English) liệt kê API và mọi khác biệt một cách chi tiết.

## Hạn chế

- Các gói chưa có trên npm, và tên gói có thể thay đổi khi được phát hành.
- Bộ máy luật trên trình duyệt chưa chạy luồng; chỉ bộ máy luật Java chạy được (xem [Luồng quyết định](flows.md)). Vùng vẽ chỉ kiểm tra những gì nó kiểm tra được khi không có bộ máy luật: không có kiểm tra tên hay kiểu xuyên qua các nút.
- Vùng vẽ đọc các giá trị của nút `derive` dưới dạng `values` (tên ánh xạ tới biểu thức), còn định dạng của bộ máy luật Java khai báo chúng cùng kiểu trong `outputs` và đặt chúng bằng `set`. Các trường của nút `flow` (`flow`, `version`) theo đề xuất; bộ máy luật Java chưa hỗ trợ nút `flow`.
- Lưới luật không ảo hoá (virtualize) các dòng, nên bảng có hàng nghìn luật hiển thị chậm.
