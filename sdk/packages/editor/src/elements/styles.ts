// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Design tokens and shared styles. Every token is a CSS custom property the host page can set
// (documented in the README); the defaults below give a light and a dark theme that meet
// WCAG 2.1 AA contrast. Styles are adopted as constructable stylesheets, never inline.

import { css, unsafeCSS } from 'lit'

/** Token name (without the --rs- prefix) → [light default, dark default]. */
export const TOKENS: Readonly<Record<string, readonly [string, string]>> = {
  'color-bg': ['#ffffff', '#0f1217'],
  'color-surface': ['#f6f8fa', '#161b22'],
  'color-surface-raised': ['#ffffff', '#1c2129'],
  'color-text': ['#1f2328', '#e6edf3'],
  'color-text-muted': ['#59636e', '#9da7b3'],
  'color-border': ['#d1d9e0', '#3d444d'],
  'color-border-strong': ['#818b98', '#656c76'],
  'color-accent': ['#0b5cad', '#4493f8'],
  'color-on-accent': ['#ffffff', '#0f1217'],
  'color-accent-subtle': ['#ddf4ff', '#12263f'],
  'color-focus': ['#0969da', '#58a6ff'],
  'color-error': ['#b42318', '#ff7b72'],
  'color-error-subtle': ['#ffebe9', '#3a1517'],
  'color-warning': ['#7d4e00', '#d29922'],
  'color-warning-subtle': ['#fff8c5', '#33270b'],
  'color-success': ['#116329', '#3fb950'],
  'color-match': ['#dafbe1', '#12361f'],
  'color-nomatch': ['#f6f8fa', '#161b22'],
  'syntax-string': ['#0a3069', '#a5d6ff'],
  'syntax-number': ['#0550ae', '#79c0ff'],
  'syntax-keyword': ['#8250df', '#d2a8ff'],
  'syntax-function': ['#6639ba', '#d2a8ff'],
  'syntax-field': ['#953800', '#ffa657'],
  'syntax-variable': ['#1f2328', '#e6edf3'],
  'syntax-operator': ['#cf222e', '#ff7b72'],
  'syntax-comment': ['#59636e', '#9da7b3'],
}

/** Non-colour tokens: one value for both themes. */
export const LAYOUT_TOKENS: Readonly<Record<string, string>> = {
  'font-family': "system-ui, -apple-system, 'Segoe UI', Roboto, 'Noto Sans', sans-serif",
  'font-family-mono': "ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace",
  'font-size': '14px',
  radius: '6px',
  space: '8px',
  'focus-width': '2px',
}

function block(index: 0 | 1): string {
  return Object.entries(TOKENS)
    .map(([name, values]) => `--_${name}: var(--rs-${name}, ${values[index]});`)
    .join('\n')
}

const layout = Object.entries(LAYOUT_TOKENS).map(([name, value]) => `--_${name}: var(--rs-${name}, ${value});`).join('\n')

/** The tokens, light by default, dark with prefers-color-scheme or theme="dark". */
export const tokens = css`
  :host {
    ${unsafeCSS(layout)}
    ${unsafeCSS(block(0))}
    color-scheme: light;
  }
  @media (prefers-color-scheme: dark) {
    :host(:not([theme='light'])) {
      ${unsafeCSS(block(1))}
      color-scheme: dark;
    }
  }
  :host([theme='dark']) {
    ${unsafeCSS(block(1))}
    color-scheme: dark;
  }
`

/** Shared base styles: text, form controls, buttons, focus and screen-reader-only text. */
export const base = css`
  :host {
    display: block;
    box-sizing: border-box;
    font-family: var(--_font-family);
    font-size: var(--_font-size);
    line-height: 1.45;
    color: var(--_color-text);
    background: var(--_color-bg);
  }
  :host([hidden]) {
    display: none;
  }
  *,
  *::before,
  *::after {
    box-sizing: inherit;
  }
  :focus-visible {
    outline: var(--_focus-width) solid var(--_color-focus);
    outline-offset: 2px;
  }
  button,
  input,
  select,
  textarea {
    font: inherit;
    color: inherit;
  }
  input,
  select,
  textarea {
    background: var(--_color-surface-raised);
    border: 1px solid var(--_color-border-strong);
    border-radius: var(--_radius);
    padding: 4px 6px;
    min-height: 30px;
    max-width: 100%;
  }
  input[aria-invalid='true'],
  select[aria-invalid='true'],
  textarea[aria-invalid='true'] {
    border-color: var(--_color-error);
    box-shadow: 0 0 0 1px var(--_color-error);
  }
  input:disabled,
  select:disabled,
  textarea:disabled {
    opacity: 0.7;
  }
  button {
    cursor: pointer;
    background: var(--_color-surface-raised);
    border: 1px solid var(--_color-border-strong);
    border-radius: var(--_radius);
    padding: 4px 10px;
    min-height: 30px;
    min-width: 30px;
  }
  button:hover:not(:disabled) {
    background: var(--_color-surface);
  }
  button:disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }
  button.primary {
    background: var(--_color-accent);
    border-color: var(--_color-accent);
    color: var(--_color-on-accent);
  }
  button.primary:hover:not(:disabled) {
    background: var(--_color-accent);
    filter: brightness(1.08);
  }
  button.icon {
    padding: 2px 6px;
  }
  button[aria-pressed='true'] {
    background: var(--_color-accent-subtle);
    border-color: var(--_color-accent);
  }
  label {
    display: inline-flex;
    flex-direction: column;
    gap: 2px;
    font-size: 0.92em;
    color: var(--_color-text-muted);
  }
  label > input,
  label > select,
  label > textarea {
    color: var(--_color-text);
    font-size: var(--_font-size);
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    border: 0;
  }
  .muted {
    color: var(--_color-text-muted);
  }
  .error-text {
    color: var(--_color-error);
  }
  .warning-text {
    color: var(--_color-warning);
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: var(--_space);
    align-items: center;
  }
  .mono {
    font-family: var(--_font-family-mono);
  }
  .banner {
    border: 1px solid var(--_color-warning);
    background: var(--_color-warning-subtle);
    border-radius: var(--_radius);
    padding: 6px 10px;
  }
  pre.code {
    font-family: var(--_font-family-mono);
    font-size: 0.9em;
    background: var(--_color-surface);
    border: 1px solid var(--_color-border);
    border-radius: var(--_radius);
    padding: 8px;
    overflow: auto;
    max-height: 360px;
    white-space: pre;
    margin: 0;
  }
  .tok-string {
    color: var(--_syntax-string);
  }
  .tok-number {
    color: var(--_syntax-number);
  }
  .tok-keyword {
    color: var(--_syntax-keyword);
    font-weight: 600;
  }
  .tok-function {
    color: var(--_syntax-function);
  }
  .tok-field {
    color: var(--_syntax-field);
  }
  .tok-variable {
    color: var(--_syntax-variable);
  }
  .tok-operator {
    color: var(--_syntax-operator);
  }
  .tok-comment {
    color: var(--_syntax-comment);
    font-style: italic;
  }
  .tok-error,
  .tok-err {
    text-decoration: underline wavy var(--_color-error);
    text-decoration-skip-ink: none;
    text-underline-offset: 3px;
  }
`
