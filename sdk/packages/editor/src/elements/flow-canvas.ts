// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { css, html, nothing, svg, type PropertyDeclarations, type PropertyValues, type SVGTemplateResult, type TemplateResult } from 'lit'
import {
  NODE_HEIGHT, NODE_TYPES, NODE_WIDTH, addNode, allEdges, autoLayout, checkFlow, connect, disconnect, edgesOf, emptyFlow,
  flowText, parseFlow, removeNode, renameNode, updateNode, type Branch, type Edge, type FlowJson, type FlowLayout,
  type FlowNodeJson, type FlowProblem, type NodeType,
} from '../model/flow.js'
import { PRIMITIVES, cloneJson, type TypeSpec } from '../model/table.js'
import { RulestoneElement, define } from './base.js'

const PALETTE_TYPE = 'application/x-rulestone-node'

interface View {
  readonly x: number
  readonly y: number
  readonly zoom: number
}

type Drag =
  | { readonly kind: 'move'; readonly id: string; readonly startX: number; readonly startY: number; readonly origX: number; readonly origY: number; moved: boolean }
  | { readonly kind: 'pan'; readonly startX: number; readonly startY: number; readonly view: View }
  | { readonly kind: 'connect'; readonly from: string; readonly failure: boolean }

/** The detail of rulestone-change. */
export interface FlowChangeDetail {
  readonly flow: FlowJson
  readonly layout: FlowLayout
  readonly problems: readonly FlowProblem[]
}

/**
 * `<rulestone-flow-canvas>`: designs a decision flow (.flow.json, the proposal in
 * docs/hybrid-ai.md section 3) on a canvas: a palette of node types, drag and drop, edges,
 * pan and zoom, a properties panel and the checks the editor can make. Everything has a
 * keyboard alternative. The engine cannot run flows yet, which the canvas says.
 *
 * Node positions are kept in `layout`, apart from the flow, whose file format has no layout.
 *
 * @fires rulestone-change - detail FlowChangeDetail, after every edit
 * @fires rulestone-export - detail `{ flow, json }`, when the user exports
 * @csspart banner - the design-only notice
 * @csspart palette - the node palette
 * @csspart canvas - the SVG canvas
 * @csspart panel - the properties panel
 * @csspart problems - the list of problems
 */
export class RulestoneFlowCanvas extends RulestoneElement {
  static override properties: PropertyDeclarations = {
    ...RulestoneElement.properties,
    flow: { attribute: false },
    layout: { attribute: false },
    tables: { attribute: false },
    _selected: { state: true },
    _view: { state: true },
    _connecting: { state: true },
    _pointer: { state: true },
    _importText: { state: true },
    _importError: { state: true },
    _size: { state: true },
    _fieldErrors: { state: true },
  }

  declare flow: FlowJson
  /** Node positions by id. Missing ones are laid out automatically. */
  declare layout: FlowLayout
  /** Decision ids that table nodes may use; when set, unknown ones are reported. */
  declare tables: readonly string[] | undefined
  declare _selected: string | undefined
  declare _view: View
  declare _connecting: { from: string; failure: boolean } | undefined
  declare _pointer: { x: number; y: number } | undefined
  declare _importText: string
  declare _importError: string | undefined
  declare _size: { w: number; h: number }
  declare _fieldErrors: ReadonlyMap<string, string>

  private drag: Drag | undefined
  private observer: ResizeObserver | undefined
  private focusNode: string | undefined

  static override styles = [
    ...RulestoneElement.styles,
    css`
      .banner {
        margin-bottom: 8px;
      }
      .toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        align-items: center;
        margin-bottom: 8px;
      }
      .toolbar .spacer {
        flex: 1;
      }
      .layout {
        display: grid;
        grid-template-columns: 150px minmax(0, 1fr) minmax(260px, 340px);
        gap: 8px;
      }
      @media (max-width: 900px) {
        .layout {
          grid-template-columns: minmax(0, 1fr);
        }
      }
      .palette,
      .panel {
        border: 1px solid var(--_color-border);
        border-radius: var(--_radius);
        background: var(--_color-surface);
        padding: 8px;
        min-width: 0;
      }
      .palette h3,
      .panel h3,
      .problems h3 {
        margin: 0 0 6px;
        font-size: 1em;
      }
      .palette ul {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      @media (max-width: 900px) {
        .palette ul {
          flex-direction: row;
          flex-wrap: wrap;
        }
      }
      .palette button {
        width: 100%;
        text-align: left;
        border-left-width: 4px;
      }
      .canvas {
        border: 1px solid var(--_color-border);
        border-radius: var(--_radius);
        background: var(--_color-bg);
        overflow: hidden;
        min-width: 0;
      }
      svg {
        display: block;
        width: 100%;
        height: 540px;
        touch-action: none;
        user-select: none;
      }
      @media (max-width: 900px) {
        svg {
          height: 420px;
        }
      }
      .grid-dot {
        fill: var(--_color-border);
      }
      .node rect.box {
        fill: var(--_color-surface-raised);
        stroke: var(--_color-border-strong);
        stroke-width: 1.2;
      }
      .node.selected rect.box {
        stroke: var(--_color-focus);
        stroke-width: 3;
      }
      .node.connect-source rect.box {
        stroke: var(--_color-accent);
        stroke-dasharray: 5 3;
        stroke-width: 2.5;
      }
      .node:focus {
        outline: none;
      }
      .node:focus-visible rect.box {
        stroke: var(--_color-focus);
        stroke-width: 3;
      }
      .node .type {
        fill: var(--_color-text-muted);
        font-size: 11px;
      }
      .node .id {
        fill: var(--_color-text);
        font-size: 14px;
        font-weight: 600;
        font-family: var(--_font-family-mono);
      }
      .node .detail {
        fill: var(--_color-text-muted);
        font-size: 11px;
        font-family: var(--_font-family-mono);
      }
      .node {
        cursor: move;
      }
      .stripe.table,
      .palette .table {
        fill: var(--_color-accent);
        border-left-color: var(--_color-accent);
      }
      .stripe.switch,
      .palette .switch {
        fill: var(--_syntax-keyword);
        border-left-color: var(--_syntax-keyword);
      }
      .stripe.derive,
      .palette .derive {
        fill: var(--_syntax-number);
        border-left-color: var(--_syntax-number);
      }
      .stripe.model,
      .palette .model {
        fill: var(--_syntax-field);
        border-left-color: var(--_syntax-field);
      }
      .stripe.split,
      .palette .split {
        fill: var(--_syntax-function);
        border-left-color: var(--_syntax-function);
      }
      .stripe.end,
      .palette .end {
        fill: var(--_color-success);
        border-left-color: var(--_color-success);
      }
      .stripe.flow,
      .palette .flow {
        fill: var(--_syntax-string);
        border-left-color: var(--_syntax-string);
      }
      .port {
        fill: var(--_color-surface-raised);
        stroke: var(--_color-accent);
        stroke-width: 2;
        cursor: crosshair;
      }
      .port.failure {
        stroke: var(--_color-error);
      }
      .badge circle {
        fill: var(--_color-error);
      }
      .badge text {
        fill: #ffffff;
        font-size: 11px;
        font-weight: 700;
      }
      .start-mark {
        fill: var(--_color-success);
        font-size: 11px;
        font-weight: 700;
      }
      .edge {
        fill: none;
        stroke: var(--_color-border-strong);
        stroke-width: 1.6;
      }
      .edge.failure {
        stroke: var(--_color-error);
        stroke-dasharray: 6 4;
      }
      .edge.temp {
        stroke: var(--_color-accent);
        stroke-dasharray: 4 3;
      }
      .edge-label {
        fill: var(--_color-text-muted);
        font-size: 11px;
        font-family: var(--_font-family-mono);
        paint-order: stroke;
        stroke: var(--_color-bg);
        stroke-width: 4px;
      }
      .arrow {
        fill: var(--_color-border-strong);
      }
      .arrow.failure {
        fill: var(--_color-error);
      }
      .panel .field {
        display: flex;
        flex-direction: column;
        gap: 2px;
        margin: 6px 0;
      }
      .panel .field > span {
        font-size: 0.9em;
        color: var(--_color-text-muted);
      }
      .panel input,
      .panel select,
      .panel textarea {
        width: 100%;
      }
      .panel textarea {
        font-family: var(--_font-family-mono);
        font-size: 0.88em;
        min-height: 70px;
      }
      .panel .entries {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      .panel .entry {
        display: grid;
        grid-template-columns: minmax(0, 2fr) minmax(0, 3fr) auto;
        gap: 4px;
        align-items: center;
      }
      .panel .entry.three {
        grid-template-columns: minmax(0, 2fr) minmax(0, 1fr) minmax(0, 2fr) auto;
      }
      .panel .entry input,
      .panel .entry select {
        min-width: 0;
      }
      .mono-input {
        font-family: var(--_font-family-mono);
        font-size: 0.92em;
      }
      .problems {
        margin-top: 8px;
        border: 1px solid var(--_color-border);
        border-radius: var(--_radius);
        padding: 8px 10px;
      }
      .problems ul {
        list-style: none;
        margin: 0;
        padding: 0;
      }
      .problems li {
        display: flex;
        gap: 8px;
        align-items: baseline;
        padding: 2px 0;
      }
      .problems .sev {
        font-weight: 700;
      }
      .problems .sev.error {
        color: var(--_color-error);
      }
      .problems .sev.warning {
        color: var(--_color-warning);
      }
      details.import {
        border: 1px solid var(--_color-border);
        border-radius: var(--_radius);
        padding: 4px 8px;
        margin-bottom: 8px;
      }
      details.import textarea {
        width: 100%;
        min-height: 120px;
        font-family: var(--_font-family-mono);
      }
      .ok {
        color: var(--_color-success);
      }
      .field-error {
        color: var(--_color-error);
        font-size: 0.88em;
      }
    `,
  ]

  constructor() {
    super()
    this.flow = emptyFlow()
    this.layout = {}
    this.tables = undefined
    this._selected = undefined
    this._view = { x: 0, y: 0, zoom: 1 }
    this._connecting = undefined
    this._pointer = undefined
    this._importText = ''
    this._importError = undefined
    this._size = { w: 900, h: 540 }
    this._fieldErrors = new Map()
  }

  override connectedCallback(): void {
    super.connectedCallback()
    if (typeof ResizeObserver !== 'undefined') {
      this.observer = new ResizeObserver((entries) => {
        const rect = entries[0]?.contentRect
        if (rect && rect.width > 0 && (Math.abs(rect.width - this._size.w) > 1 || Math.abs(rect.height - this._size.h) > 1)) {
          this._size = { w: rect.width, h: rect.height }
        }
      })
    }
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback()
    this.observer?.disconnect()
  }

  protected override firstUpdated(): void {
    const svgEl = this.renderRoot.querySelector('svg')
    if (svgEl && this.observer) this.observer.observe(svgEl)
    svgEl?.addEventListener('wheel', (e) => this.onWheel(e), { passive: false })
  }

  protected override willUpdate(changed: PropertyValues): void {
    if (changed.has('flow') || changed.has('layout')) {
      const missing = Object.keys(this.flow.nodes).some((id) => !this.layout[id])
      if (missing) this.layout = autoLayout(this.flow, this.layout)
      if (this._selected && !(this._selected in this.flow.nodes)) this._selected = undefined
    }
  }

  protected override updated(changed: PropertyValues): void {
    super.updated(changed)
    if (this.focusNode) {
      const node = this.renderRoot.querySelector<SVGGElement>(`g.node[data-id="${this.focusNode}"]`)
      this.focusNode = undefined
      node?.focus()
    }
  }

  /** The checks' result for the current flow. */
  get problems(): FlowProblem[] {
    return checkFlow(this.flow, this.tables ? { tables: this.tables } : {})
  }

  /** The flow as .flow.json text. */
  toJson(): string {
    return flowText(this.flow)
  }

  private change(flow: FlowJson, layout: FlowLayout = this.layout, announcement?: string): void {
    this.flow = flow
    this.layout = layout
    this.emit<FlowChangeDetail>('rulestone-change', { flow: cloneJson(flow), layout: { ...layout }, problems: this.problems })
    if (announcement) this.announce(announcement)
  }

  // --- Rendering -------------------------------------------------------------------------------

  protected override render() {
    const problems = this.problems
    return html`
      <p part="banner" class="banner" role="note">${this.t('flow.designOnly')}</p>
      <div class="toolbar">
        <button type="button" @click=${() => this.exportJson()}>${this.t('flow.export')}</button>
        <button type="button" @click=${() => void this.copyJson()}>${this.t('common.copy')} JSON</button>
        <span class="spacer"></span>
        <button type="button" class="icon" aria-label=${this.t('flow.zoomOut')} title=${this.t('flow.zoomOut')} @click=${() => this.zoomBy(1 / 1.2)}>−</button>
        <button type="button" class="icon" aria-label=${this.t('flow.zoomIn')} title=${this.t('flow.zoomIn')} @click=${() => this.zoomBy(1.2)}>+</button>
        <button type="button" @click=${() => this.fit()}>${this.t('flow.zoomReset')}</button>
      </div>
      ${this.readonly ? nothing : this.renderImport()}
      <div class="layout">
        ${this.renderPalette()}
        <div part="canvas" class="canvas" @dragover=${this.onPaletteDragOver} @drop=${this.onPaletteDrop}>${this.renderSvg(problems)}</div>
        ${this.renderPanel()}
      </div>
      ${this.renderProblems(problems)}
      ${this.liveRegion()}
    `
  }

  private renderImport(): TemplateResult {
    return html`<details class="import">
      <summary>${this.t('flow.import')}</summary>
      <p><label>${this.t('flow.importFile')} <input type="file" accept=".json,application/json" @change=${this.onFile} /></label></p>
      <label>${this.t('flow.importPaste')}
        <textarea spellcheck="false" .value=${this._importText} @input=${(e: Event) => (this._importText = (e.target as HTMLTextAreaElement).value)}></textarea>
      </label>
      <p><button type="button" class="primary" @click=${() => this.importText(this._importText)}>${this.t('flow.importApply')}</button></p>
      ${this._importError ? html`<p class="field-error" role="alert">${this._importError}</p>` : nothing}
    </details>`
  }

  private renderPalette(): TemplateResult {
    return html`<nav part="palette" class="palette" aria-labelledby="palette-heading">
      <h3 id="palette-heading">${this.t('flow.palette')}</h3>
      <p class="muted" id="palette-help">${this.t('flow.paletteHelp')}</p>
      <ul>
        ${NODE_TYPES.map(
          (type) => html`<li>
            <button
              type="button"
              class=${type}
              draggable=${this.readonly ? 'false' : 'true'}
              ?disabled=${this.readonly}
              title=${this.t(`flow.typeHelp.${type}`)}
              aria-describedby="palette-help"
              @dragstart=${(e: DragEvent) => {
                e.dataTransfer?.setData(PALETTE_TYPE, type)
                e.dataTransfer?.setData('text/plain', type)
                if (e.dataTransfer) e.dataTransfer.effectAllowed = 'copy'
              }}
              @click=${() => this.addAt(type)}
            >${this.t(`flow.types.${type}`)}</button>
          </li>`,
        )}
      </ul>
    </nav>`
  }

  private renderSvg(problems: readonly FlowProblem[]): TemplateResult {
    const v = this._view
    const w = this._size.w / v.zoom
    const h = this._size.h / v.zoom
    const edges = allEdges(this.flow).filter((e) => e.to in this.flow.nodes)
    const temp = this._connecting && this._pointer ? this.layout[this._connecting.from] : undefined
    return html`<svg
      viewBox="${v.x} ${v.y} ${w} ${h}"
      role="group"
      tabindex="0"
      aria-label=${this.t('flow.canvas')}
      aria-describedby="canvas-help"
      @pointerdown=${this.onBackgroundDown}
      @pointermove=${this.onPointerMove}
      @pointerup=${this.onPointerUp}
      @keydown=${this.onCanvasKey}
    >
      <desc id="canvas-help">${this.t('flow.canvasHelp')}</desc>
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
          <path class="arrow" d="M 0 0 L 10 5 L 0 10 z"></path>
        </marker>
        <marker id="arrow-failure" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
          <path class="arrow failure" d="M 0 0 L 10 5 L 0 10 z"></path>
        </marker>
      </defs>
      <g class="edges">${edges.map((e) => this.renderEdge(e))}</g>
      ${temp && this._pointer
        ? svg`<path class="edge temp" d=${curve(temp.x + NODE_WIDTH, temp.y + NODE_HEIGHT / 2, this._pointer.x, this._pointer.y)}></path>`
        : nothing}
      <g class="nodes">${Object.entries(this.flow.nodes).map(([id, node]) => this.renderNode(id, node, problems.filter((p) => p.node === id)))}</g>
    </svg>`
  }

  private renderEdge(edge: Edge): SVGTemplateResult {
    const a = this.layout[edge.from]!
    const b = this.layout[edge.to]!
    const failure = edge.kind === 'failure'
    const x1 = failure ? a.x + NODE_WIDTH / 2 : a.x + NODE_WIDTH
    const y1 = failure ? a.y + NODE_HEIGHT : a.y + NODE_HEIGHT / 2
    const x2 = b.x
    const y2 = b.y + NODE_HEIGHT / 2
    const label = edge.kind === 'next' ? '' : edge.label.length > 28 ? edge.label.slice(0, 27) + '…' : edge.label
    return svg`<g>
      <path class="edge ${failure ? 'failure' : ''}" d=${curve(x1, y1, x2, y2, failure)} marker-end=${failure ? 'url(#arrow-failure)' : 'url(#arrow)'}>
        <title>${edge.from} → ${edge.to}${edge.label ? ` (${edge.label})` : ''}</title>
      </path>
      ${label ? svg`<text class="edge-label" x=${(x1 + x2) / 2} y=${(y1 + y2) / 2 - 4} text-anchor="middle">${label}</text>` : nothing}
    </g>`
  }

  private renderNode(id: string, node: FlowNodeJson, problems: readonly FlowProblem[]): SVGTemplateResult {
    const p = this.layout[id] ?? { x: 0, y: 0 }
    const type = String(node.type)
    const known = (NODE_TYPES as readonly string[]).includes(type)
    const typeLabel = known ? this.t(`flow.types.${type as NodeType}`) : type
    const selected = this._selected === id
    const start = this.flow.start === id
    const detail = nodeDetail(node)
    const label = [
      this.t('flow.nodeLabel', { type: typeLabel, id }),
      start ? this.t('flow.isStart') : '',
      problems.length > 0 ? this.t('flow.problemsCount', { n: problems.length }) : '',
    ].filter(Boolean).join(', ')
    return svg`<g
      class="node ${selected ? 'selected' : ''} ${this._connecting?.from === id ? 'connect-source' : ''}"
      data-id=${id}
      transform="translate(${p.x} ${p.y})"
      tabindex="0"
      role="button"
      aria-pressed=${selected ? 'true' : 'false'}
      aria-label=${label}
      @pointerdown=${(e: PointerEvent) => this.onNodeDown(e, id)}
      @keydown=${(e: KeyboardEvent) => this.onNodeKey(e, id)}
      @focus=${() => {
        if (!this._connecting) this._selected = id
      }}
    >
      <rect class="box" width=${NODE_WIDTH} height=${NODE_HEIGHT} rx="8"></rect>
      <rect class="stripe ${type}" width="6" height=${NODE_HEIGHT} rx="3"></rect>
      <text class="type" x="16" y="18">${typeLabel}${start ? '' : ''}</text>
      ${start ? svg`<text class="start-mark" x=${NODE_WIDTH - 10} y="18" text-anchor="end">▶ ${this.t('flow.start')}</text>` : nothing}
      <text class="id" x="16" y="38">${id.length > 20 ? id.slice(0, 19) + '…' : id}</text>
      ${detail ? svg`<text class="detail" x="16" y="55">${detail.length > 24 ? detail.slice(0, 23) + '…' : detail}</text>` : nothing}
      ${problems.length > 0
        ? svg`<g class="badge" transform="translate(${NODE_WIDTH - 2} 2)"><circle r="9"></circle><text text-anchor="middle" y="4">${problems.length}</text></g>`
        : nothing}
      ${type !== 'end' && !this.readonly
        ? svg`<circle class="port" cx=${NODE_WIDTH} cy=${NODE_HEIGHT / 2} r="6" @pointerdown=${(e: PointerEvent) => this.onPortDown(e, id, false)}></circle>`
        : nothing}
      ${type === 'model' && !this.readonly
        ? svg`<circle class="port failure" cx=${NODE_WIDTH / 2} cy=${NODE_HEIGHT} r="6" @pointerdown=${(e: PointerEvent) => this.onPortDown(e, id, true)}></circle>`
        : nothing}
    </g>`
  }

  private renderProblems(problems: readonly FlowProblem[]): TemplateResult {
    return html`<section part="problems" class="problems" aria-labelledby="flow-problems">
      <h3 id="flow-problems">${this.t('problems.title')} <span class="muted">(${problems.length})</span></h3>
      ${problems.length === 0
        ? html`<p class="ok">${this.t('flow.noProblems')}</p>`
        : html`<ul>
            ${problems.map(
              (p) => html`<li>
                <span class="sev ${p.severity}">${p.severity === 'error' ? this.t('problems.error') : this.t('problems.warning')}</span>
                ${p.node ? html`<span class="mono">${p.node}</span>` : nothing}
                <span>${this.t(`flow.checks.${p.code}`, p.params)}</span>
                ${p.node && p.node in this.flow.nodes
                  ? html`<button type="button" @click=${() => {
                      this._selected = p.node
                      this.focusNode = p.node
                    }}>${this.t('problems.show')}</button>`
                  : nothing}
              </li>`,
            )}
          </ul>`}
    </section>`
  }

  // --- Properties panel ------------------------------------------------------------------------

  private renderPanel(): TemplateResult {
    const id = this._selected
    const node = id === undefined ? undefined : this.flow.nodes[id]
    return html`<aside part="panel" class="panel" aria-labelledby="panel-heading">
      <h3 id="panel-heading">${node && id ? this.t('flow.nodeProperties', { id }) : this.t('flow.flowProperties')}</h3>
      ${node && id ? this.renderNodePanel(id, node) : this.renderFlowPanel()}
    </aside>`
  }

  private targets(except?: string): string[] {
    return Object.keys(this.flow.nodes).filter((n) => n !== except)
  }

  private text(label: string, value: string | undefined, set: (v: string) => void, mono = false, list?: string): TemplateResult {
    return html`<label class="field"><span>${label}</span>
      <input class=${mono ? 'mono-input' : ''} list=${list ?? nothing} .value=${value ?? ''} ?readonly=${this.readonly} @change=${(e: Event) => set((e.target as HTMLInputElement).value)} />
    </label>`
  }

  private targetSelect(label: string, value: string | undefined, set: (v: string | undefined) => void, except?: string, allowNone = true): TemplateResult {
    const options = this.targets(except)
    return html`<select aria-label=${label} ?disabled=${this.readonly} @change=${(e: Event) => {
      const v = (e.target as HTMLSelectElement).value
      set(v === '' ? undefined : v)
    }}>
      ${allowNone ? html`<option value="" ?selected=${!value}>—</option>` : nothing}
      ${value && !options.includes(value) ? html`<option selected value=${value}>${value} ?</option>` : nothing}
      ${options.map((o) => html`<option value=${o} ?selected=${o === value}>${o}</option>`)}
    </select>`
  }

  private setNode(id: string, node: FlowNodeJson): void {
    this.change(updateNode(this.flow, id, node))
  }

  private renderFlowPanel(): TemplateResult {
    const f = this.flow
    const set = (patch: Partial<FlowJson>) => this.change({ ...f, ...patch })
    const json = (key: 'inputs' | 'outputs') => html`<label class="field"><span>${this.t(`flow.fields.${key}`)}</span>
      <textarea spellcheck="false" .value=${JSON.stringify(f[key], null, 2)} ?readonly=${this.readonly} aria-invalid=${this._fieldErrors.has(key) ? 'true' : 'false'}
        @change=${(e: Event) => {
          const errors = new Map(this._fieldErrors)
          try {
            const value = JSON.parse((e.target as HTMLTextAreaElement).value) as unknown
            if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('{ "name": "type" }')
            errors.delete(key)
            this._fieldErrors = errors
            set({ [key]: value as Record<string, TypeSpec> })
          } catch (err) {
            errors.set(key, err instanceof Error ? err.message : String(err))
            this._fieldErrors = errors
          }
        }}></textarea>
      ${this._fieldErrors.has(key) ? html`<span class="field-error" role="alert">${this._fieldErrors.get(key)}</span>` : nothing}
    </label>`
    return html`
      ${this.text(this.t('flow.fields.id'), f.id, (v) => set({ id: v.trim() }), true)}
      ${this.text(this.t('flow.fields.version'), f.version, (v) => set({ version: v.trim() }), true)}
      ${this.text(this.t('flow.fields.description'), f.description, (v) => {
        const next: FlowJson = { ...f, description: v }
        if (v === '') delete next.description
        this.change(next)
      })}
      <label class="field"><span>${this.t('flow.start')}</span>${this.targetSelect(this.t('flow.start'), f.start, (v) => set({ start: v ?? '' }))}</label>
      ${json('inputs')}
      ${json('outputs')}
      <p class="muted">${this.t('flow.nothingSelected')}</p>
    `
  }

  private renderNodePanel(id: string, node: FlowNodeJson): TemplateResult {
    const set = (patch: Partial<FlowNodeJson>) => {
      const next: FlowNodeJson = { ...node, ...patch }
      for (const [k, v] of Object.entries(patch)) if (v === undefined || v === '') delete next[k]
      this.setNode(id, next)
    }
    const type = String(node.type)
    const idError = this._fieldErrors.get('node-id')
    return html`
      <label class="field"><span>${this.t('flow.id')}</span>
        <input class="mono-input" .value=${id} ?readonly=${this.readonly} aria-invalid=${idError ? 'true' : 'false'} @change=${(e: Event) => {
          const next = (e.target as HTMLInputElement).value.trim()
          const errors = new Map(this._fieldErrors)
          if (next === id) return
          if (next === '' || next in this.flow.nodes) {
            errors.set('node-id', this.t('flow.renameTaken'))
            this._fieldErrors = errors
            ;(e.target as HTMLInputElement).value = id
            return
          }
          errors.delete('node-id')
          this._fieldErrors = errors
          const layout = { ...this.layout, [next]: this.layout[id]! }
          delete layout[id]
          this._selected = next
          this.change(renameNode(this.flow, id, next), layout)
        }} />
        ${idError ? html`<span class="field-error" role="alert">${idError}</span>` : nothing}
      </label>
      <p class="muted">${this.t('flow.type')}: ${(NODE_TYPES as readonly string[]).includes(type) ? this.t(`flow.types.${type as NodeType}`) : type}
        · ${this.flow.start === id ? this.t('flow.isStart') : html`<button type="button" ?disabled=${this.readonly} @click=${() => this.change({ ...this.flow, start: id })}>${this.t('flow.makeStart')}</button>`}</p>
      ${this.typeFields(id, node, set)}
      ${this.renderEdgeList(id, node)}
      ${this.readonly ? nothing : html`<p><button type="button" @click=${() => this.removeAt(id)}>${this.t('flow.removeNode')}</button></p>`}
    `
  }

  private typeFields(id: string, node: FlowNodeJson, set: (patch: Partial<FlowNodeJson>) => void): TemplateResult {
    const f = (key: keyof Messages_Fields) => this.t(`flow.fields.${key}`)
    switch (node.type) {
      case 'table':
        return html`${this.text(f('decision'), node.decision, (v) => set({ decision: v.trim() }), true, 'known-tables')}
          <datalist id="known-tables">${(this.tables ?? []).map((t) => html`<option value=${t}></option>`)}</datalist>
          ${this.text(f('as'), node.as, (v) => set({ as: v.trim() }), true)}
          ${this.mapEditor(f('facts'), node.facts, (m) => set({ facts: m }))}
          ${this.nextEditor(id, node, set)}`
      case 'switch':
        return this.branchEditor(f('cases'), id, node.cases ?? [], (cases) => set({ cases }), this.t('flow.addCase'))
      case 'derive':
        return html`${this.mapEditor(f('values'), node.values, (m) => set({ values: m }))}${this.nextEditor(id, node, set)}`
      case 'model':
        return html`${this.text(f('modelId'), node.model?.id, (v) => set({ model: { ...node.model, id: v.trim() } }), true)}
          ${this.text(f('modelVersion'), node.model?.version, (v) => set({ model: { ...node.model, version: v.trim() } }), true)}
          <label class="field"><span>${f('mode')}</span>
            <select ?disabled=${this.readonly} @change=${(e: Event) => set({ mode: (e.target as HTMLSelectElement).value })}>
              ${['invoke', 'supplied'].map((m) => html`<option value=${m} ?selected=${node.mode === m}>${m}</option>`)}
            </select>
          </label>
          ${this.text(f('as'), node.as, (v) => set({ as: v.trim() }), true)}
          ${this.mapEditor(f('features'), node.features, (m) => set({ features: m }))}
          ${this.typeMapEditor(f('modelOutputs'), node.outputs ?? {}, (m) => set({ outputs: m }))}
          ${this.text(f('valid'), node.valid, (v) => set({ valid: v }), true)}
          <label class="field"><span>${f('timeoutMs')}</span>
            <input type="number" min="1" .value=${node.timeoutMs === undefined ? '' : String(node.timeoutMs)} ?readonly=${this.readonly}
              @change=${(e: Event) => {
                const v = (e.target as HTMLInputElement).value
                set({ timeoutMs: v === '' ? undefined : Number(v) } as Partial<FlowNodeJson>)
              }} />
          </label>
          <label class="field"><span>${f('onFailure')}</span>
            ${this.targetSelect(f('onFailure'), node.onFailure?.to, (to) => {
              const onFailure = { ...(node.onFailure ?? {}) }
              if (to === undefined) delete onFailure.to
              else onFailure.to = to
              set({ onFailure })
            }, id)}
          </label>
          ${this.text(f('failureReason'), node.onFailure?.reasonCode, (v) => {
            const onFailure = { ...(node.onFailure ?? {}) }
            if (v.trim() === '') delete onFailure.reasonCode
            else onFailure.reasonCode = v.trim()
            set({ onFailure })
          }, true)}
          ${this.nextEditor(id, node, set)}`
      case 'split':
        return html`${this.text(f('key'), node.key, (v) => set({ key: v }), true)}
          ${this.text(f('salt'), node.salt, (v) => set({ salt: v }), true)}
          ${this.bucketEditor(id, node, set)}`
      case 'end': {
        const entries = { ...Object.fromEntries(Object.keys(this.flow.outputs).map((o) => [o, ''])), ...(node.set ?? {}) }
        return this.mapEditor(f('set'), entries, (m) => set({ set: m }), true)
      }
      case 'flow':
        return html`${this.text(f('flow'), node.flow, (v) => set({ flow: v.trim() }), true)}
          ${this.text(f('flowVersion'), node.version, (v) => set({ version: v.trim() }), true)}
          ${this.text(f('as'), node.as, (v) => set({ as: v.trim() }), true)}
          ${this.nextEditor(id, node, set)}`
      default:
        return html``
    }
  }

  /** Name → expression pairs. With fixedKeys, names cannot be changed, only values. */
  private mapEditor(label: string, map: Record<string, string> | undefined, set: (m: Record<string, string>) => void, fixedKeys = false): TemplateResult {
    const entries = Object.entries(map ?? {})
    const update = (index: number, key: string, value: string) => {
      const next = entries.map(([k, v], i) => (i === index ? [key, value] : [k, v]) as [string, string])
      set(Object.fromEntries(next.filter(([k]) => k !== '')))
    }
    return html`<fieldset class="field"><legend>${label}</legend>
      <div class="entries">
        ${entries.map(
          ([k, v], i) => html`<div class="entry">
            <input class="mono-input" aria-label=${`${label}: ${this.t('flow.name')} ${i + 1}`} .value=${k} ?readonly=${this.readonly || fixedKeys} @change=${(e: Event) => update(i, (e.target as HTMLInputElement).value.trim(), v)} />
            <input class="mono-input" aria-label=${`${label}: ${k} ${this.t('flow.expression')}`} .value=${v} ?readonly=${this.readonly} @change=${(e: Event) => update(i, k, (e.target as HTMLInputElement).value)} />
            ${fixedKeys || this.readonly ? html`<span></span>` : html`<button type="button" class="icon" aria-label=${`${this.t('common.remove')} ${k}`} @click=${() => set(Object.fromEntries(entries.filter((_, j) => j !== i)))}>✕</button>`}
          </div>`,
        )}
      </div>
      ${fixedKeys || this.readonly ? nothing : html`<button type="button" @click=${() => set({ ...Object.fromEntries(entries), [freeKey(entries.map(([k]) => k))]: '' })}>+ ${this.t('flow.addEntry')}</button>`}
    </fieldset>`
  }

  private typeMapEditor(label: string, map: Record<string, TypeSpec>, set: (m: Record<string, TypeSpec>) => void): TemplateResult {
    const entries = Object.entries(map)
    return html`<fieldset class="field"><legend>${label}</legend>
      <div class="entries">
        ${entries.map(
          ([k, v], i) => html`<div class="entry">
            <input class="mono-input" aria-label=${`${label}: ${this.t('flow.name')} ${i + 1}`} .value=${k} ?readonly=${this.readonly}
              @change=${(e: Event) => set(Object.fromEntries(entries.map(([kk, vv], j) => [j === i ? (e.target as HTMLInputElement).value.trim() : kk, vv])))} />
            <select aria-label=${`${label}: ${k} ${this.t('schema.type')}`} ?disabled=${this.readonly}
              @change=${(e: Event) => set(Object.fromEntries(entries.map(([kk, vv], j) => [kk, j === i ? (e.target as HTMLSelectElement).value : vv])))}>
              ${PRIMITIVES.map((p) => html`<option value=${p} ?selected=${p === v}>${p}</option>`)}
            </select>
            ${this.readonly ? html`<span></span>` : html`<button type="button" class="icon" aria-label=${`${this.t('common.remove')} ${k}`} @click=${() => set(Object.fromEntries(entries.filter((_, j) => j !== i)))}>✕</button>`}
          </div>`,
        )}
      </div>
      ${this.readonly ? nothing : html`<button type="button" @click=${() => set({ ...map, [freeKey(entries.map(([k]) => k))]: 'decimal' })}>+ ${this.t('flow.addEntry')}</button>`}
    </fieldset>`
  }

  private nextEditor(id: string, node: FlowNodeJson, set: (patch: Partial<FlowNodeJson>) => void): TemplateResult {
    const label = this.t('flow.fields.next')
    if (Array.isArray(node.next)) {
      return this.branchEditor(this.t('flow.fields.branches'), id, node.next, (branches) => set({ next: branches.length === 0 ? undefined : branches } as Partial<FlowNodeJson>), this.t('flow.addBranch'))
    }
    return html`<div class="field"><span>${label}</span>
      ${this.targetSelect(label, node.next, (to) => set({ next: to } as Partial<FlowNodeJson>), id)}
      ${this.readonly ? nothing : html`<button type="button" @click=${() => set({ next: [{ when: '', to: '' }, { to: typeof node.next === 'string' ? node.next : '' }] })}>+ ${this.t('flow.addBranch')}</button>`}
    </div>`
  }

  private branchEditor(label: string, id: string, branches: readonly Branch[], set: (b: Branch[]) => void, addLabel: string): TemplateResult {
    const update = (i: number, branch: Branch) => set(branches.map((b, j) => (j === i ? branch : b)))
    return html`<fieldset class="field"><legend>${label}</legend>
      <div class="entries">
        ${branches.map(
          (b, i) => html`<div class="entry">
            <input class="mono-input" aria-label=${`${this.t('flow.when')} ${i + 1}`} placeholder=${this.t('flow.otherwise')} .value=${b.when ?? ''} ?readonly=${this.readonly}
              @change=${(e: Event) => {
                const when = (e.target as HTMLInputElement).value
                update(i, when.trim() === '' ? { to: b.to } : { when, to: b.to })
              }} />
            ${this.targetSelect(`${this.t('flow.target')} ${i + 1}`, b.to || undefined, (to) => update(i, { ...b, to: to ?? '' }), id)}
            ${this.readonly ? html`<span></span>` : html`<button type="button" class="icon" aria-label=${`${this.t('common.remove')} ${i + 1}`} @click=${() => set(branches.filter((_, j) => j !== i))}>✕</button>`}
          </div>`,
        )}
      </div>
      ${this.readonly ? nothing : html`<button type="button" @click=${() => {
        const last = branches.at(-1)
        set(last && last.when === undefined ? [...branches.slice(0, -1), { when: '', to: '' }, last] : [...branches, { to: '' }])
      }}>+ ${addLabel}</button>`}
    </fieldset>`
  }

  private bucketEditor(id: string, node: FlowNodeJson, set: (patch: Partial<FlowNodeJson>) => void): TemplateResult {
    const buckets = node.buckets ?? []
    const update = (i: number, patch: Partial<{ name: string; percent: number; to: string }>) => set({ buckets: buckets.map((b, j) => (j === i ? { ...b, ...patch } : b)) })
    return html`<fieldset class="field"><legend>${this.t('flow.fields.buckets')}</legend>
      <div class="entries">
        ${buckets.map(
          (b, i) => html`<div class="entry three">
            <input class="mono-input" aria-label=${`${this.t('flow.bucket')} ${i + 1}: ${this.t('flow.name')}`} .value=${b.name} ?readonly=${this.readonly} @change=${(e: Event) => update(i, { name: (e.target as HTMLInputElement).value.trim() })} />
            <input type="number" min="0" max="100" aria-label=${`${this.t('flow.bucket')} ${i + 1}: ${this.t('flow.percent')}`} .value=${String(b.percent)} ?readonly=${this.readonly} @change=${(e: Event) => update(i, { percent: Number((e.target as HTMLInputElement).value) })} />
            ${this.targetSelect(`${this.t('flow.bucket')} ${i + 1}: ${this.t('flow.target')}`, b.to || undefined, (to) => update(i, { to: to ?? '' }), id)}
            ${this.readonly ? html`<span></span>` : html`<button type="button" class="icon" aria-label=${`${this.t('common.remove')} ${b.name}`} @click=${() => set({ buckets: buckets.filter((_, j) => j !== i) })}>✕</button>`}
          </div>`,
        )}
      </div>
      ${this.readonly ? nothing : html`<button type="button" @click=${() => set({ buckets: [...buckets, { name: `bucket${buckets.length + 1}`, percent: 0, to: '' }] })}>+ ${this.t('flow.addBucket')}</button>`}
    </fieldset>`
  }

  private renderEdgeList(id: string, node: FlowNodeJson): TemplateResult {
    const edges = edgesOf(id, node).filter((e) => e.to !== '')
    if (edges.length === 0) return html``
    return html`<fieldset class="field"><legend>${this.t('flow.edges')}</legend>
      <ul class="entries">
        ${edges.map((e) => html`<li class="row">
          <span class="mono">→ ${e.to}${e.label ? ` (${e.label})` : ''}</span>
          ${this.readonly ? nothing : html`<button type="button" class="icon" aria-label=${this.t('flow.removeEdge', { from: e.from, to: e.to })} @click=${() => this.change(disconnect(this.flow, e))}>✕</button>`}
        </li>`)}
      </ul>
    </fieldset>`
  }

  // --- Operations ------------------------------------------------------------------------------

  private center(): { x: number; y: number } {
    const v = this._view
    return { x: v.x + this._size.w / v.zoom / 2 - NODE_WIDTH / 2, y: v.y + this._size.h / v.zoom / 2 - NODE_HEIGHT / 2 }
  }

  /** Adds a node of a type, at a canvas position or in the middle of the view. */
  addAt(type: NodeType, at?: { x: number; y: number }): string {
    const { flow, id } = addNode(this.flow, type)
    let position = at ?? this.center()
    // Do not stack new nodes exactly on top of each other.
    while (Object.values(this.layout).some((p) => Math.abs(p.x - position.x) < 8 && Math.abs(p.y - position.y) < 8)) {
      position = { x: position.x + 24, y: position.y + 24 }
    }
    this._selected = id
    this.focusNode = id
    this.change(flow, { ...this.layout, [id]: { x: Math.round(position.x), y: Math.round(position.y) } }, this.t('flow.added', { id }))
    return id
  }

  private removeAt(id: string): void {
    const layout = { ...this.layout }
    delete layout[id]
    this._selected = undefined
    this.change(removeNode(this.flow, id), layout, this.t('flow.removed', { id }))
    void this.updateComplete.then(() => this.renderRoot.querySelector<SVGSVGElement>('svg')?.focus())
  }

  /** Connects two nodes, as a drag from the port or the C key then Enter do. */
  connectNodes(from: string, to: string, failure = false): void {
    if (from === to) return
    this.change(connect(this.flow, from, to, failure), this.layout, this.t('flow.connected', { from, to }))
  }

  private zoomBy(factor: number, around?: { x: number; y: number }): void {
    const v = this._view
    const zoom = Math.max(0.3, Math.min(2.5, v.zoom * factor))
    const c = around ?? { x: v.x + this._size.w / v.zoom / 2, y: v.y + this._size.h / v.zoom / 2 }
    this._view = { zoom, x: c.x - (c.x - v.x) * (v.zoom / zoom), y: c.y - (c.y - v.y) * (v.zoom / zoom) }
  }

  /** Zooms and pans so that every node is visible. */
  fit(): void {
    const points = Object.values(this.layout)
    if (points.length === 0) {
      this._view = { x: 0, y: 0, zoom: 1 }
      return
    }
    const minX = Math.min(...points.map((p) => p.x)) - 30
    const minY = Math.min(...points.map((p) => p.y)) - 30
    const maxX = Math.max(...points.map((p) => p.x)) + NODE_WIDTH + 30
    const maxY = Math.max(...points.map((p) => p.y)) + NODE_HEIGHT + 30
    const zoom = Math.max(0.3, Math.min(1.5, this._size.w / (maxX - minX), this._size.h / (maxY - minY)))
    this._view = { zoom, x: minX - (this._size.w / zoom - (maxX - minX)) / 2, y: minY - (this._size.h / zoom - (maxY - minY)) / 2 }
  }

  private exportJson(): void {
    const json = this.toJson()
    const proceed = this.emit('rulestone-export', { flow: cloneJson(this.flow), json })
    if (!proceed || typeof URL.createObjectURL !== 'function') return
    const url = URL.createObjectURL(new Blob([json], { type: 'application/json' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `${this.flow.id || 'flow'}.flow.json`
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }

  private async copyJson(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.toJson())
      this.announce(this.t('common.copied'))
    } catch {
      // Clipboard access can be refused; export still works.
    }
  }

  /** Replaces the flow with one read from JSON text. */
  importText(text: string): boolean {
    try {
      const flow = parseFlow(text)
      this._importError = undefined
      this._importText = ''
      this._selected = undefined
      this.change(flow, autoLayout(flow))
      void this.updateComplete.then(() => this.fit())
      return true
    } catch (e) {
      this._importError = this.t('flow.importError', { message: e instanceof Error ? e.message : String(e) })
      return false
    }
  }

  private onFile(e: Event): void {
    const file = (e.target as HTMLInputElement).files?.[0]
    if (!file) return
    void file.text().then((text) => this.importText(text))
  }

  // --- Pointer and keyboard --------------------------------------------------------------------

  private toCanvas(e: { clientX: number; clientY: number }): { x: number; y: number } {
    const svgEl = this.renderRoot.querySelector('svg')
    const rect = svgEl?.getBoundingClientRect()
    const v = this._view
    if (!rect || rect.width === 0) return { x: v.x, y: v.y }
    return { x: v.x + ((e.clientX - rect.left) / rect.width) * (this._size.w / v.zoom), y: v.y + ((e.clientY - rect.top) / rect.height) * (this._size.h / v.zoom) }
  }

  private nodeAt(point: { x: number; y: number }): string | undefined {
    return Object.keys(this.flow.nodes).reverse().find((id) => {
      const p = this.layout[id]
      return p && point.x >= p.x && point.x <= p.x + NODE_WIDTH && point.y >= p.y && point.y <= p.y + NODE_HEIGHT
    })
  }

  private capture(e: PointerEvent): void {
    const svgEl = this.renderRoot.querySelector('svg')
    try {
      svgEl?.setPointerCapture(e.pointerId)
    } catch {
      // Not every environment supports pointer capture.
    }
  }

  private onBackgroundDown = (e: PointerEvent): void => {
    if (this.drag || e.button !== 0) return
    this.drag = { kind: 'pan', startX: e.clientX, startY: e.clientY, view: this._view }
    this.capture(e)
  }

  private onNodeDown(e: PointerEvent, id: string): void {
    if (e.button !== 0) return
    e.stopPropagation()
    const p = this.layout[id]!
    this._selected = id
    if (this.readonly) return
    this.drag = { kind: 'move', id, startX: e.clientX, startY: e.clientY, origX: p.x, origY: p.y, moved: false }
    this.capture(e)
  }

  private onPortDown(e: PointerEvent, id: string, failure: boolean): void {
    e.stopPropagation()
    this.drag = { kind: 'connect', from: id, failure }
    this._connecting = { from: id, failure }
    this._pointer = this.toCanvas(e)
    this.capture(e)
  }

  private onPointerMove = (e: PointerEvent): void => {
    const drag = this.drag
    if (!drag) return
    const zoom = this._view.zoom
    if (drag.kind === 'pan') {
      this._view = { ...drag.view, x: drag.view.x - (e.clientX - drag.startX) / zoom, y: drag.view.y - (e.clientY - drag.startY) / zoom }
    } else if (drag.kind === 'move') {
      const dx = (e.clientX - drag.startX) / zoom
      const dy = (e.clientY - drag.startY) / zoom
      if (Math.abs(dx) + Math.abs(dy) > 2) drag.moved = true
      this.layout = { ...this.layout, [drag.id]: { x: Math.round(drag.origX + dx), y: Math.round(drag.origY + dy) } }
    } else {
      this._pointer = this.toCanvas(e)
    }
  }

  private onPointerUp = (e: PointerEvent): void => {
    const drag = this.drag
    this.drag = undefined
    if (!drag) return
    if (drag.kind === 'move' && drag.moved) this.change(this.flow, this.layout)
    if (drag.kind === 'connect') {
      const target = this.nodeAt(this.toCanvas(e))
      this._connecting = undefined
      this._pointer = undefined
      if (target && target !== drag.from) this.connectNodes(drag.from, target, drag.failure)
    }
  }

  private onWheel(e: WheelEvent): void {
    e.preventDefault()
    this.zoomBy(e.deltaY < 0 ? 1.1 : 1 / 1.1, this.toCanvas(e))
  }

  private onCanvasKey = (e: KeyboardEvent): void => {
    if ((e.target as Element).tagName.toLowerCase() !== 'svg') return
    const step = (e.shiftKey ? 120 : 40) / this._view.zoom
    const v = this._view
    switch (e.key) {
      case 'ArrowLeft':
        this._view = { ...v, x: v.x - step }
        break
      case 'ArrowRight':
        this._view = { ...v, x: v.x + step }
        break
      case 'ArrowUp':
        this._view = { ...v, y: v.y - step }
        break
      case 'ArrowDown':
        this._view = { ...v, y: v.y + step }
        break
      case '+':
      case '=':
        this.zoomBy(1.2)
        break
      case '-':
        this.zoomBy(1 / 1.2)
        break
      case '0':
        this.fit()
        break
      default:
        return
    }
    e.preventDefault()
  }

  private onNodeKey(e: KeyboardEvent, id: string): void {
    const connecting = this._connecting
    if (e.key === 'Escape') {
      e.preventDefault()
      if (connecting) {
        this._connecting = undefined
        this.announce(this.t('flow.connectCancelled'))
      } else this._selected = undefined
      return
    }
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      if (connecting) {
        this._connecting = undefined
        if (connecting.from !== id) this.connectNodes(connecting.from, id, connecting.failure)
      } else this._selected = id
      return
    }
    if (this.readonly) return
    if (e.key === 'c' || e.key === 'C' || e.key === 'f' || e.key === 'F') {
      e.preventDefault()
      const failure = e.key.toLowerCase() === 'f' && this.flow.nodes[id]?.type === 'model'
      if (this.flow.nodes[id]?.type === 'end') return
      this._connecting = { from: id, failure }
      this.announce(this.t('flow.connectFrom', { id }))
      return
    }
    if (e.key === 'Delete' || e.key === 'Backspace') {
      e.preventDefault()
      this.removeAt(id)
      return
    }
    const step = e.shiftKey ? 50 : 10
    const delta = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[e.key]
    if (delta) {
      e.preventDefault()
      const p = this.layout[id]!
      this.change(this.flow, { ...this.layout, [id]: { x: p.x + delta[0]!, y: p.y + delta[1]! } }, this.t('flow.movedNode', { id }))
      this.focusNode = id
    }
  }

  private onPaletteDragOver = (e: DragEvent): void => {
    if (this.readonly) return
    if (e.dataTransfer?.types.includes(PALETTE_TYPE)) {
      e.preventDefault()
      e.dataTransfer.dropEffect = 'copy'
    }
  }

  private onPaletteDrop = (e: DragEvent): void => {
    const type = e.dataTransfer?.getData(PALETTE_TYPE)
    if (!type || !(NODE_TYPES as readonly string[]).includes(type)) return
    e.preventDefault()
    const p = this.toCanvas(e)
    this.addAt(type as NodeType, { x: p.x - NODE_WIDTH / 2, y: p.y - NODE_HEIGHT / 2 })
  }
}

type Messages_Fields = import('../i18n/en.js').Messages['flow']['fields']

function freeKey(keys: readonly string[]): string {
  for (let n = 1; ; n++) if (!keys.includes(`name${n}`)) return `name${n}`
}

/** A cubic curve between two points; edges into a node come from the left. */
function curve(x1: number, y1: number, x2: number, y2: number, downward = false): string {
  if (downward) {
    const dy = Math.max(40, Math.abs(y2 - y1) / 2)
    return `M ${x1} ${y1} C ${x1} ${y1 + dy}, ${x2 - 60} ${y2}, ${x2} ${y2}`
  }
  const dx = Math.max(40, Math.abs(x2 - x1) / 2)
  return `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`
}

/** One line about the node for the canvas: its table, model, key or outputs. */
function nodeDetail(node: FlowNodeJson): string {
  switch (node.type) {
    case 'table':
      return node.decision ?? ''
    case 'model':
      return node.model?.id ? `${node.model.id}@${node.model.version ?? '?'}` : ''
    case 'split':
      return (node.buckets ?? []).map((b) => `${b.percent}%`).join('/')
    case 'switch':
      return `${(node.cases ?? []).length} cases`
    case 'derive':
      return Object.keys(node.values ?? {}).join(', ')
    case 'end':
      return Object.values(node.set ?? {}).join(', ')
    case 'flow':
      return node.flow ?? ''
    default:
      return ''
  }
}

define('rulestone-flow-canvas', RulestoneFlowCanvas)

declare global {
  interface HTMLElementTagNameMap {
    'rulestone-flow-canvas': RulestoneFlowCanvas
  }
}
