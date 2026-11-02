// Binary local-worker RPC. Tensor bytes never become JSON numbers.
export const MAX_PACKET_BYTES=64*1024*1024;
const MAX_META_BYTES=1024*1024;
const TYPES={Uint8Array,Uint8ClampedArray,Int8Array,Uint16Array,Int16Array,Uint32Array,Int32Array,Float32Array,Float64Array,...(typeof BigInt64Array==='function'?{BigInt64Array,BigUint64Array}:{})};
export function encodePacket(value){
  const blocks=[],seen=new Set();let payloadBytes=0,nodes=0;
  function visit(input,depth){
    if(++nodes>200000||depth>64)throw new Error('RPC数据结构过大');
    if(input===null||typeof input==='string'||typeof input==='boolean')return input;
    if(typeof input==='number'){if(!Number.isFinite(input))throw new Error('RPC元数据包含无效数字');return input;}
    if(typeof input==='undefined')return{k:'undefined'};
    if(typeof input==='bigint')return{k:'bigint',v:String(input)};
    if(input instanceof ArrayBuffer||ArrayBuffer.isView(input)){
      let type,bytes;
      if(input instanceof ArrayBuffer){type='ArrayBuffer';bytes=new Uint8Array(input);}
      else{type=input instanceof DataView?'DataView':input.constructor.name;if(type!=='DataView'&&!TYPES[type])throw new Error('不支持的RPC数组类型：'+type);bytes=new Uint8Array(input.buffer,input.byteOffset,input.byteLength);}
      const offset=payloadBytes;payloadBytes+=bytes.byteLength;
      if(payloadBytes+4>MAX_PACKET_BYTES)throw new Error('RPC二进制数据超过64MiB');
      blocks.push(bytes);return{k:'binary',t:type,o:offset,n:bytes.byteLength};
    }
    if(typeof input!=='object')throw new Error('RPC不支持这个值类型');
    if(seen.has(input))throw new Error('RPC不支持循环引用');seen.add(input);
    let result;
    if(Array.isArray(input))result={k:'array',v:input.map(item=>visit(item,depth+1))};
    else{const values=Object.create(null);for(const[key,item]of Object.entries(input))values[key]=visit(item,depth+1);result={k:'object',v:values};}
    seen.delete(input);return result;
  }
  const metadata=new TextEncoder().encode(JSON.stringify({version:1,value:visit(value,0)}));
  if(metadata.byteLength>MAX_META_BYTES||metadata.byteLength+payloadBytes+4>MAX_PACKET_BYTES)throw new Error('RPC返回超过安全大小上限');
  const packet=new ArrayBuffer(4+metadata.byteLength+payloadBytes);new DataView(packet).setUint32(0,metadata.byteLength,true);
  const out=new Uint8Array(packet);out.set(metadata,4);let offset=4+metadata.byteLength;
  for(const block of blocks){out.set(block,offset);offset+=block.byteLength;}return packet;
}
export function decodePacket(input){
  const bytes=input instanceof ArrayBuffer?new Uint8Array(input):new Uint8Array(input.buffer,input.byteOffset,input.byteLength);
  if(bytes.byteLength<4||bytes.byteLength>MAX_PACKET_BYTES)throw new Error('RPC数据包大小无效');
  const length=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength).getUint32(0,true);
  if(length>MAX_META_BYTES||length+4>bytes.byteLength)throw new Error('RPC元数据长度无效');
  const meta=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes.subarray(4,4+length)));
  if(meta.version!==1)throw new Error('RPC数据版本不兼容');const dataStart=4+length;let nodes=0;
  function restore(value,depth){
    if(++nodes>200000||depth>64)throw new Error('RPC元数据嵌套过深');
    if(value===null||['string','number','boolean'].includes(typeof value))return value;
    if(!value||typeof value!=='object')throw new Error('RPC元数据无效');
    if(value.k==='undefined')return undefined;if(value.k==='bigint')return BigInt(value.v);
    if(value.k==='array'){if(!Array.isArray(value.v))throw new Error('RPC数组无效');return value.v.map(item=>restore(item,depth+1));}
    if(value.k==='object'){if(!value.v||typeof value.v!=='object'||Array.isArray(value.v))throw new Error('RPC对象无效');const out=Object.create(null);for(const[key,item]of Object.entries(value.v))out[key]=restore(item,depth+1);return out;}
    if(value.k==='binary'){
      if(!Number.isSafeInteger(value.o)||!Number.isSafeInteger(value.n)||value.o<0||value.n<0||dataStart+value.o+value.n>bytes.byteLength)throw new Error('RPC二进制块越界');
      const copy=bytes.slice(dataStart+value.o,dataStart+value.o+value.n).buffer;
      if(value.t==='ArrayBuffer')return copy;if(value.t==='DataView')return new DataView(copy);
      const Constructor=TYPES[value.t];if(!Constructor||value.n%Constructor.BYTES_PER_ELEMENT)throw new Error('RPC数组类型或对齐无效');return new Constructor(copy);
    }throw new Error('RPC元数据标签无效');
  }return restore(meta.value,0);
}
