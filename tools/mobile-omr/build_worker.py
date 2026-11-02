"""Build the pinned Partsong-derived bridge, retaining homr's piano grouping."""
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'app/src/main/assets/local-omr'
source=(OUT/'sources/partsong-worker.js').read_text(encoding='utf-8')
source=source.replace('const PYODIDE_URL = "https://cdn.jsdelivr.net/pyodide/v314.0.7/full/";','const BASE_URL = new URL("./", import.meta.url);\nconst PYODIDE_URL = new URL("pyodide/", BASE_URL).href;')
source=source.replace('const ORT_URL = "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.30.0/dist/";','const ORT_URL = new URL("ort/", BASE_URL).href;')
start=source.index('// Models download in 4 MB pieces')
end=source.index('const TYPED =',start)
source=source[:start]+'''// All model bytes are bundled in the signed APK. No CDN or remote fallback.
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
''' +source[end:]
source=source.replace('if (typeof WebAssembly.Suspending !== "function") {','if (typeof WebAssembly.Suspending !== "function" || typeof WebAssembly.promising !== "function") {')
source=source.replace('This browser lacks WebAssembly JSPI. Use an up-to-date Chrome.','系统 WebView 不支持离线识谱所需的 WebAssembly JSPI。请更新 Android System WebView / Chrome（137及以上）后重试。')
source=source.replace('await pyodide.loadPackage(["numpy", "opencv-python", "pillow", "typing-extensions"]);','post({type:"progress",stage:"packages"});\n  await pyodide.loadPackage(["numpy", "opencv-python", "pillow", "typing-extensions"]);')
source=source.replace('await loadPyodide({ indexURL: PYODIDE_URL })','await loadPyodide({ indexURL: PYODIDE_URL, packageBaseUrl: PYODIDE_URL })')
source=source.replace('fetch("vendor/homr.zip" + v)','fetch(new URL("homr.zip", BASE_URL))')
source=source.replace('fetch("omr/" + f + v)','fetch(new URL("python/" + f, BASE_URL))')
source=source.replace('fetch(`omr/${f}${v}&fresh=${Date.now()}`, { cache: "reload" })','fetch(new URL("python/" + f, BASE_URL), { cache: "reload" })')
source=source.replace('  for (const name of MODELS) {\n    await downloadModel(name);\n  }','  post({type:"progress",stage:"models",text:"离线模型已随安装包提供，将按需加载"});')
source=source.replace('post({ type: "ready", gpu: useGpu });','post({ type: "ready", gpu: useGpu, offline: true, engine: "homr-465" });')
start=source.index('let ready;\nself.onmessage')
source=source[:start]+'''let ready;
let running = false;
let pageCounter = 0;
self.onmessage = async ({ data }) => {
  try {
    if (data.type === "init") {
      ready ??= init();
      await ready;
    } else if (data.type === "recognise") {
      if (running) throw new Error("请等待当前页识别完成");
      running = true;
      ready ??= init();
      await ready;
      const image = new Uint8Array(data.image);
      if (!image.length || image.length > 20 * 1024 * 1024) throw new Error("页面图片为空或超过20MB");
      const path = `/tmp/score-page-${++pageCounter}.png`;
      try {
        pyodide.FS.writeFile(path, image);
        const t0 = performance.now();
        runTimes = [];
        const xml = await recognise.callPromising(path, "");
        const ms = Math.round(performance.now() - t0);
        const inModels = runTimes.reduce((sum, t) => sum + (t?.ms ?? 0), 0);
        post({ type: "log", msg: `Page in ${ms} ms; models ${Math.round(inModels)} ms` });
        post({ type: "result", id: data.id, xml, ms, offline:true });
      } finally {
        try { pyodide.FS.unlink(path); } catch (_) { }
      }
    }
  } catch (e) {
    post({ type: "error", id: data.id, msg: String(e && e.message ? e.message : e) });
  } finally {
    if (data.type === "recognise") running = false;
  }
};
'''
source='// Adapted from Partsong, AGPL-3.0. See sources/PARTSONG-LICENSE.txt.\n'+source
(OUT/'worker.js').write_text(source,encoding='utf-8')

original=(OUT/'sources/partsong-runner.py').read_text(encoding='utf-8')
# Reuse runtime stubs, generic invalid-zero-rest guard and decoder JSPI bridge;
# do not include choir-specific same-clef splitting, voice assignment or key edits.
runner=original[:original.index('# homr\'s model tags')]
generic_start=original.index('# The model sometimes predicts a grace note')
generic_end=original.index('# homr merges overlapping shapes',generic_start)
runner+=original[generic_start:generic_end]
decoder_start=original.index('# homr\'s decoder runs a greedy loop')
decoder_end=original.index('# Skip writing the preview PNG',decoder_start)
runner+='from homr.transformer.vocabulary import EncodedSymbol\n'+original[decoder_start:decoder_end]
config_start=original.index('# Skip writing the preview PNG')
config_end=original.index('def recognise(',config_start)
runner+=original[config_start:config_end]
# A piano system is one ten-line Staff, not an independent orchestral part.
# Normalize before homr's voice-count heuristic; never trim a whole detected row.
runner+='''
from homr import staff_parsing
import homr.main as piano_main
from homr.model import MultiStaff, Staff, StaffPoint
from piano_grouping import normalize_piano_staffs, recover_five_line_staffs
_piano_layout_events = []

def layout_report():
    return json.dumps(_piano_layout_events, ensure_ascii=False)

_detect_piano_staffs = piano_main.detect_staff

def _recover_piano_staffs(debug, mask, *args, **kwargs):
    detected = _detect_piano_staffs(debug, mask, *args, **kwargs)
    return recover_five_line_staffs(mask, detected, Staff, StaffPoint, _piano_layout_events.append)

piano_main.detect_staff = _recover_piano_staffs

def _normalize_piano_layout(rows):
    return normalize_piano_staffs(rows, MultiStaff, _piano_layout_events.append)

staff_parsing._ensure_same_number_of_staffs = _normalize_piano_layout
_parse_piano_staff_image = staff_parsing.parse_staff_image

def _checked_piano_staff_image(debug, index, staff, *args, **kwargs):
    result = _parse_piano_staff_image(debug, index, staff, *args, **kwargs)
    _piano_layout_events.append({"event": "piano-system-decoded", "system": index,
                                "symbols": len(result), "detectedNotes": staff.get_number_of_notes()})
    if not result and staff.get_number_of_notes() > 0:
        raise ValueError("有一行检测到音符却未能解码，请使用清晰的完整谱表重新识别")
    return result

staff_parsing.parse_staff_image = _checked_piano_staff_image

'''
runner+='''def recognise(image_path, systems_json=""):
    """Recognise every piano system in physical page order."""
    import os
    _piano_layout_events.clear()
    xml_path = process_image(image_path, _CONFIG, XmlGeneratorArguments())
    try:
        root = ET.parse(xml_path).getroot()
        parts = root.findall("part")
        if len(parts) != 1:
            # Not fatal: the Java importer merges parts that still share one piano page.
            _piano_layout_events.append({"event": "piano-layout-unresolved", "parts": len(parts)})
        ident = root.find("identification")
        if ident is None:
            ident = ET.SubElement(root, "identification")
        metadata = ET.SubElement(ident, "miscellaneous")
        ET.SubElement(metadata, "miscellaneous-field", name="shipu-piano-layout-v1").text = json.dumps(_piano_layout_events, ensure_ascii=False)
        return ET.tostring(root, encoding="unicode")
    finally:
        try:
            os.remove(xml_path)
        except OSError:
            pass

def recognise_profiled(image_path, systems_json=""):
    return recognise(image_path, systems_json)
'''
(OUT/'python').mkdir(parents=True,exist_ok=True)
(OUT/'python/runner.py').write_text(runner,encoding='utf-8')
(OUT/'python/onnxruntime.py').write_text((OUT/'sources/partsong-onnxruntime.py').read_text(encoding='utf-8'),encoding='utf-8')
print('Prepared worker.js and piano-preserving runner.py')

# Compatibility transport: ORT and Python run in separate workers. Python makes
# a synchronous, same-origin XHR which the Android host resolves from the other
# worker's binary result. Neither JSPI nor SharedArrayBuffer is required.
import ast
import shutil
prefix=source[:source.index('let pyodide;')]
prefix=prefix.replace('// Python blocks on model calls through JSPI, so this needs a JSPI-capable\n// browser (Chrome/Edge 137+).','// ORT runs alone in a worker; Python calls it through local binary RPC.')
infer="import {encodePacket} from './rpc-codec.js';\n"+prefix+'''
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
'''
(OUT/'inference-worker.js').write_text(infer,encoding='utf-8')
for name in ['worker.js','rpc-codec.js','bootstrap.js']:
    shutil.copyfile(Path(__file__).resolve().parent/'templates'/name,OUT/name)
shutil.copyfile(Path(__file__).resolve().parent/'templates/piano_grouping.py',OUT/'python/piano_grouping.py')

class SynchronousRpc(ast.NodeTransformer):
    def visit_Call(self,node):
        self.generic_visit(node)
        if isinstance(node.func,ast.Name) and node.func.id=='run_sync':
            if len(node.args)!=1 or node.keywords: raise ValueError('Unexpected run_sync signature')
            return node.args[0]
        return node
    def visit_ImportFrom(self,node):
        if node.module=='pyodide.ffi':
            node.names=[alias for alias in node.names if alias.name!='run_sync']
            if not node.names:return None
        return node
for path in [OUT/'python/runner.py',OUT/'python/onnxruntime.py']:
    tree=ast.parse(path.read_text(encoding='utf-8'))
    tree=SynchronousRpc().visit(tree);ast.fix_missing_locations(tree)
    if path.name=='onnxruntime.py' and isinstance(tree.body[0],ast.Expr) and isinstance(tree.body[0].value,ast.Constant):
        tree.body[0].value.value='''Minimal onnxruntime adapter inside Pyodide.

Model calls cross a local synchronous-XHR binary RPC to an independent ORT
worker. The Android host resolves the local response. No JSPI, run_sync,
SharedArrayBuffer, remote server or numeric JSON tensor serialization is used.
'''
    path.write_text(ast.unparse(tree)+'\n',encoding='utf-8')
print('Prepared native-rpc workers and synchronous Python adapter (no JSPI/SAB)')
