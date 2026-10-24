// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

/**
 * The editor's model, without any element: table, schema, condition and flow operations, and
 * validation. Use it to build your own user interface or to test what the elements do.
 *
 * @packageDocumentation
 */

export * from './table.js'
export * from './schema.js'
export * from './validate.js'
export * from './facts.js'
export * from './cel-text.js'
export {
  parseCondition, printCondition, printValue, celString, precedence, nodeId, findNode, findParent, pathTo, updateNode,
  insertNode, removeNode as removeConditionNode, moveNode, moveBy, indent, outdent, wrapInGroup, newGroup, newLeaf,
  newQuantifier, newRaw, fieldSpec, operatorsFor, takesValue, defaultValue, numberValue, COMPARISONS,
} from './condition.js'
export type { CondNode, Group, Leaf, Quantifier, Raw, RawReason, ValueNode, Operator, PrintOptions } from './condition.js'
export {
  NODE_TYPES, NODE_WIDTH, NODE_HEIGHT, emptyFlow, edgesOf, allEdges, newNodeId, newNode, addNode, removeNode as removeFlowNode,
  renameNode, updateNode as updateFlowNode, connect, disconnect, checkFlow, autoLayout, layoutFor, layoutBounds, fitZoom,
  LAYOUT_SPACING, FIT_ZOOM, flowText, parseFlow,
} from './flow.js'
export type { NodeType, Branch, FlowNodeJson, FlowJson, FlowLayout, Edge, EdgeKind, FlowProblem, FlowProblemCode, CheckOptions, LayoutOptions } from './flow.js'
