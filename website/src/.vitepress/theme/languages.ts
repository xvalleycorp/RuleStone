// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Flag and short code shown for each site language in the language switcher.

import { computed } from 'vue';
import { useData, withBase } from 'vitepress';
import { useLangs } from 'vitepress/dist/client/theme-default/composables/langs';

export interface Language {
  label: string;
  lang: string;
  code: string;
  flag: string;
}

const FLAGS: Record<string, { code: string; flag: string }> = {
  'en-US': { code: 'EN', flag: '/flags/gb.svg' },
  'vi-VN': { code: 'VI', flag: '/flags/vn.svg' },
};

/** The current language and links to the same page in the other languages, each with its flag. */
export function useLanguages() {
  const { site } = useData();
  const { localeLinks, currentLang } = useLangs({ correspondingLink: true });

  const byLabel = (label: string): Language => {
    const lang = Object.values(site.value.locales).find((l) => l.label === label)?.lang ?? '';
    const known = FLAGS[lang] ?? { code: lang.slice(0, 2).toUpperCase(), flag: '' };
    return { label, lang, code: known.code, flag: known.flag && withBase(known.flag) };
  };

  const current = computed(() => byLabel(currentLang.value.label ?? ''));
  const others = computed(() => localeLinks.value.map((l) => ({ ...byLabel(l.text), link: l.link })));
  const enabled = computed(() => others.value.length > 0 && !!currentLang.value.label);

  return { current, others, enabled };
}
