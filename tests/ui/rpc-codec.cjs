// Exercise the production binary transport with real tensor-sized payloads.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const source = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/local-omr/rpc-codec.js'), 'utf8');
  const { encodePacket, decodePacket, MAX_PACKET_BYTES } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
  const bytes = new Uint8Array(20 * 1024 * 1024 + 17);
  for (let offset = 0; offset < bytes.length; offset += 997) bytes[offset] = offset % 251;
  const original = { ok: true, value: { raw: bytes, floats: new Float32Array([0, -1.5, Infinity, NaN]), dims: [8, 256, 256, 3], ints: new Int32Array([-7, 2147483647]), big: new BigInt64Array([1n, -9007199254740993n]), zero: new Uint8Array(), missing: undefined } };
  const packet = encodePacket(original);
  assert(packet.byteLength > 20 * 1024 * 1024, 'must exceed old 16MiB bridge limit');
  const result = decodePacket(packet);
  assert.equal(result.ok, true);
  assert(result.value.raw instanceof Uint8Array);
  assert.deepEqual(result.value.raw, bytes);
  assert.deepEqual(result.value.floats, original.value.floats);
  assert.deepEqual(result.value.ints, original.value.ints);
  assert.deepEqual(result.value.big, original.value.big);
  assert.equal(result.value.zero.length, 0);
  assert.equal(result.value.missing, undefined);
  assert.deepEqual(result.value.dims, original.value.dims);
  // Typed-array slices must transmit only the view, not unrelated buffer bytes.
  const sliced = new Uint16Array([1, 2, 3, 4]).subarray(1, 3);
  assert.deepEqual(decodePacket(encodePacket(sliced)), new Uint16Array([2, 3]));
  assert.deepEqual(new Uint8Array(decodePacket(encodePacket(new Uint8Array([3, 9]).buffer))), new Uint8Array([3, 9]));
  const unsafeKeys = JSON.parse('{"__proto__":{"polluted":true},"constructor":"preserved"}');
  const safe = decodePacket(encodePacket(unsafeKeys));
  assert.equal(Object.getPrototypeOf(safe), null);
  assert.equal(safe.__proto__.polluted, true);
  assert.equal({}.polluted, undefined);
  const cyclic = {}; cyclic.self = cyclic;
  assert.throws(() => encodePacket(cyclic), /循环/);
  assert.throws(() => encodePacket({ n: Infinity }), /无效数字/);
  assert.throws(() => encodePacket(new Uint8Array(MAX_PACKET_BYTES)), /64MiB/);
  assert.throws(() => decodePacket(new ArrayBuffer(3)), /大小无效/);
  assert.throws(() => decodePacket(packet.slice(0, packet.byteLength - 1)), /越界/);
  const invalidLength = packet.slice(0); new DataView(invalidLength).setUint32(0, 0xffffffff, true);
  assert.throws(() => decodePacket(invalidLength), /长度无效/);
  console.log(JSON.stringify({ passed: true, productionCodec: true, tensorPacketBytes: packet.byteLength, exceeds16MiB: true, maxPacketBytes: MAX_PACKET_BYTES, preservesTypesAndSlices: true, rejectsTruncationAndOversize: true }));
})().catch(error => { console.error(error); process.exitCode = 1; });
