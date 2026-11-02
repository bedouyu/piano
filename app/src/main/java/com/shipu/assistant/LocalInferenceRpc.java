package com.shipu.assistant;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Bounded, one-shot binary responses for a dedicated worker's synchronous XHR.
 * Response creation never waits. Only InputStream.read waits for delivery.
 * This class has no Android dependencies so concurrency is tested on a real JDK.
 */
public final class LocalInferenceRpc implements Closeable {
    public static final int CHUNK_BYTES = 512 * 1024;
    public static final int MAX_CHUNKS = 128;
    public static final long MAX_RESULT_BYTES = 64L * 1024 * 1024;
    public static final int MAX_ACTIVE_CALLS = 8;
    // Phone WASM inference is single-threaded: one segmentation run on a dense
    // page can take several minutes.  A 3-minute deadline cut page 2 of long
    // scores off mid-call and surfaced as a bare "connection interrupted" error.
    public static final long DEADLINE_MILLIS = 600_000L;
    private static final Pattern RUN_ID = Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");
    private static final Pattern CALL_ID = Pattern.compile("c[1-9][0-9]{0,7}");

    public interface ChunkDecoder { byte[] decode(String encoded) throws Exception; }

    /** Explicitly injectable small limits keep boundary and timeout tests fast. */
    public static final class Limits {
        final int calls, chunks, chunkBytes;
        final long resultBytes, bufferedBytes, deadlineMillis;
        public Limits(int calls, int chunks, int chunkBytes, long resultBytes, long bufferedBytes, long deadlineMillis) {
            if (calls < 1 || chunks < 1 || chunkBytes < 1 || resultBytes < 1 || bufferedBytes < 1 || deadlineMillis < 1)
                throw new IllegalArgumentException("Invalid RPC limits");
            this.calls = calls; this.chunks = chunks; this.chunkBytes = chunkBytes;
            this.resultBytes = resultBytes; this.bufferedBytes = bufferedBytes; this.deadlineMillis = deadlineMillis;
        }
        static Limits defaults() {
            return new Limits(MAX_ACTIVE_CALLS, MAX_CHUNKS, CHUNK_BYTES, MAX_RESULT_BYTES, 96L * 1024 * 1024, DEADLINE_MILLIS);
        }
    }

    public static final class StaleRunException extends IOException {
        StaleRunException() { super("本机推理任务已取消或过期"); }
    }

    private static final class Pending {
        final String runId, callId;
        final long deadline;
        final CompletableFuture<Pending> ready = new CompletableFuture<Pending>();
        final ArrayList<byte[]> chunks = new ArrayList<byte[]>();
        int totalChunks = -1, nextChunk;
        long totalBytes, retainedBytes;
        boolean opened, decoding, complete, retired;
        IOException failure;
        Pending(String runId, String callId, long deadline) {
            this.runId = runId; this.callId = callId; this.deadline = deadline;
        }
    }

    private final Object lock = new Object();
    private final Map<String, Pending> pending = new HashMap<String, Pending>();
    private final ChunkDecoder decoder;
    private final Limits limits;
    private final ScheduledExecutorService timer;
    private String activeRun;
    private long bufferedBytes;
    private int retiredCallWatermark;
    private boolean closed;

    public LocalInferenceRpc(ChunkDecoder decoder) { this(decoder, Limits.defaults()); }

    public LocalInferenceRpc(ChunkDecoder decoder, Limits limits) {
        if (decoder == null || limits == null) throw new NullPointerException();
        this.decoder = decoder; this.limits = limits;
        timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "shipu-rpc-deadlines"); thread.setDaemon(true); return thread;
        });
        long interval = Math.max(5L, Math.min(1000L, limits.deadlineMillis / 4L));
        timer.scheduleWithFixedDelay(() -> {
            synchronized (lock) {
                long now = System.nanoTime();
                for (Pending entry : new ArrayList<Pending>(pending.values()))
                    if (now >= entry.deadline) retire(entry, new IOException("本机推理响应等待超时，请重试"));
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    public void startRun(String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) throw new IllegalArgumentException("Invalid RPC runId");
        synchronized (lock) {
            if (closed) throw new IllegalStateException("RPC transport has been closed");
            cancelAll(new StaleRunException());
            activeRun = runId; retiredCallWatermark = 0;
        }
    }

    /** No future.get, wait, decoding, or model work is permitted in this method. */
    public InputStream openResponse(String runId, String callId) throws IOException {
        synchronized (lock) {
            Pending entry = getOrCreate(runId, callId);
            if (entry.opened) {
                IOException failure = new IOException("重复读取本机推理响应"); retire(entry, failure); throw failure;
            }
            entry.opened = true;
            return new ResponseStream(entry);
        }
    }

    /** Runs directly on the bridge thread. Decoding is outside the bookkeeping lock. */
    public void deliverChunk(String runId, String callId, int index, int total, String encoded) throws IOException {
        final Pending entry;
        synchronized (lock) {
            entry = getOrCreate(runId, callId);
            String problem = null;
            if (total < 1 || total > limits.chunks) problem = "本机推理分块数量超过限制";
            else if (index < 0 || index >= total || index != entry.nextChunk || entry.decoding || entry.complete)
                problem = "本机推理分块乱序或重复";
            else if (entry.totalChunks != -1 && entry.totalChunks != total) problem = "本机推理分块数量不一致";
            else if (!validBase64(encoded, limits.chunkBytes)) problem = "本机推理分块编码无效或超过限制";
            if (problem != null) { IOException failure = new IOException(problem); retire(entry, failure); throw failure; }
            entry.totalChunks = total; entry.decoding = true;
        }

        byte[] bytes;
        try { bytes = decoder.decode(encoded); }
        catch (Exception decodeFailure) {
            IOException failure = new IOException("本机推理分块解码失败", decodeFailure);
            synchronized (lock) { if (!entry.retired) retire(entry, failure); }
            throw failure;
        }

        synchronized (lock) {
            requireRun(runId);
            if (entry.retired || pending.get(callId) != entry) throw entry.failure != null ? entry.failure : new StaleRunException();
            if (System.nanoTime() >= entry.deadline) {
                IOException failure = new IOException("本机推理响应等待超时，请重试"); retire(entry, failure); throw failure;
            }
            String problem = null;
            if (bytes == null || bytes.length > limits.chunkBytes) problem = "本机推理分块超过限制";
            else if (entry.totalBytes + bytes.length > limits.resultBytes) problem = "本机推理结果超过64MB限制，请缩小乐谱页面";
            else if (bufferedBytes + bytes.length > limits.bufferedBytes) problem = "同时等待的本机推理结果过大，请重试";
            if (problem != null) { IOException failure = new IOException(problem); retire(entry, failure); throw failure; }
            entry.chunks.add(bytes); entry.nextChunk++; entry.decoding = false;
            entry.totalBytes += bytes.length; entry.retainedBytes += bytes.length; bufferedBytes += bytes.length;
            if (entry.nextChunk == total) { entry.complete = true; entry.ready.complete(entry); }
        }
    }

    private static byte[] errorPacket(String message) {
        String text = message == null || message.isEmpty() ? "本机推理失败" : message;
        StringBuilder json = new StringBuilder("{\"version\":1,\"value\":{\"ok\":false,\"error\":\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') json.append('\\').append(c);
            else if (c < 0x20) json.append("\\u").append(String.format("%04x", (int)c));
            else json.append(c);
        }
        json.append("\"}}");
        byte[] meta = json.toString().getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[4 + meta.length];
        for (int i = 0; i < 4; i++) packet[i] = (byte)((meta.length >>> (8 * i)) & 0xff);
        System.arraycopy(meta, 0, packet, 4, meta.length);
        return packet;
    }

    private static boolean validBase64(String encoded, int maximumBytes) {
        if (encoded == null || encoded.length() % 4 != 0 || encoded.length() > ((maximumBytes + 2L) / 3L) * 4L) return false;
        int padding = encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0;
        for (int i = 0; i < encoded.length() - padding; i++) {
            char c = encoded.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/')) return false;
        }
        return ((long)encoded.length() / 4L) * 3L - padding <= maximumBytes;
    }

    private Pending getOrCreate(String runId, String callId) throws IOException {
        requireRun(runId);
        if (callId == null || !CALL_ID.matcher(callId).matches()) throw new IOException("本机推理调用编号无效");
        Pending entry = pending.get(callId);
        if (entry != null) {
            if (System.nanoTime() >= entry.deadline) {
                IOException failure = new IOException("本机推理响应等待超时，请重试"); retire(entry, failure); throw failure;
            }
            return entry;
        }
        if (Integer.parseInt(callId.substring(1)) <= retiredCallWatermark) throw new IOException("本机推理调用已结束，不能重复使用");
        if (pending.size() >= limits.calls) throw new IOException("等待中的本机推理调用过多");
        entry = new Pending(runId, callId, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.deadlineMillis));
        pending.put(callId, entry);
        return entry;
    }

    private void requireRun(String runId) throws IOException {
        if (closed || runId == null || !runId.equals(activeRun)) throw new StaleRunException();
    }

    private void retire(Pending entry, IOException failure) {
        if (entry.retired) return;
        entry.retired = true; entry.failure = failure;
        pending.remove(entry.callId);
        retiredCallWatermark = Math.max(retiredCallWatermark, Integer.parseInt(entry.callId.substring(1)));
        bufferedBytes -= entry.retainedBytes; entry.retainedBytes = 0; entry.chunks.clear();
        if (failure != null) entry.ready.completeExceptionally(failure);
    }

    private void cancelAll(IOException failure) {
        for (Pending entry : new ArrayList<Pending>(pending.values())) retire(entry, failure);
        pending.clear(); bufferedBytes = 0;
    }

    public void cancelRun(String runId) {
        synchronized (lock) {
            if (runId == null || !runId.equals(activeRun)) return;
            activeRun = null; cancelAll(new StaleRunException());
        }
    }

    public int activeCalls() { synchronized (lock) { return pending.size(); } }
    public long bufferedBytes() { synchronized (lock) { return bufferedBytes; } }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true; activeRun = null; cancelAll(new IOException("本机识谱页面已关闭"));
        }
        timer.shutdownNow();
    }

    private final class ResponseStream extends InputStream {
        final Pending entry;
        int chunkIndex, chunkOffset;
        boolean streamClosed, eof;
        // When a call fails before any byte was delivered, the reason is written
        // into the body as the same {ok:false,error} packet the worker already
        // understands.  A bare IOException only reaches Python as the opaque
        // "Failed to load" network error, which hid every real cause.
        byte[] errorBody;
        int errorOffset;
        ResponseStream(Pending entry) { this.entry = entry; }

        private void awaitResult() throws IOException {
            synchronized (lock) {
                if (streamClosed) throw new IOException("本机推理响应已关闭");
                if (entry.failure != null) { deliverFailure(entry.failure); return; }
                if (entry.complete || eof) return;
            }
            long remaining = entry.deadline - System.nanoTime();
            try {
                if (remaining <= 0) throw new TimeoutException();
                entry.ready.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); close(); throw new IOException("读取本机推理响应已中断", interrupted);
            } catch (TimeoutException timeout) {
                IOException failure = new IOException("本机推理响应等待超时，请重试");
                synchronized (lock) { retire(entry, failure); }
                deliverFailure(failure);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                IOException failure = cause instanceof IOException ? (IOException)cause : new IOException("本机推理响应未完成", cause);
                deliverFailure(failure);
            }
        }

        /** Emits an error packet when nothing was streamed yet, otherwise fails the read. */
        private void deliverFailure(IOException failure) throws IOException {
            synchronized (lock) {
                if (streamClosed) throw new IOException("本机推理响应已关闭");
                if (!entry.chunks.isEmpty() || chunkIndex != 0 || chunkOffset != 0) throw failure;
                if (errorBody == null) errorBody = errorPacket(failure.getMessage());
            }
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1]; int count = read(one, 0, 1); return count == -1 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] destination, int offset, int length) throws IOException {
            if (destination == null) throw new NullPointerException();
            if (offset < 0 || length < 0 || length > destination.length - offset) throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            awaitResult(); // Deliberately outside lock and outside the intercept callback.
            synchronized (lock) {
                if (streamClosed) throw new IOException("本机推理响应已关闭");
                if (errorBody != null) {
                    if (errorOffset >= errorBody.length) { eof = true; retire(entry, null); return -1; }
                    int count = Math.min(length, errorBody.length - errorOffset);
                    System.arraycopy(errorBody, errorOffset, destination, offset, count);
                    errorOffset += count;
                    return count;
                }
                if (entry.failure != null) throw entry.failure;
                if (eof) return -1;
                while (chunkIndex < entry.chunks.size()) {
                    byte[] chunk = entry.chunks.get(chunkIndex);
                    if (chunkOffset >= chunk.length) {
                        entry.chunks.set(chunkIndex++, null); chunkOffset = 0;
                        entry.retainedBytes -= chunk.length; bufferedBytes -= chunk.length;
                        continue;
                    }
                    int count = Math.min(length, chunk.length - chunkOffset);
                    System.arraycopy(chunk, chunkOffset, destination, offset, count); chunkOffset += count;
                    return count;
                }
                eof = true; retire(entry, null); return -1;
            }
        }

        @Override public int available() {
            synchronized (lock) { return !streamClosed && entry.complete && entry.failure == null ? (int)Math.min(Integer.MAX_VALUE, entry.retainedBytes) : 0; }
        }

        @Override public void close() {
            synchronized (lock) {
                if (streamClosed) return;
                streamClosed = true;
                if (!entry.retired) retire(entry, eof ? null : new IOException("本机推理响应已关闭"));
            }
        }
    }
}
