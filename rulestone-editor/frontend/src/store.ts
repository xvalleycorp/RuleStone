// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { ref } from 'vue'
import { api } from './api'
import type { Meta } from './types'

/** Server metadata (types, hit policies, functions), loaded once. */
export const meta = ref<Meta | null>(null)

export async function loadMeta(): Promise<Meta> {
  if (!meta.value) meta.value = await api.meta()
  return meta.value
}

/** Hash routes: #/ (workspace) and #/edit/<file>. */
export const route = ref(parseHash())

function parseHash(): { view: 'workspace' } | { view: 'edit'; file: string } {
  const hash = typeof location === 'undefined' ? '' : location.hash
  const m = /^#\/edit\/(.+)$/.exec(hash)
  return m ? { view: 'edit', file: decodeURIComponent(m[1]) } : { view: 'workspace' }
}

if (typeof window !== 'undefined') {
  window.addEventListener('hashchange', () => {
    route.value = parseHash()
  })
}

export function openTable(file: string): void {
  location.hash = '#/edit/' + encodeURIComponent(file)
}

export function openWorkspace(): void {
  location.hash = '#/'
}

/** Set by the table editor while it has unsaved changes. */
export const dirty = ref(false)
