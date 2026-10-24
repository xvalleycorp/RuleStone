// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { en, type Messages } from './en.js'
import { vi } from './vi.js'

export { en, vi }
export type { Messages }

export type Locale = 'en' | 'vi'

/** Some of the strings of a locale, to override the built-in ones. */
export type MessageOverrides = DeepPartial<Messages>
type DeepPartial<T> = { [K in keyof T]?: T[K] extends string ? string : DeepPartial<T[K]> }

type Leaves<T, P extends string = ''> = {
  [K in keyof T & string]: T[K] extends string ? `${P}${K}` : Leaves<T[K], `${P}${K}.`>
}[keyof T & string]

/** A key of a string, e.g. 'grid.addRule'. */
export type MessageKey = Leaves<Messages>

const builtIn: Record<Locale, Messages> = { en, vi }
const registered: Partial<Record<string, MessageOverrides>> = {}

/**
 * Overrides strings of a locale for every component on the page, for example to use the
 * bank's own wording. Components' `messages` property overrides these in turn.
 */
export function registerMessages(locale: Locale, messages: MessageOverrides): void {
  registered[locale] = merge(registered[locale] ?? {}, messages)
}

function merge<T extends object>(base: T, extra: object | undefined): T {
  if (!extra) return base
  const out: Record<string, unknown> = { ...(base as Record<string, unknown>) }
  for (const [key, value] of Object.entries(extra)) {
    out[key] = value !== null && typeof value === 'object' ? merge((out[key] as object) ?? {}, value as object) : value
  }
  return out as T
}

/** The strings for a locale with the page's and the component's overrides applied. */
export function resolveMessages(locale: string | undefined, overrides?: MessageOverrides): Messages {
  const lang: Locale = locale?.toLowerCase().startsWith('vi') ? 'vi' : 'en'
  return merge(merge(builtIn[lang], registered[lang]), overrides)
}

/** Looks up a key and fills in {placeholders}. The result is plain text. */
export function format(messages: Messages, key: MessageKey, params: Readonly<Record<string, string | number>> = {}): string {
  let value: unknown = messages
  for (const part of key.split('.')) value = (value as Record<string, unknown> | undefined)?.[part]
  const text = typeof value === 'string' ? value : key
  return text.replace(/\{(\w+)\}/g, (match, name: string) => (name in params ? String(params[name]) : match))
}
