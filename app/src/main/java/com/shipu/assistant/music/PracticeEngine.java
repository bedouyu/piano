package com.shipu.assistant.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Wait-until-correct practice, independent of rendering, audio, or Android APIs. */
public final class PracticeEngine {
    public enum Hand { RIGHT, LEFT, BOTH }
    public enum Mode { NOTE, MEASURE }
    public enum Result { WRONG, PARTIAL, ADVANCED, COMPLETED, IGNORED }

    public static final class Step {
        public final int measureIndex;
        public final int measureNumber;
        public final int eventIndex;
        public final int noteIndex;
        public final double onset;
        public final double duration;
        public final List<Score.Note> notes;

        private Step(int measureIndex, int measureNumber, int eventIndex, int noteIndex,
                     double onset, double duration, List<Score.Note> notes) {
            this.measureIndex = measureIndex;
            this.measureNumber = measureNumber;
            this.eventIndex = eventIndex;
            this.noteIndex = noteIndex;
            this.onset = onset;
            this.duration = duration;
            this.notes = Collections.unmodifiableList(new ArrayList<Score.Note>(notes));
        }

        public Set<Integer> expectedMidi() {
            Set<Integer> result = new LinkedHashSet<Integer>();
            for (Score.Note note : notes) result.add(note.midi);
            return Collections.unmodifiableSet(result);
        }
    }

    private final Score score;
    private final List<Step> steps = new ArrayList<Step>();
    private final Set<Integer> matched = new LinkedHashSet<Integer>();
    private int index = 0;
    private boolean loop = false;
    public int correctNotes = 0;
    public int wrongNotes = 0;
    public int completedSteps = 0;
    public int loops = 0;

    public PracticeEngine(Score score) {
        if (score == null) throw new IllegalArgumentException("乐谱不能为空");
        this.score = score;
        if (!score.measures.isEmpty()) {
            configure(Hand.RIGHT, Mode.NOTE, 0, score.measures.size() - 1, false, false);
        }
    }

    /** Range endpoints are inclusive and zero-based. Configuration starts a fresh session. */
    public void configure(Hand hand, Mode mode, int startMeasureIndex, int endMeasureIndex,
                          boolean loop, boolean swapHands) {
        if (hand == null || mode == null) throw new IllegalArgumentException("请选择练习模式");
        if (score.measures.isEmpty()) {
            steps.clear();
            this.loop = loop;
            restart();
            return;
        }
        if (startMeasureIndex < 0 || endMeasureIndex < startMeasureIndex
                || endMeasureIndex >= score.measures.size()) {
            throw new IllegalArgumentException("小节范围不正确");
        }
        steps.clear();
        this.loop = loop;
        for (int mi = startMeasureIndex; mi <= endMeasureIndex; mi++) {
            Score.Measure measure = score.measures.get(mi);
            List<Integer> order = new ArrayList<Integer>();
            for (int ei = 0; ei < measure.events.size(); ei++) order.add(ei);
            final Score.Measure selectedMeasure = measure;
            Collections.sort(order, new Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) {
                    Score.Event first = selectedMeasure.events.get(a);
                    Score.Event second = selectedMeasure.events.get(b);
                    int onsetOrder = Double.compare(first.onset, second.onset);
                    if (onsetOrder != 0) return onsetOrder;
                    if (first.grace != second.grace) return first.grace ? -1 : 1;
                    return a.compareTo(b);
                }
            });
            for (int ei : order) {
                Score.Event event = measure.events.get(ei);
                List<Score.Note> notes = new ArrayList<Score.Note>();
                List<Integer> noteIndices = new ArrayList<Integer>();
                for (int ni = 0; ni < event.notes.size(); ni++) {
                    Score.Note note = event.notes.get(ni);
                    if (note.tiedContinuation || !matchesHand(note, hand, swapHands)) continue;
                    notes.add(note);
                    noteIndices.add(ni);
                }
                if (notes.isEmpty()) continue;
                if (mode == Mode.NOTE) {
                    for (int ni = 0; ni < notes.size(); ni++) {
                        Score.Note note = notes.get(ni);
                        steps.add(new Step(mi, measure.number, ei, noteIndices.get(ni),
                                event.onset, note.duration, Collections.singletonList(note)));
                    }
                } else {
                    steps.add(new Step(mi, measure.number, ei, -1,
                            event.onset, event.duration, notes));
                }
            }
        }
        restart();
    }

    public static boolean matchesHand(Score.Note note, Hand hand, boolean swapHands) {
        if (hand == Hand.BOTH) return true;
        boolean right = note.staff <= 1;
        if (swapHands) right = !right;
        return hand == Hand.RIGHT ? right : !right;
    }

    public Step current() { return index < steps.size() ? steps.get(index) : null; }
    public int currentIndex() { return index; }
    public int size() { return steps.size(); }
    public boolean isFinished() { return current() == null; }
    public Set<Integer> matched() {
        return Collections.unmodifiableSet(new LinkedHashSet<Integer>(matched));
    }
    public List<Step> sequence() { return Collections.unmodifiableList(steps); }

    public Result press(int midi) {
        Step step = current();
        if (step == null) return Result.COMPLETED;
        if (matched.contains(midi)) return Result.IGNORED;
        if (!step.expectedMidi().contains(midi)) {
            wrongNotes++;
            return Result.WRONG;
        }
        matched.add(midi);
        correctNotes++;
        if (!matched.containsAll(step.expectedMidi())) return Result.PARTIAL;
        completedSteps++;
        matched.clear();
        index++;
        if (index == steps.size()) {
            if (loop && !steps.isEmpty()) {
                loops++;
                index = 0;
                return Result.ADVANCED;
            }
            return Result.COMPLETED;
        }
        return Result.ADVANCED;
    }

    public double accuracy() {
        int attempts = correctNotes + wrongNotes;
        return attempts == 0 ? 1.0 : (double) correctNotes / attempts;
    }

    public void restart() {
        index = 0;
        matched.clear();
        correctNotes = 0;
        wrongNotes = 0;
        completedSteps = 0;
        loops = 0;
    }

    /** Reviewing a previous step preserves cumulative session statistics. */
    public void previous() {
        if (!steps.isEmpty()) index = Math.max(0, Math.min(index - 1, steps.size() - 1));
        matched.clear();
    }
}
