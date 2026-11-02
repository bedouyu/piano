package com.shipu.assistant.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An offline piano demonstration in score order. Times are relative to the selected range. */
public final class PlaybackTimeline {
    private static final double EPSILON = 0.000001;

    public static final class NoteEvent {
        public final long startMs, endMs;
        public final int midi, staff, measureIndex, eventIndex, noteIndex;
        private NoteEvent(RawNote note, long start, long end) {
            startMs = start; endMs = Math.max(start + 35, end);
            midi = note.note.midi; staff = note.note.staff;
            measureIndex = note.mi; eventIndex = note.ei; noteIndex = note.ni;
        }
    }

    public static final class Position {
        public final long timeMs;
        public final int measureIndex, eventIndex;
        private Position(long time, int mi, int ei) { timeMs = time; measureIndex = mi; eventIndex = ei; }
    }

    private final List<NoteEvent> notes;
    private final List<Position> positions;
    public final long durationMs;
    private PlaybackTimeline(List<NoteEvent> notes, List<Position> positions, long duration) {
        this.notes = Collections.unmodifiableList(notes);
        this.positions = Collections.unmodifiableList(positions);
        durationMs = duration;
    }
    public List<NoteEvent> notes() { return notes; }
    public List<Position> positions() { return positions; }

    /** A positive tempo scales all score tempo marks; zero uses the score's original tempos. */
    public static PlaybackTimeline create(Score score, PracticeEngine.Hand hand, int startMeasure,
                                          int endMeasure, boolean swapHands, double tempoBpm) {
        if (score == null || hand == null) throw new IllegalArgumentException("请选择乐谱和左右手");
        if (startMeasure < 0 || endMeasure < startMeasure || endMeasure >= score.measures.size())
            throw new IllegalArgumentException("播放小节范围不正确");
        if (!Double.isFinite(tempoBpm) || tempoBpm < 0 || tempoBpm > 1000)
            throw new IllegalArgumentException("播放速度无效");
        double factor = tempoBpm > 0 ? tempoBpm / validTempo(score.tempo) : 1;
        List<TempoPoint> tempos = new ArrayList<TempoPoint>();
        List<RawNote> rawNotes = new ArrayList<RawNote>();
        List<RawPosition> rawPositions = new ArrayList<RawPosition>();
        double beat = 0;
        for (int mi = startMeasure; mi <= endMeasure; mi++) {
            Score.Measure measure = score.measures.get(mi);
            tempos.add(new TempoPoint(beat, validTempo(measure.tempo) * factor));
            for (Score.TempoChange change : measure.tempoChanges)
                if (Double.isFinite(change.onset) && change.onset >= 0)
                    tempos.add(new TempoPoint(beat + change.onset, validTempo(change.bpm) * factor));
            List<Integer> order = eventOrder(measure);
            double length = Math.max(0, measure.duration);
            for (int ei : order) {
                Score.Event event = measure.events.get(ei);
                if (!Double.isFinite(event.onset) || event.onset < 0) continue;
                length = Math.max(length, event.onset + Math.max(0, event.duration));
                boolean selected = false;
                for (int ni = 0; ni < event.notes.size(); ni++) {
                    Score.Note note = event.notes.get(ni);
                    if (Double.isFinite(note.duration) && note.duration >= 0) length = Math.max(length, event.onset + note.duration);
                    if (!PracticeEngine.matchesHand(note, hand, swapHands)) continue;
                    if (note.midi < 21 || note.midi > 108 || !Double.isFinite(note.duration) || note.duration < 0) continue;
                    rawNotes.add(new RawNote(note, beat + event.onset, beat + event.onset + note.duration,
                            event.grace, mi, ei, ni));
                    selected = true;
                }
                if (selected) rawPositions.add(new RawPosition(beat + event.onset, event.grace, mi, ei));
            }
            if (!Double.isFinite(length) || length > 1024) throw new IllegalArgumentException("小节时长无效");
            beat += length;
        }
        Collections.sort(tempos, new Comparator<TempoPoint>() {
            @Override public int compare(TempoPoint a, TempoPoint b) { return Double.compare(a.beat, b.beat); }
        });
        TempoMap tempoMap = new TempoMap(tempos);
        Map<String, RawNote> preceding = new HashMap<String, RawNote>();
        List<RawNote> attacks = new ArrayList<RawNote>();
        for (RawNote note : rawNotes) {
            String key = note.note.staff + ":" + note.note.voice + ":" + note.note.midi;
            RawNote previous = preceding.get(key);
            if (!note.grace && note.note.tiedContinuation && previous != null
                    && Math.abs(previous.end - note.start) < EPSILON) {
                previous.end = Math.max(previous.end, note.end);
            } else {
                // A range starting in the middle of a tie must still be audible.
                attacks.add(note);
                preceding.put(key, note);
            }
        }
        List<Position> positions = new ArrayList<Position>();
        Map<String, Long> offsets = new HashMap<String, Long>();
        for (int pi = 0; pi < rawPositions.size();) {
            int end = pi + 1;
            while (end < rawPositions.size() && Math.abs(rawPositions.get(end).beat - rawPositions.get(pi).beat) < EPSILON) end++;
            int graceCount = 0;
            for (int i = pi; i < end; i++) if (rawPositions.get(i).grace) graceCount++;
            long graceMs = 60;
            if (end < rawPositions.size() && graceCount > 0) {
                long gap = tempoMap.millis(rawPositions.get(end).beat) - tempoMap.millis(rawPositions.get(pi).beat);
                graceMs = Math.min(graceMs, Math.max(15, gap / (graceCount + 1) / 2));
            }
            int graceIndex = 0;
            for (int i = pi; i < end; i++) {
                RawPosition position = rawPositions.get(i);
                long offset = position.grace ? graceMs * graceIndex++ : graceMs * graceCount;
                offsets.put(position.mi + ":" + position.ei, offset);
                positions.add(new Position(tempoMap.millis(position.beat) + offset, position.mi, position.ei));
            }
            pi = end;
        }
        List<NoteEvent> result = new ArrayList<NoteEvent>();
        long duration = tempoMap.millis(beat);
        for (RawNote note : attacks) {
            Long offset = offsets.get(note.mi + ":" + note.ei);
            long start = tempoMap.millis(note.start) + (offset == null ? 0 : offset);
            long end = note.grace ? start + 55 : tempoMap.millis(note.end);
            NoteEvent event = new NoteEvent(note, start, end);
            result.add(event); duration = Math.max(duration, event.endMs);
        }
        Collections.sort(result, new Comparator<NoteEvent>() {
            @Override public int compare(NoteEvent a, NoteEvent b) { return Long.compare(a.startMs, b.startMs); }
        });
        Collections.sort(positions, new Comparator<Position>() {
            @Override public int compare(Position a, Position b) { return Long.compare(a.timeMs, b.timeMs); }
        });
        return new PlaybackTimeline(result, positions, duration);
    }

    private static List<Integer> eventOrder(final Score.Measure measure) {
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < measure.events.size(); i++) order.add(i);
        Collections.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                Score.Event first = measure.events.get(a), second = measure.events.get(b);
                int onset = Double.compare(first.onset, second.onset);
                return onset != 0 ? onset : first.grace != second.grace ? (first.grace ? -1 : 1) : a.compareTo(b);
            }
        });
        return order;
    }
    private static double validTempo(double bpm) { return Double.isFinite(bpm) && bpm >= 1 && bpm <= 1000 ? bpm : 120; }
    private static final class RawNote {
        final Score.Note note; final double start; double end; final boolean grace; final int mi, ei, ni;
        RawNote(Score.Note n, double s, double e, boolean g, int m, int v, int i) { note=n; start=s; end=e; grace=g; mi=m; ei=v; ni=i; }
    }
    private static final class RawPosition {
        final double beat; final boolean grace; final int mi, ei;
        RawPosition(double b, boolean g, int m, int e) { beat=b; grace=g; mi=m; ei=e; }
    }
    private static final class TempoPoint {
        final double beat, bpm; double millis;
        TempoPoint(double beat, double bpm) { this.beat=beat; this.bpm=bpm; }
    }
    private static final class TempoMap {
        private final List<TempoPoint> points = new ArrayList<TempoPoint>();
        TempoMap(List<TempoPoint> candidates) {
            for (TempoPoint candidate : candidates) {
                if (!points.isEmpty() && Math.abs(points.get(points.size()-1).beat - candidate.beat) < EPSILON)
                    points.remove(points.size()-1);
                points.add(candidate);
            }
            for (int i=1;i<points.size();i++) {
                TempoPoint before=points.get(i-1), current=points.get(i);
                current.millis=before.millis + (current.beat-before.beat)*60000.0/before.bpm;
            }
        }
        long millis(double beat) {
            int low=0, high=points.size()-1;
            while (low < high) { int middle=(low+high+1)/2; if (points.get(middle).beat <= beat) low=middle; else high=middle-1; }
            TempoPoint point=points.get(low);
            return Math.max(0, Math.round(point.millis+(beat-point.beat)*60000.0/point.bpm));
        }
    }
}
