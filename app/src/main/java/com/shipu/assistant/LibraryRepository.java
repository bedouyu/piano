package com.shipu.assistant;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** One synchronized owner prevents foreground edits and recognition from overwriting each other. */
final class LibraryRepository {
    private static LibraryRepository instance;
    static synchronized LibraryRepository get(Context context) throws Exception {
        if (instance == null) instance = new LibraryRepository(context.getApplicationContext());
        return instance;
    }
    private final File root;
    private JSONArray library, history;
    private LibraryRepository(Context context) throws Exception {
        root = context.getFilesDir();
        library = new JSONArray(read(new File(root, "library.json"), "[]", 32 * 1024 * 1024));
        history = new JSONArray(read(new File(root, "history.json"), "[]", 2 * 1024 * 1024));
    }
    synchronized JSONArray library() throws Exception { return new JSONArray(library.toString()); }
    synchronized JSONArray history() throws Exception { return new JSONArray(history.toString()); }
    synchronized JSONObject find(String id) throws Exception {
        for (int i = 0; i < library.length(); i++) if (id.equals(library.getJSONObject(i).optString("id")))
            return new JSONObject(library.getJSONObject(i).toString());
        throw new Exception("没有找到这份乐谱");
    }
    synchronized void add(JSONObject item) throws Exception { library.put(new JSONObject(item.toString())); saveLibrary(); }
    synchronized void updateScore(String id, JSONObject score, boolean reviewed, int pages) throws Exception {
        for (int i = 0; i < library.length(); i++) {
            JSONObject item = library.getJSONObject(i);
            if (!id.equals(item.optString("id"))) continue;
            item.put("score", score).put("reviewed", reviewed).put("title", score.optString("title", item.optString("title")));
            if (pages > 0) item.put("recognitionSource", "device").put("recognitionPages", pages).put("recognizedAt", System.currentTimeMillis());
            saveLibrary(); return;
        }
        throw new Exception("这份乐谱已被删除，识别结果未保存");
    }
    synchronized void remove(String id) throws Exception {
        JSONObject item = find(id);
        if (item.has("file")) { File original = source(item); if (original.exists() && !original.delete()) throw new IOException("原始文件暂时无法删除，请稍后重试"); }
        for (int i = library.length() - 1; i >= 0; i--) if (id.equals(library.getJSONObject(i).optString("id"))) library.remove(i);
        deleteTree(checkpointDirectory(id));
        saveLibrary();
    }
    synchronized void addHistory(JSONObject session) throws Exception {
        history.put(session); while (history.length() > 100) history.remove(0);
        write(new File(root, "history.json"), history.toString());
    }
    File source(JSONObject item) throws Exception { return child(new File(root, "imports"), item.getString("file")); }
    File newSource(String id) throws Exception { File folder = new File(root, "imports"); folder.mkdirs(); return child(folder, id); }
    File checkpointDirectory(String id) throws Exception { return child(new File(root, "recognition"), id); }
    private static File child(File parent, String name) throws Exception {
        if (!name.matches("[a-fA-F0-9-]{36}")) throw new IOException("乐谱文件标识无效");
        File file = new File(parent, name).getCanonicalFile();
        if (!file.getParentFile().equals(parent.getCanonicalFile())) throw new IOException("文件位置无效");
        return file;
    }
    private void deleteTree(File folder) throws Exception {
        File expected = new File(root, "recognition").getCanonicalFile();
        if (!folder.getCanonicalFile().getParentFile().equals(expected)) throw new IOException("缓存位置无效");
        File[] children = folder.listFiles();
        if (children != null) for (File child : children) if (child.isFile()) child.delete();
        folder.delete();
    }
    private void saveLibrary() throws Exception { write(new File(root, "library.json"), library.toString()); }
    static String read(File file, String fallback, int limit) throws Exception {
        if (!file.isFile()) return fallback;
        try (InputStream in = new FileInputStream(file)) { return read(in, limit); }
    }
    static String read(InputStream in, int limit) throws Exception {
        if (in == null) throw new IOException("文件无法读取");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n, total = 0;
        while ((n = in.read(buffer)) != -1) { if ((total += n) > limit) throw new IOException("数据过大，请分段导入"); bytes.write(buffer, 0, n); }
        return bytes.toString("UTF-8");
    }
    static void write(File file, String text) throws Exception {
        File parent = file.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("保存目录无法创建");
        File pending = new File(parent, file.getName() + ".pending");
        try (FileOutputStream out = new FileOutputStream(pending)) { out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        if (!pending.renameTo(file)) throw new IOException("本地保存失败，请检查剩余空间");
    }
}
