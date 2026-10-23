// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { ref } from 'vue'
import { en } from './en'
import { vi } from './vi'

export const LOCALES = { en, vi } as const
export type Locale = keyof typeof LOCALES

function initialLocale(): Locale {
  try {
    const saved = localStorage.getItem('rulestone.locale')
    if (saved === 'en' || saved === 'vi') return saved
  } catch {
    // storage unavailable
  }
  return typeof navigator !== 'undefined' && navigator.language?.toLowerCase().startsWith('vi') ? 'vi' : 'en'
}

export const locale = ref<Locale>(typeof window === 'undefined' ? 'en' : initialLocale())

export function setLocale(value: Locale): void {
  locale.value = value
  try {
    localStorage.setItem('rulestone.locale', value)
  } catch {
    // storage unavailable
  }
  if (typeof document !== 'undefined') document.documentElement.lang = value
}

function lookup(messages: unknown, key: string): string | undefined {
  let node: unknown = messages
  for (const part of key.split('.')) {
    if (node && typeof node === 'object' && part in (node as Record<string, unknown>)) {
      node = (node as Record<string, unknown>)[part]
    } else {
      // keys may contain dots themselves, e.g. cond.errors.invalid.int
      const rest = key.slice(key.indexOf(part))
      if (node && typeof node === 'object' && rest in (node as Record<string, unknown>)) {
        return (node as Record<string, string>)[rest]
      }
      return undefined
    }
  }
  return typeof node === 'string' ? node : undefined
}

/** The message for `key` in the current language, with {name} placeholders filled in. Falls back to English. */
export function t(key: string, params: Record<string, string | number> = {}): string {
  const text = lookup(LOCALES[locale.value], key) ?? lookup(en, key) ?? key
  return text.replace(/\{(\w+)\}/g, (m, name: string) => (name in params ? String(params[name]) : m))
}

/** Every key of a message tree, for checking that the translations are complete. */
export function keysOf(messages: unknown, prefix = ''): string[] {
  if (typeof messages !== 'object' || messages === null) return [prefix]
  return Object.entries(messages).flatMap(([k, v]) => keysOf(v, prefix ? `${prefix}.${k}` : k))
}
