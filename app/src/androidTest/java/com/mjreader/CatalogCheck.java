package com.mjreader;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import org.json.*;
import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** A local HTTPS OPDS server tests failover and actual downloads without public book payloads. */
final class CatalogCheck {
    private final Instrumentation test;
    private final Context context;
    private final java.util.concurrent.atomic.AtomicInteger transientRequests = new java.util.concurrent.atomic.AtomicInteger();
    CatalogCheck(Instrumentation test) { this.test = test; context = test.getTargetContext(); }
    void run(boolean live) throws Exception {
        String before = context.getSharedPreferences("reader", 0).getString("options", "{}"), catalogBefore = context.getSharedPreferences("reader", 0).getString("catalog", "auto");
        byte[] epub = epub(); String original = "Привет из каталога. Hello from the catalog. " + System.currentTimeMillis();
        byte[] slow = (original + "\n" + "Тестовая книга. Original story.\n".repeat(16000)).getBytes(StandardCharsets.UTF_8);
        SSLSocketFactory oldFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = test.getContext().getAssets().open("catalog-test.p12")) { keys.load(input, "test-only".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, "test-only".toCharArray());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        HttpsURLConnection.setDefaultSSLSocketFactory(ssl.getSocketFactory());
        SSLServerSocket server = (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"));
        String origin = "https://localhost:" + server.getLocalPort();
        ExecutorService requests = Executors.newCachedThreadPool(); List<String> imported = new ArrayList<>(), jobs = new ArrayList<>();
        requests.execute(() -> {
            while (!server.isClosed()) try {
                SSLSocket socket = (SSLSocket) server.accept(); requests.execute(() -> serve(socket, origin, epub, slow));
            } catch (IOException ignored) { }
        });
        try {
            require(Flibusta.catalog("https://example.test/").equals("https://example.test/opds"), "Bare mirror domain normalized");
            try { Flibusta.catalog("https://example.test/wrong"); throw new AssertionError("Unsupported catalog path accepted"); } catch (IOException expected) { }
            var mirrorPrefs = context.getSharedPreferences("qa-mirrors", 0);
            mirrorPrefs.edit().putString("catalog.mirrors", origin + "/good/opds\nhttp://invalid.test/opds\n" + origin + "/good/opds")
                    .putString("catalog.last", origin + "/good/opds").commit();
            require(Flibusta.mirrors(mirrorPrefs).size() == 3 && Flibusta.mirrors(mirrorPrefs).get(0).equals(origin + "/good/opds"), "Validated mirror list deduplication and preference");
            mirrorPrefs.edit().clear().commit();
            var failover = Flibusta.loadMirrors(origin + "/bad/opds/search?searchTerm=test", Arrays.asList(origin + "/bad/opds", origin + "/good/opds"));
            require(failover.base().endsWith("/good/opds") && failover.feed().entries().size() == 2, "Automatic mirror failover");
            var retried = Flibusta.loadMirrors(origin + "/flaky/opds", Collections.singletonList(origin + "/flaky/opds"));
            require(transientRequests.get() == 3 && !retried.feed().entries().isEmpty(), "Bounded HTTP 503 retries");
            var first = failover.feed().entries().get(0);
            require(first.book() && first.format().equals("epub") && first.author().equals("MJReader"), "OPDS EPUB preference and author");
            require(failover.feed().next().contains("page=2"), "Next-page URL");
            try { Flibusta.parse("<html>Blocked</html>".getBytes(StandardCharsets.UTF_8), origin); throw new AssertionError("HTML accepted as OPDS"); } catch (IOException expected) { }
            try { Flibusta.https("http://example.test/book.epub"); throw new AssertionError("Cleartext accepted"); } catch (IOException expected) { }
            String xxe = "<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///data/local/tmp/secret'>]><feed xmlns='http://www.w3.org/2005/Atom'><title>&x;</title></feed>";
            try { Flibusta.parse(xxe.getBytes(StandardCharsets.UTF_8), origin); throw new AssertionError("DTD entities accepted"); } catch (IOException expected) { }
            context.getSharedPreferences("reader", 0).edit().putString("catalog", origin + "/good/opds").commit();
            test.getUiAutomation().executeShellCommand("pm grant com.mjreader android.permission.POST_NOTIFICATIONS").close();
            for (int theme : new int[]{0, 2}) {
                context.getSharedPreferences("reader", 0).edit().putString("options", "{\"theme\":" + theme + "}").commit();
                CatalogActivity screen = (CatalogActivity) test.startActivitySync(new Intent(context, CatalogActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                test.waitForIdleSync(); capture("catalog-home-" + theme);
                EditText input = (EditText) field(screen, "input");
                test.runOnMainSync(() -> { input.requestFocus(); input.requestRectangleOnScreen(new android.graphics.Rect(0, 0, input.getWidth(), input.getHeight()), true);
                    ((android.view.inputmethod.InputMethodManager) screen.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT); });
                Thread.sleep(500); require(screen.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()), "Catalog search keyboard");
                test.runOnMainSync(() -> { input.setText("Орбита"); input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH); }); awaitUi(screen);
                require(((List<?>) field(screen, "entries")).size() >= 2, "UI search results");
                Object listRoot = field(screen, "root");
                long pagesUntil = System.currentTimeMillis() + 8000;
                while (((List<?>) field(screen, "entries")).size() < 3 && System.currentTimeMillis() < pagesUntil) {
                    ListView list = (ListView) field(screen, "resultsList");
                    test.runOnMainSync(() -> list.smoothScrollBy(700, 200)); Thread.sleep(300);
                }
                require(((List<?>) field(screen, "entries")).size() == 3 && field(screen, "root") == listRoot, "Infinite paging appends without rebuilding screen");
                test.runOnMainSync(() -> ((ListView) uncheckedField(screen, "resultsList")).setSelection(0)); test.waitForIdleSync(); capture("catalog-results-" + theme);
                test.runOnMainSync(() -> invoke(screen, "bookDetails", new Class[]{Flibusta.Entry.class}, first)); test.waitForIdleSync(); capture("catalog-book-" + theme);
                test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); test.waitForIdleSync();
                if (theme == 0) {
                    test.runOnMainSync(() -> invoke(screen, "download", new Class[]{Flibusta.Entry.class}, first)); awaitUi(screen);
                    String id = BookDownloads.create(context, first); jobs.add(id); JSONObject complete = waitState(id, "complete", 20000);
                    String bookId = complete.getString("book"); imported.add(bookId);
                    Books.Book book = new Books(context).list().stream().filter(b -> b.id().equals(bookId)).findFirst().orElseThrow();
                    require(book.title().equals("Орбита из каталога") && book.chapters().size() == 2 && book.cover() != null, "EPUB title, cover and chapters imported");
                    Thread.sleep(1300); capture("catalog-complete");
                    test.runOnMainSync(screen::finish);
                    CatalogActivity taskScreen = (CatalogActivity) test.startActivitySync(new Intent(context, CatalogActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("downloads", true));
                    var slowEntry = failover.feed().entries().get(1); String slowId = BookDownloads.create(context, slowEntry); jobs.add(slowId);
                    test.runOnMainSync(() -> BookDownloadService.command(context, "start", slowId));
                    long deadline = System.currentTimeMillis() + 10000;
                    while (BookDownloads.get(context, slowId).optLong("done") == 0 && System.currentTimeMillis() < deadline) Thread.sleep(100);
                    require(BookDownloads.get(context, slowId).optLong("done") > 0, "Stream progress persisted");
                    test.runOnMainSync(() -> BookDownloadService.command(context, "cancel", slowId)); waitState(slowId, "cancelled", 10000);
                    test.runOnMainSync(() -> BookDownloadService.command(context, "start", slowId));
                    test.runOnMainSync(taskScreen::finish); JSONObject retry = waitState(slowId, "complete", 20000); imported.add(retry.getString("book"));
                    require(new Books(context).list().stream().anyMatch(b -> b.text().contains(original)), "Background retry and full text import");
                } else {
                    Instrumentation.ActivityMonitor monitor = test.addMonitor(CatalogActivity.class.getName(), null, false); test.runOnMainSync(screen::recreate);
                    CatalogActivity restored = (CatalogActivity) test.waitForMonitorWithTimeout(monitor, 5000); test.removeMonitor(monitor); require(restored != null, "Screen recreated"); awaitUi(restored);
                    require(((List<?>) field(restored, "entries")).size() == 3, "All loaded pages retained after recreation"); test.runOnMainSync(restored::finish);
                }
            }
        } finally {
            for (String id : jobs) { context.stopService(new Intent(context, BookDownloadService.class)); if (!BookDownloadService.active(id)) BookDownloads.delete(BookDownloads.dir(context, id)); }
            for (String id : imported) Files.deleteIfExists(new File(context.getFilesDir(), "books/" + id + ".json").toPath());
            server.close(); requests.shutdownNow(); HttpsURLConnection.setDefaultSSLSocketFactory(oldFactory);
            context.getSharedPreferences("reader", 0).edit().putString("catalog", catalogBefore).putString("options", before).commit();
        }
        if (live) {
            var root = Flibusta.loadMirrors(Flibusta.DEFAULT, Flibusta.MIRRORS); require(!root.feed().entries().isEmpty(), "Live OPDS root");
            var authors = Flibusta.loadMirrors(Flibusta.searchUrl(root.base(), "Pushkin", true), Flibusta.mirrors(root.base()));
            require(authors.feed().entries().stream().anyMatch(e -> e.title().contains("Пушкин")), "Live author search");
            var books = Flibusta.loadMirrors(root.base() + "/new/0/new", Flibusta.mirrors(root.base()));
            require(books.feed().entries().stream().anyMatch(e -> e.format().equals("epub")), "Live book feed acquisition links");
        }
    }
    private void serve(SSLSocket socket, String origin, byte[] epub, byte[] slow) {
        try (socket) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)); String line = reader.readLine();
            if (line == null) return; String path = line.split(" ")[1]; while ((line = reader.readLine()) != null && !line.isEmpty()) { }
            int status = path.startsWith("/bad/") || path.startsWith("/flaky/") && transientRequests.getAndIncrement() < 2 ? 503 : 200;
            byte[] data = path.equals("/book.epub") ? epub : path.equals("/slow.txt") ? slow : feed(origin, path.contains("page=2")).getBytes(StandardCharsets.UTF_8);
            String mime = path.endsWith("epub") ? "application/epub+zip" : path.endsWith("txt") ? "text/plain" : "application/atom+xml";
            OutputStream output = socket.getOutputStream(); output.write(("HTTP/1.1 " + status + " Test\r\nContent-Type: " + mime + "\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            for (int pos = 0; pos < data.length; pos += 4096) { output.write(data, pos, Math.min(4096, data.length - pos)); output.flush(); if (path.equals("/slow.txt")) Thread.sleep(25); }
        } catch (Exception ignored) { }
    }
    private String feed(String origin, boolean second) {
        String result = "<feed xmlns='http://www.w3.org/2005/Atom'><title>Книги для нового путешествия</title><link rel='next' href='?page=2'/><entry><title>Орбита из каталога</title><author><name>MJReader</name></author><content>История о маяке на орбите. Original bilingual test story.</content><link rel='http://opds-spec.org/acquisition/open-access' type='application/fb2+zip' href='/not-used.fb2'/><link rel='http://opds-spec.org/acquisition/open-access' type='application/epub+zip' href='" + origin + "/book.epub'/></entry><entry><title>Долгая история</title><author><name>MJReader</name></author><link rel='http://opds-spec.org/acquisition/open-access' type='text/plain' href='" + origin + "/slow.txt'/></entry></feed>";
        if (second) result = result.replace("<link rel='next' href='?page=2'/>", "").replace("</feed>", "<entry><title>Новая история</title><author><name>MJReader</name></author><link rel='http://opds-spec.org/acquisition/open-access' type='application/epub+zip' href='" + origin + "/book.epub?edition=2'/></entry></feed>");
        return result;
    }
    private byte[] epub() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "META-INF/container.xml", "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>");
            entry(zip, "book.opf", "<package xmlns='http://www.idpf.org/2007/opf'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Орбита из каталога</dc:title><dc:creator>MJReader</dc:creator></metadata><manifest><item id='a' href='a.xhtml'/><item id='b' href='b.xhtml'/><item id='cover' href='cover.png' properties='cover-image'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>");
            entry(zip, "a.xhtml", "<html><body><h1>Начало</h1><p>Собственная тестовая история. " + System.currentTimeMillis() + "</p></body></html>"); entry(zip, "b.xhtml", "<html><body><h1>Возвращение</h1><p>Original test story.</p></body></html>");
            Bitmap cover = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888); cover.eraseColor(0xffabd960); zip.putNextEntry(new ZipEntry("cover.png")); cover.compress(Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry(); cover.recycle();
        }
        return bytes.toByteArray();
    }
    private void entry(ZipOutputStream zip, String name, String data) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(data.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
    private JSONObject waitState(String id, String state, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout; JSONObject job = null;
        while (System.currentTimeMillis() < end) { job = BookDownloads.get(context, id); if (job.optString("state").equals(state)) return job; if (job.optString("state").equals("error")) throw new AssertionError(job.toString()); Thread.sleep(100); }
        throw new AssertionError("Expected " + state + ": " + job);
    }
    private Object uncheckedField(Object object, String name) { try { return field(object, name); } catch (Exception error) { throw new RuntimeException(error); } }
    private Object field(Object object, String name) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private void invoke(Object object, String name, Class<?>[] types, Object... values) { try { var method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(object, values); } catch (Exception error) { throw new RuntimeException(error); } }
    private void awaitUi(CatalogActivity activity) throws Exception { long end = System.currentTimeMillis() + 60000; while ((boolean) field(activity, "busy") && System.currentTimeMillis() < end) Thread.sleep(100); require(!(boolean) field(activity, "busy"), "UI completed"); test.waitForIdleSync(); }
    private void capture(String name) throws Exception { Thread.sleep(300); try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "qa-" + name + ".png"))) { test.getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, out); } }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
