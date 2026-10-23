// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// npm license audit for the SDK workspace: fails if any installed package, whether a runtime
// dependency or a build and test tool, has a license outside the allowlist. Run after `npm ci`.

import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ALLOWED = new Set(['Apache-2.0', 'MIT', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC'])

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const lock = JSON.parse(readFileSync(join(root, 'package-lock.json'), 'utf8'))

const problems = []
let checked = 0
const runtime = []
for (const [path, info] of Object.entries(lock.packages)) {
  // The workspace root and the workspace packages themselves are RuleStone's own code.
  if (path === '' || info.link || !path.includes('node_modules/')) continue
  checked++
  const name = path.replace(/^.*node_modules\//, '')
  const license = info.license ?? readPackageLicense(path)
  if (!isAllowed(license)) {
    problems.push(`${name}@${info.version}: license ${license ?? '(none)'} is not in the allowlist`)
  }
  if (!info.dev && !info.optional) runtime.push(`${name}@${info.version}`)
}

if (problems.length > 0) {
  console.error('npm license audit failed:\n  ' + problems.join('\n  '))
  console.error(`Allowed: ${[...ALLOWED].join(', ')}`)
  process.exit(1)
}
console.log(`npm license audit passed for ${checked} packages (allowed: ${[...ALLOWED].join(', ')})`)
console.log(runtime.length === 0 ? 'Runtime dependencies: none' : `Runtime dependencies: ${runtime.join(', ')}`)

function isAllowed(license) {
  if (!license) return false
  // SPDX expressions such as "(MIT OR Apache-2.0)": one allowed alternative suffices.
  const alternatives = license.replace(/[()]/g, '').split(/\s+OR\s+/)
  return alternatives.some((alternative) => alternative.split(/\s+AND\s+/).every((l) => ALLOWED.has(l.trim())))
}

function readPackageLicense(path) {
  const file = join(root, path, 'package.json')
  if (!existsSync(file)) return undefined
  const json = JSON.parse(readFileSync(file, 'utf8'))
  return typeof json.license === 'string' ? json.license : json.license?.type
}
