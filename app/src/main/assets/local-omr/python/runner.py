"""Runs homr on one page image inside Pyodide and returns its MusicXML."""
VERSION = 'dev'
import json
import sys
import types

def _stub(name, **attrs):
    module = types.ModuleType(name)
    module.__dict__.update(attrs)
    sys.modules[name] = module
_stub('rapidocr', RapidOCR=type('RapidOCR', (), {}))
_stub('pypdfium2')
_stub('requests', exceptions=types.SimpleNamespace(RequestException=Exception))
import xml.etree.ElementTree as ET
from homr import music_xml_generator
from homr.debug import Debug
from homr.main import ProcessingConfig, process_image
from homr.music_xml_generator import XmlGeneratorArguments
_build_note_chord = music_xml_generator.build_note_chord
_no_pitch = (music_xml_generator.empty, music_xml_generator.nonote)

def _build_note_chord_without_zero_rests(note_chord, state, chord_duration):
    groups = music_xml_generator._group_notes(note_chord.symbols)
    zero_rests = [s for s in groups.get(0, []) if s.pitch in _no_pitch]
    if zero_rests:
        kept = [s for s in note_chord.symbols if s not in zero_rests]
        if not kept:
            return []
        note_chord = music_xml_generator.SymbolChord(kept, note_chord.tuplet_mark)
    return _build_note_chord(note_chord, state, chord_duration)
music_xml_generator.build_note_chord = _build_note_chord_without_zero_rests
from homr.transformer.vocabulary import EncodedSymbol
from homr.transformer import decoder_inference
_generate = decoder_inference.ScoreDecoder.generate

def _generate_in_js(self, start_tokens, nonote_tokens, **kwargs):
    if self.fp16 or not hasattr(self.net, '_id'):
        return _generate(self, start_tokens, nonote_tokens, **kwargs)
    import numpy as np
    from onnxruntime import _to_js_bytes
    from pyodide.ffi import to_js
    import js
    context = np.ascontiguousarray(kwargs['context'], dtype=np.float32)
    heads = self.config.decoder_heads
    result = js.omrOrt.generate(self.net._id, to_js(list(context.shape)), _to_js_bytes(context), int(np.asarray(start_tokens).reshape(-1)[-1]), int(np.asarray(nonote_tokens).reshape(-1)[-1]), int(self.eos_token), int(self.max_seq_len), heads, self.config.decoder_dim // heads, self.config.decoder_depth * 4)
    tokens = np.frombuffer(result.tokens.to_bytes(), dtype=np.int32).reshape(-1, 6).astype(np.int64)
    dims = list(result.attentionDims.to_py())
    attention = np.frombuffer(result.attention.to_bytes(), dtype=np.float32).reshape([len(tokens), *dims])
    detokenize = decoder_inference.detokenize
    symbols = []
    for (rhythm, pitch, lift, position, articulation, slur), att in zip(tokens, attention):
        symbols.append(EncodedSymbol(rhythm=detokenize(np.array([[rhythm]]), self.inv_rhythm_vocab)[0], pitch=detokenize(np.array([[pitch]]), self.inv_pitch_vocab)[0], lift=detokenize(np.array([[lift]]), self.inv_lift_vocab)[0], articulation=detokenize(np.array([[articulation]]), self.inv_articulation_vocab)[0], slur=detokenize(np.array([[slur]]), self.inv_slur_vocab)[0], position=detokenize(np.array([[position]]), self.inv_position_vocab)[0], coordinates=att))
    return symbols
decoder_inference.ScoreDecoder.generate = _generate_in_js
Debug.write_teaser = lambda *args, **kwargs: None
_CONFIG = ProcessingConfig(enable_debug=False, enable_cache=False, write_staff_positions=False, read_staff_positions=False, selected_staff=-1, transformer_use_gpu=False, segnet_use_gpu=False, coreml_encoder=False, title_detection=False)
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
    width = float(staff.max_x) - float(staff.min_x)
    height = float(staff.max_y) - float(staff.min_y)
    if width < 80 or height < 20:
        # A barline block can slip through as a tiny "system"; homr would crop it
        # and cv2.resize would fail with inv_scale_x > 0, aborting the page.
        _piano_layout_events.append({'event': 'piano-system-skipped', 'system': index,
                                     'reason': 'crop too small',
                                     'width': round(width, 1), 'height': round(height, 1)})
        return []
    result = _parse_piano_staff_image(debug, index, staff, *args, **kwargs)
    _piano_layout_events.append({'event': 'piano-system-decoded', 'system': index, 'symbols': len(result), 'detectedNotes': staff.get_number_of_notes()})
    if not result and staff.get_number_of_notes() > 0:
        raise ValueError('有一行检测到音符却未能解码，请使用清晰的完整谱表重新识别')
    return result
staff_parsing.parse_staff_image = _checked_piano_staff_image

def recognise(image_path, systems_json=''):
    """Recognise every piano system in physical page order."""
    import os
    _piano_layout_events.clear()
    xml_path = process_image(image_path, _CONFIG, XmlGeneratorArguments())
    try:
        root = ET.parse(xml_path).getroot()
        parts = root.findall('part')
        if len(parts) != 1:
            _piano_layout_events.append({'event': 'piano-layout-unresolved', 'parts': len(parts)})
        ident = root.find('identification')
        if ident is None:
            ident = ET.SubElement(root, 'identification')
        metadata = ET.SubElement(ident, 'miscellaneous')
        ET.SubElement(metadata, 'miscellaneous-field', name='shipu-piano-layout-v1').text = json.dumps(_piano_layout_events, ensure_ascii=False)
        return ET.tostring(root, encoding='unicode')
    finally:
        try:
            os.remove(xml_path)
        except OSError:
            pass

def recognise_profiled(image_path, systems_json=''):
    return recognise(image_path, systems_json)
