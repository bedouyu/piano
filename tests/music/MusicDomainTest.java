import com.shipu.assistant.music.MusicXmlParser;
import com.shipu.assistant.music.PracticeEngine;
import com.shipu.assistant.music.Score;
import com.shipu.assistant.music.PlaybackTimeline;
import com.shipu.assistant.music.ScorePageMerger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Run without Android, Maven, a network connection, or third-party test libraries. */
public final class MusicDomainTest {
    private static int checks = 0;
    public static void main(String[] args) throws Exception {
        testPolyphonicMusicXml();
        testGraceTieAndHandMapping();
        testSingleHandChordPractice();
        testLoopAndSingleNotes();
        testMxl();
        testXmlSafety();
        testInvalidScores();
        testPianoPartSelectionAndTranspose();
        testPlaybackTimeline();
        testTempoAndSoundingOctaves();
        testPageOrderAndContext();
        System.out.println("PASS: " + checks + " music-domain checks");
    }

    private static void testPolyphonicMusicXml() throws Exception {
        Score score = parse(complex());
        equal("双谱表校验", score.title, "work title");
        equal("测试作者", score.composer, "composer");
        equal(-4, score.fifths, "flat key signature");
        equal(4, score.beats, "beats");
        equal(4, score.beatType, "beat type");
        equal(2, score.measures.size(), "measure count");
        Score.Measure first = score.measures.get(0);
        equal(2, first.events.size(), "rest does not create a target");
        equal(3, first.events.get(0).notes.size(), "chord plus backup voice merged by onset");
        equal(70, first.events.get(0).notes.get(0).midi, "explicit B flat maps to MIDI");
        equal("B♭4", first.events.get(0).notes.get(0).name, "flat spelling preserved");
        equal(74, first.events.get(0).notes.get(1).midi, "chord starts at head's onset");
        near(0.0, first.events.get(0).onset, "first onset");
        near(2.0, first.events.get(1).onset, "rest advances cursor");
        near(4.0, first.duration, "polyphonic measure is four quarters");
        near(4.0, first.events.get(0).notes.get(2).duration, "individual bass duration retained");
        equal(0, score.measures.get(1).fifths, "key changes retained");
        equal(3, score.measures.get(1).beats, "time changes retained");
    }

    private static void testGraceTieAndHandMapping() throws Exception {
        Score score = parse(complex());
        Score.Measure second = score.measures.get(1);
        equal(4, second.events.size(), "grace kept separate from main onset");
        check(second.events.get(0).notes.get(0).tiedContinuation, "tie stop recognized");
        check(second.events.get(2).grace, "grace ordered before its main note");
        near(second.events.get(2).onset, second.events.get(3).onset, "grace does not advance cursor");
        PracticeEngine engine = new PracticeEngine(score);
        engine.configure(PracticeEngine.Hand.LEFT, PracticeEngine.Mode.MEASURE, 0, 1, false, false);
        equal(2, engine.size(), "left hand remains staff 2 despite its G clef");
        check(engine.current().expectedMidi().contains(60), "left first key C4");
        equal(PracticeEngine.Result.ADVANCED, engine.press(60), "left first measure advanced");
        equal(1, engine.current().measureIndex, "next measure index");
        check(engine.current().expectedMidi().contains(55), "next bass G3");
        engine.configure(PracticeEngine.Hand.RIGHT, PracticeEngine.Mode.NOTE, 0, 1, false, false);
        equal(6, engine.size(), "tie continuation is not reattacked; grace is a target");
        engine.configure(PracticeEngine.Hand.RIGHT, PracticeEngine.Mode.NOTE, 0, 1, false, true);
        equal(2, engine.size(), "swapping staff-hand assignment works");
    }

    private static void testSingleHandChordPractice() throws Exception {
        PracticeEngine engine = new PracticeEngine(parse(complex()));
        engine.configure(PracticeEngine.Hand.RIGHT, PracticeEngine.Mode.MEASURE, 0, 0, false, false);
        equal(2, engine.size(), "measure mode has two onset targets");
        equal(PracticeEngine.Result.PARTIAL, engine.press(70), "single finger begins chord");
        equal(1, engine.matched().size(), "chord partial stored");
        equal(PracticeEngine.Result.IGNORED, engine.press(70), "same chord note does not inflate stats");
        equal(1, engine.correctNotes, "one unique correct note recorded");
        equal(PracticeEngine.Result.WRONG, engine.press(71), "wrong pitch rejected");
        equal(1, engine.matched().size(), "wrong key preserves partial chord");
        equal(0, engine.currentIndex(), "wrong key does not advance");
        equal(PracticeEngine.Result.ADVANCED, engine.press(74), "second finger completes chord");
        equal(0, engine.matched().size(), "next step starts without old matches");
        equal(PracticeEngine.Result.COMPLETED, engine.press(77), "last target completes session");
        check(engine.isFinished(), "finished state");
        equal(2, engine.completedSteps, "completed onset count");
        near(0.75, engine.accuracy(), "accuracy includes wrong-key attempts");
        equal(PracticeEngine.Result.COMPLETED, engine.press(60), "completed session ignores further keys");
        equal(1, engine.wrongNotes, "completed presses do not alter stats");
        engine.previous();
        check(!engine.isFinished(), "review previous after completion");
        engine.restart();
        equal(0, engine.correctNotes, "restart clears correct count");
        equal(0, engine.wrongNotes, "restart clears wrong count");
    }

    private static void testLoopAndSingleNotes() {
        Score score = Score.demo();
        equal(8, score.measures.size(), "original eight-bar demo");
        PracticeEngine engine = new PracticeEngine(score);
        engine.configure(PracticeEngine.Hand.LEFT, PracticeEngine.Mode.NOTE, 0, 0, false, false);
        equal(2, engine.size(), "note mode splits bass chord into individual notes");
        equal(PracticeEngine.Result.ADVANCED, engine.press(48), "single-note step auto advances");
        equal(PracticeEngine.Result.COMPLETED, engine.press(55), "second single-note step completes");
        engine.configure(PracticeEngine.Hand.LEFT, PracticeEngine.Mode.MEASURE, 0, 0, true, false);
        engine.press(48);
        equal(PracticeEngine.Result.ADVANCED, engine.press(55), "loop restarts rather than completes");
        equal(1, engine.loops, "loop counter");
        equal(0, engine.currentIndex(), "loop returns to start");
        check(!engine.isFinished(), "loop remains active");
        Score empty = new Score();
        check(new PracticeEngine(empty).isFinished(), "empty score is safe");
        equal("C4", Score.noteName(60), "middle C naming");
        equal("A0", Score.noteName(21), "lowest piano key");
        equal("C8", Score.noteName(108), "highest piano key");
    }

    private static void testMxl() throws Exception {
        byte[] archive = zip(new String[] {"META-INF/container.xml", "score/main.musicxml"},
                new byte[][] {
                    ("<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                     + "<rootfiles><rootfile full-path=\"score/main.musicxml\" media-type=\"application/vnd.recordare.musicxml+xml\"/>"
                     + "</rootfiles></container>").getBytes(StandardCharsets.UTF_8),
                    complex().getBytes(StandardCharsets.UTF_8)
                });
        Score score = MusicXmlParser.parse(new ByteArrayInputStream(archive), "压缩乐谱.mxl");
        equal("双谱表校验", score.title, "MXL follows container rootfile");
        byte[] fallback = zip(new String[] {"piece.xml"}, new byte[][] {complex().getBytes(StandardCharsets.UTF_8)});
        equal(2, MusicXmlParser.parse(new ByteArrayInputStream(fallback), "piece.mxl").measures.size(), "MXL fallback without container");
        final byte[] traversal = zip(new String[] {"../piece.xml"}, new byte[][] {complex().getBytes(StandardCharsets.UTF_8)});
        fails(new CheckedRunnable() { public void run() throws Exception {
            MusicXmlParser.parse(new ByteArrayInputStream(traversal), "unsafe.mxl");
        }}, "zip traversal rejected");
        byte[] huge = new byte[16 * 1024 * 1024 + 1];
        final byte[] bomb = zip(new String[] {"piece.xml"}, new byte[][] {huge});
        fails(new CheckedRunnable() { public void run() throws Exception {
            MusicXmlParser.parse(new ByteArrayInputStream(bomb), "large.mxl");
        }}, "zip inflated size limited");
    }

    private static void testXmlSafety() throws Exception {
        final String entity = "<?xml version=\"1.0\"?><!DOCTYPE score-partwise [<!ENTITY x SYSTEM \"file:///C:/secret\">]>"
                + "<score-partwise><work><work-title>&x;</work-title></work></score-partwise>";
        fails(new CheckedRunnable() { public void run() throws Exception { parse(entity); }}, "external entity rejected");
        final byte[] utf16 = entity.getBytes(StandardCharsets.UTF_16);
        fails(new CheckedRunnable() { public void run() throws Exception {
            MusicXmlParser.parse(new ByteArrayInputStream(utf16), "entity.xml");
        }}, "UTF-16 entity bypass rejected");
        String withDoctype = "<?xml version=\"1.0\"?><!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML 4.0 Partwise//EN\" "
                + "\"http://127.0.0.1:1/never-fetch-this.dtd\">" + simpleBody("<note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration></note>");
        equal(60, parse(withDoctype).measures.get(0).events.get(0).notes.get(0).midi, "normal external DOCTYPE accepted without request");
    }

    private static void testInvalidScores() throws Exception {
        fails(new CheckedRunnable() { public void run() throws Exception { parse("<score-timewise/>"); }}, "unsupported root explained");
        fails(new CheckedRunnable() { public void run() throws Exception { parse(simpleBody("<note><rest/><duration>4</duration></note>")); }}, "rest-only score explained");
        fails(new CheckedRunnable() { public void run() throws Exception { parse(simpleBody("<attributes><divisions>0</divisions></attributes>")); }}, "zero divisions rejected");
        String body = "<note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration></note>"
                + "<note><pitch><step>D</step><alter>0.5</alter><octave>4</octave></pitch><duration>1</duration></note>"
                + "<barline><repeat direction=\"backward\"/></barline>";
        Score score = parse(simpleBody(body));
        equal(1, score.measures.get(0).events.size(), "microtones are not silently rounded");
        equal(2, score.warnings.size(), "microtone and repeat limitations surfaced");
    }

    private static void testPianoPartSelectionAndTranspose() throws Exception {
        String xml = "<score-partwise><part-list><score-part id=\"flute\"><part-name>Flute</part-name></score-part>"
                + "<score-part id=\"piano\"><part-name>Piano</part-name></score-part></part-list>"
                + "<part id=\"flute\"><measure number=\"1\"><note><pitch><step>A</step><octave>5</octave></pitch><duration>1</duration></note></measure></part>"
                + "<part id=\"piano\"><measure number=\"1\"><attributes><divisions>1</divisions><transpose><chromatic>-2</chromatic></transpose></attributes>"
                + "<note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration></note></measure></part></score-partwise>";
        Score score = parse(xml);
        equal(58, score.measures.get(0).events.get(0).notes.get(0).midi, "piano part preferred and concert pitch respected");
        equal(1, score.warnings.size(), "multiple-part selection surfaced");
    }

    private static void testPlaybackTimeline() throws Exception {
        Score score = parse(complex());
        PlaybackTimeline both = PlaybackTimeline.create(score, PracticeEngine.Hand.BOTH, 0, 1, false, 120);
        equal(8, both.notes().size(), "playback merges tied F5 rather than reattacking continuation");
        PlaybackTimeline.NoteEvent f = null;
        for (PlaybackTimeline.NoteEvent note : both.notes()) if (note.midi == 77) f = note;
        equal(1000L, f.startMs, "rest before first F5 is retained");
        equal(2500L, f.endMs, "tie sustains across bar line");
        equal(3500L, both.durationMs, "playback includes full seven quarter beats");
        PlaybackTimeline left = PlaybackTimeline.create(score, PracticeEngine.Hand.LEFT, 0, 1, false, 60);
        equal(2, left.notes().size(), "selected-hand demonstration contains only left hand");
        equal(4000L, left.notes().get(1).startMs, "slow tempo scales time");
        equal(7000L, left.durationMs, "slow tempo scales range duration");
        PlaybackTimeline range = PlaybackTimeline.create(score, PracticeEngine.Hand.RIGHT, 1, 1, false, 120);
        equal(77, range.notes().get(0).midi, "starting in a tied bar creates an audible first attack");
        equal(0L, range.notes().get(0).startMs, "playback range starts at zero");
        PlaybackTimeline swapped = PlaybackTimeline.create(score, PracticeEngine.Hand.RIGHT, 0, 0, true, 120);
        equal(1, swapped.notes().size(), "playback uses same swapped staff mapping as practice");
        equal(60, swapped.notes().get(0).midi, "swapped right hand uses lower staff");
        check(both.positions().size() > 0, "playback exposes score cursor positions");
        for (int i = 1; i < both.positions().size(); i++)
            check(both.positions().get(i).timeMs >= both.positions().get(i - 1).timeMs, "cursor times are chronological");
        Score reversed = Score.demo();
        java.util.Collections.reverse(reversed.measures.get(0).events);
        PlaybackTimeline ordered = PlaybackTimeline.create(reversed, PracticeEngine.Hand.RIGHT, 0, 0, false, 120);
        equal(60, ordered.notes().get(0).midi, "unsorted edited events play by onset");
        equal(0, ordered.notes().get(0).measureIndex, "cursor retains source measure index");
        equal(3, ordered.notes().get(0).eventIndex, "cursor retains original event index after time sorting");
    }

    private static void testTempoAndSoundingOctaves() throws Exception {
        String xml = "<score-partwise><part id='P'><measure number='1'><attributes><divisions>1</divisions></attributes>"
                + "<direction><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>"
                + "<direction><direction-type><octave-shift type='down' size='8'/></direction-type></direction>"
                + "<note><pitch><step>C</step><octave>6</octave></pitch><duration>2</duration><voice>1</voice></note>"
                + "<direction><sound tempo='60'/></direction><note><pitch><step>D</step><octave>6</octave></pitch><duration>2</duration></note></measure>"
                + "<measure number='2'><direction><direction-type><metronome><beat-unit>half</beat-unit><beat-unit-dot/><per-minute>40</per-minute></metronome></direction-type></direction>"
                + "<note><pitch><step>E</step><octave>6</octave></pitch><duration>4</duration></note></measure></part></score-partwise>";
        Score score = parse(xml);
        near(120, score.tempo, "initial tempo imported");
        near(120, score.measures.get(1).tempo, "dotted half-note tempo converts to quarter BPM");
        equal(84, score.measures.get(0).events.get(0).notes.get(0).midi, "MusicXML octave-shift notation does not transpose already-sounding pitch");
        equal("1", score.measures.get(0).events.get(0).notes.get(0).voice, "voice retained for tied-note matching");
        PlaybackTimeline timeline = PlaybackTimeline.create(score, PracticeEngine.Hand.RIGHT, 0, 1, false, 0);
        equal(1000L, timeline.notes().get(1).startMs, "first two beats at 120 BPM");
        equal(3000L, timeline.notes().get(2).startMs, "mid-bar tempo change affects following two beats");
        equal(5000L, timeline.durationMs, "next measure resumes 120 BPM");
        PlaybackTimeline slow = PlaybackTimeline.create(score, PracticeEngine.Hand.RIGHT, 0, 1, false, 60);
        equal(10000L, slow.durationMs, "speed control preserves relative tempo changes");
        Score tied = parse(complex());
        check(tied.measures.get(0).events.get(1).notes.get(0).tiedStart, "tie start retained for playback and editing");
    }

    private static void testPageOrderAndContext() throws Exception {
        Score first = parse("<score-partwise><part id='P'><measure number='9'><attributes><key><fifths>-5</fifths></key><time><beats>3</beats><beat-type>4</beat-type></time></attributes>"
                + "<direction><sound tempo='90'/></direction><note><pitch><step>C</step><octave>4</octave></pitch><duration>3</duration></note></measure></part></score-partwise>");
        Score second = parse("<score-partwise><part id='P'><measure number='1'><note><pitch><step>D</step><octave>4</octave></pitch><duration>3</duration></note></measure>"
                + "<measure number='2'><attributes><time><beats>2</beats><beat-type>4</beat-type></time><key><fifths>1</fifths></key></attributes>"
                + "<note><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration></note></measure></part></score-partwise>");
        Score merged = ScorePageMerger.merge(java.util.Arrays.asList(first, second), "两页PDF");
        equal(3, merged.measures.size(), "all pages retained");
        equal(60, merged.measures.get(0).events.get(0).notes.get(0).midi, "physical first page stays first despite larger measure number");
        equal(62, merged.measures.get(1).events.get(0).notes.get(0).midi, "second page follows first page");
        equal(2, merged.measures.get(1).number, "merged numbering is contiguous");
        equal(1, merged.measures.get(1).sourcePage, "PDF page provenance retained");
        equal(0, merged.measures.get(1).sourceMeasureIndex, "page-local index retained");
        equal(9, merged.measures.get(0).sourceMeasureNumber, "printed numbering retained separately");
        equal(-5, merged.measures.get(1).fifths, "new page with omitted key inherits previous key");
        equal(3, merged.measures.get(1).beats, "new page with omitted meter inherits previous meter");
        near(90, merged.measures.get(1).tempo, "new page with omitted tempo inherits previous tempo");
        equal(1, merged.measures.get(2).fifths, "explicit new key overrides prior page context");
        equal(2, merged.measures.get(2).beats, "explicit new meter overrides prior context");
        Score delayed = parse("<score-partwise><part id='P'><measure number='1'><note><pitch><step>D</step><octave>4</octave></pitch><duration>1</duration></note>"
                + "<direction><sound tempo='60'/></direction><note><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration></note></measure></part></score-partwise>");
        Score midPage = ScorePageMerger.merge(java.util.Arrays.asList(first, delayed), "分段速度");
        near(90, midPage.measures.get(1).tempo, "mid-bar tempo on second page does not replace inherited initial tempo");
        PlaybackTimeline delayedTimeline = PlaybackTimeline.create(midPage, PracticeEngine.Hand.RIGHT, 1, 1, false, 0);
        equal(667L, delayedTimeline.notes().get(1).startMs, "second-page tempo remains inherited until its mid-bar mark");
        merged.measures.get(0).events.get(0).notes.get(0).midi = 70;
        equal(60, first.measures.get(0).events.get(0).notes.get(0).midi, "merged score does not mutate checkpoint page results");
    }

    private static String complex() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<score-partwise xmlns=\"http://www.musicxml.org/ns/musicxml\" version=\"4.0\">"
            + "<work><work-title>双谱表校验</work-title></work><identification><creator type=\"composer\">测试作者</creator></identification>"
            + "<part-list><score-part id=\"P1\"><part-name>Piano</part-name></score-part></part-list><part id=\"P1\">"
            + "<measure number=\"1\"><attributes><divisions>2</divisions><key><fifths>-4</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time>"
            + "<staves>2</staves><clef number=\"1\"><sign>G</sign><line>2</line></clef><clef number=\"2\"><sign>G</sign><line>2</line></clef></attributes>"
            + "<note><pitch><step>B</step><alter>-1</alter><octave>4</octave></pitch><duration>2</duration><voice>1</voice><staff>1</staff></note>"
            + "<note><chord/><pitch><step>D</step><octave>5</octave></pitch><duration>2</duration><voice>1</voice><staff>1</staff></note>"
            + "<note><rest/><duration>2</duration><staff>1</staff></note>"
            + "<note><pitch><step>F</step><octave>5</octave></pitch><duration>4</duration><tie type=\"start\"/><staff>1</staff></note>"
            + "<backup><duration>8</duration></backup><note><pitch><step>C</step><octave>4</octave></pitch><duration>8</duration><voice>2</voice><staff>2</staff></note></measure>"
            + "<measure number=\"2\"><attributes><divisions>4</divisions><key><fifths>0</fifths></key><time><beats>3</beats><beat-type>4</beat-type></time></attributes>"
            + "<note><pitch><step>F</step><octave>5</octave></pitch><duration>4</duration><tie type=\"stop\"/><staff>1</staff></note>"
            + "<note><pitch><step>E</step><octave>5</octave></pitch><duration>4</duration><staff>1</staff></note>"
            + "<note><grace/><pitch><step>D</step><octave>5</octave></pitch><staff>1</staff></note>"
            + "<note><pitch><step>C</step><octave>5</octave></pitch><duration>4</duration><staff>1</staff></note>"
            + "<backup><duration>12</duration></backup><note><pitch><step>G</step><octave>3</octave></pitch><duration>12</duration><staff>2</staff></note></measure>"
            + "</part></score-partwise>";
    }

    private static String simpleBody(String notes) {
        return "<score-partwise><part-list><score-part id=\"P\"><part-name>Piano</part-name></score-part></part-list>"
                + "<part id=\"P\"><measure number=\"1\">" + notes + "</measure></part></score-partwise>";
    }
    private static Score parse(String xml) throws IOException {
        return MusicXmlParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "测试.xml");
    }
    private static byte[] zip(String[] names, byte[][] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream output = new ZipOutputStream(bytes);
        for (int i = 0; i < names.length; i++) {
            output.putNextEntry(new ZipEntry(names[i]));
            output.write(contents[i]);
            output.closeEntry();
        }
        output.close();
        return bytes.toByteArray();
    }
    private interface CheckedRunnable { void run() throws Exception; }
    private static void fails(CheckedRunnable runnable, String message) throws Exception {
        boolean failed = false;
        try { runnable.run(); } catch (IOException expected) { failed = true; }
        check(failed, message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + ": expected " + expected + ", got " + actual);
    }
    private static void near(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 0.000001, message + ": expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
