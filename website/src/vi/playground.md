---
layout: page
title: Sân thử
description: Sửa và chạy bảng quyết định RuleStone ngay trong trình duyệt bằng các thành phần soạn thảo nhúng được, dựng điều kiện và thiết kế luồng quyết định.
source: playground.md
sourceHash: pending
---

<script setup>
import Playground from '../.vitepress/playground/Playground.vue'
</script>

<div class="rs-playground-page">
<div class="vp-doc">

# Sân thử

Sửa các bảng quyết định mẫu, chạy thử với dữ liệu mẫu, dựng điều kiện bằng các khối và thiết kế một luồng quyết định. Đây là các thành phần của [SDK trình soạn thảo](/vi/docs/editor-sdk), mà ngân hàng nhúng vào ứng dụng web của chính mình.

::: info Mọi thứ chạy trong trình duyệt của bạn
- Không có gì bạn làm ở đây được gửi tới máy chủ. Bảng, dữ liệu đầu vào và kết quả ở lại trong trình duyệt này. Liên kết chia sẻ chứa bảng ở phần địa chỉ sau dấu `#`, phần mà trình duyệt không gửi tới máy chủ.
- **Chạy thử** đánh giá các luật bằng bộ máy luật trên trình duyệt, cho cùng kết quả với bộ máy luật Java trên mọi trường hợp mà bộ kiểm thử tương thích bao phủ. Bộ máy luật Java vẫn là bộ máy luật chính thức (engine of record): hãy dùng nó để đánh giá các quyết định trong môi trường production.
- **Luồng quyết định hiện chỉ để thiết kế.** Vùng vẽ kiểm tra và xuất được luồng, nhưng chưa có bộ máy luật nào chạy luồng.
:::

</div>

<Playground />

<div class="vp-doc">

## Nhúng vào ứng dụng

Các thành phần này dùng được trong mọi ứng dụng web, với bất kỳ framework nào hoặc không cần framework. Các gói chưa có trên npm: hãy [build từ repository](/vi/docs/editor-sdk#install-and-build) cho tới khi chúng được phát hành. Mỗi đoạn mã dưới đây nạp trình soạn thảo bảng, đưa cho nó một bảng và lưu mọi thay đổi.

::: code-group

```html [HTML]
<!-- With a bundler such as Vite, which resolves the package import. -->
<rulestone-table-editor locale="vi"></rulestone-table-editor>
<script type="module">
  import '@rulestone/editor/table-editor'

  const editor = document.querySelector('rulestone-table-editor')
  editor.table = await (await fetch('/rules/loan_eligibility.stone.json')).json()
  editor.addEventListener('rulestone-change', (e) => save(e.detail.table))
</script>
```

```tsx [React]
// React 19 sets properties and listens to events on custom elements.
import '@rulestone/editor/table-editor'

export function RuleEditor({ table, onSave }) {
  return (
    <rulestone-table-editor
      table={table}
      locale="vi"
      onrulestone-change={(e) => onSave(e.detail.table)}
    />
  )
}
```

```ts [Angular]
import { CUSTOM_ELEMENTS_SCHEMA, Component, input } from '@angular/core'
import '@rulestone/editor/table-editor'

@Component({
  selector: 'app-rule-editor',
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `<rulestone-table-editor [table]="table()" locale="vi"
    (rulestone-change)="save($event.detail.table)"></rulestone-table-editor>`,
})
export class RuleEditorComponent {
  table = input.required<object>()
  save(table: object) { /* the host stores it */ }
}
```

```vue [Vue]
<script setup lang="ts">
import { onMounted } from 'vue'
const props = defineProps<{ table: object }>()
const emit = defineEmits<{ save: [table: object] }>()
// Client only: load the element after mounting (also under SSR, e.g. VitePress).
onMounted(() => import('@rulestone/editor/table-editor'))
</script>

<template>
  <rulestone-table-editor :table.prop="props.table" locale="vi"
    @rulestone-change="(e) => emit('save', e.detail.table)" />
</template>
```

:::

[Hướng dẫn SDK trình soạn thảo](/vi/docs/editor-sdk) mô tả từng phần tử cùng các thuộc tính, sự kiện và phương thức, cách kiểm tra phía máy chủ, giao diện (theme), cách dùng bàn phím, Content Security Policy cần có và kích thước gói.

</div>
</div>
