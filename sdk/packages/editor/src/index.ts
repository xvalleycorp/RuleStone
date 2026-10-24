// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

/**
 * Embeddable visual editors for RuleStone decision tables and decision flows, as Web
 * Components. Importing this module defines every element; import a single element's module
 * (for example `@rulestone/editor/rules-grid`) to load only that one.
 *
 * @packageDocumentation
 */

export { RulestoneTableEditor } from './elements/table-editor.js'
export type { HostProblem, TableValidator, TableChangeDetail } from './elements/table-editor.js'
export { RulestoneRulesGrid } from './elements/rules-grid.js'
export { RulestoneConditionBuilder } from './elements/condition-builder.js'
export { RulestoneSchemaDesigner } from './elements/schema-designer.js'
export { RulestoneFlowCanvas } from './elements/flow-canvas.js'
export type { FlowChangeDetail } from './elements/flow-canvas.js'
export { RulestoneTryIt, prettyJson } from './elements/try-it.js'
export type { RunDetail } from './elements/try-it.js'
export { RsCelInput } from './elements/cel-input.js'
export { RulestoneElement } from './elements/base.js'
export { TOKENS, LAYOUT_TOKENS } from './elements/styles.js'

export { en, vi, registerMessages, resolveMessages, format } from './i18n/index.js'
export type { Locale, Messages, MessageKey, MessageOverrides } from './i18n/index.js'

export * from './model/index.js'
