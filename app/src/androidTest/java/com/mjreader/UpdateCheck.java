package com.mjreader;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Local release fixtures: never contacts GitHub and never replaces the running test app. */
final class UpdateCheck {
    private final Instrumentation test;
    UpdateCheck(Instrumentation test) { this.test = test; }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Unsafe update accepted");
    }
    private static JSONObject release(String version) throws Exception {
        return new JSONObject().put("tag_name", "v" + version).put("draft", false).put("prerelease", true)
                .put("body", "New reading features. Your library stays with you.")
                .put("assets", new JSONArray().put(new JSONObject().put("name", "MJReader-" + version + ".apk")
                        .put("browser_download_url", AppUpdates.DOWNLOADS + "v" + version + "/MJReader-" + version + ".apk")
                        .put("state", "uploaded").put("digest", "sha256:" + "a".repeat(64)).put("size", 128)));
    }
    void run(String fixture) throws Exception {
        Context context = test.getTargetContext();
        SharedPreferences prefs = AppUpdates.prefs(context), reader = context.getSharedPreferences("reader", 0);
        String oldOptions = reader.getString("options", "{}");
        UpdateActivity screen = null; MainActivity library = null; File apk = null;
        try {
            checkEstimate();
            if (AppUpdates.compare("0.10.0", "0.9.99") <= 0 || AppUpdates.compare("1.0.0", "1.0.0") != 0) throw new AssertionError("Semantic version ordering");
            rejects(() -> AppUpdates.compare("../1", "1.0.0"));
            JSONArray releases = new JSONArray().put(release("0.9.0")).put(release("0.10.0")).put(release("0.8.8"));
            if (!AppUpdates.select(releases, "0.8.8").version().equals("0.10.0") || AppUpdates.select(releases, "0.10.0") != null) throw new AssertionError("Prerelease selection / downgrade");
            JSONObject draft = release("2.0.0").put("draft", true); releases.put(draft);
            if (!AppUpdates.select(releases, "0.8.8").version().equals("0.10.0")) throw new AssertionError("Draft selected");
            for (int invalid = 0; invalid < 5; invalid++) {
                JSONObject item = release("9.0.0"), asset = item.getJSONArray("assets").getJSONObject(0);
                if (invalid == 0) asset.put("browser_download_url", "https://evil.example/MJReader-9.0.0.apk");
                if (invalid == 1) asset.put("browser_download_url", AppUpdates.DOWNLOADS.replace("https:", "http:") + "v9.0.0/MJReader-9.0.0.apk");
                if (invalid == 2) asset.remove("digest");
                if (invalid == 3) asset.put("size", AppUpdates.MAX_APK + 1);
                if (invalid == 4) asset.put("state", "new");
                if (AppUpdates.select(new JSONArray().put(item), "0.8.8") != null) throw new AssertionError("Invalid asset selected " + invalid);
            }
            AppUpdates.Release update = AppUpdates.select(new JSONArray().put(release("99.0.0")), "0.8.8");
            if (!AppUpdates.decode(update.json().toString()).equals(update)) throw new AssertionError("Release persistence");
            JSONObject manifest = update.json().put("schemaVersion", 1);
            if (!update.equals(AppUpdates.selectManifest(manifest.toString(), "0.8.8"))
                    || AppUpdates.selectManifest(manifest.toString(), update.version()) != null)
                throw new AssertionError("Update manifest selection / installed version");
            rejects(() -> AppUpdates.selectManifest(manifest.put("schemaVersion", 2).toString(), "0.8.8"));
            rejects(() -> AppUpdates.selectManifest(manifest.put("schemaVersion", 1).put("url", "https://example.test/update.apk").toString(), "0.8.8"));
            java.net.URL source = new java.net.URL(AppUpdates.RELEASES + "/latest/download/update.json");
            if (!AppUpdates.manifestRedirect(source, "/MJSasha/mj-reader/releases/download/v99.0.0/update.json").getHost().equals("github.com")
                    || !AppUpdates.manifestRedirect(source, "https://release-assets.githubusercontent.com/update.json").getProtocol().equals("https"))
                throw new AssertionError("GitHub update redirect rejected");
            rejects(() -> AppUpdates.manifestRedirect(source, "http://github.com/update.json"));
            rejects(() -> AppUpdates.manifestRedirect(source, "https://github.com:444/update.json"));
            rejects(() -> AppUpdates.manifestRedirect(source, "https://github.com@example.test/update.json"));
            PackageInfo current = AppUpdates.installed(context);
            PackageInfo candidate = new PackageInfo(); candidate.packageName = current.packageName; candidate.versionName = update.version();
            candidate.versionCode = (int) AppUpdates.code(current) + 1; candidate.signatures = current.signatures;
            candidate.applicationInfo = new ApplicationInfo();
            AppUpdates.verifyPackage(current, candidate, update);
            candidate.packageName = "com.example.other"; rejects(() -> AppUpdates.verifyPackage(current, candidate, update)); candidate.packageName = current.packageName;
            candidate.signatures = new Signature[]{new Signature(new byte[]{1,2,3})}; rejects(() -> AppUpdates.verifyPackage(current, candidate, update)); candidate.signatures = current.signatures;
            candidate.versionCode = (int) AppUpdates.code(current); rejects(() -> AppUpdates.verifyPackage(current, candidate, update)); candidate.versionCode++;
            candidate.versionName = "98.0.0"; rejects(() -> AppUpdates.verifyPackage(current, candidate, update)); candidate.versionName = update.version();
            candidate.applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE; rejects(() -> AppUpdates.verifyPackage(current, candidate, update));
            prefs.edit().putBoolean("automatic", true).remove("attempt").commit();
            long now = System.currentTimeMillis();
            if (!AppUpdates.automaticDue(context, now) || AppUpdates.automaticDue(context, now + 1000) || !AppUpdates.automaticDue(context, now + 86400001L)) throw new AssertionError("Daily check limit");
            prefs.edit().putBoolean("automatic", false).commit();
            if (AppUpdates.automaticDue(context, now + 172800001L)) throw new AssertionError("Automatic checks disabled");
            prefs.edit().putString("release", update.json().toString()).commit();
            library = (MainActivity) test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity main = library;
            boolean[] loaded = {false};
            for (int i = 0; i < 100 && !loaded[0]; i++) {
                test.runOnMainSync(() -> loaded[0] = main.getWindow().getDecorView().findViewWithTag(ReaderNavigation.TAG) != null);
                if (!loaded[0]) Thread.sleep(50);
            }
            if (!loaded[0]) throw new AssertionError("Library did not load for update offer");
            Intent offer = UpdateActivity.offer(main, update);
            AlertDialog[] dialog = {null};
            test.runOnMainSync(() -> dialog[0] = main.showUpdateOffer(update.version(), offer));
            test.waitForIdleSync();
            screenshot(context, "updates-offer");
            test.runOnMainSync(() -> dialog[0].getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            if (prefs.contains("download")) throw new AssertionError("Dismissed offer started a download");
            screen = openUpdate(() -> {
                View decor = main.getWindow().getDecorView();
                decor.findViewWithTag(ReaderNavigation.Tab.SETTINGS).performClick();
                decor.findViewWithTag("updates-entry").performClick();
            });
            if (prefs.contains("download")) throw new AssertionError("Manual Updates entry started a download");
            test.runOnMainSync(screen::finish); screen = null;
            test.runOnMainSync(() -> main.getWindow().getDecorView().findViewWithTag(ReaderNavigation.Tab.LIBRARY).performClick());
            for (int theme : new int[]{0, 2}) {
                reader.edit().putString("options", "{\"theme\":" + theme + "}").commit();
                screen = (UpdateActivity) test.startActivitySync(new Intent(context, UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                UpdateActivity active = screen; test.waitForIdleSync();
                test.runOnMainSync(() -> {
                    Button download = button(active.getWindow().getDecorView(), "Download update");
                    if (download == null || !download.isEnabled() || download.getHeight() < 48 * context.getResources().getDisplayMetrics().density) throw new AssertionError("Update action / target size");
                });
                test.runOnMainSync(() -> {
                    ScrollView page = active.findViewById(android.R.id.list);
                    Button download = button(active.getWindow().getDecorView(), "Download update");
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    download.getDrawingRect(bounds); page.offsetDescendantRectToMyCoords(download, bounds);
                    page.scrollTo(0, bounds.top);
                });
                test.waitForIdleSync(); Thread.sleep(250);
                test.runOnMainSync(() -> {
                    android.graphics.Rect visible = new android.graphics.Rect();
                    if (!button(active.getWindow().getDecorView(), "Download update").getGlobalVisibleRect(visible)
                            || visible.height() < 48 * context.getResources().getDisplayMetrics().density) throw new AssertionError("Download action is not reachable by scrolling");
                });
                screenshot(context, "updates-" + theme);
                test.runOnMainSync(active::finish); screen = null; test.waitForIdleSync();
            }
            // A DownloadManager record survives closing the screen; a checksum-valid non-APK is still rejected.
            apk = AppUpdates.file(context, update); apk.getParentFile().mkdirs();
            byte[] bytes = "This is not an APK".getBytes(StandardCharsets.UTF_8);
            java.nio.file.Files.write(apk.toPath(), bytes);
            StringBuilder hex = new StringBuilder(); for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            AppUpdates.Release invalidApk = new AppUpdates.Release(update.version(), update.notes(), update.url(), hex.toString(), bytes.length);
            AppUpdates.verifyDigest(apk, invalidApk);
            File file = apk;
            rejects(() -> AppUpdates.verifyDigest(file, update));
            rejects(() -> AppUpdates.verify(context, invalidApk));
            DownloadManager manager = context.getSystemService(DownloadManager.class);
            long id = manager.addCompletedDownload("MJReader test", "Local update validation fixture", false,
                    "application/vnd.android.package-archive", apk.getAbsolutePath(), apk.length(), true);
            prefs.edit().putLong("download", id).putString("pending", invalidApk.json().toString()).commit();
            screen = (UpdateActivity) test.startActivitySync(new Intent(context, UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            UpdateActivity active = screen;
            boolean rejected = false;
            for (int i = 0; i < 100 && !rejected; i++) {
                boolean[] retry = {false}; test.runOnMainSync(() -> { Button button = button(active.getWindow().getDecorView(), "Retry download"); retry[0] = button != null && button.isEnabled(); });
                rejected = retry[0]; if (!rejected) Thread.sleep(50);
            }
            if (!rejected) throw new AssertionError("Downloaded invalid APK was not rejected");
            test.runOnMainSync(() -> button(active.getWindow().getDecorView(), "Cancel download").performClick());
            for (int i = 0; i < 100 && prefs.contains("download"); i++) Thread.sleep(50);
            if (prefs.contains("download")) throw new AssertionError("Update cancellation did not clear persisted transfer");
            test.runOnMainSync(active::finish); screen = null; test.waitForIdleSync();
            if (fixture != null) {
                screen = openUpdate(() -> main.showUpdateOffer(update.version(), offer).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                for (int i = 0; i < 100 && !prefs.contains("download"); i++) Thread.sleep(50);
                long enqueued = prefs.getLong("download", -1);
                if (enqueued < 0 || !AppUpdates.decode(prefs.getString("pending", "")).equals(update))
                    throw new AssertionError("Accepted offer did not enqueue the proposed release");
                test.runOnMainSync(screen::finish); screen = null;
                screen = openUpdate(() -> main.showUpdateOffer(update.version(), offer).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                Thread.sleep(1200);
                if (prefs.getLong("download", -1) != enqueued) throw new AssertionError("Accepting the same offer replaced its download");
                screenshot(context, "updates-offer-transfer");
                test.runOnMainSync(screen::finish); screen = null;
                AppUpdates.cancel(context);
                // Optional locally generated, signed higher-version APK. Never install over the test app.
                byte[] signed = java.nio.file.Files.readAllBytes(new File(fixture).toPath());
                java.nio.file.Files.write(apk.toPath(), signed);
                StringBuilder hash = new StringBuilder(); for (byte value : MessageDigest.getInstance("SHA-256").digest(signed)) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                AppUpdates.Release valid = new AppUpdates.Release(update.version(), update.notes(), update.url(), hash.toString(), signed.length);
                AppUpdates.verify(context, valid);
                long completeId = manager.addCompletedDownload("MJReader test", "Signed local update fixture", false,
                        "application/vnd.android.package-archive", apk.getAbsolutePath(), apk.length(), true);
                prefs.edit().putLong("download", completeId).putString("pending", valid.json().toString()).commit();
                Intent readyOffer = UpdateActivity.offer(main, valid);
                screen = openUpdate(() -> main.showUpdateOffer(valid.version(), readyOffer).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                boolean allowed = context.getPackageManager().canRequestPackageInstalls();
                boolean permission = false;
                for (int i = 0; i < 100 && !permission; i++) {
                    var window = test.getUiAutomation().getRootInActiveWindow();
                    permission = window != null && (allowed
                            ? window.getPackageName() != null && window.getPackageName().toString().contains("packageinstaller")
                                    && window.findAccessibilityNodeInfosByText("Update").stream().anyMatch(node -> node.getText() != null && node.getText().toString().equalsIgnoreCase("Update") && node.isEnabled())
                            : !window.findAccessibilityNodeInfosByText("Allow updates").isEmpty());
                    if (!permission) Thread.sleep(50);
                }
                if (!permission) throw new AssertionError("Install permission explanation missing");
                if (prefs.getLong("download", -1) != completeId) throw new AssertionError("Verified APK was downloaded again");
                screenshot(context, allowed ? "updates-installer" : "updates-permission");
                if (allowed) test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            }
        } finally {
            if (screen != null) { UpdateActivity end = screen; test.runOnMainSync(end::finish); }
            if (library != null) test.runOnMainSync(library::finish);
            AppUpdates.cancel(context); if (apk != null) apk.delete();
            prefs.edit().clear().putBoolean("automatic", false).commit(); reader.edit().putString("options", oldOptions).commit();
        }
    }
    private static void checkEstimate() {
        AppUpdates.DownloadEta eta = new AppUpdates.DownloadEta();
        if (eta.sample(1, 0, 0, 1000) != -1 || eta.sample(1, 1000, 100, 1000) != -1
                || eta.sample(1, 2000, 200, 1000) != 8 || eta.sample(1, 3000, 200, 1000) != -1
                || eta.sample(1, 4000, 250, -1) != -1 || eta.sample(1, 5000, 300, 1000) != -1
                || eta.sample(2, 6000, 400, 1000) != -1 || eta.sample(2, 7000, 500, 1000) != -1
                || eta.sample(2, 8000, 600, 1000) != 4 || eta.sample(2, 9000, 1000, 1000) != -1)
            throw new AssertionError("Download ETA requires two active intervals for the same known-size transfer");
    }
    private UpdateActivity openUpdate(Runnable open) {
        Instrumentation.ActivityMonitor monitor = test.addMonitor(UpdateActivity.class.getName(), null, false);
        try {
            test.runOnMainSync(open);
            UpdateActivity opened = (UpdateActivity) test.waitForMonitorWithTimeout(monitor, 5000);
            if (opened == null) throw new AssertionError("Updates screen did not open");
            return opened;
        } finally { test.removeMonitor(monitor); }
    }
    private Button button(View view, String label) {
        if (view instanceof Button button && button.getText().toString().equals(label)) return button;
        if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { Button found = button(group.getChildAt(i), label); if (found != null) return found; }
        return null;
    }
    private void screenshot(Context context, String name) throws Exception {
        Bitmap bitmap = test.getUiAutomation().takeScreenshot();
        if (bitmap != null) try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "qa-" + name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
}
