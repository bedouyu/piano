import com.shipu.assistant.LocalInferenceRpc;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Exercises production binary transport without depending on Android or a WebView fixture. */
public final class LocalInferenceRpcTest {
    private static final String RUN = "a00b00c0-1234-4567-89ab-123456789abc";
    private static final String NEXT = "b00b00c0-1234-4567-89ab-123456789abc";
    private static int checks;
    private static final ExecutorService THREADS = Executors.newCachedThreadPool();
    interface Action { void run() throws Exception; }
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static void rejects(Action action, String message) throws Exception {
        try { action.run(); throw new AssertionError("Accepted: " + message); }
        catch (IOException | IllegalArgumentException | IllegalStateException expected) { checks++; }
    }
    static LocalInferenceRpc rpc() {
        LocalInferenceRpc rpc = new LocalInferenceRpc(s -> Base64.getDecoder().decode(s)); rpc.startRun(RUN); return rpc;
    }
    static LocalInferenceRpc small(int calls, int chunks, int chunkBytes, int resultBytes, int buffered, int timeout) {
        LocalInferenceRpc rpc = new LocalInferenceRpc(s -> Base64.getDecoder().decode(s),
            new LocalInferenceRpc.Limits(calls, chunks, chunkBytes, resultBytes, buffered, timeout));
        rpc.startRun(RUN); return rpc;
    }
    static String encoded(byte... bytes) { return Base64.getEncoder().encodeToString(bytes); }
    static byte[] readAll(InputStream stream) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); return out.toByteArray();
        }
    }
    static void failsRead(Future<byte[]> future, String message) throws Exception {
        try { future.get(2, TimeUnit.SECONDS); throw new AssertionError(message); }
        catch (ExecutionException expected) { check(expected.getCause() instanceof IOException, message); }
    }
    static void waitRead(Future<byte[]> future, String message) throws Exception {
        try { future.get(60, TimeUnit.MILLISECONDS); throw new AssertionError(message); }
        catch (TimeoutException expected) { checks++; }
    }
    static void beforeAndAfter() throws Exception {
        try (LocalInferenceRpc rpc = rpc()) {
            rpc.deliverChunk(RUN, "c1", 0, 1, encoded((byte)1, (byte)2, (byte)3));
            check(rpc.bufferedBytes() == 3, "early result retained");
            check(Arrays.equals(readAll(rpc.openResponse(RUN, "c1")), new byte[]{1,2,3}), "result before GET");
            check(rpc.activeCalls() == 0 && rpc.bufferedBytes() == 0, "early result released");
            rejects(() -> rpc.openResponse(RUN, "c1"), "reuse retired call");
            Future<InputStream> opening = THREADS.submit(() -> rpc.openResponse(RUN, "c2"));
            InputStream stream = opening.get(2, TimeUnit.SECONDS);
            Future<byte[]> reading = THREADS.submit(() -> readAll(stream));
            waitRead(reading, "read must wait for delivery");
            rpc.deliverChunk(RUN, "c2", 0, 2, encoded((byte)4, (byte)5));
            waitRead(reading, "read must wait for final chunk");
            rpc.deliverChunk(RUN, "c2", 1, 2, encoded((byte)6));
            check(Arrays.equals(reading.get(2, TimeUnit.SECONDS), new byte[]{4,5,6}), "GET before result");
            check(rpc.activeCalls() == 0 && rpc.bufferedBytes() == 0, "late result released");
        }
    }
    static void largeBinary() throws Exception {
        try (LocalInferenceRpc rpc = rpc()) {
            // Larger than the ordinary 16MiB JSON bridge limit; real chunks, no numeric JSON arrays.
            byte[] original = new byte[20 * 1024 * 1024 + 37]; new Random(614).nextBytes(original);
            int total = (original.length + LocalInferenceRpc.CHUNK_BYTES - 1) / LocalInferenceRpc.CHUNK_BYTES;
            InputStream stream = rpc.openResponse(RUN, "c1");
            Future<byte[]> receiving = THREADS.submit(() -> readAll(stream));
            for (int i = 0; i < total; i++) {
                int start = i * LocalInferenceRpc.CHUNK_BYTES;
                rpc.deliverChunk(RUN, "c1", i, total, Base64.getEncoder().encodeToString(
                    Arrays.copyOfRange(original, start, Math.min(original.length, start + LocalInferenceRpc.CHUNK_BYTES))));
            }
            check(total == 41, "20MiB uses 41 bounded chunks");
            check(Arrays.equals(original, receiving.get(10, TimeUnit.SECONDS)), "20MiB binary payload preserved byte for byte");
            check(rpc.activeCalls() == 0 && rpc.bufferedBytes() == 0, "large buffers released");
        }
    }
    static void lifecycle() throws Exception {
        LocalInferenceRpc rpc = rpc();
        try {
            InputStream canceled = rpc.openResponse(RUN, "c1"); Future<byte[]> waiting = THREADS.submit(() -> readAll(canceled));
            rpc.cancelRun(NEXT); waitRead(waiting, "unrelated cancellation ignored");
            rpc.cancelRun(RUN); failsRead(waiting, "cancel unblocks reader");
            rejects(() -> rpc.deliverChunk(RUN, "c1", 0, 1, encoded((byte)1)), "late delivery rejected");
            rpc.startRun(RUN); InputStream replaced = rpc.openResponse(RUN, "c1");
            Future<byte[]> replaceWait = THREADS.submit(() -> readAll(replaced)); rpc.startRun(NEXT);
            failsRead(replaceWait, "replacement run unblocks reader");
            rejects(() -> rpc.openResponse(RUN, "c2"), "stale run GET");
            InputStream destroyed = rpc.openResponse(NEXT, "c1"); Future<byte[]> closeWait = THREADS.submit(() -> readAll(destroyed));
            rpc.close(); failsRead(closeWait, "destroy unblocks reader");
            check(rpc.activeCalls() == 0 && rpc.bufferedBytes() == 0, "destroy clears buffers");
            rejects(() -> rpc.startRun(RUN), "closed transport cannot restart");
        } finally { rpc.close(); }
        try (LocalInferenceRpc timed = small(2, 4, 16, 64, 64, 120)) {
            Future<byte[]> timeout = THREADS.submit(() -> readAll(timed.openResponse(RUN, "c1")));
            failsRead(timeout, "deadline unblocks reader");
            check(timed.activeCalls() == 0, "deadline retires pending");
            rejects(() -> timed.deliverChunk(RUN, "c1", 0, 1, encoded((byte)1)), "timed out call cannot resurrect");
        }
    }
    static void validation() throws Exception {
        try (LocalInferenceRpc rpc = rpc()) {
            rejects(() -> rpc.startRun("../invalid"), "run path traversal");
            rejects(() -> rpc.openResponse(NEXT, "c1"), "other run");
            for (String invalid : new String[]{"c0", "c01", "c-1", "c100000000", "../c1", "c1?x", ""})
                rejects(() -> rpc.openResponse(RUN, invalid), "invalid call " + invalid);
            InputStream first = rpc.openResponse(RUN, "c1");
            rejects(() -> rpc.openResponse(RUN, "c1"), "duplicate GET");
            rejects(() -> first.read(), "duplicate GET wakes original reader"); first.close();
            rejects(() -> rpc.deliverChunk(RUN, "c2", 1, 2, encoded((byte)1)), "out of order");
            rejects(() -> rpc.deliverChunk(RUN, "c3", 0, 129, encoded((byte)1)), "chunk total limit");
            rejects(() -> rpc.deliverChunk(RUN, "c4", 0, 1, "A===") , "base64 alphabet");
            rejects(() -> rpc.deliverChunk(RUN, "c5", 0, 1, "AA A") , "base64 whitespace");
            rpc.deliverChunk(RUN, "c6", 0, 2, encoded((byte)1));
            rejects(() -> rpc.deliverChunk(RUN, "c6", 1, 3, encoded((byte)2)), "inconsistent total");
            rpc.deliverChunk(RUN, "c7", 0, 2, encoded((byte)1));
            rejects(() -> rpc.deliverChunk(RUN, "c7", 0, 2, encoded((byte)2)), "duplicate chunk");
        }
        try (LocalInferenceRpc rpc = small(1, 3, 4, 5, 8, 5000)) {
            InputStream first = rpc.openResponse(RUN, "c1");
            rejects(() -> rpc.openResponse(RUN, "c2"), "active call bound"); first.close();
            rejects(() -> rpc.deliverChunk(RUN, "c2", 0, 1, encoded((byte)1,(byte)2,(byte)3,(byte)4,(byte)5)), "chunk byte bound");
            rpc.deliverChunk(RUN, "c3", 0, 2, encoded((byte)1,(byte)2,(byte)3,(byte)4));
            rejects(() -> rpc.deliverChunk(RUN, "c3", 1, 2, encoded((byte)5,(byte)6)), "total result byte bound");
            check(rpc.activeCalls() == 0 && rpc.bufferedBytes() == 0, "invalid packets release buffers");
        }
        try (LocalInferenceRpc rpc = small(3, 3, 4, 8, 5, 5000)) {
            rpc.deliverChunk(RUN, "c1", 0, 2, encoded((byte)1,(byte)2,(byte)3,(byte)4));
            rejects(() -> rpc.deliverChunk(RUN, "c2", 0, 1, encoded((byte)5,(byte)6)), "aggregate buffer budget");
            rpc.cancelRun(RUN); check(rpc.bufferedBytes() == 0, "cancellation releases partial packet");
        }
    }
    public static void main(String[] args) throws Exception {
        try { beforeAndAfter(); largeBinary(); lifecycle(); validation(); System.out.println("LocalInferenceRpc: " + checks + " checks passed"); }
        finally { THREADS.shutdownNow(); }
    }
}
