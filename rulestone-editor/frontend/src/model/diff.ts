// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

export interface DiffLine {
  kind: 'same' | 'added' | 'removed'
  text: string
  oldLine?: number
  newLine?: number
}

/** Line diff (longest common subsequence). Files are small, so the quadratic table is fine. */
export function diffLines(before: string, after: string): DiffLine[] {
  const a = before.split('\n')
  const b = after.split('\n')
  if (a[a.length - 1] === '') a.pop()
  if (b[b.length - 1] === '') b.pop()
  const n = a.length
  const m = b.length
  const lcs: Uint32Array[] = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1])
    }
  }
  const out: DiffLine[] = []
  let i = 0
  let j = 0
  while (i < n || j < m) {
    if (i < n && j < m && a[i] === b[j]) {
      out.push({ kind: 'same', text: a[i], oldLine: i + 1, newLine: j + 1 })
      i++
      j++
    } else if (j < m && (i >= n || lcs[i][j + 1] >= lcs[i + 1][j])) {
      out.push({ kind: 'added', text: b[j], newLine: j + 1 })
      j++
    } else {
      out.push({ kind: 'removed', text: a[i], oldLine: i + 1 })
      i++
    }
  }
  return out
}

/** Only the changed lines with `context` unchanged lines around them; gaps become null. */
export function withContext(lines: DiffLine[], context = 3): (DiffLine | null)[] {
  const keep = new Array<boolean>(lines.length).fill(false)
  lines.forEach((l, i) => {
    if (l.kind !== 'same') {
      for (let k = Math.max(0, i - context); k <= Math.min(lines.length - 1, i + context); k++) keep[k] = true
    }
  })
  const out: (DiffLine | null)[] = []
  let gap = false
  lines.forEach((l, i) => {
    if (keep[i]) {
      out.push(l)
      gap = false
    } else if (!gap) {
      out.push(null)
      gap = true
    }
  })
  return out
}
