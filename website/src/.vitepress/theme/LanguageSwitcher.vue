<!--
  Copyright 2026 The RuleStone Authors
  SPDX-License-Identifier: Apache-2.0

  Replaces VitePress's VPNavBarTranslations (see `vite.resolve.alias` in config.mts): the
  button shows the flag and code of the current language instead of a generic icon.
-->
<script setup lang="ts">
import { computed } from 'vue';
import { useData } from 'vitepress';
import VPFlyout from 'vitepress/dist/client/theme-default/components/VPFlyout.vue';
import { useLanguages } from './languages';

const { theme } = useData();
const { current, others, enabled } = useLanguages();

// VPFlyout renders `button` as HTML; every value here comes from the site's own config.
const button = computed(
  () =>
    `<span class="rs-lang-button"><img class="rs-flag" src="${current.value.flag}" alt="" width="21" height="14">` +
    `<span class="rs-lang-code">${current.value.code}</span></span>`,
);
const label = computed(() => `${theme.value.langMenuLabel || 'Change language'} (${current.value.label})`);
</script>

<template>
  <VPFlyout v-if="enabled" class="VPNavBarTranslations rs-lang" :button="button" :label="label">
    <div class="items">
      <p class="title" :lang="current.lang">
        <img class="rs-flag" :src="current.flag" alt="" width="21" height="14" />
        <span>{{ current.label }}</span>
        <svg class="rs-check" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
          <path d="M5 12.5l4.5 4.5L19 7.5" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </p>
      <a
        v-for="language in others"
        :key="language.link"
        class="rs-lang-link"
        :href="language.link"
        :lang="language.lang"
        :hreflang="language.lang"
      >
        <img class="rs-flag" :src="language.flag" alt="" width="21" height="14" />
        <span>{{ language.label }}</span>
      </a>
    </div>
  </VPFlyout>
</template>

<style scoped>
.VPNavBarTranslations {
  display: none;
}

@media (min-width: 768px) {
  .VPNavBarTranslations {
    display: flex;
    align-items: center;
  }
}

.title,
.rs-lang-link {
  display: flex;
  align-items: center;
  gap: 10px;
  border-radius: 6px;
  padding: 0 12px;
  line-height: 32px;
  font-size: 14px;
  white-space: nowrap;
  color: var(--vp-c-text-1);
}

.title {
  font-weight: 700;
}

.rs-check {
  flex: none;
  margin-left: auto;
  color: var(--vp-c-brand-1);
}

.rs-lang-link {
  font-weight: 500;
  transition: background-color 0.25s, color 0.25s;
}

.rs-lang-link:hover {
  background-color: var(--vp-c-default-soft);
  color: var(--vp-c-brand-1);
}
</style>
