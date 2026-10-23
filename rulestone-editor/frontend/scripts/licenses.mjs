// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// npm license audit, run by the Maven build after `npm ci`:
//  1. fails if any installed package (runtime or build tool) has a license outside the allowlist;
//  2. writes the notice for the packages bundled into the jar (runtime dependencies), with their
//     license texts, to the path given as the first argument (default: dist-licenses/THIRD-PARTY-WEB.txt).

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ALLOWED = new Set(['MIT', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC', '0BSD'])

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const output = process.argv[2] ?? join(root, 'dist-licenses', 'THIRD-PARTY-WEB.txt')
const lock = JSON.parse(readFileSync(join(root, 'package-lock.json'), 'utf8'))

const problems = []
const runtime = []
for (const [path, info] of Object.entries(lock.packages)) {
  if (path === '' || info.link) continue
  const name = path.replace(/^.*node_modules\//, '')
  const license = info.license ?? readPackageLicense(path)
  if (!isAllowed(license)) {
    problems.push(`${name}@${info.version}: license ${license ?? '(none)'} is not in the allowlist`)
  }
  if (!info.dev && !info.optional) runtime.push({ name, path, version: info.version, license })
}

if (problems.length > 0) {
  console.error('npm license audit failed:\n  ' + problems.join('\n  '))
  console.error(`Allowed: ${[...ALLOWED].join(', ')}`)
  process.exit(1)
}

runtime.sort((a, b) => a.name.localeCompare(b.name) || a.version.localeCompare(b.version))
const seen = new Set()
let text = 'Third-party software in the RuleStone editor user interface\n'
text += '=============================================================\n\n'
text += 'The user interface (vn/io/dxa/rulestone/editor/web/) is built from these npm packages\n'
text += '(runtime dependencies; the bundle contains the parts that the editor uses):\n\n'
for (const p of runtime) {
  const key = `${p.name}@${p.version}`
  if (seen.has(key)) continue
  seen.add(key)
  text += `  ${key}  (${p.license})\n`
}
text += '\nLicense texts\n-------------\n'
seen.clear()
for (const p of runtime) {
  const key = `${p.name}@${p.version}`
  if (seen.has(key)) continue
  seen.add(key)
  const licenseText = readLicenseText(p.path)
  text += `\n=== ${key} (${p.license}) ===\n\n${licenseText ?? `Licensed under ${p.license}.`}\n`
}

mkdirSync(dirname(output), { recursive: true })
writeFileSync(output, text)
console.log(`npm license audit passed for ${Object.keys(lock.packages).length - 1} packages; wrote ${output}`)

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

function readLicenseText(path) {
  const dir = join(root, path)
  if (!existsSync(dir)) return undefined
  const file = readdirSync(dir).find((f) => /^(LICEN[CS]E|COPYING)(\.(md|txt))?$/i.test(f))
  return file ? readFileSync(join(dir, file), 'utf8').trim() : undefined
}
