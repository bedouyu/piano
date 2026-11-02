import com.shipu.assistant.music.MusicXmlParser;
import com.shipu.assistant.music.PracticeEngine;
import com.shipu.assistant.music.Score;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Runs a real recognition output through the production parser and every practice mode. */
public final class MusicImportE2e {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: MusicImportE2e input.musicxml output.json");
        Path input = Paths.get(args[0]);
        Score score;
        try (InputStream stream = new FileInputStream(input.toFile())) {
            score = MusicXmlParser.parse(stream, input.getFileName().toString());
        }
        if (score.measures.isEmpty()) throw new AssertionError("Real recognition yielded no measures");
        int events = 0, notes = 0, continuations = 0, minimum = 128, maximum = -1;
        Set<Integer> playableMeasures = playableMeasures(score, PracticeEngine.Hand.BOTH);
        if (playableMeasures.isEmpty()) throw new AssertionError("Real recognition yielded no playable notes");
        for (Score.Measure measure : score.measures) {
            events += measure.events.size();
            for (Score.Event event : measure.events) {
                for (Score.Note note : event.notes) {
                    notes++;
                    if (note.midi < 21 || note.midi > 108) throw new AssertionError("Out-of-piano-range note: " + note.midi);
                    minimum = Math.min(minimum, note.midi);
                    maximum = Math.max(maximum, note.midi);
                    if (note.tiedContinuation) continuations++;
                }
            }
        }
        List<String> modes = new ArrayList<String>();
        for (PracticeEngine.Hand hand : PracticeEngine.Hand.values()) {
            Set<Integer> expectedMeasures = playableMeasures(score, hand);
            if (expectedMeasures.isEmpty()) throw new AssertionError("Real piano recognition has no targets for hand " + hand);
            for (PracticeEngine.Mode mode : PracticeEngine.Mode.values()) {
                PracticeEngine engine = new PracticeEngine(score);
                engine.configure(hand, mode, 0, score.measures.size() - 1, false, false);
                int size = engine.size();
                if (size == 0) throw new AssertionError("No practice steps: " + hand + "/" + mode);
                Set<Integer> reached = new LinkedHashSet<Integer>();
                int simulatedSteps = 0;
                while (!engine.isFinished()) {
                    PracticeEngine.Step step = engine.current();
                    reached.add(step.measureIndex);
                    Set<Integer> targets = step.expectedMidi();
                    if (targets.isEmpty()) throw new AssertionError("Empty practice target");
                    for (Integer midi : targets) {
                        PracticeEngine.Result result = engine.press(midi);
                        if (result == PracticeEngine.Result.WRONG || result == PracticeEngine.Result.IGNORED) {
                            throw new AssertionError("Correct target rejected: " + result);
                        }
                    }
                    if (++simulatedSteps > size) throw new AssertionError("Practice did not advance");
                }
                if (simulatedSteps != size || engine.completedSteps != size) throw new AssertionError("Some steps were skipped");
                if (!reached.equals(expectedMeasures)) throw new AssertionError("Some playable measures are unreachable in " + hand + "/" + mode);
                if (engine.wrongNotes != 0 || engine.accuracy() != 1.0) throw new AssertionError("Perfect run not scored correctly");
                modes.add("{\"hand\":" + quote(hand.name()) + ",\"mode\":" + quote(mode.name())
                        + ",\"steps\":" + size + ",\"visitedPlayableMeasures\":" + reached.size()
                        + ",\"acceptedKeyPresses\":" + engine.correctNotes
                        + ",\"allPlayableMeasuresReachable\":true,\"completed\":true,\"accuracy\":1.0}");
            }
        }
        PracticeEngine selector = new PracticeEngine(score);
        for (int i = 0; i < score.measures.size(); i++) {
            selector.configure(PracticeEngine.Hand.BOTH, PracticeEngine.Mode.MEASURE, i, i, false, false);
            for (PracticeEngine.Step step : selector.sequence()) {
                if (step.measureIndex != i) throw new AssertionError("Single-measure selection escaped its range");
            }
        }
        List<String> warnings = new ArrayList<String>();
        for (String warning : score.warnings) warnings.add(quote(warning));
        String json = "{\n  \"status\":\"passed\",\n  \"source\":" + quote(input.toString())
                + ",\n  \"parser\":\"production MusicXmlParser\",\n  \"title\":" + quote(score.title)
                + ",\n  \"measures\":" + score.measures.size() + ",\n  \"events\":" + events
                + ",\n  \"notes\":" + notes + ",\n  \"tieContinuations\":" + continuations
                + ",\n  \"minimumMidi\":" + minimum + ",\n  \"maximumMidi\":" + maximum
                + ",\n  \"allNotesWithin88Keys\":true,\n  \"allMeasuresSelectable\":true"
                + ",\n  \"playableMeasures\":" + playableMeasures.size()
                + ",\n  \"restOrContinuationOnlyMeasures\":" + (score.measures.size() - playableMeasures.size())
                + ",\n  \"warnings\":[" + String.join(",", warnings) + "]"
                + ",\n  \"practiceModes\":[\n    " + String.join(",\n    ", modes) + "\n  ]\n}\n";
        Path output = Paths.get(args[1]);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.write(output, json.getBytes(StandardCharsets.UTF_8));
        System.out.println(json);
    }

    private static Set<Integer> playableMeasures(Score score, PracticeEngine.Hand hand) {
        Set<Integer> result = new LinkedHashSet<Integer>();
        for (int mi = 0; mi < score.measures.size(); mi++) {
            for (Score.Event event : score.measures.get(mi).events) {
                for (Score.Note note : event.notes) {
                    if (!note.tiedContinuation && PracticeEngine.matchesHand(note, hand, false)) result.add(mi);
                }
            }
        }
        return result;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
