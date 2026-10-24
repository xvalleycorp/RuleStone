// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Share links for the playground: the table is compressed (deflate-raw, CompressionStream) and
// encoded as base64url in the URL fragment, which browsers never send to a server. Reading a
// link only decodes text; the caller parses it with the engine's strict reader. Nothing in a
// link is executed.

/** The fragment prefix of a shared table: #table=<base64url>. */
export const SHARE_PREFIX = '#table=';
/** Longest encoded table accepted in a link, in characters. Long URLs break in many tools. */
export const MAX_ENCODED = 16_000;
/** Largest decompressed table accepted from a link, in bytes. */
export const MAX_DECODED = 512 * 1024;

export type ShareProblem = 'unsupported' | 'tooLarge' | 'malformed';

export class ShareError extends Error {
  constructor(
    readonly problem: ShareProblem,
    message: string,
  ) {
    super(message);
  }
}

export const canShare = (): boolean => typeof CompressionStream === 'function' && typeof DecompressionStream === 'function';

function toBase64Url(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(text: string): Uint8Array {
  const base64 = text.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(base64 + '='.repeat((4 - (base64.length % 4)) % 4));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/** Reads a stream to the end, failing once it has produced more than `limit` bytes. */
async function readAll(stream: ReadableStream<Uint8Array>, limit: number): Promise<Uint8Array> {
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > limit) {
      await reader.cancel();
      throw new ShareError('tooLarge', `more than ${limit} bytes`);
    }
    chunks.push(value);
  }
  const out = new Uint8Array(size);
  let at = 0;
  for (const c of chunks) {
    out.set(c, at);
    at += c.length;
  }
  return out;
}

/** The fragment (with SHARE_PREFIX) for a table's JSON text. */
export async function encodeShare(json: string): Promise<string> {
  if (!canShare()) throw new ShareError('unsupported', 'CompressionStream is not available');
  const input = new Blob([new TextEncoder().encode(json)]).stream().pipeThrough(new CompressionStream('deflate-raw'));
  const encoded = toBase64Url(await readAll(input, Number.MAX_SAFE_INTEGER));
  if (encoded.length > MAX_ENCODED) throw new ShareError('tooLarge', `${encoded.length} characters`);
  return SHARE_PREFIX + encoded;
}

/** The JSON text in a fragment made by encodeShare; undefined when the fragment is not a shared table. */
export async function decodeShare(hash: string): Promise<string | undefined> {
  if (!hash.startsWith(SHARE_PREFIX)) return undefined;
  const encoded = hash.slice(SHARE_PREFIX.length);
  if (!canShare()) throw new ShareError('unsupported', 'DecompressionStream is not available');
  if (encoded.length > MAX_ENCODED) throw new ShareError('tooLarge', `${encoded.length} characters`);
  if (!/^[A-Za-z0-9_-]+$/.test(encoded)) throw new ShareError('malformed', 'not base64url');
  let bytes: Uint8Array;
  try {
    bytes = fromBase64Url(encoded);
  } catch {
    throw new ShareError('malformed', 'not base64url');
  }
  let raw: Uint8Array;
  try {
    raw = await readAll(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw')), MAX_DECODED);
  } catch (e) {
    if (e instanceof ShareError) throw e;
    throw new ShareError('malformed', 'not compressed data');
  }
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(raw);
  } catch {
    throw new ShareError('malformed', 'not UTF-8 text');
  }
}
