import {encodePacket} from './rpc-codec.js';
// Adapted from Partsong, AGPL-3.0. See sources/PARTSONG-LICENSE.txt.
// Module worker. Runs homr (Python, via Pyodide) with its ONNX models on onnxruntime-web.
// ORT runs alone in a worker; Python calls it through local binary RPC.

const BASE_URL = new URL("./", import.meta.url);
const PYODIDE_URL = new URL("pyodide/", BASE_URL).href;
const ORT_URL = new URL("ort/", BASE_URL).href;
const MODEL_CACHE = "partscanner-models-v1";
const MODELS = [
  "segnet_308-3296ccd40960f90ca6ab9c035cca945675d30a0f.onnx",
  "encoder_pytorch_model_465-597144cab54c8f6d0f6c9619df5c5312694eadd6.onnx",
  "decoder_pytorch_model_465-597144cab54c8f6d0f6c9619df5c5312694eadd6.onnx",
];
// Uncompressed total, for progress: servers may gzip, so content-length
// doesn't match the bytes received.
const MODELS_TOTAL_BYTES = 157482318;
// The decoder runs hundreds of tiny steps per staff, where WebGPU's per-call
// overhead loses to WASM; the big convolutional models gain from the GPU.
const GPU_MODELS = new Set([MODELS[0], MODELS[1]]);

let ort;

const post = (msg) => self.postMessage(msg);
const sessions = [];
const sessionNames = []; // id -> model name
const onGpu = new Set(); // ids of sessions created on WebGPU
const sessionIds = new Map();
let useGpu = false;

// All model bytes are bundled in the signed APK. No CDN or remote fallback.
const MODEL_SIZES = {
  [MODELS[0]]: 57311361,
  [MODELS[1]]: 52861122,
  [MODELS[2]]: 47309835,
};
async function modelBytes(name) {
  if (!MODELS.includes(name)) throw new Error("未知离线模型：" + name);
  post({type:"progress",stage:"models",name});
  const response = await fetch(new URL("models/" + name, BASE_URL));
  if (!response.ok) throw new Error("离线模型无法读取：" + name);
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (bytes.byteLength !== MODEL_SIZES[name]) throw new Error("离线模型文件不完整：" + name);
  return bytes;
}
const TYPED = {
  float32: Float32Array,
  int64: BigInt64Array,
  int32: Int32Array,
  uint8: Uint8Array,
  bool: Uint8Array,
};

const kept = new Map(); // handle -> ort.Tensor
let lastKept = 0;

async function newSession(id) {
  const name = sessionNames[id];
  const gpu = useGpu && GPU_MODELS.has(name);
  const eps = gpu ? ["webgpu", "wasm"] : ["wasm"];
  const t0 = performance.now();
  const session = await ort.InferenceSession.create(await modelBytes(name), {
    executionProviders: eps,
    graphOptimizationLevel: "all",
  });
  if (gpu) onGpu.add(id);
  else onGpu.delete(id);
  post({ type: "log", msg: `Loaded ${name} on ${eps[0]} in ${Math.round(performance.now() - t0)} ms` });
  return session;
}

// Called from omr/onnxruntime.py.
self.omrOrt = {
  async create(name) {
    if (!sessionIds.has(name)) {
      const id = sessions.length;
      sessionNames[id] = name;
      sessions[id] = await newSession(id);
      sessionIds.set(name, id);
    }
    const s = sessions[sessionIds.get(name)];
    return { id: sessionIds.get(name), inputNames: s.inputNames, outputNames: s.outputNames };
  },

  // feeds: [name, type, dims, bytes], or [name, "kept", handle] for an output
  // kept from an earlier run. Outputs named in `keep` stay here as handles
  // (homr's decoder cache: Python only passes it back in on the next step, so
  // copying it across every step was most of the decoder's time). A kept
  // tensor is released once it has been passed back in.
  async run(id, names, feeds, keep = []) {
    const t0 = performance.now();
    const inputs = {};
    const used = [];
    for (const [name, type, dims, bytes] of feeds) {
      if (type === "kept") {
        inputs[name] = kept.get(dims);
        used.push(dims);
      } else inputs[name] = new ort.Tensor(type, new TYPED[type](bytes.slice().buffer), dims);
    }
    let out;
    try {
      out = await sessions[id].run(inputs, names);
    } catch (e) {
      // The phone's GPU can go away (e.g. under memory pressure); onnxruntime's
      // WebGPU backend then fails every run. Carry on without the GPU.
      if (!onGpu.has(id)) throw e;
      post({ type: "log", msg: `WebGPU run failed (${e.message}); using WASM from now on` });
      useGpu = false;
      for (const other of [...onGpu]) {
        const old = sessions[other];
        Promise.resolve().then(() => old.release()).catch(() => {});
        sessions[other] = await newSession(other);
      }
      out = await sessions[id].run(inputs, names);
    }
    for (const h of used) kept.delete(h);
    const t = (runTimes[id] ??= { calls: 0, ms: 0 });
    t.calls++;
    t.ms += performance.now() - t0;
    return names.map((n) => {
      const t = out[n];
      if (keep.includes(n)) {
        kept.set(++lastKept, t);
        return { type: t.type, dims: t.dims, handle: lastKept };
      }
      const d = t.data;
      return { type: t.type, dims: t.dims, data: new Uint8Array(d.buffer, d.byteOffset, d.byteLength), handle: 0 };
    });
  },

  // homr's greedy decoding loop for one staff (homr/transformer/
  // decoder_inference.py ScoreDecoder.generate), run here so the hundreds of
  // steps per staff don't each cross into Python and back. Returns, per step,
  // the chosen token of each head (rhythm, pitch, lift, position, articulation,
  // slur) and the attention output; the step whose rhythm is EOS isn't included.
  async generate(id, contextDims, contextBytes, start, nonote, eos, maxLen, heads, headDim, caches) {
    const t0 = performance.now();
    const session = sessions[id];
    const context = new ort.Tensor("float32", new Float32Array(contextBytes.slice().buffer), contextDims);
    // After the first step homr passes only the context's first position.
    const reduced = new ort.Tensor("float32", context.data.slice(0, contextDims[2]), [contextDims[0], 1, contextDims[2]]);
    const token = (v) => new ort.Tensor("int64", BigInt64Array.of(BigInt(v)), [1, 1]);
    const heads6 = ["out_rhythms", "out_pitchs", "out_lifts", "out_positions", "out_articulations", "out_slurs"];
    const names = [...heads6, "attention", ...Array.from({ length: caches }, (_, i) => `cache_out${i}`)];
    let cache = Array.from({ length: caches }, () => new ort.Tensor("float32", new Float32Array(0), [1, heads, 0, headDim]));
    let last = { rhythm: start, pitch: nonote, lift: nonote, articulation: nonote, slur: nonote };
    const tokens = [];
    const attention = [];
    let attentionDims = [];
    let steps = 0;
    for (let step = 0; step < maxLen; step++) {
      const feeds = {
        rhythms: token(last.rhythm),
        pitchs: token(last.pitch),
        lifts: token(last.lift),
        articulations: token(last.articulation),
        slurs: token(last.slur),
        context: step === 0 ? context : reduced,
        cache_len: new ort.Tensor("int64", BigInt64Array.of(BigInt(step)), [1]),
      };
      cache.forEach((t, i) => (feeds[`cache_in${i}`] = t));
      const out = await session.run(feeds, names);
      steps++;
      cache = names.slice(7).map((n) => out[n]);
      // Greedy: the most likely token at the last position of each head.
      const pick = (n) => {
        const t = out[n];
        const size = t.dims.at(-1);
        const d = t.data;
        let best = d.length - size;
        for (let k = best + 1; k < d.length; k++) if (d[k] > d[best]) best = k;
        return best - (d.length - size);
      };
      const chosen = heads6.map(pick);
      if (chosen[0] === eos) break;
      tokens.push(...chosen);
      const a = out.attention;
      attentionDims = a.dims;
      attention.push(Float32Array.from(a.data));
      last = { rhythm: chosen[0], pitch: chosen[1], lift: chosen[2], articulation: chosen[4], slur: chosen[5] };
    }
    const t = (runTimes[id] ??= { calls: 0, ms: 0 });
    t.calls += steps;
    t.ms += performance.now() - t0;
    const flat = new Float32Array(attention.reduce((n, a) => n + a.length, 0));
    attention.reduce((at, a) => (flat.set(a, at), at + a.length), 0);
    return { tokens: Int32Array.from(tokens), attention: new Uint8Array(flat.buffer), attentionDims };
  },

  // The contents of a kept output, if Python does want to read one.
  data(handle) {
    const d = kept.get(handle).data;
    return new Uint8Array(d.buffer, d.byteOffset, d.byteLength);
  },
};


let runTimes=[];
let ortReady;
async function loadOrt(){
  ortReady ??= (async()=>{
    ort=await import(ORT_URL+'ort.wasm.min.mjs');
    ort.env.wasm.wasmPaths=ORT_URL;
    ort.env.wasm.numThreads=1;
    ort.env.wasm.proxy=false;
    useGpu=false;
    post({type:'log',msg:'本机兼容推理：CPU/WASM，单线程，不使用JSPI或SharedArrayBuffer'});
  })();
  return ortReady;
}
let calls=Promise.resolve();
async function handleCall(data){
  const {runId,callId,method,args}=data;
  if(typeof runId!=='string'||!/^c[1-9][0-9]{0,7}$/.test(callId))throw new Error('无效的本地推理调用');
  if(!['echo','create','run','generate','data'].includes(method)||!Array.isArray(args))throw new Error('不支持的本地推理方法');
  let response;
  try{
    if(method==='echo')response={ok:true,value:args[0]};
    else{await loadOrt();response={ok:true,value:await self.omrOrt[method](...args)};}
  }catch(error){response={ok:false,error:String(error&&error.message?error.message:error).slice(0,4000)};}
  let packet;
  try{packet=encodePacket(response);}catch(error){packet=encodePacket({ok:false,error:String(error.message||error).slice(0,4000)});}
  self.postMessage({type:'inferenceResult',runId,callId,packet},[packet]);
}
self.onmessage=({data})=>{
  if(data.type!=='call')return;
  calls=calls.then(()=>handleCall(data)).catch(error=>post({type:'error',runId:data.runId,callId:data.callId,msg:String(error.message||error)}));
};
post({type:'inferenceReady',transport:'native-rpc'});
