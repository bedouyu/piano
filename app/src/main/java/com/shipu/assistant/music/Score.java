package com.shipu.assistant.music;

import java.util.ArrayList;
import java.util.List;

/** Editable, quarter-note based score shared by import, correction and practice. */
public final class Score {
    public String title = "未命名乐谱";
    public String composer = "";
    public int fifths = 0;
    public int beats = 4;
    public int beatType = 4;
    /** Quarter notes per minute. */
    public double tempo = 120.0;
    public final List<Measure> measures = new ArrayList<Measure>();
    public final List<String> warnings = new ArrayList<String>();

    public Score() {}
    public Score(String title) { this.title = title; }

    public static final class Measure {
        public int number = 1;
        public int fifths = 0;
        public int beats = 4;
        public int beatType = 4;
        public double duration = 4.0;
        public double tempo = 120.0;
        /** Zero-based PDF/image page and within-page index; -1 denotes an imported XML source. */
        public int sourcePage = -1;
        public int sourceMeasureIndex = -1;
        public int sourceMeasureNumber = -1;
        public boolean keySpecified = false;
        public boolean timeSpecified = false;
        public boolean tempoSpecified = false;
        public final List<TempoChange> tempoChanges = new ArrayList<TempoChange>();
        public final List<Event> events = new ArrayList<Event>();
        public Measure() {}
        public Measure(int number) { this.number = number; }
    }

    public static final class TempoChange {
        public double onset;
        public double bpm;
        public TempoChange(double onset, double bpm) { this.onset = onset; this.bpm = bpm; }
    }

    /** Notes sharing an onset; each Note retains its own duration and tie state. */
    public static final class Event {
        public double onset = 0.0;
        public double duration = 1.0;
        public boolean grace = false;
        public final List<Note> notes = new ArrayList<Note>();
        public Event() {}
        public Event(double onset, double duration) {
            this.onset = onset;
            this.duration = duration;
        }
    }

    public static final class Note {
        public int midi = 60;
        /** Staff number is the MusicXML staff, independent of clef. */
        public int staff = 1;
        public String name = "C4";
        public boolean tiedContinuation = false;
        public boolean tiedStart = false;
        public String voice = "";
        public double duration = 1.0;
        public Note() {}
        public Note(int midi, int staff) {
            this.midi = midi;
            this.staff = staff;
            this.name = noteName(midi);
        }
        public Note(int midi, int staff, String name, boolean tiedContinuation) {
            this.midi = midi;
            this.staff = staff;
            this.name = name;
            this.tiedContinuation = tiedContinuation;
        }
        public Note copy() {
            Note result = new Note(midi, staff, name, tiedContinuation);
            result.duration = duration;
            result.tiedStart = tiedStart;
            result.voice = voice;
            return result;
        }
    }

    public static String noteName(int midi) {
        String[] names = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};
        int pitchClass = ((midi % 12) + 12) % 12;
        return names[pitchClass] + (int) Math.floor(midi / 12.0 - 1.0);
    }

    /** Original short exercise, deliberately unrelated to the user's uploaded score. */
    public static Score demo() {
        Score score = new Score("初见 · 八小节练习");
        score.composer = "识谱助手 · 原创练习";
        int[][] melody = {
            {60, 64, 67, 64}, {62, 65, 69, 65},
            {64, 67, 71, 67}, {65, 69, 72, 69},
            {67, 71, 74, 71}, {69, 72, 76, 72},
            {65, 62, 67, 71}, {72, 67, 64, 60}
        };
        int[][] harmony = {
            {48, 55}, {50, 57}, {52, 59}, {53, 60},
            {55, 62}, {45, 52}, {43, 50}, {48, 55}
        };
        for (int index = 0; index < melody.length; index++) {
            Measure measure = new Measure(index + 1);
            for (int beat = 0; beat < 4; beat++) {
                Event event = new Event(beat, beat == 0 ? 4.0 : 1.0);
                event.notes.add(new Note(melody[index][beat], 1));
                if (beat == 0) {
                    for (int pitch : harmony[index]) {
                        Note bass = new Note(pitch, 2);
                        bass.duration = 4.0;
                        event.notes.add(bass);
                    }
                }
                measure.events.add(event);
            }
            score.measures.add(measure);
        }
        return score;
    }
}
