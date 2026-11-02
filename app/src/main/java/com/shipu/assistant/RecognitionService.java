package com.shipu.assistant;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.util.Base64;
import android.webkit.*;
import com.shipu.assistant.music.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** User-started foreground work. The activity can navigate, rotate, or be destroyed independently. */
public final class RecognitionService extends Service {
    static final String UPDATE = "com.shipu.assistant.RECOGNITION_STATUS";
    static final String CANCEL = "cancel";
    private static final String CHANNEL = "offline-recognition";
    private static final int NOTIFICATION = 1701;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LocalInferenceRpc rpc = new LocalInferenceRpc(encoded -> Base64.decode(encoded, Base64.NO_WRAP));
    private final ArrayDeque<String> trace = new ArrayDeque<String>();
    private WebView web;
    private LocalAssetHost assets;
    private PowerManager.WakeLock wakeLock;
    private LibraryRepository repository;
    private volatile String id = "", runId = "";
    private String title, mime;
    private File source, checkpoint;
    private volatile boolean active;
    private boolean loaded;
    private boolean acceptingPage;
    private int serviceStartId;
    private int pages, page;
    private long xmlBytes;
    private final List<Score> results = new ArrayList<Score>();
    private final List<String> warnings = new ArrayList<String>();
    private JSONObject status = new JSONObject();
    private static volatile RecognitionService runningService;

    static void cancel(Context context, String id, String token) {
        RecognitionService service = runningService;
        if (service != null && service.active && service.id.equals(id) && (token.isEmpty() || service.runId.equals(token))) {
            service.finishJob("canceled", "已取消识别，已完成的页面会保留", "");
        } else {
            try {
                JSONObject job = snapshot(context);
                if (id.equals(job.optString("id")) && (token.isEmpty() || token.equals(job.optString("runId")))) {
                    job.put("state", "canceled").put("message", "已取消识别，已完成的页面会保留");
                    context.getSharedPreferences("recognition", MODE_PRIVATE).edit().putString("job", job.toString()).apply();
                    context.sendBroadcast(new Intent(UPDATE).setPackage(context.getPackageName()));
                }
            } catch (Exception ignored) { }
        }
    }

    static JSONObject snapshot(Context context) {
        try {
            JSONObject job = new JSONObject(context.getSharedPreferences("recognition", MODE_PRIVATE).getString("job", "{}"));
            if ("running".equals(job.optString("state")) && job.optInt("process", -1) != android.os.Process.myPid())
                job.put("state", "error").put("message", "上次识别被系统中断，重新识别会接着已完成的页面处理").put("error", "应用进程已结束，页面缓存已保留");
            return job;
        } catch (Exception ignored) { return new JSONObject(); }
    }
    static String diagnostic(Context context) {
        return context.getSharedPreferences("recognition", MODE_PRIVATE).getString("diagnostic", "{}");
    }
    @Override public void onCreate() {
        super.onCreate();
        runningService = this;
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) { NotificationChannel channel = new NotificationChannel(CHANNEL, "手机识谱进度", NotificationManager.IMPORTANCE_LOW); channel.setDescription("离线识谱期间显示处理进度和取消按钮"); manager.createNotificationChannel(channel); }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (CANCEL.equals(intent.getAction())) {
            if (active && (intent.getStringExtra("id") == null || id.equals(intent.getStringExtra("id")))
                && (intent.getStringExtra("runId") == null || runId.equals(intent.getStringExtra("runId")))) finishJob("canceled", "已取消识别，已完成的页面会保留", "");
            else if (!active) stopSelf();
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        id = intent.getStringExtra("id");
        if (id == null || !id.matches("[a-fA-F0-9-]{36}")) { stopSelf(); return START_NOT_STICKY; }
        serviceStartId = startId;
        results.clear(); warnings.clear(); trace.clear(); loaded = false; acceptingPage = false; pages = page = 0; xmlBytes = 0;
        runId = UUID.randomUUID().toString(); active = true;
        Notification initial = notification("正在准备手机识谱…", 0);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION, initial);
        PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shipu:offline-score"); wakeLock.setReferenceCounted(false); wakeLock.acquire(3 * 60 * 60 * 1000L);
        rpc.startRun(runId); update("正在准备手机识谱，可切换到其他应用", 1);
        final String token = runId;
        worker.submit(() -> {
            try {
                repository = LibraryRepository.get(this); JSONObject item = repository.find(id);
                title = item.optString("title", "我的乐谱"); mime = item.optString("mime"); source = repository.source(item);
                if (!source.isFile()) throw new IOException("原始乐谱已不存在，请重新导入");
                pages = ScoreImageRenderer.pages(source, mime); if (pages < 1 || pages > 40) throw new IOException("一次支持1至40页，请将长PDF分段导入");
                checkpoint = repository.checkpointDirectory(id); checkpoint.mkdirs();
                String fingerprint = "6:" + source.length() + ":" + source.lastModified() + ":" + pages;
                String previous = LibraryRepository.read(new File(checkpoint, "source"), "", 1000);
                if (!fingerprint.equals(previous)) {
                    File[] files = checkpoint.listFiles(); if (files != null) for (File file : files) if (file.isFile()) file.delete();
                    LibraryRepository.write(new File(checkpoint, "source"), fingerprint);
                }
                page = 0; xmlBytes = 0;
                for (; page < pages; page++) {
                    File cached = new File(checkpoint, "page-" + page + ".musicxml"); if (!cached.isFile()) break;
                    try {
                        String xml = LibraryRepository.read(cached, "", 4 * 1024 * 1024);
                        Score part = parse(xml, page); results.add(part); xmlBytes += xml.length();
                    } catch (Exception invalid) { cached.delete(); break; }
                }
                if (!current(token)) return;
                if (page == pages) { mergeAndSave(token); return; }
                handler.post(() -> { if (!current(token)) return; update(page > 0 ? "接着第" + (page + 1) + "/" + pages + "页识别" : "正在载入内置模型，乐谱不会上传", progress(5)); createEngine(); });
            } catch (Exception error) { fail(token, error); }
            catch (OutOfMemoryError error) { fail(token, new Exception("手机可用内存不足，请关闭其他应用或将PDF分段导入")); }
        });
        return START_NOT_STICKY;
    }
    private boolean current(String token) { return active && runId.equals(token); }
    private int progress(int local) { return Math.max(1, Math.min(98, (int)(page * 95.0 / Math.max(1, pages) + local * 0.95 / Math.max(1, pages)))); }
    private void createEngine() {
        final String engineToken = runId;
        assets = new LocalAssetHost(this, rpc); web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.getSettings().setOffscreenPreRaster(true);
        if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.addJavascriptInterface(new EngineBridge(engineToken), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return assets.response(request.getUrl(), request.getMethod()); }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) { fail(engineToken, new Exception("识谱运行环境被系统回收，已完成页面已保存，请重新识别")); return true; }
        });
        web.loadUrl(LocalAssetHost.ORIGIN + "/omr-engine.html");
        final String token = runId;
        handler.postDelayed(() -> { if (current(token) && !loaded) fail(token, new Exception("手机识谱页面无法启动，请复制运行信息")); }, 45000);
    }
    private final class EngineBridge {
        private final String engineToken;
        EngineBridge(String engineToken) { this.engineToken = engineToken; }
        @JavascriptInterface public void deliverInferenceChunk(String token, String callId, int index, int total, String encoded) {
            if (!current(token)) return;
            try { rpc.deliverChunk(token, callId, index, total, encoded); }
            catch (LocalInferenceRpc.StaleRunException ignored) { }
            catch (IOException error) { fail(token, error); }
        }
        @JavascriptInterface public void postMessage(String text) {
            if (!current(engineToken)) return;
            if (text == null || text.length() > 5 * 1024 * 1024) { fail(engineToken, new Exception("识谱页面返回内容过大")); return; }
            try {
                JSONObject message = new JSONObject(text);
                handler.post(() -> { if (!current(engineToken)) return; try { receive(message); } catch (Exception error) { fail(engineToken, error); } });
            } catch (Exception error) { fail(engineToken, error); }
        }
    }
    private void receive(JSONObject message) throws Exception {
        if (!active) return;
        String action = message.optString("action");
        if ("engineLoaded".equals(action)) {
            if (loaded) return; loaded = true;
            command(new JSONObject().put("type", "start").put("id", id).put("runId", runId)); return;
        }
        if (!runId.equals(message.optString("runId")) || !id.equals(message.optString("id"))) return;
        if ("ready".equals(action)) { requestPage(runId); return; }
        if ("failed".equals(action)) { fail(runId, new Exception(message.optString("message", "识谱未完成"))); return; }
        if ("engineProgress".equals(action)) {
            String stage = message.optString("stage");
            record(stage, "");
            String text = "models".equals(stage) ? "正在载入 APK 内的乐谱模型" : "packages".equals(stage) ? "正在载入内置识谱组件" : "python".equals(stage) ? "正在准备本机运行环境" : "正在检查本机识谱通道";
            update(text + "，可在后台等待", progress(10)); return;
        }
        if ("diagnostic".equals(action)) { record(message.optString("stage"), message.optString("detail")); return; }
        if ("pageResult".equals(action)) {
            final int resultPage = message.optInt("page", -1); if (resultPage != page || acceptingPage) return;
            acceptingPage = true;
            final String xml = message.optString("xml"); final String token = runId;
            update("第" + (page + 1) + "/" + pages + "页已识别，正在保存", progress(90));
            worker.submit(() -> {
                try {
                    if (!current(token)) return;
                    if (xml.length() > 4 * 1024 * 1024 || (xmlBytes += xml.length()) > 12 * 1024 * 1024) throw new IOException("乐谱音符数据过多，请分段导入");
                    Score part = parse(xml, resultPage);
                    LibraryRepository.write(new File(checkpoint, "page-" + resultPage + ".musicxml"), xml);
                    if (!current(token)) return;
                    results.add(part);
                    JSONArray extra = message.optJSONArray("warnings"); if (extra != null) for (int i = 0; i < Math.min(extra.length(), 100); i++) { String warning = extra.optString(i); if (warning.length() <= 4000 && !warnings.contains(warning)) warnings.add(warning); }
                    page++;
                    if (!current(token)) return;
                    if (page >= pages) mergeAndSave(token); else requestPage(token);
                } catch (Exception error) { fail(token, error); }
            });
        }
    }
    private Score parse(String xml, int pageIndex) throws Exception {
        if (xml.isEmpty()) throw new IOException("第" + (pageIndex + 1) + "页没有有效音符");
        return MusicXmlParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), title + ".musicxml");
    }
    private void requestPage(String token) {
        final int requestedPage = page;
        handler.post(() -> { if (current(token)) { acceptingPage = false; update("正在识别第" + (requestedPage + 1) + "/" + pages + "页，可切换到其他应用", progress(20)); } });
        worker.submit(() -> {
            try {
                if (!current(token)) return; JSONObject image = ScoreImageRenderer.render(source, mime, requestedPage, true);
                image.put("type", "page").put("runId", token);
                handler.post(() -> { if (current(token)) command(image); });
            } catch (Exception error) { fail(token, error); }
            catch (OutOfMemoryError error) { fail(token, new Exception("这一页占用内存过多，请将PDF分段导入")); }
        });
    }
    private void mergeAndSave(String token) throws Exception {
        if (!current(token)) return;
        Score merged = ScorePageMerger.merge(results, title);
        for (String warning : warnings) if (!merged.warnings.contains(warning)) merged.warnings.add(warning);
        int noteCount = 0; for (Score.Measure measure : merged.measures) for (Score.Event event : measure.events) noteCount += event.notes.size();
        if (noteCount == 0) throw new IOException("没有识别到可练习音符，请使用清晰的五线谱图片");
        JSONObject encoded = ScoreJson.encode(merged); ScoreJson.decode(encoded);
        if (!current(token)) return; repository.updateScore(id, encoded, false, pages);
        handler.post(() -> { if (current(token)) finishJob("complete", "全部" + pages + "页识别完成，请打开乐谱校对后练习", ""); });
    }
    private void command(JSONObject data) { if (web != null && active) web.evaluateJavascript("window.engineCommand&&window.engineCommand(" + data + ")", null); }
    private void record(String stage, String detail) {
        if (trace.size() >= 35) trace.removeFirst(); trace.addLast(stage + " " + detail);
        saveDiagnostic("");
    }
    private void saveDiagnostic(String error) {
        try {
            JSONObject info = new JSONObject().put("app", "识谱助手 0.3.0").put("owner", "foreground-service").put("transport", "native-rpc")
                .put("phase", status.optString("state")).put("page", page).put("pages", pages).put("activeCalls", rpc.activeCalls())
                .put("bufferedBytes", rpc.bufferedBytes()).put("events", new JSONArray(trace)).put("lastError", error);
            if (assets != null) info.put("localAssets", assets.events());
            getSharedPreferences("recognition", MODE_PRIVATE).edit().putString("diagnostic", info.toString()).apply();
        } catch (Exception ignored) { }
    }
    private void update(String message, int percent) {
        try {
            status = new JSONObject().put("id", id).put("runId", runId).put("state", "running").put("message", message)
                .put("percent", percent).put("page", Math.min(page + 1, pages)).put("pages", pages).put("title", title == null ? "我的乐谱" : title).put("process", android.os.Process.myPid());
            publish(); ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification(message, percent));
        } catch (Exception ignored) { }
    }
    private void publish() {
        getSharedPreferences("recognition", MODE_PRIVATE).edit().putString("job", status.toString()).apply();
        sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
    }
    private Notification notification(String message, int progress) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("scoreId", id);
        PendingIntent content = PendingIntent.getActivity(this, NOTIFICATION, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancel = new Intent(this, RecognitionService.class).setAction(CANCEL).putExtra("id", id).putExtra("runId", runId);
        PendingIntent cancelAction = PendingIntent.getService(this, NOTIFICATION + 1, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(com.shipu.assistant.R.drawable.ic_launcher).setContentTitle("识谱助手 · 本机识谱").setContentText(message)
            .setContentIntent(content).setOngoing(active).setOnlyAlertOnce(true).setProgress(100, progress, pages == 0)
            .addAction(new Notification.Action.Builder(null, "取消识别", cancelAction).build()).build();
    }
    private void fail(String token, Exception failure) {
        handler.post(() -> { if (!current(token)) return; String detail = String.valueOf(failure.getMessage()); saveDiagnostic(detail);
            String text = describe(detail);
            finishJob("error", text, detail);
        });
    }
    /** Turns known transport reasons inside a Python traceback into an actionable message. */
    private static String describe(String detail) {
        String generic = "手机识谱未完成，已完成页面已保存。请复制运行信息以便排查";
        if (detail.contains("本机推理响应等待超时")) return "本机识谱计算超时，请重试；若反复超时请把这一页拆成更小的乐谱再识别";
        if (detail.contains("本机推理响应已关闭") || detail.contains("连接已中断")) return "本机识谱连接中断，请重新识别；已完成页面已保存";
        if (detail.contains("本机推理结果超过64MB") || detail.contains("结果过大")) return "这一页音符数据过多，请把这页拆小后重新识别";
        if (detail.length() > 180 || detail.contains("Traceback") || detail.contains("\n")) return generic;
        return detail;
    }
    private void finishJob(String state, String message, String error) {
        if (!active) return;
        active = false; rpc.cancelRun(runId);
        try { status.put("state", state).put("message", message).put("error", error).put("percent", "complete".equals(state) ? 100 : status.optInt("percent")); publish(); } catch (Exception ignored) { }
        releaseEngine();
        if ("complete".equals(state) || "error".equals(state)) {
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            Intent open = new Intent(this, MainActivity.class).putExtra("scoreId", id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Notification done = builder.setSmallIcon(com.shipu.assistant.R.drawable.ic_launcher).setContentTitle("识谱助手 · " + ("complete".equals(state) ? "识谱完成" : "识谱未完成"))
                .setContentText(message).setAutoCancel(true).setContentIntent(PendingIntent.getActivity(this, NOTIFICATION + 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build();
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION + 2, done);
        }
        stopForeground(true); stopSelf(serviceStartId);
    }
    private void releaseEngine() {
        if (web != null) { web.stopLoading(); web.removeJavascriptInterface("Android"); web.destroy(); web = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
    @Override public void onTimeout(int startId, int fgsType) { if (active) finishJob("error", "本次后台处理时间较长，已完成页面已保存，请重新开始", "Android后台任务时限已到"); else stopSelf(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (active) { try { status.put("state", "error").put("message", "识谱已中断，重新识别会接着已完成页面处理"); publish(); } catch (Exception ignored) { } }
        active = false; if (runningService == this) runningService = null; rpc.close(); worker.shutdownNow(); handler.removeCallbacksAndMessages(null); releaseEngine(); super.onDestroy();
    }
}
