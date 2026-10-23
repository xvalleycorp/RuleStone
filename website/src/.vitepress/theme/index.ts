// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { h } from 'vue';
import DefaultTheme from 'vitepress/theme';
import TranslationNotice from './TranslationNotice';
import './custom.css';

export default {
  extends: DefaultTheme,
  Layout: () =>
    h(DefaultTheme.Layout, null, {
      'doc-before': () => h(TranslationNotice),
      'home-hero-before': () => h(TranslationNotice, { home: true }),
    }),
};
