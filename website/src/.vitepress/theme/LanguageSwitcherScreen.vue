<!--
  Copyright 2026 The RuleStone Authors
  SPDX-License-Identifier: Apache-2.0

  Replaces VitePress's VPNavScreenTranslations, the language switcher in the mobile menu,
  so that it also shows flags.
-->
<script setup lang="ts">
import { ref } from 'vue';
import { useLanguages } from './languages';

const { current, others, enabled } = useLanguages();
const isOpen = ref(false);
</script>

<template>
  <div v-if="enabled" class="VPNavScreenTranslations" :class="{ open: isOpen }">
    <button class="title" type="button" :aria-expanded="isOpen" @click="isOpen = !isOpen">
      <img class="rs-flag" :src="current.flag" alt="" width="21" height="14" />
      <span :lang="current.lang">{{ current.label }}</span>
      <span class="vpi-chevron-down icon chevron" />
    </button>
    <ul class="list">
      <li v-for="language in others" :key="language.link" class="item">
        <a class="link" :href="language.link" :lang="language.lang" :hreflang="language.lang">
          <img class="rs-flag" :src="language.flag" alt="" width="21" height="14" />
          <span>{{ language.label }}</span>
        </a>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.VPNavScreenTranslations {
  height: 24px;
  overflow: hidden;
}

.VPNavScreenTranslations.open {
  height: auto;
}

.title,
.link {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--vp-c-text-1);
}

.title {
  font-size: 14px;
  font-weight: 500;
}

.chevron {
  font-size: 16px;
  margin-left: -4px;
}

.list {
  padding: 4px 0 0 29px;
}

.link {
  line-height: 32px;
  font-size: 13px;
}
</style>
