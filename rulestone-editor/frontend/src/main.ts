// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { createApp } from 'vue'
import App from './App.vue'
import { locale, setLocale } from './i18n'
import './styles.css'

setLocale(locale.value)
createApp(App).mount('#app')
