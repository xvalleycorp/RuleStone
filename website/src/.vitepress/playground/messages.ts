// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Strings of the playground's own controls. The components bring their own strings
// (@rulestone/editor, English and Vietnamese). Vietnamese terms follow docs/vi/GLOSSARY.md.

const en = {
  tabs: 'Playground views',
  tabTable: 'Decision table',
  tabBuilder: 'Condition builder',
  tabFlow: 'Decision flow',
  loading: 'Loading the editor…',
  loadFailed: 'The editor could not be loaded: {message}',
  example: 'Example',
  featured: 'Featured examples',
  others: 'More examples',
  shared: 'Table from a shared link',
  imported: 'Imported file',
  sample: 'Sample facts come from the test case “{case}” in {file}.',
  noSample: 'This example has no sample facts; fill in the Try it form.',
  reset: 'Reset example',
  import: 'Import .stone.json',
  download: 'Download .stone.json',
  copy: 'Copy JSON',
  share: 'Copy share link',
  copied: 'The table’s JSON is on the clipboard.',
  copyFailed: 'The browser did not allow copying. Use Download, or select the text in the JSON tab.',
  downloaded: 'Downloaded {file}.',
  shareCopied: 'Link copied. It holds the whole table, compressed, in the part of the address after #, which browsers do not send to any server.',
  shareReady: 'Share link (the browser did not allow copying it):',
  shareTooLarge: 'This table is too large for a link ({size} characters compressed; the limit is {limit}). Download the file and share that instead.',
  shareUnsupported: 'This browser cannot compress the table for a link (CompressionStream). Download the file instead.',
  shareNotReadable: 'The table has errors the strict reader rejects, so it cannot be shared as a link yet: {message}',
  sharedOpened: 'Opened a table from a shared link. It was read with the engine’s strict reader; nothing in it runs until you press Run.',
  sharedFailed: 'This shared link could not be opened: {message}. The example is shown instead.',
  sharedProblem: {
    unsupported: 'this browser cannot decompress it (DecompressionStream)',
    tooLarge: 'it is larger than the playground accepts',
    malformed: 'it is damaged or incomplete',
  },
  importedFile: 'Imported {file}. It was read with the engine’s strict reader.',
  importFailed: '{file} is not a decision table the engine can read: {message}',
  fileTooLarge: '{file} is larger than {limit} KB.',
  restored: 'Restored your last edit of this example, saved in this browser.',
  builderInputs: 'Fields from',
  builderRule: 'Start from rule',
  builderCel: 'The condition as CEL',
  builderHelp: 'Build the condition with blocks, or switch to CEL text. The CEL below is what the rule’s "when" field would contain.',
  flowTables: 'The tables this flow refers to are part of the design proposal; they are not in examples/.',
  resetFlow: 'Reset flow',
};

export type PlaygroundMessages = typeof en;

const vi: PlaygroundMessages = {
  tabs: 'Các chế độ của sân thử',
  tabTable: 'Bảng quyết định',
  tabBuilder: 'Trình tạo điều kiện',
  tabFlow: 'Luồng quyết định',
  loading: 'Đang tải trình soạn thảo…',
  loadFailed: 'Không tải được trình soạn thảo: {message}',
  example: 'Ví dụ',
  featured: 'Ví dụ tiêu biểu',
  others: 'Các ví dụ khác',
  shared: 'Bảng từ liên kết chia sẻ',
  imported: 'Tệp đã nhập',
  sample: 'Dữ liệu mẫu lấy từ ca kiểm thử “{case}” trong {file}.',
  noSample: 'Ví dụ này không có dữ liệu mẫu; hãy điền biểu mẫu Chạy thử.',
  reset: 'Khôi phục ví dụ',
  import: 'Nhập .stone.json',
  download: 'Tải về .stone.json',
  copy: 'Sao chép JSON',
  share: 'Sao chép liên kết chia sẻ',
  copied: 'Đã chép JSON của bảng vào bộ nhớ tạm.',
  copyFailed: 'Trình duyệt không cho sao chép. Hãy dùng nút Tải về, hoặc chọn văn bản trong thẻ JSON.',
  downloaded: 'Đã tải về {file}.',
  shareCopied: 'Đã chép liên kết. Liên kết chứa toàn bộ bảng, đã nén, ở phần địa chỉ sau dấu #, phần mà trình duyệt không gửi tới máy chủ nào.',
  shareReady: 'Liên kết chia sẻ (trình duyệt không cho tự sao chép):',
  shareTooLarge: 'Bảng quá lớn để đưa vào liên kết ({size} ký tự sau khi nén; giới hạn là {limit}). Hãy tải tệp về và chia sẻ tệp.',
  shareUnsupported: 'Trình duyệt này không nén được bảng để tạo liên kết (CompressionStream). Hãy tải tệp về.',
  shareNotReadable: 'Bảng còn lỗi mà trình đọc chặt chẽ không chấp nhận, nên chưa chia sẻ bằng liên kết được: {message}',
  sharedOpened: 'Đã mở một bảng từ liên kết chia sẻ. Bảng được đọc bằng trình đọc chặt chẽ của bộ máy luật; không có gì trong bảng được chạy cho tới khi bạn nhấn Chạy.',
  sharedFailed: 'Không mở được liên kết chia sẻ này: {message}. Sân thử hiện ví dụ thay thế.',
  sharedProblem: {
    unsupported: 'trình duyệt này không giải nén được (DecompressionStream)',
    tooLarge: 'liên kết lớn hơn mức sân thử chấp nhận',
    malformed: 'liên kết bị hỏng hoặc không đầy đủ',
  },
  importedFile: 'Đã nhập {file}. Tệp được đọc bằng trình đọc chặt chẽ của bộ máy luật.',
  importFailed: '{file} không phải bảng quyết định mà bộ máy luật đọc được: {message}',
  fileTooLarge: '{file} lớn hơn {limit} KB.',
  restored: 'Đã khôi phục lần sửa gần nhất của bạn với ví dụ này, được lưu trong trình duyệt này.',
  builderInputs: 'Trường lấy từ',
  builderRule: 'Bắt đầu từ luật',
  builderCel: 'Điều kiện dưới dạng CEL',
  builderHelp: 'Dựng điều kiện bằng các khối, hoặc chuyển sang viết CEL. Đoạn CEL bên dưới là nội dung trường "when" của luật.',
  flowTables: 'Các bảng mà luồng này tham chiếu thuộc đề xuất thiết kế; chúng không có trong examples/.',
  resetFlow: 'Khôi phục luồng',
};

export const MESSAGES: Record<'en' | 'vi', PlaygroundMessages> = { en, vi };

/** Fills {placeholders}. */
export const fill = (text: string, params: Record<string, string | number> = {}): string =>
  text.replace(/\{(\w+)\}/g, (_, key: string) => String(params[key] ?? `{${key}}`));
