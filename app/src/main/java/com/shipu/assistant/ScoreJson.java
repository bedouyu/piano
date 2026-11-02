package com.shipu.assistant;

import com.shipu.assistant.music.Score;
import org.json.JSONArray;
import org.json.JSONObject;

final class ScoreJson {
    static JSONObject encode(Score score) throws Exception {
        JSONObject out = new JSONObject();
        out.put("title", score.title).put("composer", score.composer);
        out.put("fifths", score.fifths).put("beats", score.beats).put("beatType", score.beatType).put("tempo", score.tempo);
        out.put("warnings", new JSONArray(score.warnings));
        JSONArray measures = new JSONArray();
        for (Score.Measure measure : score.measures) {
            JSONObject m = new JSONObject().put("number", measure.number).put("fifths", measure.fifths)
                .put("beats", measure.beats).put("beatType", measure.beatType).put("duration", measure.duration)
                .put("tempo", measure.tempo).put("sourcePage", measure.sourcePage).put("sourceMeasureIndex", measure.sourceMeasureIndex)
                .put("sourceMeasureNumber", measure.sourceMeasureNumber).put("keySpecified", measure.keySpecified)
                .put("timeSpecified", measure.timeSpecified).put("tempoSpecified", measure.tempoSpecified);
            JSONArray tempos = new JSONArray();
            for (Score.TempoChange tempo : measure.tempoChanges) tempos.put(new JSONObject().put("onset", tempo.onset).put("bpm", tempo.bpm));
            m.put("tempoChanges", tempos);
            JSONArray events = new JSONArray();
            for (Score.Event event : measure.events) {
                JSONObject e = new JSONObject().put("onset", event.onset).put("duration", event.duration).put("grace", event.grace);
                JSONArray notes = new JSONArray();
                for (Score.Note note : event.notes) notes.put(new JSONObject().put("midi", note.midi)
                    .put("staff", note.staff).put("name", note.name).put("duration", note.duration).put("tiedContinuation", note.tiedContinuation)
                    .put("tiedStart", note.tiedStart).put("voice", note.voice));
                e.put("notes", notes); events.put(e);
            }
            m.put("events", events); measures.put(m);
        }
        return out.put("measures", measures);
    }

    static Score decode(JSONObject input) throws Exception {
        Score score = new Score();
        score.title = input.optString("title", "我的乐谱"); score.composer = input.optString("composer", "");
        score.fifths = input.optInt("fifths", 0); score.beats = input.optInt("beats", 4); score.beatType = input.optInt("beatType", 4);
        score.tempo = validTempo(input.optDouble("tempo", 120));
        JSONArray warnings = input.optJSONArray("warnings");
        if (warnings != null) for (int i = 0; i < warnings.length(); i++) score.warnings.add(warnings.getString(i));
        JSONArray measures = input.getJSONArray("measures");
        if (measures.length() > 2000) throw new Exception("乐谱过长，请分段导入");
        int noteCount = 0;
        for (int mi = 0; mi < measures.length(); mi++) {
            JSONObject m = measures.getJSONObject(mi); Score.Measure measure = new Score.Measure();
            measure.number = m.optInt("number", mi + 1);
            measure.fifths = m.optInt("fifths", score.fifths); measure.beats = m.optInt("beats", score.beats);
            measure.beatType = m.optInt("beatType", score.beatType); measure.duration = m.optDouble("duration", 4);
            if (!Double.isFinite(measure.duration) || measure.duration < 0 || measure.duration > 1024) throw new Exception("小节时长无效");
            measure.tempo = validTempo(m.optDouble("tempo", score.tempo));
            measure.sourcePage = m.optInt("sourcePage", -1); measure.sourceMeasureIndex = m.optInt("sourceMeasureIndex", -1);
            measure.sourceMeasureNumber = m.optInt("sourceMeasureNumber", measure.number);
            measure.keySpecified = m.optBoolean("keySpecified", false); measure.timeSpecified = m.optBoolean("timeSpecified", false);
            measure.tempoSpecified = m.optBoolean("tempoSpecified", false);
            JSONArray tempos = m.optJSONArray("tempoChanges");
            if (tempos != null) for (int ti = 0; ti < tempos.length(); ti++) {
                JSONObject t = tempos.getJSONObject(ti); double onset = t.optDouble("onset", 0);
                if (!Double.isFinite(onset) || onset < 0 || onset > 1024) throw new Exception("速度位置无效");
                measure.tempoChanges.add(new Score.TempoChange(onset, validTempo(t.optDouble("bpm", measure.tempo))));
            }
            JSONArray events = m.getJSONArray("events");
            for (int ei = 0; ei < events.length(); ei++) {
                JSONObject e = events.getJSONObject(ei); Score.Event event = new Score.Event();
                event.onset = e.optDouble("onset", ei); event.duration = e.optDouble("duration", 1);
                event.grace = e.optBoolean("grace", false);
                if (!Double.isFinite(event.onset) || !Double.isFinite(event.duration) || event.onset < 0 || event.duration < 0)
                    throw new Exception("音符时间无效");
                JSONArray notes = e.getJSONArray("notes");
                for (int ni = 0; ni < notes.length(); ni++) {
                    JSONObject n = notes.getJSONObject(ni); Score.Note note = new Score.Note();
                    note.midi = n.getInt("midi"); note.staff = n.optInt("staff", 1);
                    if (note.midi < 21 || note.midi > 108) throw new Exception("音符须在钢琴 A0–C8 范围内");
                    note.name = n.optString("name", Score.noteName(note.midi));
                    note.tiedContinuation = n.optBoolean("tiedContinuation", false);
                    note.tiedStart = n.optBoolean("tiedStart", false); note.voice = n.optString("voice", "");
                    note.duration = n.optDouble("duration", event.duration);
                    if (!Double.isFinite(note.duration) || note.duration < 0 || note.duration > 1024) throw new Exception("音符时长无效");
                    event.notes.add(note);
                    if (++noteCount > 30000) throw new Exception("音符过多，请分段导入");
                }
                measure.events.add(event);
            }
            score.measures.add(measure);
        }
        if (score.measures.isEmpty()) throw new Exception("至少需要一个小节");
        return score;
    }

    private static double validTempo(double tempo) throws Exception {
        if (!Double.isFinite(tempo) || tempo < 1 || tempo > 1000) throw new Exception("乐谱速度无效");
        return tempo;
    }
}
