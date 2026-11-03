// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// The read-only repository interface and the reload helper, as the Java engine's RuleRepository and
// RepositoryReloader, asynchronous and without Node.js APIs, so that they run in browsers and at the
// edge too: a repository can be read over HTTP with fetch, and the last accepted release kept in any store.

import { InvalidDecisionError } from './errors.js'
import { createEngine, type EngineOptions, type RuleStoneEngine } from './engine.js'
import { unverifiedRelease, type ApprovalVerifier, type ArtifactKind, type NamedApproval } from './approvals.js'
import { parseReasonCodeCatalog, reasonCodeCatalogVersionOf } from './explanation.js'
import { flowVersionOf, parseDecisionFlow } from './flow.js'
import { parameterSetVersionOf, parseParameterSet } from './params.js'
import { RELEASE_FILE_SUFFIX, type Release } from './releases.js'
import { parseDecisionTable, ruleVersionOf } from './table.js'

/** The content of one version, as stored. */
export interface RepositoryArtifact {
  readonly kind: ArtifactKind
  /** Its id; for a catalog, the name the repository gives it, by default reason-codes. */
  readonly id: string
  /** Its version label as stored, not verified. */
  readonly version: string
  /** Where it came from, such as a file name, for problems. */
  readonly source: string
  /** The JSON text, exactly as stored. */
  readonly content: string
}

/** What an engine loads for one target, as RuleRepository.Snapshot. */
export interface RepositorySnapshot {
  readonly target: string
  /** The sequence of the release, as the repository read it, not verified; 0 if unreadable. */
  readonly sequence: number
  readonly release: NamedApproval
  /** The content of the versions the release lists. */
  readonly artifacts: readonly RepositoryArtifact[]
  readonly approvals: readonly NamedApproval[]
}

/**
 * Where an engine reads its rules from, as the Java RuleRepository: read-only, returning raw content
 * that the engine verifies. Implement it over HTTP, a database API or a bundled set of files.
 */
export interface RuleRepository {
  /** The current release of a target, the content of every version it lists and the approval statements. */
  load(target: string): Promise<RepositorySnapshot>
  /** One exact version, <label>@sha256:<hex>, for replay, also when it is retired or not in the current release. */
  find(kind: ArtifactKind, id: string, exactVersion: string): Promise<RepositoryArtifact | undefined>
  /** The sequence of the current release, as the repository claims it; without it, the reloader loads the snapshot. */
  latestSequence?(target: string): Promise<number>
  /** Calls the listener when a newer release is issued; returns a function that stops it. Optional. */
  watch?(target: string, listener: (sequence: number) => void): () => void
}

/**
 * A repository held in memory: for tests, and for applications that receive snapshots some other way,
 * such as bundled with the application or pushed over a message channel. put replaces a target's
 * snapshot and notifies watchers.
 */
export class InMemoryRuleRepository implements RuleRepository {
  private readonly snapshots = new Map<string, RepositorySnapshot>()
  private readonly watchers = new Map<string, Set<(sequence: number) => void>>()

  /** Stores the snapshot of a target: its signed release, the content it lists and the approvals. */
  put(target: string, content: {
    readonly release: string
    readonly artifacts: readonly RepositoryArtifact[]
    readonly approvals: readonly (string | NamedApproval)[]
  }): RepositorySnapshot {
    const snapshot: RepositorySnapshot = {
      target,
      sequence: unverifiedRelease(content.release)?.sequence ?? 0,
      release: { name: `${target}${RELEASE_FILE_SUFFIX}`, jws: content.release },
      artifacts: [...content.artifacts],
      approvals: content.approvals.map((a, i) => (typeof a === 'string' ? { name: `approvals[${i}]`, jws: a } : a)),
    }
    this.snapshots.set(target, snapshot)
    for (const listener of this.watchers.get(target) ?? []) listener(snapshot.sequence)
    return snapshot
  }

  async load(target: string): Promise<RepositorySnapshot> {
    const snapshot = this.snapshots.get(target)
    if (!snapshot) throw new Error(`no release of target '${target}'`)
    return snapshot
  }

  async find(kind: ArtifactKind, id: string, exactVersion: string): Promise<RepositoryArtifact | undefined> {
    const label = exactVersion.slice(0, Math.max(0, exactVersion.indexOf('@sha256:')))
    for (const snapshot of this.snapshots.values()) {
      for (const artifact of snapshot.artifacts) {
        if (artifact.kind === kind && artifact.id === id && artifact.version === label
          && (await artifactVersionOf(artifact)) === exactVersion) return artifact
      }
    }
    return undefined
  }

  async latestSequence(target: string): Promise<number> {
    return (await this.load(target)).sequence
  }

  watch(target: string, listener: (sequence: number) => void): () => void {
    if (!this.watchers.has(target)) this.watchers.set(target, new Set())
    this.watchers.get(target)!.add(listener)
    return () => this.watchers.get(target)?.delete(listener)
  }
}

/** The exact version of an artifact's content, or undefined if it cannot be read. */
async function artifactVersionOf(artifact: RepositoryArtifact): Promise<string | undefined> {
  try {
    switch (artifact.kind) {
      case 'table':
        return await ruleVersionOf(parseDecisionTable(artifact.content))
      case 'flow':
        return await flowVersionOf(parseDecisionFlow(artifact.content))
      case 'params':
        return await parameterSetVersionOf(parseParameterSet(artifact.content))
      case 'catalog':
        return await reasonCodeCatalogVersionOf(parseReasonCodeCatalog(artifact.content))
    }
  } catch (e) {
    if (e instanceof InvalidDecisionError) return undefined
    throw e
  }
}

/** Keeps the last release accepted for a target, signed, so that a restart cannot be rolled back. */
export interface ReleaseStore {
  load(target: string): Promise<string | undefined>
  save(target: string, jws: string): Promise<void>
}

/** A ReleaseStore in memory, for tests; in a browser, a store over localStorage or IndexedDB keeps it across reloads. */
export class MemoryReleaseStore implements ReleaseStore {
  private readonly releases = new Map<string, string>()

  async load(target: string): Promise<string | undefined> {
    return this.releases.get(target)
  }

  async save(target: string, jws: string): Promise<void> {
    this.releases.set(target, jws)
  }
}

export interface RepositoryReloaderOptions {
  readonly repository: RuleRepository
  readonly target: string
  /** Verifies the approvals and the releases. */
  readonly verifier: ApprovalVerifier
  /** Every other option of createEngine, such as the clock and audit sinks; content comes from the repository. */
  readonly engine?: Omit<EngineOptions, 'tables' | 'flows' | 'paramSets' | 'reasonCodeCatalog' | 'approvals' | 'release'
    | 'snapshot' | 'requireApproved' | 'requireRelease' | 'lastAcceptedRelease'>
  /** Keeps the last release accepted; read on the first reload, written before onReload is called. */
  readonly store?: ReleaseStore
  /** Receives each new engine. */
  readonly onReload?: (engine: RuleStoneEngine, release: Release) => void
  /** Receives each failure with the sequence the repository claimed (0 if unknown); the running engine stays. */
  readonly onFailure?: (sequence: number, error: unknown) => void
}

/**
 * Builds an engine from the current release of a target, and a new one whenever a newer release
 * appears, swapping it in only if it builds, as the Java RepositoryReloader. Starts no timer unless
 * pollEvery is called.
 */
export class RepositoryReloader {
  private current: { engine: RuleStoneEngine; release: Release } | undefined
  private floor: Release | undefined
  private floorRead = false
  private running: Promise<boolean> | undefined
  private readonly stops: (() => void)[] = []

  constructor(private readonly options: RepositoryReloaderOptions) {}

  /** The engine of the last release accepted, if one was. */
  get engine(): RuleStoneEngine | undefined {
    return this.current?.engine
  }

  /** The release the current engine runs, if one was accepted. */
  get release(): Release | undefined {
    return this.current?.release
  }

  /**
   * Loads the target's release and swaps in a new engine if the repository has a newer release than
   * the one running, or if none is running yet. Reloads never run concurrently: a call while one runs
   * waits for it. Resolves to whether a new engine was swapped in.
   * @throws InvalidDecisionError, on the first call, if the store holds a release that does not verify
   */
  reloadIfNewer(): Promise<boolean> {
    const next = (this.running ?? Promise.resolve(false)).then(() => this.reload(), () => this.reload())
    this.running = next
    return next
  }

  /** Calls reloadIfNewer every intervalMs milliseconds until stop. */
  pollEvery(intervalMs: number): void {
    if (!(intervalMs > 0)) throw new RangeError('interval must be positive')
    const timer = setInterval(() => void this.reloadIfNewer().catch(() => undefined), intervalMs)
    this.stops.push(() => clearInterval(timer))
  }

  /** Calls reloadIfNewer whenever the repository notifies a newer release, until stop. */
  watch(): void {
    const stop = this.options.repository.watch?.(this.options.target, () => void this.reloadIfNewer().catch(() => undefined))
    if (stop) this.stops.push(stop)
  }

  /** Stops polling and watching; the current engine stays usable. */
  stop(): void {
    for (const stop of this.stops.splice(0)) stop()
  }

  private async readFloor(): Promise<void> {
    if (this.floorRead) return
    const { store, target, verifier } = this.options
    const jws = store ? await store.load(target) : undefined
    if (jws !== undefined) {
      const checked = await verifier.checkRelease(jws)
      const problems = checked.problems.map((p) => `release store: ${p}`)
      if (checked.value && checked.value.target !== target) {
        problems.push(`release store: target is '${checked.value.target}', but this reloader loads '${target}'`)
      }
      if (problems.length > 0) throw new InvalidDecisionError(problems)
      this.floor = checked.value
    }
    this.floorRead = true
  }

  private async reload(): Promise<boolean> {
    await this.readFloor()
    const { repository, target, verifier, store } = this.options
    let sequence = 0
    try {
      sequence = repository.latestSequence ? await repository.latestSequence(target) : (await repository.load(target)).sequence
      if (this.current && sequence <= this.current.release.sequence) return false
      const snapshot = await repository.load(target)
      sequence = snapshot.sequence
      const engine = await createEngine({
        ...this.options.engine, snapshot, requireApproved: verifier, requireRelease: target,
        ...(this.floor ? { lastAcceptedRelease: this.floor } : {}),
      })
      const release = await verifier.verifyRelease(snapshot.release.jws)
      if (this.current && release.sequence === this.current.release.sequence) return false
      if (store && (!this.floor || release.sequence > this.floor.sequence)) await store.save(target, snapshot.release.jws.trim())
      this.floor = release
      this.current = { engine, release }
      this.options.onReload?.(engine, release)
      return true
    } catch (e) {
      this.options.onFailure?.(sequence, e)
      return false
    }
  }
}
