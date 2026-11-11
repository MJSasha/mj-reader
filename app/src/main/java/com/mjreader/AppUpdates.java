package com.mjreader;

import android.app.DownloadManager;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Environment;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** GitHub release discovery and verified, user-requested Android updates. */
final class AppUpdates {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    static final long MAX_APK = 32L * 1024 * 1024;
    static final String API = "https://api.github.com/repos/MJSasha/mj-reader/releases?per_page=20";
    static final String DOWNLOADS = "https://github.com/MJSasha/mj-reader/releases/download/";
    record Release(String version, String notes, String url, String sha256, long size) {
        JSONObject json() throws JSONException {
            return new JSONObject().put("version", version).put("notes", notes).put("url", url).put("sha256", sha256).put("size", size);
        }
    }
    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("updates", 0); }
    static PackageInfo installed(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
    }
    static String version(Context context) {
        try { return installed(context).versionName; } catch (Exception e) { return "0.0.0"; }
    }
    static int compare(String left, String right) {
        if (!validVersion(left) || !validVersion(right)) throw new IllegalArgumentException("Invalid version");
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int comparison = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }
    private static boolean validVersion(String value) { return value != null && value.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"); }
    private static Release validated(String version, String notes, String url, String digest, long size) throws IOException {
        if (!validVersion(version) || !url.equals(DOWNLOADS + "v" + version + "/MJReader-" + version + ".apk")
                || !digest.matches("[a-fA-F0-9]{64}") || size <= 0 || size > MAX_APK) throw new IOException("Invalid release asset");
        return new Release(version, notes.substring(0, Math.min(notes.length(), 12000)), url, digest.toLowerCase(Locale.ROOT), size);
    }
    static Release select(JSONArray releases, String current) throws JSONException {
        Release best = null;
        for (int i = 0; i < releases.length(); i++) try {
            JSONObject item = releases.getJSONObject(i);
            String tag = item.optString("tag_name"), version = tag.startsWith("v") ? tag.substring(1) : "";
            // 0.x releases are intentionally marked prerelease; include them, but never drafts.
            if (item.optBoolean("draft") || !validVersion(version) || compare(version, current) <= 0
                    || best != null && compare(version, best.version()) <= 0) continue;
            JSONArray assets = item.getJSONArray("assets");
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                String digest = asset.optString("digest");
                if (!asset.optString("name").equals("MJReader-" + version + ".apk") || !asset.optString("state").equals("uploaded") || !digest.startsWith("sha256:")) continue;
                best = validated(version, item.optString("body"), asset.optString("browser_download_url"), digest.substring(7), asset.optLong("size"));
                break;
            }
        } catch (IOException | JSONException | IllegalArgumentException ignored) { /* Incomplete releases are not installable. */ }
        return best;
    }
    static Release cached(Context context) {
        try { return decode(prefs(context).getString("release", "")); } catch (Exception e) { return null; }
    }
    static Release decode(String json) throws Exception {
        JSONObject item = new JSONObject(json);
        return validated(item.getString("version"), item.optString("notes"), item.getString("url"), item.getString("sha256"), item.getLong("size"));
    }
    static Release check(Context context) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "MJReader/" + version(context));
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("GitHub HTTP " + status);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024) throw new IOException("Release response too large");
                    output.write(buffer, 0, count);
                }
            }
            Release release = select(new JSONArray(output.toString(StandardCharsets.UTF_8.name())), version(context));
            SharedPreferences.Editor edit = prefs(context).edit().putLong("checked", System.currentTimeMillis());
            if (release == null) edit.remove("release"); else edit.putString("release", release.json().toString());
            edit.commit();
            return release;
        } finally { connection.disconnect(); }
    }
    static synchronized boolean automaticDue(Context context, long now) {
        SharedPreferences prefs = prefs(context);
        long previous = prefs.getLong("attempt", 0);
        if (!prefs.getBoolean("automatic", true) || now >= previous && now - previous < 24L * 60 * 60 * 1000) return false;
        prefs.edit().putLong("attempt", now).apply(); return true;
    }
    static File file(Context context, Release release) throws IOException {
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) throw new IOException("Download storage unavailable");
        return new File(directory, "MJReader-" + release.version() + ".apk");
    }
    static synchronized long download(Context context, Release release) throws Exception {
        cancel(context);
        File destination = file(context, release);
        if (destination.exists() && !destination.delete()) throw new IOException("Cannot replace download");
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.url()))
                .setTitle("MJReader " + release.version()).setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, destination.getName());
        long id = manager.enqueue(request);
        if (!prefs(context).edit().putLong("download", id).putString("pending", release.json().toString()).commit()) {
            manager.remove(id); throw new IOException("Cannot save download");
        }
        return id;
    }
    static synchronized void cancel(Context context) {
        long id = prefs(context).getLong("download", -1);
        if (id >= 0) context.getSystemService(DownloadManager.class).remove(id);
        prefs(context).edit().remove("download").remove("pending").commit();
    }
    static void verifyDigest(File file, Release release) throws Exception {
        if (file.length() != release.size() || file.length() > MAX_APK) throw new IOException("APK size mismatch");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int count; long total = 0;
            while ((count = input.read(buffer)) != -1) {
                if ((total += count) > MAX_APK) throw new IOException("APK too large");
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder(); for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (!hex.toString().equals(release.sha256())) throw new IOException("APK checksum mismatch");
    }
    static long code(PackageInfo info) { return android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    static void verifyPackage(PackageInfo current, PackageInfo candidate, Release release) throws IOException {
        if (candidate == null || !"com.mjreader".equals(candidate.packageName) || !candidate.packageName.equals(current.packageName)
                || !release.version().equals(candidate.versionName) || code(candidate) <= code(current)
                || candidate.applicationInfo == null || (candidate.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                || current.signatures == null || candidate.signatures == null || current.signatures.length == 0
                || !new HashSet<>(Arrays.asList(current.signatures)).equals(new HashSet<>(Arrays.asList(candidate.signatures))))
            throw new IOException("APK identity, version or signature mismatch");
    }
    static void verify(Context context, Release release) throws Exception {
        File apk = file(context, release); verifyDigest(apk, release);
        verifyPackage(installed(context), context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES), release);
    }
}
