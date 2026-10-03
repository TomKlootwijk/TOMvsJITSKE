/* Raw-byte hashing for the isolated functions JavaScript runtime; no Node APIs required. */
function decodeBase64(value) {
  if (typeof value !== 'string' || value.length % 4) {
    throw new Error('Malformed or truncated raw-file base64');
  }
  const padding = value.endsWith('==') ? 2 : value.endsWith('=') ? 1 : 0;
  const payloadLength = value.length - padding;
  // A repeated-group regex can exhaust V8's stack on large otherwise-valid inputs.
  // Check the alphabet in bounded slices; '=' is permitted only in the final padding.
  for (let offset = 0; offset < payloadLength; offset += 65536) {
    if (/[^A-Za-z0-9+/]/.test(value.slice(offset, Math.min(offset + 65536, payloadLength)))) {
      throw new Error('Malformed or truncated raw-file base64');
    }
  }
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const lookup = new Int16Array(128);
  for (let i = 0; i < alphabet.length; i++) lookup[alphabet.charCodeAt(i)] = i;
  const length = value.length / 4 * 3 - padding;
  const bytes = new Uint8Array(length);
  let output = 0;
  for (let i = 0; i < value.length; i += 4) {
    const bits = (lookup[value.charCodeAt(i)] << 18) | (lookup[value.charCodeAt(i + 1)] << 12)
      | (lookup[value.charCodeAt(i + 2)] << 6) | lookup[value.charCodeAt(i + 3)];
    if (output < length) bytes[output++] = bits >>> 16;
    if (output < length) bytes[output++] = bits >>> 8;
    if (output < length) bytes[output++] = bits;
  }
  return bytes;
}

function encodeAsciiBase64(value) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let encoded = '';
  if (/[^\x00-\x7f]/.test(value)) throw new Error('Checkpoint payload must be ASCII JSON');
  for (let i = 0; i < value.length; i += 3) {
    const a = value.charCodeAt(i), b = i + 1 < value.length ? value.charCodeAt(i + 1) : 0;
    const c = i + 2 < value.length ? value.charCodeAt(i + 2) : 0;
    encoded += alphabet[a >>> 2] + alphabet[((a & 3) << 4) | (b >>> 4)]
      + (i + 1 < value.length ? alphabet[((b & 15) << 2) | (c >>> 6)] : '=')
      + (i + 2 < value.length ? alphabet[c & 63] : '=');
  }
  return encoded;
}

function sha256(bytes) {
  const K = new Int32Array([
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2,
  ]);
  const H = new Int32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]);
  const padded = new Uint8Array(Math.ceil((bytes.length + 9) / 64) * 64);
  padded.set(bytes);
  padded[bytes.length] = 0x80;
  const bitsHigh = Math.floor(bytes.length / 0x20000000), bitsLow = (bytes.length * 8) >>> 0;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, bitsHigh);
  view.setUint32(padded.length - 4, bitsLow);
  const W = new Int32Array(64);
  for (let offset = 0; offset < padded.length; offset += 64) {
    for (let i = 0; i < 16; i++) W[i] = view.getInt32(offset + i * 4);
    for (let i = 16; i < 64; i++) {
      const x = W[i - 15], y = W[i - 2];
      const s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
      const s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
      W[i] = (W[i - 16] + s0 + W[i - 7] + s1) | 0;
    }
    let [a,b,c,d,e,f,g,h] = H;
    for (let i = 0; i < 64; i++) {
      const s1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      const choice = (e & f) ^ (~e & g);
      const t1 = (h + s1 + choice + K[i] + W[i]) | 0;
      const s0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      const majority = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (s0 + majority) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
    H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
  }
  return Array.from(H, value => (value >>> 0).toString(16).padStart(8, '0')).join('');
}

if (typeof module !== 'undefined') module.exports = {decodeBase64, encodeAsciiBase64, sha256};
