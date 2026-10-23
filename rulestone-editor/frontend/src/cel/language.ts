// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// CodeMirror support for CEL: highlighting and autocomplete of input paths and functions.

import { type Completion, type CompletionContext, type CompletionResult, snippetCompletion } from '@codemirror/autocomplete'
import { HighlightStyle, StreamLanguage, type StringStream } from '@codemirror/language'
import { tags } from '@lezer/highlight'
import { fieldsAt } from '../model/schema'
import type { FunctionInfo, TypeSpec } from '../types'

interface State {
  inString: string | null
}

export const celLanguage = StreamLanguage.define<State>({
  name: 'cel',
  startState: () => ({ inString: null }),
  token(stream: StringStream, state: State) {
    if (state.inString) {
      while (!stream.eol()) {
        const ch = stream.next()
        if (ch === '\\') stream.next()
        else if (ch === state.inString) {
          state.inString = null
          break
        }
      }
      return 'string'
    }
    if (stream.eatSpace()) return null
    if (stream.match('//')) {
      stream.skipToEnd()
      return 'comment'
    }
    const quote = stream.match(/^[rRbB]{0,2}(['"])/) as RegExpMatchArray | null
    if (quote) {
      state.inString = quote[1]
      while (!stream.eol()) {
        const ch = stream.next()
        if (ch === '\\') stream.next()
        else if (ch === state.inString) {
          state.inString = null
          break
        }
      }
      return 'string'
    }
    if (stream.match(/^(0[xX][0-9a-fA-F]+|[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?)[uU]?/)) return 'number'
    if (stream.match(/^(true|false|null)\b/)) return 'bool'
    if (stream.match(/^in\b/)) return 'keyword'
    if (stream.match(/^[A-Za-z_][A-Za-z0-9_]*/)) {
      return stream.peek() === '(' ? 'function' : stream.string.charAt(stream.start - 1) === '.' ? 'propertyName' : 'variableName'
    }
    if (stream.match(/^(==|!=|<=|>=|&&|\|\||[<>!+\-*/%?:])/)) return 'operator'
    stream.next()
    return 'punctuation'
  },
  languageData: { commentTokens: { line: '//' } },
})

export const celHighlight = HighlightStyle.define([
  { tag: tags.string, color: 'var(--syntax-string)' },
  { tag: tags.number, color: 'var(--syntax-number)' },
  { tag: tags.bool, color: 'var(--syntax-number)', fontWeight: '600' },
  { tag: tags.keyword, color: 'var(--syntax-keyword)', fontWeight: '600' },
  { tag: tags.operator, color: 'var(--syntax-operator)' },
  { tag: [tags.function(tags.variableName), tags.function(tags.propertyName)], color: 'var(--syntax-function)' },
  { tag: tags.propertyName, color: 'var(--syntax-property)' },
  { tag: tags.variableName, color: 'var(--syntax-variable)' },
  { tag: tags.comment, color: 'var(--text-muted)', fontStyle: 'italic' },
])

export interface CompletionData {
  inputs: Record<string, TypeSpec>
  implicitInputs: { name: string; type: string }[]
  functions: FunctionInfo[]
}

function typeLabel(spec: TypeSpec): string {
  if (typeof spec === 'string') return spec
  if (Array.isArray(spec)) return `list<${typeLabel(spec[0])}>`
  return 'struct'
}

function functionCompletion(f: FunctionInfo, boost: number): Completion {
  return snippetCompletion(f.insert.replace('${0}', '${}'), {
    label: f.name,
    detail: f.signature,
    info: f.description,
    type: f.kind === 'method' ? 'method' : 'function',
    boost,
  })
}

/** Autocomplete: after "a.b." the fields of a.b and the methods; otherwise inputs, functions and keywords. */
export function celCompletions(data: () => CompletionData) {
  return (context: CompletionContext): CompletionResult | null => {
    const word = context.matchBefore(/[A-Za-z_][\w.]*\.?[A-Za-z_]*$|\.[A-Za-z_]*$/)
    if (!word && !context.explicit) return null
    const text = word?.text ?? ''
    const { inputs, implicitInputs, functions } = data()
    const dot = text.lastIndexOf('.')
    if (dot >= 0) {
      const base = text.slice(0, dot).split('.').filter(Boolean)
      const fields = fieldsAt(inputs, base).map(([name, type]): Completion => ({
        label: name, detail: typeLabel(type), type: 'property', boost: 2,
      }))
      const methods = functions.filter((f) => f.kind === 'method' || (f.kind === 'macro' && f.name !== 'has'))
        .map((f) => functionCompletion(f, 0))
      return { from: (word?.from ?? context.pos) + dot + 1, options: [...fields, ...methods], validFor: /^[A-Za-z_]*$/ }
    }
    const options: Completion[] = [
      ...Object.entries(inputs).map(([name, type]) => ({ label: name, detail: typeLabel(type), type: 'variable', boost: 3 })),
      ...implicitInputs.map((i) => ({ label: i.name, detail: i.type, type: 'variable', boost: 2 })),
      ...functions.filter((f) => f.kind === 'function' || f.name === 'has').map((f) => functionCompletion(f, 1)),
      ...['true', 'false', 'in'].map((k) => ({ label: k, type: 'keyword' })),
    ]
    return { from: word?.from ?? context.pos, options, validFor: /^[A-Za-z_]*$/ }
  }
}
