package com.shipu.assistant.music;

import java.util.List;

/** Preserve physical page order, then each page's MusicXML document order. */
public final class ScorePageMerger {
    private ScorePageMerger() {}
    public static Score merge(List<Score> pages, String fallbackTitle) {
        if (pages == null || pages.isEmpty()) throw new IllegalArgumentException("没有识别到乐谱页面");
        Score merged = new Score(fallbackTitle == null || fallbackTitle.trim().isEmpty() ? "我的乐谱" : fallbackTitle);
        Score first = pages.get(0);
        if (first == null) throw new IllegalArgumentException("第一页识别结果缺失");
        if (first.title != null && !first.title.trim().isEmpty() && !first.title.equals("未命名乐谱")) merged.title = first.title;
        merged.composer = first.composer;
        merged.fifths = first.fifths; merged.beats = first.beats; merged.beatType = first.beatType; merged.tempo = first.tempo;
        int fifths = merged.fifths, beats = merged.beats, beatType = merged.beatType;
        double tempo = merged.tempo;
        for (int pi = 0; pi < pages.size(); pi++) {
            Score page = pages.get(pi);
            if (page == null || page.measures.isEmpty()) throw new IllegalArgumentException("第 " + (pi + 1) + " 页没有可练习的识别结果");
            for (int mi = 0; mi < page.measures.size(); mi++) {
                Score.Measure source = page.measures.get(mi);
                if (pi == 0 || source.keySpecified) fifths = source.fifths;
                if (pi == 0 || source.timeSpecified) { beats = source.beats; beatType = source.beatType; }
                if (pi == 0 || source.tempoSpecified) tempo = source.tempo;
                Score.Measure target = new Score.Measure(merged.measures.size() + 1);
                target.fifths = fifths; target.beats = beats; target.beatType = beatType; target.tempo = tempo;
                target.keySpecified = source.keySpecified; target.timeSpecified = source.timeSpecified; target.tempoSpecified = source.tempoSpecified;
                target.sourcePage = pi; target.sourceMeasureIndex = mi; target.sourceMeasureNumber = source.number;
                target.duration = source.events.isEmpty() && !source.timeSpecified ? beats * 4.0 / beatType : source.duration;
                for (Score.TempoChange change : source.tempoChanges) {
                    target.tempoChanges.add(new Score.TempoChange(change.onset, change.bpm));
                    tempo = change.bpm;
                }
                for (Score.Event event : source.events) {
                    Score.Event copied = new Score.Event(event.onset, event.duration); copied.grace = event.grace;
                    for (Score.Note note : event.notes) copied.notes.add(note.copy());
                    target.events.add(copied);
                }
                merged.measures.add(target);
            }
            for (String warning : page.warnings) {
                String contextual = "第 " + (pi + 1) + " 页：" + warning;
                if (!merged.warnings.contains(contextual)) merged.warnings.add(contextual);
            }
        }
        return merged;
    }
}
