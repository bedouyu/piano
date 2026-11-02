package com.shipu.assistant;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline, polyphonic piano samples (Alexander Holm, CC BY 3.0). */
final class PianoAudio {
    private static final String[] NAMES = {"A0", "C1", "Fs1", "C2", "Fs2", "C3", "Fs3", "C4", "Fs4", "C5", "Fs5", "C6", "Fs6", "C7", "C8"};
    private static final int[] PITCHES = {21, 24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84, 90, 96, 108};

    private final SoundPool pool;
    private final Map<Integer, Integer> samples = new HashMap<>();
    private final Set<Integer> loaded = new HashSet<>();
    private final List<AssetFileDescriptor> descriptors = new ArrayList<>();
    private final Set<Integer> streams = new LinkedHashSet<>();
    private final AtomicInteger pending = new AtomicInteger(NAMES.length);

    PianoAudio(Context context) {
        pool = new SoundPool.Builder().setMaxStreams(16).setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).build();

        // SoundPool.load() is asynchronous and its completion callback is dispatched
        // on the main looper.  Never block the main thread waiting for it: doing so
        // prevents the callbacks from running and leaves the pool permanently empty
        // (that made both key presses and playback silent).  Load in the background
        // and keep each AssetFileDescriptor open until the pool reports the result.
        pool.setOnLoadCompleteListener((p, id, status) -> {
            if (status == 0) synchronized (loaded) { loaded.add(id); }
            finishOne();
        });
        for (int i = 0; i < NAMES.length; i++) {
            AssetFileDescriptor fd = null;
            try {
                fd = context.getAssets().openFd("piano/" + NAMES[i] + ".mp3");
                int id = pool.load(fd, 1);
                if (id != 0) {
                    synchronized (samples) { samples.put(PITCHES[i], id); }
                    synchronized (descriptors) { descriptors.add(fd); }
                } else {
                    closeQuietly(fd);
                    finishOne();
                }
            } catch (Exception ignored) {
                closeQuietly(fd);
                finishOne();
            }
        }
    }

    private void finishOne() {
        if (pending.decrementAndGet() == 0) releaseDescriptors();
    }

    private void releaseDescriptors() {
        List<AssetFileDescriptor> list;
        synchronized (descriptors) { list = new ArrayList<>(descriptors); descriptors.clear(); }
        for (AssetFileDescriptor fd : list) closeQuietly(fd);
    }

    private static void closeQuietly(AssetFileDescriptor fd) {
        if (fd == null) return;
        try { fd.close(); } catch (Exception ignored) { }
    }

    /** Plays the closest loaded sample and pitch-shifts it to the requested key. */
    int play(int midi) {
        int nearest = -1;
        synchronized (loaded) {
            synchronized (samples) {
                for (Map.Entry<Integer, Integer> entry : samples.entrySet())
                    if (loaded.contains(entry.getValue()) && (nearest < 0 || Math.abs(entry.getKey() - midi) < Math.abs(nearest - midi))) nearest = entry.getKey();
            }
        }
        if (nearest < 0) return 0;
        int id = pool.play(samples.get(nearest), 0.8f, 0.8f, 1, 0, (float) Math.pow(2, (midi - nearest) / 12.0));
        if (id != 0) synchronized (streams) {
            streams.add(id);
            while (streams.size() > 64) {
                int oldest = streams.iterator().next(); pool.stop(oldest); streams.remove(oldest);
            }
        }
        return id;
    }
    void stop(int streamId) { if (streamId != 0) { pool.stop(streamId); synchronized (streams) { streams.remove(streamId); } } }
    void silence() { synchronized (streams) { for (Integer id : streams) pool.stop(id); streams.clear(); } }
    void close() { releaseDescriptors(); pool.release(); }
}
