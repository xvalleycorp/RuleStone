// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import type {
  EvaluationResult, LoadedTable, Meta, Problem, SaveResult, TableJson, TableSummary, ValidationResult,
} from './types'

/** An API error response: { error: { code, message } }, possibly with problems. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly problems: Problem[] = [],
    readonly body: unknown = undefined,
  ) {
    super(message)
  }
}

// Relative URLs, so the editor also works behind a reverse proxy under a sub-path.
async function call<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? headers : { 'Content-Type': 'application/json', ...headers },
    body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
    credentials: 'same-origin',
  })
  let json: unknown = null
  try {
    json = await response.json()
  } catch {
    // empty or non-JSON body
  }
  if (!response.ok) {
    const j = (json ?? {}) as { error?: { code?: string; message?: string }; problems?: Problem[] }
    throw new ApiError(response.status, j.error?.code ?? 'http_' + response.status, j.error?.message ?? response.statusText,
      j.problems ?? [], json)
  }
  return json as T
}

const file = (name: string) => 'api/tables/' + name.split('/').map(encodeURIComponent).join('/')

export const api = {
  meta: () => call<Meta>('GET', 'api/meta'),
  tables: () => call<{ directory: string; tables: TableSummary[] }>('GET', 'api/tables'),
  load: (name: string) => call<LoadedTable>('GET', file(name)),
  save: (name: string, table: TableJson, etag: string | undefined, force: boolean) =>
    call<SaveResult>('PUT', file(name) + (force ? '?force=true' : ''), table, etag ? { 'If-Match': `"${etag}"` } : {}),
  create: (body: { file?: string; table: TableJson }, force = false) =>
    call<SaveResult>('POST', 'api/tables' + (force ? '?force=true' : ''), body),
  validate: (table: TableJson, name?: string) =>
    call<ValidationResult>('POST', 'api/validate' + (name ? '?file=' + encodeURIComponent(name) : ''), table),
  format: (table: TableJson) => call<{ content: string }>('POST', 'api/format', table),
  /** `factsJson` is sent as written, so its numbers never pass through JavaScript floating point. */
  evaluate: (table: TableJson, factsJson: string, asOf?: string) =>
    call<EvaluationResult>('POST', 'api/evaluate',
      `{"table": ${JSON.stringify(table)}, "facts": ${factsJson}${asOf ? `, "asOf": ${JSON.stringify(asOf)}` : ''}}`),
}
