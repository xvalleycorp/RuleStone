// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { analyzeExpression, type Expr } from '@rulestone/engine'
import type { TableJson } from '../src/model/table.js'

/** The repository root; tests run in the package directory. */
export const REPO = resolve(process.cwd(), '../../..')
export const EXAMPLES = join(REPO, 'examples')

export function exampleFiles(): string[] {
  return readdirSync(EXAMPLES).filter((f) => f.endsWith('.stone.json')).sort()
}

export function exampleTable(file: string): TableJson {
  return JSON.parse(readFileSync(join(EXAMPLES, file), 'utf8')) as TableJson
}

export function exampleTables(): [string, TableJson][] {
  return exampleFiles().map((f) => [f, exampleTable(f)])
}

/** The syntax tree of an expression without positions and types, for structural comparison. */
export function shape(source: string): unknown {
  const { ast } = analyzeExpression(source, { variables: {} })
  if (!ast) return { syntaxError: source }
  return strip(ast)
}

function strip(node: Expr): unknown {
  return JSON.parse(
    JSON.stringify(node, (key, value: unknown) =>
      ['pos', 'start', 'end', 'fieldPos', 'variablePos', 'type', 'overloads'].includes(key)
        ? undefined
        : typeof value === 'bigint'
          ? `${value}n`
          : value,
    ),
  )
}

/** Waits for Lit to finish rendering an element and its descendants. */
export async function settle(element: Element): Promise<void> {
  for (let i = 0; i < 5; i++) {
    const pending = [element, ...deep(element)].map((e) => (e as { updateComplete?: Promise<unknown> }).updateComplete).filter(Boolean)
    await Promise.all(pending)
    await new Promise((r) => setTimeout(r, 0))
  }
}

function deep(root: Element): Element[] {
  const out: Element[] = []
  const visit = (node: Element | ShadowRoot) => {
    for (const child of Array.from(node.children)) {
      out.push(child)
      if (child.shadowRoot) visit(child.shadowRoot)
      visit(child)
    }
  }
  if (root.shadowRoot) visit(root.shadowRoot)
  visit(root)
  return out
}

/** querySelector that looks through shadow roots. */
export function $(root: Element | ShadowRoot | Document, selector: string): HTMLElement | null {
  const direct = root.querySelector(selector)
  if (direct) return direct as HTMLElement
  for (const el of Array.from(root.querySelectorAll('*'))) {
    if (el.shadowRoot) {
      const found = $(el.shadowRoot, selector)
      if (found) return found
    }
  }
  return null
}

export function $$(root: Element | ShadowRoot | Document, selector: string): HTMLElement[] {
  const out = Array.from(root.querySelectorAll(selector)) as HTMLElement[]
  for (const el of Array.from(root.querySelectorAll('*'))) if (el.shadowRoot) out.push(...$$(el.shadowRoot, selector))
  return out
}
