// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Notice at the top of a translated page whose English source changed since the translation
// was last checked. The build sets `frontmatter.outdated` to the English page's URL (see
// transformPageData in ../config.mts).

import { defineComponent, h } from 'vue';
import { useData } from 'vitepress';

const MESSAGES: Record<string, { title: string; before: string; link: string; after: string }> = {
  'vi-VN': {
    title: 'Bản dịch có thể đã cũ',
    before: 'Bản dịch này có thể đã cũ so với bản tiếng Anh, vốn đã được cập nhật sau lần dịch gần nhất. Khi có chỗ khác nhau, hãy theo ',
    link: 'bản tiếng Anh',
    after: '.',
  },
};

export default defineComponent({
  name: 'TranslationNotice',
  props: { home: { type: Boolean, default: false } },
  setup(props) {
    const { frontmatter, lang } = useData();
    return () => {
      const english = frontmatter.value.outdated;
      const text = MESSAGES[lang.value];
      if (!english || !text) return null;
      return h('div', { class: ['custom-block', 'warning', 'translation-notice', { 'translation-notice-home': props.home }] }, [
        h('p', { class: 'custom-block-title' }, text.title),
        h('p', [text.before, h('a', { href: english, lang: 'en' }, text.link), text.after]),
      ]);
    };
  },
});
