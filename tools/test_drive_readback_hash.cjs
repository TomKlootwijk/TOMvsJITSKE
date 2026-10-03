'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const {decodeBase64, sha256} = require('./drive_readback_hash.cjs');

function verifyBytes(source, label) {
  const decoded = decodeBase64(source.toString('base64'));
  assert.equal(decoded.length, source.length, label + ': decoded length');
  assert.ok(Buffer.from(decoded.buffer, decoded.byteOffset, decoded.byteLength).equals(source),
    label + ': exact decoded bytes');
  const expected = crypto.createHash('sha256').update(source).digest('hex');
  assert.equal(sha256(decoded), expected, label + ': SHA-256 matches Node crypto');
  return {label, bytes: source.length, sha256: expected};
}

for (const value of ['', 'f', 'fo', 'foo', 'foob', 'fooba', 'foobar']) {
  verifyBytes(Buffer.from(value), 'padding vector ' + JSON.stringify(value));
}
for (const value of [null, 42, {}, 'A', 'AA', 'AAA', '====', 'A===', '=AAA',
  'AA=A', 'AAA=AAAA', 'AA==AAAA', 'AA\n=', 'AA =', 'AA-=', 'AA_=', 'AAé=', 'AAAA\n']) {
  assert.throws(() => decodeBase64(value), /Malformed or truncated/, 'reject ' + JSON.stringify(value));
}
const boundary = 'A'.repeat(65536 * 2);
for (const index of [0, 65535, 65536, 65537, boundary.length - 1]) {
  assert.throws(() => decodeBase64(boundary.slice(0, index) + '!' + boundary.slice(index + 1)),
    /Malformed or truncated/, 'reject invalid byte at chunk boundary ' + index);
}
// Size exceeds the failing archive and produces roughly 50 MB of base64 text.
const large = Buffer.alloc(36 * 1024 * 1024);
for (let i = 0; i < large.length; i++) large[i] = i % 251;
console.log(JSON.stringify(verifyBytes(large, '36 MiB regression')));
// Optional real-file check remains read-only, with no archive or receipt mutation.
if (process.argv[2]) console.log(JSON.stringify(verifyBytes(fs.readFileSync(process.argv[2]), 'supplied archive')));
console.log('PASS: large base64, strict malformed-input checks, and SHA-256');
