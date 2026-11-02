package com.shipu.assistant.music;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** MusicXML partwise importer. No network access or filesystem extraction is performed. */
public final class MusicXmlParser {
    private static final int MAX_INPUT_BYTES = 24 * 1024 * 1024;
    private static final int MAX_XML_BYTES = 16 * 1024 * 1024;
    private static final int MAX_UNZIPPED_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 128;
    private static final int MAX_MEASURES = 5000;
    private static final int MAX_NOTES = 100000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private MusicXmlParser() {}

    public static Score parse(InputStream input, String filename) throws IOException {
        if (input == null) throw new IOException("无法读取乐谱文件");
        byte[] data = readLimited(input, MAX_INPUT_BYTES);
        if (data.length == 0) throw new IOException("乐谱文件为空");
        boolean zipped = data.length >= 4 && data[0] == 'P' && data[1] == 'K';
        if (zipped) data = readMxl(data);
        if (data.length > MAX_XML_BYTES) throw new IOException("MusicXML 文件过大（上限 16 MB）");
        return parseDocument(safeDocument(data), filename);
    }

    private static byte[] readMxl(byte[] zipped) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        int count = 0;
        int total = 0;
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipped));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ZIP_ENTRIES) throw new IOException("MXL 文件包含过多条目");
                String name = entry.getName().replace('\\', '/');
                if (!safeZipName(name)) throw new IOException("MXL 文件路径不安全");
                if (entry.isDirectory()) { zip.closeEntry(); continue; }
                if (entries.containsKey(name)) throw new IOException("MXL 文件包含重复条目");
                byte[] content = readLimited(zip, Math.min(MAX_XML_BYTES, MAX_UNZIPPED_BYTES - total));
                total += content.length;
                if (total > MAX_UNZIPPED_BYTES) throw new IOException("MXL 解压后过大");
                entries.put(name, content);
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
        byte[] container = entries.get("META-INF/container.xml");
        if (container != null) {
            Document document = safeDocument(container);
            NodeList rootfiles = document.getElementsByTagNameNS("*", "rootfile");
            for (int i = 0; i < rootfiles.getLength(); i++) {
                Element rootfile = (Element) rootfiles.item(i);
                String path = rootfile.getAttribute("full-path").replace('\\', '/');
                if (!safeZipName(path)) throw new IOException("MXL 主文件路径不安全");
                String media = rootfile.getAttribute("media-type");
                if (media.isEmpty() || media.contains("musicxml")) {
                    byte[] result = entries.get(path);
                    if (result == null) throw new IOException("MXL 文件缺少声明的主乐谱");
                    return result;
                }
            }
            throw new IOException("MXL 文件中没有 MusicXML 主乐谱");
        }
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (!name.startsWith("meta-inf/") && (name.endsWith(".musicxml") || name.endsWith(".xml"))) {
                return entry.getValue();
            }
        }
        throw new IOException("MXL 文件中没有找到乐谱");
    }

    private static boolean safeZipName(String name) {
        if (name.length() == 0 || name.startsWith("/") || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) return false;
        for (String component : name.split("/")) if (component.equals("..")) return false;
        return true;
    }

    private static byte[] readLimited(InputStream stream, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(8192, Math.max(0, max)));
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > max) throw new IOException("乐谱或解压内容超过大小限制");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static Document safeDocument(byte[] bytes) throws IOException {
        // MusicXML commonly has an external DOCTYPE. Accept it without loading its DTD,
        // but reject entity declarations, including internal expansion attacks.
        String preview = new String(bytes, UTF8);
        String inspected = preview.replace(String.valueOf((char) 0), ""); // Also catches UTF-16 entity declarations.
        if (inspected.contains("<!ENTITY")) throw new IOException("不支持含实体定义的 XML 文件");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            setFeature(factory, "http://javax.xml.XMLConstants/feature/secure-processing", true);
            try { factory.setXIncludeAware(false); } catch (UnsupportedOperationException ignored) {}
            try { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", ""); } catch (IllegalArgumentException ignored) {}
            try { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", ""); } catch (IllegalArgumentException ignored) {}
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(new EntityResolver() {
                @Override public InputSource resolveEntity(String publicId, String systemId) {
                    return new InputSource(new StringReader(""));
                }
            });
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception exception) {
            throw new IOException("无法解析 MusicXML：" + readable(exception), exception);
        }
    }

    private static void setFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try { factory.setFeature(feature, value); } catch (Exception ignored) {
            // Android XML implementations support different flags. Entity declarations are
            // rejected above and the resolver always supplies an empty, local DTD.
        }
    }

    private static String readable(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "文件格式有误" : message;
    }

    private static Score parseDocument(Document document, String filename) throws IOException {
        Element root = document.getDocumentElement();
        if (root == null || !is(root, "score-partwise")) {
            throw new IOException("仅支持 score-partwise 格式的 MusicXML 乐谱");
        }
        Score score = new Score();
        Element work = child(root, "work");
        String title = work == null ? "" : text(work, "work-title");
        if (title.isEmpty()) title = text(root, "movement-title");
        if (title.isEmpty() && filename != null) {
            title = filename.replaceAll("(?i)\\.(musicxml|mxl|xml)$", "");
        }
        if (!title.isEmpty()) score.title = title;
        Element identification = child(root, "identification");
        if (identification != null) {
            for (Element creator : children(identification, "creator")) {
                if (creator.getAttribute("type").equals("composer")) {
                    score.composer = value(creator);
                    break;
                }
            }
        }
        List<Element> parts = children(root, "part");
        if (parts.isEmpty()) throw new IOException("MusicXML 中没有可练习的声部");
        Element part = choosePart(root, parts);
        if (parts.size() > 1) {
            // OMR output often splits one piano page into several <part> entries.  homr
            // writes one part per staff position of every system and labels the upper
            // stave "Voice" (midi-program 54) while the lower stave becomes "Piano",
            // so a pure piano page looks like a duet.  Merge the parts that belong to
            // the same instrument instead of dropping everything but the first.
            List<Element> mergeParts = collectMergeParts(root, parts);
            if (mergeParts.size() > 1) {
                mergeMeasuresInto(part, mergeParts);
                warn(score, "已自动合并同一页的上下谱表。");
            } else {
                warn(score, "检测到多个乐器声部，当前仅导入优先选中的钢琴声部（无钢琴标记时选择第一声部）。");
            }
        }
        List<Element> measureElements = children(part, "measure");
        if (measureElements.size() > MAX_MEASURES) throw new IOException("乐谱小节过多（上限 5000）");
        double divisions = 1.0;
        int fifths = 0, beats = 4, beatType = 4, transpose = 0;
        double tempo = score.tempo;
        int noteCount = 0;
        for (int mi = 0; mi < measureElements.size(); mi++) {
            Element measureElement = measureElements.get(mi);
            Score.Measure measure = new Score.Measure(integer(measureElement.getAttribute("number"), mi + 1));
            measure.fifths = fifths;
            measure.beats = beats;
            measure.beatType = beatType;
            measure.tempo = tempo;
            measure.sourceMeasureIndex = mi;
            measure.sourceMeasureNumber = measure.number;
            double cursor = 0.0;
            double maxEnd = 0.0;
            double lastOnset = 0.0;
            Score.Event lastEvent = null;
            for (Element item : children(measureElement, null)) {
                if (is(item, "attributes")) {
                    String divisionsText = text(item, "divisions");
                    if (!divisionsText.isEmpty()) {
                        divisions = decimal(divisionsText, -1.0);
                        if (!finite(divisions) || divisions <= 0) throw new IOException("乐谱 divisions 参数无效");
                    }
                    Element key = child(item, "key");
                    if (key != null && !text(key, "fifths").isEmpty()) { fifths = integer(text(key, "fifths"), fifths); measure.keySpecified = true; }
                    Element time = child(item, "time");
                    if (time != null && !text(time, "beats").isEmpty()) {
                        beats = additiveBeats(text(time, "beats"));
                        beatType = integer(text(time, "beat-type"), 4);
                        if (beats <= 0 || beatType <= 0) throw new IOException("乐谱拍号无效");
                        measure.timeSpecified = true;
                    }
                    Element transposition = child(item, "transpose");
                    if (transposition != null) {
                        transpose = integer(text(transposition, "chromatic"), 0)
                                + 12 * integer(text(transposition, "octave-change"), 0);
                    }
                    measure.fifths = fifths;
                    measure.beats = beats;
                    measure.beatType = beatType;
                    if (mi == 0) { score.fifths = fifths; score.beats = beats; score.beatType = beatType; }
                } else if (is(item, "direction") || is(item, "sound")) {
                    double indicatedTempo = tempo(item);
                    if (finite(indicatedTempo) && indicatedTempo >= 1 && indicatedTempo <= 1000) {
                        double offset = is(item, "direction") ? decimal(text(item, "offset"), 0) / divisions : 0;
                        double onset = Math.max(0, cursor + (finite(offset) ? offset : 0));
                        measure.tempoChanges.add(new Score.TempoChange(onset, indicatedTempo));
                        if (onset < 0.000001) {
                            measure.tempoSpecified = true;
                            measure.tempo = indicatedTempo;
                            if (mi == 0) score.tempo = indicatedTempo;
                        }
                        tempo = indicatedTempo;
                    }
                } else if (is(item, "backup") || is(item, "forward")) {
                    double duration = duration(item, divisions);
                    cursor += is(item, "backup") ? -duration : duration;
                    if (cursor < -0.000001) warn(score, "部分声部时间位置异常，已将小节起点以前的位置归零，请校对。");
                    cursor = Math.max(0.0, cursor);
                    maxEnd = Math.max(maxEnd, cursor);
                    lastEvent = null;
                } else if (is(item, "note")) {
                    if (++noteCount > MAX_NOTES) throw new IOException("乐谱音符过多（上限 100000）");
                    boolean chord = child(item, "chord") != null;
                    boolean grace = child(item, "grace") != null;
                    double duration = grace ? 0.0 : duration(item, divisions);
                    double onset = chord && lastEvent != null ? lastOnset : cursor;
                    boolean rest = child(item, "rest") != null;
                    Element pitch = child(item, "pitch");
                    Score.Note note = null;
                    if (!rest && pitch != null) {
                        note = parsePitch(pitch, item, duration, transpose, score);
                    } else if (!rest) {
                        warn(score, "无固定音高的打击乐音符未加入钢琴练习。");
                    }
                    if (note != null) {
                        Score.Event event;
                        if (chord && lastEvent != null) {
                            event = lastEvent;
                        } else if (grace) {
                            event = new Score.Event(onset, duration);
                            event.grace = true;
                            measure.events.add(event);
                        } else {
                            event = findEvent(measure, onset);
                            if (event == null) {
                                event = new Score.Event(onset, duration);
                                measure.events.add(event);
                            }
                        }
                        event.duration = Math.max(event.duration, duration);
                        event.notes.add(note);
                        lastEvent = event;
                        lastOnset = onset;
                    } else if (!chord) {
                        lastEvent = null;
                        lastOnset = onset;
                    }
                    maxEnd = Math.max(maxEnd, onset + duration);
                    if (!chord) cursor += duration;
                } else if (is(item, "barline")) {
                    if (child(item, "repeat") != null || child(item, "ending") != null) {
                        warn(score, "当前按谱面的小节书写顺序练习，未自动展开反复记号与跳房。");
                    }
                }
            }
            Collections.sort(measure.events, new Comparator<Score.Event>() {
                @Override public int compare(Score.Event a, Score.Event b) {
                    int order = Double.compare(a.onset, b.onset);
                    if (order != 0) return order;
                    if (a.grace != b.grace) return a.grace ? -1 : 1;
                    return 0;
                }
            });
            measure.duration = maxEnd > 0 ? maxEnd : beats * 4.0 / beatType;
            score.measures.add(measure);
        }
        int playable = 0;
        for (Score.Measure measure : score.measures) {
            for (Score.Event event : measure.events) {
                for (Score.Note note : event.notes) if (!note.tiedContinuation) playable++;
            }
        }
        if (playable == 0) throw new IOException("乐谱中没有可用于钢琴练习的音符");
        return score;
    }

    private static Element choosePart(Element root, List<Element> parts) {
        Element list = child(root, "part-list");
        if (list != null) {
            for (Element description : children(list, "score-part")) {
                String name = text(description, "part-name").toLowerCase(java.util.Locale.ROOT);
                boolean piano = name.contains("piano") || name.contains("钢琴") || name.contains("pianoforte");
                Element midi = child(description, "midi-instrument");
                if (midi != null) {
                    int program = integer(text(midi, "midi-program"), -1);
                    piano |= program >= 1 && program <= 8;
                }
                if (piano) {
                    for (Element part : parts) {
                        if (part.getAttribute("id").equals(description.getAttribute("id"))) return part;
                    }
                }
            }
        }
        return parts.get(0);
    }

    /** Parts that should be merged into the chosen part to rebuild one practice score. */
    private static List<Element> collectMergeParts(Element root, List<Element> parts) {
        Element list = child(root, "part-list");
        if (list == null) return new ArrayList<Element>(parts);
        List<Element> descriptions = children(list, "score-part");
        if (descriptions.isEmpty() || looksLikeOmrStaffParts(descriptions)) {
            return new ArrayList<Element>(parts);
        }
        return collectPianoParts(parts);
    }

    /** True when the part list matches how homr splits one page by staff position:
     *  every part is called "Piano" or "Voice" with midi-program 1 or 54.  Such a page
     *  is a single piano score, so all parts are merged back together. */
    private static boolean looksLikeOmrStaffParts(List<Element> descriptions) {
        boolean sawPiano = false, sawVoice = false;
        for (Element description : descriptions) {
            String name = text(description, "part-name").trim().toLowerCase(java.util.Locale.ROOT);
            if (name.equals("piano")) sawPiano = true;
            else if (name.equals("voice")) sawVoice = true;
            else return false;
            Element midi = child(description, "midi-instrument");
            int program = midi == null ? -1 : integer(text(midi, "midi-program"), -1);
            if (program != -1 && program != 1 && program != 54) return false;
        }
        return sawPiano || sawVoice;
    }

    /** Collect every <part> whose score-part description identifies a keyboard instrument.
     *  A part-list entry is treated as keyboard if its part-name mentions piano/钢琴 or
     *  its midi-program is in the GM "Piano" family (1–8).  Parts without a recognisable
     *  keyboard tag (e.g. a violin + piano duet) are deliberately excluded so we do not
     *  merge an unrelated melody line into the practice score. */
    private static List<Element> collectPianoParts(List<Element> parts) {
        List<Element> result = new ArrayList<Element>();
        for (Element part : parts) {
            Element descriptor = partDescriptor(part);
            if (descriptor == null) { result.add(part); continue; }
            String name = text(descriptor, "part-name").toLowerCase(java.util.Locale.ROOT);
            boolean keyboard = name.contains("piano") || name.contains("钢琴") || name.contains("pianoforte");
            Element midi = child(descriptor, "midi-instrument");
            if (midi != null) {
                int program = integer(text(midi, "midi-program"), -1);
                keyboard |= program >= 1 && program <= 8;
            }
            if (keyboard) result.add(part);
        }
        if (result.isEmpty()) result.addAll(parts);
        return result;
    }

    private static Element partDescriptor(Element part) {
        Element root = part.getOwnerDocument().getDocumentElement();
        if (root == null) return null;
        Element list = child(root, "part-list");
        if (list == null) return null;
        String id = part.getAttribute("id");
        for (Element description : children(list, "score-part")) {
            if (description.getAttribute("id").equals(id)) return description;
        }
        return null;
    }

    /** Merge the notes of every part in {@code mergeParts} into {@code target}.  Measures
     *  are aligned by position because all parts of one OMR page cover the same systems;
     *  when the counts differ they are aligned by the measure {@code number} attribute.
     *  Structural children (attributes) are skipped so a merged part does not override
     *  the target's key/time signature.  When a source part only uses staff 1 while the
     *  target already occupies staff 1, the imported notes move to the next free staff
     *  number so upper and lower staves remain separate. */
    private static void mergeMeasuresInto(Element target, List<Element> mergeParts) {
        List<Element> targetMeasures = children(target, "measure");
        Map<Integer, Element> targetByNumber = new LinkedHashMap<Integer, Element>();
        for (Element measure : targetMeasures) {
            targetByNumber.put(integer(measure.getAttribute("number"), -1), measure);
        }
        Set<Integer> usedStaffs = staffNumbers(targetMeasures);
        for (Element sourcePart : mergeParts) {
            if (sourcePart == target) continue;
            List<Element> sourceMeasures = children(sourcePart, "measure");
            Set<Integer> sourceStaffs = staffNumbers(sourceMeasures);
            Integer remapped = null;
            if (sourceStaffs.size() == 1 && usedStaffs.containsAll(sourceStaffs)) {
                int oldStaff = sourceStaffs.iterator().next();
                int next = 1;
                for (Integer staff : usedStaffs) next = Math.max(next, staff + 1);
                if (next == oldStaff) next = oldStaff + 1;
                remapped = next;
                usedStaffs.add(next);
            } else {
                usedStaffs.addAll(sourceStaffs);
            }
            boolean alignedByIndex = sourceMeasures.size() == targetMeasures.size();
            for (int index = 0; index < sourceMeasures.size(); index++) {
                Element sourceMeasure = sourceMeasures.get(index);
                int number = integer(sourceMeasure.getAttribute("number"), -1);
                Element destination = alignedByIndex ? targetMeasures.get(index)
                        : number >= 0 ? targetByNumber.get(number) : null;
                if (destination == null) {
                    destination = (Element) target.getOwnerDocument().importNode(sourceMeasure, true);
                    if (remapped != null) remapStaffs(destination, remapped);
                    target.appendChild(destination);
                    if (number >= 0) targetByNumber.put(number, destination);
                    continue;
                }
                for (Element item : children(sourceMeasure, null)) {
                    if ("attributes".equals(item.getLocalName())) continue;
                    Element imported = (Element) target.getOwnerDocument().importNode(item, true);
                    if (remapped != null) remapStaffs(imported, remapped);
                    destination.appendChild(imported);
                }
            }
        }
    }

    /** Staff numbers used by the notes of these measures; 1 is the MusicXML default. */
    private static Set<Integer> staffNumbers(List<Element> measures) {
        Set<Integer> staffs = new LinkedHashSet<Integer>();
        for (Element measure : measures) {
            for (Element note : children(measure, "note")) {
                String staff = text(note, "staff");
                staffs.add(staff.isEmpty() ? 1 : integer(staff, 1));
            }
        }
        if (staffs.isEmpty()) staffs.add(1);
        return staffs;
    }

    private static void remapStaffs(Element scope, int staff) {
        if (is(scope, "staff")) { scope.setTextContent(Integer.toString(staff)); return; }
        for (Element child : children(scope, null)) remapStaffs(child, staff);
    }

    private static Score.Note parsePitch(Element pitch, Element noteElement, double duration,
                                         int transpose, Score score) throws IOException {
        String step = text(pitch, "step");
        int semitone;
        if (step.equals("C")) semitone = 0;
        else if (step.equals("D")) semitone = 2;
        else if (step.equals("E")) semitone = 4;
        else if (step.equals("F")) semitone = 5;
        else if (step.equals("G")) semitone = 7;
        else if (step.equals("A")) semitone = 9;
        else if (step.equals("B")) semitone = 11;
        else throw new IOException("无法识别的音名：" + step);
        int octave = integer(text(pitch, "octave"), Integer.MIN_VALUE);
        if (octave == Integer.MIN_VALUE) throw new IOException("音符缺少八度信息");
        double alteration = decimal(text(pitch, "alter"), 0);
        if (!finite(alteration) || alteration != Math.rint(alteration)) {
            warn(score, "检测到微分音，普通钢琴无法演奏，该音未加入练习。");
            return null;
        }
        if (octave < -1 || octave > 10 || alteration < -4 || alteration > 4) {
            warn(score, "部分音符超出普通钢琴的音域或升降号范围，未加入练习。");
            return null;
        }
        int alter = (int) alteration;
        int midi = (octave + 1) * 12 + semitone + alter + transpose;
        if (midi < 21 || midi > 108) {
            warn(score, "部分音符超出 88 键钢琴范围（A0–C8），未加入练习。");
            return null;
        }
        String accidental = alter == -2 ? "𝄫" : alter == -1 ? "♭" : alter == 1 ? "♯" : alter == 2 ? "𝄪" : "";
        String name = transpose == 0 ? step + accidental + octave : Score.noteName(midi);
        boolean tieStop = false, tieStart = false;
        for (Element tie : children(noteElement, "tie")) {
            tieStop |= tie.getAttribute("type").equals("stop"); tieStart |= tie.getAttribute("type").equals("start");
        }
        Element notations = child(noteElement, "notations");
        if (notations != null) {
            for (Element tie : children(notations, "tied")) {
                tieStop |= tie.getAttribute("type").equals("stop"); tieStart |= tie.getAttribute("type").equals("start");
            }
        }
        Score.Note note = new Score.Note(midi, Math.max(1, integer(text(noteElement, "staff"), 1)), name, tieStop);
        note.duration = duration;
        note.tiedStart = tieStart;
        note.voice = text(noteElement, "voice");
        return note;
    }

    private static double tempo(Element item) {
        Element sound = is(item, "sound") ? item : child(item, "sound");
        if (sound != null && !sound.getAttribute("tempo").isEmpty()) return decimal(sound.getAttribute("tempo"), Double.NaN);
        Element directionType = child(item, "direction-type");
        Element metronome = directionType == null ? null : child(directionType, "metronome");
        if (metronome == null) return Double.NaN;
        double bpm = decimal(text(metronome, "per-minute"), Double.NaN);
        String unit = text(metronome, "beat-unit");
        double quarters = unit.equals("whole") ? 4 : unit.equals("half") ? 2 : unit.equals("quarter") ? 1
                : unit.equals("eighth") ? 0.5 : unit.equals("16th") ? 0.25 : unit.equals("32nd") ? 0.125 : Double.NaN;
        double dotted = 1, addition = 0.5;
        for (Element ignored : children(metronome, "beat-unit-dot")) { dotted += addition; addition *= 0.5; }
        return bpm * quarters * dotted;
    }

    private static Score.Event findEvent(Score.Measure measure, double onset) {
        for (Score.Event event : measure.events) {
            if (!event.grace && Math.abs(event.onset - onset) < 0.000001) return event;
        }
        return null;
    }

    private static double duration(Element item, double divisions) throws IOException {
        String value = text(item, "duration");
        if (value.isEmpty()) return 0.0;
        double duration = decimal(value, -1) / divisions;
        if (!finite(duration) || duration < 0) throw new IOException("乐谱中的音符时值无效");
        return duration;
    }

    private static int additiveBeats(String value) {
        int result = 0;
        for (String part : value.split("\\+")) result += integer(part, 0);
        return result;
    }
    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return fallback; }
    }
    private static double decimal(String value, double fallback) {
        try { return Double.parseDouble(value.trim()); } catch (Exception ignored) { return fallback; }
    }
    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
    private static void warn(Score score, String warning) {
        if (!score.warnings.contains(warning)) score.warnings.add(warning);
    }
    private static String value(Element element) { return element.getTextContent().trim(); }
    private static String text(Element parent, String name) {
        Element result = child(parent, name);
        return result == null ? "" : value(result);
    }
    private static boolean is(Element element, String name) {
        String local = element.getLocalName();
        return name.equals(local == null ? element.getTagName() : local);
    }
    private static Element child(Element parent, String name) {
        for (Element element : children(parent, name)) return element;
        return null;
    }
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<Element>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && (name == null || is((Element) node, name))) {
                result.add((Element) node);
            }
        }
        return result;
    }
}
