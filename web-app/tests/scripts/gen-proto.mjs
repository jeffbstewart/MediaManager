#!/usr/bin/env node
//
// Generate TypeScript types AND Connect service stubs from the project's
// .proto files for the web app. Single source of truth: ../proto/*.proto.
// Output: src/app/proto-gen/.
//
// Stack: @bufbuild/protoc-gen-es (>= 2.x) emits both messages and Connect
// service definitions in one pass — no separate connect-es plugin needed
// in this version. The runtime side uses @connectrpc/connect-web's
// createGrpcWebTransport, which talks application/grpc-web+proto to
// Armeria. JS never needs to touch the cookie value: fetch sends
// HttpOnly cookies automatically when the transport is configured with
// `credentials: 'include'`.
//
// Spike scope: catalog → common → time. Expands as more RPCs migrate.
//
// Run: `npm run proto:gen`

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const webRoot = resolve(here, '..', '..');
// Look for proto sources at the conventional repo-root location first,
// then at /proto (Docker layout).
const protoRoot = [
  resolve(webRoot, '..', 'proto'),
  '/proto',
].find(p => existsSync(p));
const outDir = join(webRoot, 'src', 'app', 'proto-gen');

if (!protoRoot) {
  console.warn('proto codegen skipped: no proto/ sources found at ../proto or /proto');
  process.exit(0);
}

const protoFiles = ['common.proto', 'time.proto', 'catalog.proto', 'artist.proto', 'playlist.proto', 'wishlist.proto', 'playback.proto'];

const protocBin = process.platform === 'win32'
  ? join(webRoot, 'node_modules', '.bin', 'protoc.cmd')
  : join(webRoot, 'node_modules', '.bin', 'protoc');
const esPlugin = process.platform === 'win32'
  ? join(webRoot, 'node_modules', '.bin', 'protoc-gen-es.cmd')
  : join(webRoot, 'node_modules', '.bin', 'protoc-gen-es');

rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });

const esOpts = [
  // Emit TypeScript (vs JS).
  'target=ts',
  // Generate Connect-compatible service definitions alongside messages.
  // Connect-ES 2.x consumes these directly via createPromiseClient.
  'json_types=true',
];

const args = [
  `--plugin=protoc-gen-es=${esPlugin}`,
  `--es_out=${outDir}`,
  `--es_opt=${esOpts.join(',')}`,
  `--proto_path=${protoRoot}`,
  ...protoFiles.map(f => join(protoRoot, f)),
];

console.log(`> protoc ${protoFiles.join(' ')} → ${outDir}`);
execFileSync(protocBin, args, { stdio: 'inherit', shell: process.platform === 'win32' });
console.log('proto codegen complete.');
