package com.mjreader;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

final class BookDownloads {
    static final Object LOCK = new Object();
    static File root(Context context) { File dir = new File(context.getFilesDir(), "downloads"); dir.mkdirs(); return dir; }
    static File dir(Context context, String id) {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid download id");
        return new File(root(context), id);
    }
    static List<JSONObject> list(Context context) {
        synchronized (LOCK) {
            List<JSONObject> jobs = new ArrayList<>(); File[] dirs = root(context).listFiles(File::isDirectory);
            if (dirs != null) for (File directory : dirs) try { jobs.add(get(context, directory.getName())); } catch (Exception ignored) { }
            jobs.sort(Comparator.comparingLong((JSONObject j) -> j.optLong("created")).reversed()); return jobs;
        }
    }
    static JSONObject get(Context context, String id) throws Exception {
        synchronized (LOCK) { return new JSONObject(new String(new AtomicFile(new File(dir(context, id), "job.json")).readFully(), StandardCharsets.UTF_8)); }
    }
    static void save(Context context, JSONObject job) throws Exception {
        synchronized (LOCK) {
            File directory = dir(context, job.getString("id")); directory.mkdirs();
            AtomicFile file = new AtomicFile(new File(directory, "job.json")); FileOutputStream output = file.startWrite();
            try { output.write(job.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(output); }
            catch (Exception error) { file.failWrite(output); throw error; }
        }
    }
    static String create(Context context, Flibusta.Entry entry) throws Exception {
        if (!entry.book() || !(entry.format().equals("epub") || entry.format().equals("txt") || entry.format().equals("fb2"))) throw new IOException("Нет EPUB, FB2 или TXT / No EPUB, FB2 or TXT available");
        Flibusta.https(entry.url());
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(entry.url().getBytes(StandardCharsets.UTF_8))) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        String id = hash.toString();
        synchronized (LOCK) {
            if (new File(dir(context, id), "job.json").exists()) return id;
            save(context, new JSONObject().put("id", id).put("title", entry.title()).put("author", entry.author()).put("url", entry.url())
                    .put("format", entry.format()).put("state", "queued").put("done", 0).put("total", -1).put("created", System.currentTimeMillis()));
        }
        return id;
    }
    static String size(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0);
    }
    static void delete(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) { Files.deleteIfExists(file.toPath()); return; }
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child);
        Files.deleteIfExists(file.toPath());
    }
}
