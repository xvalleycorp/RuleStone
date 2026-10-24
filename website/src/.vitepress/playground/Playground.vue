<!--
  Copyright 2026 The RuleStone Authors
  SPDX-License-Identifier: Apache-2.0

  The playground: the @rulestone/editor components, running in the visitor's browser on the
  repository's example tables and the example decision flow. The components are loaded on the
  client only, when the page mounts (VitePress also renders pages at build time, where there is
  no DOM for custom elements). They follow the site's language and light or dark appearance.
-->
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { useData } from 'vitepress';
import { data } from './examples.data';
import { MESSAGES, fill } from './messages';
import { MAX_ENCODED, ShareError, decodeShare, encodeShare } from './share';

type Engine = typeof import('@rulestone/engine');
type Model = typeof import('@rulestone/editor/model');
type TableEditor = import('@rulestone/editor').RulestoneTableEditor;
type ConditionBuilder = import('@rulestone/editor').RulestoneConditionBuilder;
type FlowCanvas = import('@rulestone/editor').RulestoneFlowCanvas;
type Tab = 'table' | 'builder' | 'flow';
type Notice = { kind: 'info' | 'success' | 'error'; text: string };

const TABS: Tab[] = ['table', 'builder', 'flow'];
const STORAGE_PREFIX = 'rulestone.playground.v1.';
const MAX_IMPORT_KB = 1024;
const SHARED = '__shared';
const IMPORTED = '__imported';

const { lang, isDark } = useData();
const locale = computed(() => (lang.value.startsWith('vi') ? 'vi' : 'en'));
const m = computed(() => MESSAGES[locale.value]);
const theme = computed(() => (isDark.value ? 'dark' : 'light'));

const examples = data.examples;
const featured = examples.filter((e) => e.featured);
const others = examples.filter((e) => !e.featured);
const byName = new Map(examples.map((e) => [e.name, e]));

const tab = ref<Tab>('table');
const opened = ref<Set<Tab>>(new Set(['table']));
const ready = ref<Set<Tab>>(new Set());
const loadError = ref<string>();
const selection = ref('loan_eligibility.v3');
const importedName = ref('');
const notice = ref<Notice>();
const shareUrl = ref('');

const editor = ref<TableEditor>();
const builder = ref<ConditionBuilder>();
const canvas = ref<FlowCanvas>();
const fileInput = ref<HTMLInputElement>();
const engine = shallowRef<Engine>();
const model = shallowRef<Model>();

const current = computed(() => byName.get(selection.value));

// --- Storage: the last edit of each example, in this browser only ------------------------------

function stored(name: string): string | undefined {
  try {
    return localStorage.getItem(STORAGE_PREFIX + name) ?? undefined;
  } catch {
    return undefined;
  }
}

function store(name: string, text: string | undefined): void {
  try {
    if (text === undefined) localStorage.removeItem(STORAGE_PREFIX + name);
    else localStorage.setItem(STORAGE_PREFIX + name, text);
  } catch {
    // Storage can be full, disabled or blocked; the playground works without it.
  }
}

// --- Reading tables strictly -----------------------------------------------------------------------

/** The first problems of a table the strict reader rejects, as one line. */
function problemsOf(e: unknown): string {
  const problems = (e as { problems?: readonly string[] }).problems;
  if (problems?.length) return problems.slice(0, 3).join('; ') + (problems.length > 3 ? '; …' : '');
  return e instanceof Error ? e.message : String(e);
}

/** Parses JSON text with the engine's strict .stone.json reader; returns the plain object. */
function readTable(text: string): unknown {
  engine.value!.parseDecisionTable(text);
  return JSON.parse(text);
}

// --- The decision table editor ---------------------------------------------------------------------

async function show(table: unknown, facts: string | undefined, asOf: string | undefined): Promise<void> {
  const el = editor.value;
  if (!el) return;
  el.facts = undefined;
  el.asOf = asOf;
  el.table = table as TableEditor['table'];
  await el.updateComplete;
  el.facts = facts ?? '{}';
}

function clearHash(): void {
  if (location.hash) history.replaceState(history.state, '', location.pathname + location.search);
}

async function loadExample(name: string, restore = true): Promise<void> {
  const example = byName.get(name) ?? examples[0]!;
  selection.value = example.name;
  shareUrl.value = '';
  let text = example.text;
  notice.value = undefined;
  const saved = restore ? stored(example.name) : undefined;
  if (saved !== undefined) {
    try {
      readTable(saved);
      text = saved;
      notice.value = { kind: 'info', text: m.value.restored };
    } catch {
      store(example.name, undefined);
    }
  }
  await show(JSON.parse(text), example.facts, example.asOf);
}

async function openHash(): Promise<boolean> {
  let text: string | undefined;
  try {
    text = await decodeShare(location.hash);
  } catch (e) {
    const reason = e instanceof ShareError ? m.value.sharedProblem[e.problem] : String(e);
    notice.value = { kind: 'error', text: fill(m.value.sharedFailed, { message: reason }) };
    return false;
  }
  if (text === undefined) return false;
  try {
    const table = readTable(text);
    selection.value = SHARED;
    shareUrl.value = '';
    await show(table, undefined, undefined);
    notice.value = { kind: 'info', text: m.value.sharedOpened };
    return true;
  } catch (e) {
    notice.value = { kind: 'error', text: fill(m.value.sharedFailed, { message: problemsOf(e) }) };
    return false;
  }
}

async function onHashChange(): Promise<void> {
  if (!(await openHash()) && selection.value === SHARED) await loadExample('loan_eligibility.v3');
}

function onSelect(e: Event): void {
  clearHash();
  void loadExample((e.target as HTMLSelectElement).value);
}

function reset(): void {
  clearHash();
  const name = current.value?.name ?? 'loan_eligibility.v3';
  store(name, undefined);
  void loadExample(name, false);
}

function onEdit(e: Event): void {
  const detail = (e as CustomEvent<{ table: unknown }>).detail;
  if (current.value) store(current.value.name, JSON.stringify(detail.table, null, 2) + '\n');
}

function tableText(): string {
  const table = editor.value!.getTable();
  return model.value ? model.value.tableText(table) : JSON.stringify(table, null, 2) + '\n';
}

function download(): void {
  const table = editor.value!.getTable();
  const file = `${table.id || 'table'}.stone.json`;
  const url = URL.createObjectURL(new Blob([tableText()], { type: 'application/json' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = file;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
  notice.value = { kind: 'success', text: fill(m.value.downloaded, { file }) };
}

async function copy(): Promise<void> {
  try {
    await navigator.clipboard.writeText(tableText());
    notice.value = { kind: 'success', text: m.value.copied };
  } catch {
    notice.value = { kind: 'error', text: m.value.copyFailed };
  }
}

async function share(): Promise<void> {
  shareUrl.value = '';
  const json = JSON.stringify(editor.value!.getTable());
  try {
    engine.value!.parseDecisionTable(json);
  } catch (e) {
    notice.value = { kind: 'error', text: fill(m.value.shareNotReadable, { message: problemsOf(e) }) };
    return;
  }
  let hash: string;
  try {
    hash = await encodeShare(json);
  } catch (e) {
    const tooLarge = e instanceof ShareError && e.problem === 'tooLarge';
    notice.value = {
      kind: 'error',
      text: tooLarge ? fill(m.value.shareTooLarge, { size: e.message.replace(/\D/g, ''), limit: MAX_ENCODED }) : m.value.shareUnsupported,
    };
    return;
  }
  const url = location.origin + location.pathname + hash;
  history.replaceState(history.state, '', location.pathname + hash);
  try {
    await navigator.clipboard.writeText(url);
    notice.value = { kind: 'success', text: m.value.shareCopied };
  } catch {
    shareUrl.value = url;
    notice.value = { kind: 'info', text: m.value.shareReady };
  }
}

async function importFile(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  if (file.size > MAX_IMPORT_KB * 1024) {
    notice.value = { kind: 'error', text: fill(m.value.fileTooLarge, { file: file.name, limit: MAX_IMPORT_KB }) };
    return;
  }
  try {
    const table = readTable(await file.text());
    clearHash();
    selection.value = IMPORTED;
    importedName.value = file.name;
    shareUrl.value = '';
    await show(table, undefined, undefined);
    notice.value = { kind: 'success', text: fill(m.value.importedFile, { file: file.name }) };
  } catch (err) {
    notice.value = { kind: 'error', text: fill(m.value.importFailed, { file: file.name, message: problemsOf(err) }) };
  }
}

// --- The condition builder ---------------------------------------------------------------------

const builderExample = ref('consumer_loan_underwriting');
const builderRule = ref('REFER_LTV');
const builderCel = ref('');
const builderTable = computed(() => JSON.parse(byName.get(builderExample.value)?.text ?? '{}') as { inputs?: object; rules?: { id: string; when: string }[] });
const builderRules = computed(() => builderTable.value.rules ?? []);

function loadBuilder(): void {
  const el = builder.value;
  if (!el) return;
  const rules = builderRules.value;
  const rule = rules.find((r) => r.id === builderRule.value) ?? rules.find((r) => r.when !== 'true') ?? rules[0];
  if (rule) builderRule.value = rule.id;
  el.inputs = (builderTable.value.inputs ?? {}) as ConditionBuilder['inputs'];
  el.value = rule?.when ?? 'true';
  builderCel.value = el.value;
}

function onBuilderChange(e: Event): void {
  builderCel.value = (e as CustomEvent<{ value: string }>).detail.value;
}

// --- The decision flow ---------------------------------------------------------------------------

const flowTables = (() => {
  const flow = JSON.parse(data.flow) as { nodes: Record<string, { type: string; decision?: string }> };
  const used = Object.values(flow.nodes).flatMap((n) => (n.type === 'table' && n.decision ? [n.decision] : []));
  return [...new Set([...used, ...examples.map((e) => JSON.parse(e.text).id as string)])];
})();

function loadFlow(): void {
  const el = canvas.value;
  if (!el) return;
  el.tables = flowTables;
  el.layout = {};
  el.flow = JSON.parse(data.flow);
  void el.updateComplete.then(() => el.fit());
}

// --- Tabs and loading ------------------------------------------------------------------------------

async function load(which: Tab): Promise<void> {
  if (ready.value.has(which)) return;
  try {
    if (which === 'table') {
      const [engineModule, modelModule] = await Promise.all([
        import('@rulestone/engine'),
        import('@rulestone/editor/model'),
        import('@rulestone/editor/table-editor'),
      ]);
      engine.value = engineModule;
      model.value = modelModule;
    } else if (which === 'builder') {
      await import('@rulestone/editor/condition-builder');
    } else {
      await import('@rulestone/editor/flow-canvas');
    }
  } catch (e) {
    loadError.value = fill(m.value.loadFailed, { message: e instanceof Error ? e.message : String(e) });
    return;
  }
  ready.value = new Set([...ready.value, which]);
  await nextTick();
  if (which === 'table') {
    editor.value?.addEventListener('rulestone-change', onEdit);
    if (!(await openHash())) await loadExample(selection.value);
  } else if (which === 'builder') {
    builder.value?.addEventListener('rulestone-change', onBuilderChange);
    loadBuilder();
  } else {
    loadFlow();
  }
}

function select(next: Tab, focus = false): void {
  tab.value = next;
  opened.value = new Set([...opened.value, next]);
  const url = new URL(location.href);
  if (next === 'table') url.searchParams.delete('tab');
  else url.searchParams.set('tab', next);
  history.replaceState(history.state, '', url.pathname + url.search + url.hash);
  void load(next);
  // A canvas that was hidden has no size until it is shown: fit it again.
  if (next === 'flow' && ready.value.has('flow')) void nextTick(() => canvas.value?.fit());
  if (focus) void nextTick(() => document.getElementById(`rs-pg-tab-${next}`)?.focus());
}

function onTabKey(e: KeyboardEvent): void {
  const i = TABS.indexOf(tab.value);
  const next = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? TABS.length - 1 : undefined;
  if (next === undefined) return;
  e.preventDefault();
  select(TABS[(next + TABS.length) % TABS.length]!, true);
}

watch(builderExample, () => {
  builderRule.value = '';
  loadBuilder();
});
watch(builderRule, () => loadBuilder());

onMounted(() => {
  const wanted = new URLSearchParams(location.search).get('tab');
  if (wanted === 'builder' || wanted === 'flow') select(wanted);
  void load('table');
  window.addEventListener('hashchange', onHashChange);
});

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', onHashChange);
  editor.value?.removeEventListener('rulestone-change', onEdit);
  builder.value?.removeEventListener('rulestone-change', onBuilderChange);
});
</script>

<template>
  <div class="rs-pg" :class="{ dark: isDark }">
    <div class="rs-pg-tabs" role="tablist" :aria-label="m.tabs" @keydown="onTabKey">
      <button
        v-for="t in TABS"
        :id="`rs-pg-tab-${t}`"
        :key="t"
        type="button"
        role="tab"
        :aria-selected="tab === t ? 'true' : 'false'"
        :aria-controls="`rs-pg-panel-${t}`"
        :tabindex="tab === t ? 0 : -1"
        @click="select(t)"
      >
        {{ t === 'table' ? m.tabTable : t === 'builder' ? m.tabBuilder : m.tabFlow }}
      </button>
    </div>

    <p v-if="loadError" class="rs-pg-notice error" role="alert">{{ loadError }}</p>

    <!-- Decision table -->
    <section id="rs-pg-panel-table" v-show="tab === 'table'" role="tabpanel" aria-labelledby="rs-pg-tab-table" class="rs-pg-panel">
      <div class="rs-pg-toolbar">
        <label class="rs-pg-field">
          <span>{{ m.example }}</span>
          <select v-model="selection" :disabled="!ready.has('table')" @change="onSelect($event)">
            <optgroup :label="m.featured">
              <option v-for="e in featured" :key="e.name" :value="e.name">{{ e.name }}</option>
            </optgroup>
            <optgroup :label="m.others">
              <option v-for="e in others" :key="e.name" :value="e.name">{{ e.name }}</option>
            </optgroup>
            <option v-if="selection === SHARED" :value="SHARED">{{ m.shared }}</option>
            <option v-if="selection === IMPORTED" :value="IMPORTED">{{ m.imported }}: {{ importedName }}</option>
          </select>
        </label>
        <div class="rs-pg-buttons">
          <button type="button" :disabled="!ready.has('table')" @click="reset">{{ m.reset }}</button>
          <button type="button" :disabled="!ready.has('table')" @click="fileInput?.click()">{{ m.import }}</button>
          <input ref="fileInput" class="rs-pg-file" type="file" accept=".json,application/json" tabindex="-1" aria-hidden="true" @change="importFile" />
          <button type="button" :disabled="!ready.has('table')" @click="download">{{ m.download }}</button>
          <button type="button" :disabled="!ready.has('table')" @click="copy">{{ m.copy }}</button>
          <button type="button" class="brand" :disabled="!ready.has('table')" @click="share">{{ m.share }}</button>
        </div>
      </div>
      <p v-if="current" class="rs-pg-sample">
        {{ current.caseName ? fill(m.sample, { case: current.caseName, file: `examples/${current.name}.stone-test.json` }) : m.noSample }}
      </p>
      <div class="rs-pg-status" role="status" aria-live="polite">
        <p v-if="notice" class="rs-pg-notice" :class="notice.kind">{{ notice.text }}</p>
        <input v-if="shareUrl" class="rs-pg-share" readonly :value="shareUrl" :aria-label="m.share" @focus="($event.target as HTMLInputElement).select()" />
      </div>
      <p v-if="!ready.has('table') && !loadError" class="rs-pg-loading">{{ m.loading }}</p>
      <rulestone-table-editor v-if="ready.has('table')" ref="editor" :locale="locale" :theme="theme"></rulestone-table-editor>
    </section>

    <!-- Condition builder -->
    <section id="rs-pg-panel-builder" v-show="tab === 'builder'" role="tabpanel" aria-labelledby="rs-pg-tab-builder" class="rs-pg-panel">
      <template v-if="opened.has('builder')">
        <div class="rs-pg-toolbar start">
          <label class="rs-pg-field">
            <span>{{ m.builderInputs }}</span>
            <select v-model="builderExample">
              <option v-for="e in examples" :key="e.name" :value="e.name">{{ e.name }}</option>
            </select>
          </label>
          <label class="rs-pg-field">
            <span>{{ m.builderRule }}</span>
            <select v-model="builderRule">
              <option v-for="r in builderRules" :key="r.id" :value="r.id">{{ r.id }}</option>
            </select>
          </label>
        </div>
        <p class="rs-pg-sample">{{ m.builderHelp }}</p>
        <p v-if="!ready.has('builder') && !loadError" class="rs-pg-loading">{{ m.loading }}</p>
        <rulestone-condition-builder v-if="ready.has('builder')" ref="builder" :locale="locale" :theme="theme"></rulestone-condition-builder>
        <h3 class="rs-pg-cel-title">{{ m.builderCel }}</h3>
        <pre class="rs-pg-cel" tabindex="0" :aria-label="m.builderCel">{{ builderCel }}</pre>
      </template>
    </section>

    <!-- Decision flow -->
    <section id="rs-pg-panel-flow" v-show="tab === 'flow'" role="tabpanel" aria-labelledby="rs-pg-tab-flow" class="rs-pg-panel">
      <template v-if="opened.has('flow')">
        <div class="rs-pg-toolbar">
          <p class="rs-pg-sample">{{ m.flowTables }}</p>
          <div class="rs-pg-buttons">
            <button type="button" :disabled="!ready.has('flow')" @click="loadFlow">{{ m.resetFlow }}</button>
          </div>
        </div>
        <p v-if="!ready.has('flow') && !loadError" class="rs-pg-loading">{{ m.loading }}</p>
        <rulestone-flow-canvas v-if="ready.has('flow')" ref="canvas" :locale="locale" :theme="theme"></rulestone-flow-canvas>
      </template>
    </section>
  </div>
</template>

<style scoped>
/* The components' tokens, set from the site's own colours (see "Theming" in docs/editor-sdk.md). */
.rs-pg {
  --rs-color-bg: var(--vp-c-bg);
  --rs-color-surface: var(--vp-c-bg-soft);
  --rs-color-surface-raised: var(--vp-c-bg);
  --rs-color-text: var(--vp-c-text-1);
  --rs-color-text-muted: var(--vp-c-text-2);
  --rs-color-border: var(--vp-c-divider);
  --rs-color-accent: var(--vp-c-brand-1);
  --rs-color-on-accent: #ffffff;
  --rs-color-accent-subtle: var(--vp-c-brand-soft);
  --rs-font-family: var(--vp-font-family-base);
  --rs-font-family-mono: var(--vp-font-family-mono);
  margin-top: 16px;
}

.rs-pg.dark {
  --rs-color-on-accent: #1c1917;
  --rs-color-surface-raised: var(--vp-c-bg-alt);
}

.rs-pg-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  border-bottom: 1px solid var(--vp-c-divider);
  margin-bottom: 16px;
}

.rs-pg-tabs [role='tab'] {
  border: 1px solid transparent;
  border-bottom: 0;
  border-radius: 8px 8px 0 0;
  padding: 8px 14px;
  font-size: 15px;
  font-weight: 500;
  color: var(--vp-c-text-2);
  margin-bottom: -1px;
}

.rs-pg-tabs [role='tab']:hover {
  color: var(--vp-c-text-1);
}

.rs-pg-tabs [role='tab'][aria-selected='true'] {
  color: var(--vp-c-brand-1);
  border-color: var(--vp-c-divider);
  background: var(--vp-c-bg);
  font-weight: 600;
}

.rs-pg-tabs [role='tab']:focus-visible,
.rs-pg button:focus-visible,
.rs-pg select:focus-visible,
.rs-pg-cel:focus-visible {
  outline: 2px solid var(--vp-c-brand-1);
  outline-offset: 2px;
}

.rs-pg-panel {
  min-width: 0;
}

.rs-pg-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  justify-content: space-between;
  gap: 8px 16px;
  margin-bottom: 8px;
}

.rs-pg-toolbar.start {
  justify-content: flex-start;
}

.rs-pg-field {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
  color: var(--vp-c-text-2);
  min-width: 0;
  max-width: 100%;
}

.rs-pg select,
.rs-pg-share {
  font: inherit;
  font-size: 14px;
  color: var(--vp-c-text-1);
  background: var(--vp-c-bg);
  border: 1px solid var(--vp-c-border);
  border-radius: 6px;
  padding: 4px 8px;
  min-height: 34px;
  max-width: 100%;
}

.rs-pg-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.rs-pg-buttons button {
  border: 1px solid var(--vp-c-border);
  border-radius: 6px;
  padding: 4px 12px;
  min-height: 34px;
  font-size: 14px;
  font-weight: 500;
  color: var(--vp-c-text-1);
  background: var(--vp-c-bg-soft);
  transition: border-color 0.2s, color 0.2s;
}

.rs-pg-buttons button:hover:not(:disabled) {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.rs-pg-buttons button.brand {
  background: var(--vp-button-brand-bg);
  border-color: var(--vp-button-brand-bg);
  color: var(--vp-button-brand-text);
}

.rs-pg-buttons button.brand:hover:not(:disabled) {
  background: var(--vp-button-brand-hover-bg);
  color: var(--vp-button-brand-hover-text);
}

.rs-pg-buttons button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.rs-pg-file {
  display: none;
}

.rs-pg-sample {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--vp-c-text-2);
  overflow-wrap: anywhere;
}

.rs-pg-notice {
  margin: 0 0 8px;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 14px;
  border: 1px solid var(--vp-c-divider);
  background: var(--vp-c-bg-soft);
}

.rs-pg-notice.success {
  border-color: var(--vp-c-green-2);
  background: var(--vp-c-green-soft);
}

.rs-pg-notice.error {
  border-color: var(--vp-c-red-2);
  background: var(--vp-c-red-soft);
}

.rs-pg-share {
  width: 100%;
  margin-bottom: 8px;
  font-family: var(--vp-font-family-mono);
  font-size: 13px;
}

.rs-pg-loading {
  padding: 48px 0;
  text-align: center;
  color: var(--vp-c-text-2);
}

.rs-pg-cel-title {
  margin: 16px 0 6px;
  font-size: 15px;
}

.rs-pg-cel {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
  background: var(--vp-c-bg-soft);
  font-family: var(--vp-font-family-mono);
  font-size: 13px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
