package com.mjreader;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.*;
import java.util.zip.*;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;

public class ReaderSmokeTest extends Instrumentation {
    private boolean visualOnly, sampleOnly, readerOnly, benchmarkOnly, catalogOnly, liveCatalog, adaptiveOnly, showcaseOnly;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        showcaseOnly = arguments != null && arguments.getString("showcase", "false").equals("true");
        adaptiveOnly = arguments != null && arguments.getString("adaptive", "false").equals("true");
        catalogOnly = arguments != null && arguments.getString("catalog", "false").equals("true");
        liveCatalog = arguments != null && arguments.getString("live", "false").equals("true");
        benchmarkOnly = arguments != null && arguments.getString("benchmark", "false").equals("true");
        readerOnly = arguments != null && arguments.getString("reader", "false").equals("true");
        sampleOnly = arguments != null && arguments.getString("sample", "false").equals("true");
        visualOnly = arguments != null && arguments.getString("visual", "false").equals("true");
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            if (showcaseOnly) { showcase(); result.putString("stream", "PASS: original demonstration screenshots\n"); finish(Activity.RESULT_OK, result); return; }
            if (adaptiveOnly) { adaptiveCheck(); result.putString("stream", "PASS: phone/Fold/tablet resize, grid, reader position/fullscreen, catalog retention, hover/focus and touch targets\n"); finish(Activity.RESULT_OK, result); return; }
            if (catalogOnly) { new CatalogCheck(this).run(liveCatalog); result.putString("stream", "PASS: OPDS failover, HTTPS EPUB download, cover/chapters import, cancellation/retry, background import, themed UI and recreation\n"); finish(Activity.RESULT_OK, result); return; }
            if (benchmarkOnly) {
                benchmarkCheck(result);
                finish(Activity.RESULT_OK, result); return;
            }
            if (readerOnly) {
                readerCheck();
                result.putString("stream", "PASS: bounded text windows, long-press book menu, typography, Dusk, quotes, volume/tap paging, fullscreen anchor and search keyboard\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (sampleOnly) {
                sampleCheck();
                result.putString("stream", "PASS: supplied EPUB cover, 63 chapter anchors, legacy metadata repair, duplicate detection and reading position\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (visualOnly) {
                visualCheck();
                result.putString("stream", "PASS: themed library grid, settings, custom menus and scrollable actions\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            Books store = new Books(context);
            for (Books.Book book : store.list()) {
                if (new ReadingText(book.text()).words.isEmpty()) throw new AssertionError("Empty book");
            }
            String text = "Привет, мир! Hello, world! Ёж идёт домой. Don't stop. Run " + System.currentTimeMillis();
            checkImport(store, "mj-test-utf8.txt", text.getBytes(StandardCharsets.UTF_8), text);
            checkImport(store, "mj-test-cp1251.txt", (text + " CP").getBytes(Charset.forName("windows-1251")), text + " CP");
            checkImport(store, "mj-test-utf16.txt", (text + " UTF16").getBytes(StandardCharsets.UTF_16), text + " UTF16");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                entry(zip, "META-INF/container.xml", "<?xml version='1.0'?><!DOCTYPE container SYSTEM 'https://invalid.example/unreachable.dtd'><container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>");
                entry(zip, "OPS/book.opf", "<package xmlns='http://www.idpf.org/2007/opf'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Emulator EPUB</dc:title></metadata><manifest><item id='b' href='second.xhtml'/><item id='a' href='first.xhtml'/><item id='nav' href='nav.xhtml' properties='nav'/><item id='cover' href='cover.png' properties='cover-image'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>");
                entry(zip, "OPS/first.xhtml", "<html><body><h1>Первая глава</h1><p>Первая глава. First chapter. " + text + "</p></body></html>");
                entry(zip, "OPS/nav.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><body><nav epub:type='toc'><ol><li><a href='first.xhtml'>Начало</a></li><li><a href='second.xhtml#second'>Продолжение</a></li></ol></nav></body></html>");
                android.graphics.Bitmap cover = android.graphics.Bitmap.createBitmap(10, 20, android.graphics.Bitmap.Config.ARGB_8888);
                cover.eraseColor(android.graphics.Color.GREEN);
                zip.putNextEntry(new ZipEntry("OPS/cover.png"));
                cover.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip);
                zip.closeEntry();
                entry(zip, "OPS/second.xhtml", "<html><body><p id='second'>Вторая глава. Second chapter.</p></body></html>");
            }
            Books.Book epub = checkImport(store, "mj-test.epub", buffer.toByteArray(), "Первая глава.");
            if (epub.text().indexOf("Первая") >= epub.text().indexOf("Вторая")) throw new AssertionError("Spine order");
            verifyCoverVariants(epub.cover());
            if (epub.cover() == null || !epub.chapters().get(0).title().equals("Начало")) throw new AssertionError("EPUB cover/navigation");
            if (epub.chapters().size() != 2 || epub.chapters().get(1).offset() <= 0) throw new AssertionError("Chapter positions");
            try { checkImport(store, "duplicate.epub", buffer.toByteArray(), "Первая глава."); throw new AssertionError("Duplicate accepted"); }
            catch (Books.DuplicateBook expected) {}
            File legacyFile = new File(context.getFilesDir(), "books/" + epub.id() + ".json");
            JSONObject legacy = new JSONObject(new String(java.nio.file.Files.readAllBytes(legacyFile.toPath()), StandardCharsets.UTF_8));
            legacy.remove("cover"); // Retain fingerprint to reproduce blocked cover repair in 0.4.0.
            java.nio.file.Files.write(legacyFile.toPath(), legacy.toString().getBytes(StandardCharsets.UTF_8));
            Books.Book upgraded = checkImport(store, "legacy-upgrade.epub", buffer.toByteArray(), "Первая глава.");
            if (!upgraded.id().equals(epub.id()) || !upgraded.text().equals(epub.text()) || upgraded.chapters().size() != 2)
                throw new AssertionError("Legacy enrichment changed identity/text");
            JSONObject missingNavigation = new JSONObject(new String(java.nio.file.Files.readAllBytes(legacyFile.toPath()), StandardCharsets.UTF_8));
            missingNavigation.remove("chapters");
            java.nio.file.Files.write(legacyFile.toPath(), missingNavigation.toString().getBytes(StandardCharsets.UTF_8));
            Books.Book navigationRepair = checkImport(store, "navigation-repair.epub", buffer.toByteArray(), "Первая глава.");
            if (!navigationRepair.id().equals(epub.id()) || !navigationRepair.chapters().equals(epub.chapters())
                    || !java.util.Arrays.equals(navigationRepair.cover(), epub.cover()))
                throw new AssertionError("Cover blocked chapter repair");
            android.content.SharedPreferences prefs = context.getSharedPreferences("reader", 0);
            prefs.edit().putBoolean("grid", true).remove("favorite.demo-en").remove("options.demo-en").remove("options.demo-ru").putInt("anchor.demo-en", 0).putInt("anchor.demo-ru", 0).putString("options", "{\"countdown\":false}").apply();
            MainActivity activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            awaitField(activity, "librarySearch");
            Uri first = createDocument("bulk-one.txt", (text + " bulk-one").getBytes(StandardCharsets.UTF_8));
            Uri second = createDocument("bulk-two.txt", (text + " bulk-two").getBytes(StandardCharsets.UTF_8));
            Uri invalid = createDocument("invalid.epub", new byte[]{1, 2, 3});
            try {
                ClipData clip = new ClipData("Books", new String[]{"text/plain"}, new ClipData.Item(first));
                clip.addItem(new ClipData.Item(second)); clip.addItem(new ClipData.Item(invalid));
                Intent batch = new Intent();
                batch.setClipData(clip);
                runOnMainSync(() -> activity.onActivityResult(1, Activity.RESULT_OK, batch));
                for (int i = 0; i < 100 && (boolean) field(activity, "importing"); i++) Thread.sleep(100);
                if ((boolean) field(activity, "importing")) throw new AssertionError("Bulk import stuck");
                clickSystem("OK");
                if (store.list().stream().filter(book -> book.text().contains(text + " bulk-")).count() != 2)
                    throw new AssertionError("Partial batch failed");
            } finally {
                context.getContentResolver().delete(first, null, null);
                context.getContentResolver().delete(second, null, null);
                context.getContentResolver().delete(invalid, null, null);
            }
            runOnMainSync(() -> ((EditText) field(activity, "librarySearch")).setText("The Quiet Orbit"));
            runOnMainSync(() -> ((ListView) field(activity, "libraryList")).setSelection(1));
            awaitText(activity, "The Quiet Orbit");
            click(activity, "The Quiet Orbit");
            awaitField(activity, "reading");
            waitForIdleSync();
            switchMode(activity, true);
            click(activity, context.getString(R.string.start));
            Thread.sleep(1100);
            click(activity, context.getString(R.string.pause));
            int advanced = (int) field(activity, "position");
            if (advanced < 1) throw new AssertionError("RSVP did not advance");
            switchMode(activity, false);
            waitForIdleSync();
            int before = (int) field(activity, "anchor");
            runOnMainSync(() -> {
                ReaderOptions options = (ReaderOptions) field(activity, "options");
                options.put("size", 30); options.put("theme", 2); options.put("margin", 24);
                invoke(activity, "changedOptions");
            });
            waitForIdleSync();
            if ((int) field(activity, "anchor") != before) throw new AssertionError("Reflow lost anchor");
            runOnMainSync(() -> invoke(activity, "jump", new Class[]{int.class}, 200));
            waitForIdleSync();
            if ((int) field(activity, "returnAnchor") != before) throw new AssertionError("Jump history lost");
            runOnMainSync(() -> invoke(activity, "searchBook"));
            typeSystem(0, "Maya");
            clickSystemResult("Maya");
            if ((int) field(activity, "anchor") != englishText(store).indexOf("Maya")) throw new AssertionError("Book search position");
            runOnMainSync(() -> invoke(activity, "addBookmark"));
            typeSystem(0, "Emulator note");
            clickSystem("Save");
            if (new JSONArray(prefs.getString("bookmarks.demo-en", "[]")).length() == 0) throw new AssertionError("Bookmark not saved");
            runOnMainSync(() -> invoke(activity, "settings", new Class[]{boolean.class}, false));
            waitForIdleSync();
            click(activity, "Only for this book");
            runOnMainSync(() -> {
                ReaderOptions options = (ReaderOptions) field(activity, "options");
                options.put("size", 32); invoke(activity, "changedOptions");
            });
            if (!prefs.contains("options.demo-en")) throw new AssertionError("Book settings absent");
            if (new JSONObject(prefs.getString("options", "{}")).optInt("size") == 32) throw new AssertionError("Book settings leaked globally");
            runOnMainSync(activity::onBackPressed);
            runOnMainSync(activity::onBackPressed);
            waitForIdleSync();
            Books.Book english = store.list().stream().filter(book -> book.id().equals("demo-en")).findFirst().get();
            runOnMainSync(() -> invoke(activity, "editBook", new Class[]{Books.Book.class}, english));
            typeSystem(0, "A very long title for an imported book — Проверка длинного названия книги");
            typeSystem(1, "Test Author");
            clickSystem("Save");
            if (!prefs.getString("title.demo-en", "").contains("Проверка")) throw new AssertionError("Rename failed");
            prefs.edit().remove("title.demo-en").remove("author.demo-en").apply();
            runOnMainSync(() -> invoke(activity, "assignCollections", new Class[]{Books.Book.class}, english));
            clickSystem("New");
            String collection = "Test collection " + System.currentTimeMillis();
            typeSystem(0, collection);
            clickSystem("Create");
            if (!prefs.getStringSet("collections.demo-en", java.util.Collections.emptySet()).contains(collection))
                throw new AssertionError("Collection assignment failed");
            runOnMainSync(() -> invoke(activity, "bookMenu", new Class[]{Books.Book.class}, english));
            clickSystem("Favorite");
            if (!prefs.getBoolean("favorite.demo-en", false)) throw new AssertionError("Favorite not saved");
            runOnMainSync(() -> invoke(activity, "bookMenu", new Class[]{Books.Book.class}, english));
            clickSystem("Reading status"); clickSystem("Finished");
            if (!prefs.getString("status.demo-en", "").equals("done")) throw new AssertionError("Reading status not saved");
            runOnMainSync(() -> invoke(activity, "bookMenu", new Class[]{Books.Book.class}, english));
            clickSystem("Delete"); clickSystem("Delete");
            if (!prefs.getBoolean("deleted.demo-en", false)) throw new AssertionError("Delete not saved");
            clickSystem("Undo delete");
            if (prefs.getBoolean("deleted.demo-en", false)) throw new AssertionError("Undo did not restore");
            runOnMainSync(() -> ((EditText) field(activity, "librarySearch")).setText("Станция"));
            runOnMainSync(() -> ((ListView) field(activity, "libraryList")).setSelection(1));
            awaitText(activity, "Станция «Тишина»");
            click(activity, "Станция «Тишина»");
            awaitField(activity, "reading");
            waitForIdleSync();
            switchMode(activity, true);
            click(activity, context.getString(R.string.start));
            Thread.sleep(700);
            click(activity, context.getString(R.string.pause));
            int ruAnchor = (int) field(activity, "anchor");
            runOnMainSync(activity::finish);
            waitForIdleSync();
            MainActivity reopened = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitField(reopened, "librarySearch");
            runOnMainSync(() -> ((EditText) field(reopened, "librarySearch")).setText("Станция"));
            runOnMainSync(() -> ((ListView) field(reopened, "libraryList")).setSelection(1));
            awaitText(reopened, "Станция «Тишина»");
            click(reopened, "Станция «Тишина»");
            awaitField(reopened, "reading");
            waitForIdleSync();
            if ((int) field(reopened, "anchor") != ruAnchor) throw new AssertionError("Relaunch lost position");
            Thread.sleep(400);
            try (FileOutputStream screenshot = new FileOutputStream(new File(context.getFilesDir(), "qa-reader.png"))) {
                getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, screenshot);
            }
            runOnMainSync(() -> invoke(reopened, "settings", new Class[]{boolean.class}, false));
            waitForIdleSync();
            Thread.sleep(400);
            try (FileOutputStream screenshot = new FileOutputStream(new File(context.getFilesDir(), "qa-settings.png"))) {
                getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, screenshot);
            }
            runOnMainSync(reopened::onBackPressed);
            runOnMainSync(reopened::onBackPressed);
            waitForIdleSync();
            Thread.sleep(400);
            try (FileOutputStream screenshot = new FileOutputStream(new File(context.getFilesDir(), "qa-library.png"))) {
                getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, screenshot);
            }
            for (int theme : new int[]{0, 1, 2, 3}) {
                runOnMainSync(() -> {
                    ReaderOptions options = (ReaderOptions) field(reopened, "options");
                    options.put("theme", theme); options.put("size", 21);
                    invoke(reopened, "changedOptions");
                    invoke(reopened, "settings", new Class[]{boolean.class}, false);
                });
                waitForIdleSync();
                Thread.sleep(350);
                assertNoSpinner(reopened.getWindow().getDecorView());
                capture("settings-" + theme);
                runOnMainSync(() -> ((ScrollView) field(reopened, "settingsScroll")).scrollTo(0, 320));
                Thread.sleep(200);
                capture("settings-colors-" + theme);
                runOnMainSync(reopened::onBackPressed);
                runOnMainSync(() -> ((EditText) field(reopened, "librarySearch")).setText("Emulator EPUB"));
                runOnMainSync(() -> ((ListView) field(reopened, "libraryList")).setSelection(1));
                waitForIdleSync();
                Thread.sleep(300);
                capture("covers-" + theme);
            }
            runOnMainSync(reopened::finish);
            result.putString("stream", "PASS: batch/invalid-file imports, duplicates, EPUB covers/navigation, rename/collections/favorite/status/delete/undo, in-book search, RU/EN RSVP, search library, bookmark, jump history, reflow anchor, per-book settings, relaunch position\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void sampleCheck() throws Exception {
        Context context = getTargetContext();
        Books store = new Books(context);
        byte[] bytes = java.nio.file.Files.readAllBytes(new File(context.getFilesDir(), "qa-sample.epub").toPath());
        Books.Book book;
        try { book = checkImport(store, "sample.epub", bytes, "Сияние"); }
        catch (Books.DuplicateBook duplicate) { book = store.list().stream().filter(b -> b.title().equals("Сияние")).findFirst().get(); }
        if (!EpubCover.isImage(book.cover())) throw new AssertionError("Supplied EPUB cover missing");
        if (book.chapters().size() != 63) throw new AssertionError("Supplied EPUB chapters: " + book.chapters().size());
        for (Books.Chapter chapter : book.chapters()) {
            String expected = chapter.title().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
            String actual = book.text().substring(chapter.offset(), Math.min(book.text().length(), chapter.offset() + expected.length() + 50))
                    .replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
            if (!actual.startsWith(expected)) throw new AssertionError("Incorrect chapter anchor: " + chapter.title());
        }
        File saved = new File(context.getFilesDir(), "books/" + book.id() + ".json");
        byte[] original = java.nio.file.Files.readAllBytes(saved.toPath());
        android.content.SharedPreferences prefs = context.getSharedPreferences("reader", 0);
        int anchor = book.chapters().get(20).offset();
        prefs.edit().putInt("anchor." + book.id(), anchor).apply();
        try {
            for (int variant = 0; variant < 3; variant++) {
                JSONObject legacy = new JSONObject(new String(original, StandardCharsets.UTF_8));
                if (variant == 0) legacy.remove("chapters"); // Existing cover must not block chapter repair.
                if (variant == 1) legacy.remove("cover");
                if (variant == 2) { // Equal-length but stale navigation also needs refreshing.
                    JSONArray stale = legacy.getJSONArray("chapters");
                    for (int i = 0; i < stale.length(); i++) stale.getJSONObject(i).put("offset", 0);
                }
                java.nio.file.Files.write(saved.toPath(), legacy.toString().getBytes(StandardCharsets.UTF_8));
                Books.Book repaired = checkImport(store, "sample-reimport.epub", bytes, "Сияние");
                if (!repaired.id().equals(book.id()) || !repaired.text().equals(book.text())
                        || !repaired.chapters().equals(book.chapters()) || !java.util.Arrays.equals(repaired.cover(), book.cover())
                        || prefs.getInt("anchor." + book.id(), -1) != anchor)
                    throw new AssertionError("Reimport did not preserve identity/text/position and repair metadata, variant " + variant);
            }
            try { checkImport(store, "sample-duplicate.epub", bytes, "Сияние"); throw new AssertionError("Duplicate accepted"); }
            catch (Books.DuplicateBook expected) {}
        } finally { java.nio.file.Files.write(saved.toPath(), original); }
        MainActivity activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitField(activity, "librarySearch");
        runOnMainSync(() -> ((EditText) field(activity, "librarySearch")).setText("Сияние"));
        runOnMainSync(() -> ((ListView) field(activity, "libraryList")).setSelection(1));
        waitForIdleSync(); Thread.sleep(250);
        capture("sample-cover");
        click(activity, "Сияние");
        awaitField(activity, "reading");
        waitForIdleSync();
        runOnMainSync(() -> invoke(activity, "contents"));
        waitForIdleSync(); Thread.sleep(250);
        capture("sample-contents");
        Books.Chapter target = book.chapters().get(2);
        clickSystem(target.title());
        if (Math.abs((int) field(activity, "anchor") - target.offset()) > 100) throw new AssertionError("UI chapter jump failed");
        runOnMainSync(activity::finish);
    }
    private void benchmarkCheck(Bundle result) throws Exception {
        Context context = getTargetContext();
        MainActivity activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitField(activity, "librarySearch");
        runOnMainSync(() -> ((EditText) field(activity, "librarySearch")).setText("Сияние"));
        runOnMainSync(() -> ((ListView) field(activity, "libraryList")).setSelection(1));
        waitForIdleSync();
        long started = android.os.SystemClock.elapsedRealtime();
        click(activity, "Сияние");
        awaitField(activity, "reading"); waitForIdleSync();
        long elapsed = android.os.SystemClock.elapsedRealtime() - started;
        int rendered = ((TextView) field(activity, "body")).length();
        result.putString("stream", "OPEN_MS=" + elapsed + " RENDERED_CHARS=" + rendered + "\n");
        runOnMainSync(activity::finish);
    }
    private void readerCheck() throws Exception {
        Context context = getTargetContext();
        MainActivity activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitField(activity, "librarySearch");
        runOnMainSync(() -> ((EditText) field(activity, "librarySearch")).setText("Сияние"));
        runOnMainSync(() -> ((ListView) field(activity, "libraryList")).setSelection(1));
        View[] card = {null};
        runOnMainSync(() -> {
            View view = find(activity.getWindow().getDecorView(), "Сияние");
            while (view != null && !view.isLongClickable() && view.getParent() instanceof View parent) view = parent;
            card[0] = view;
            if (view == null || !view.performLongClick()) throw new AssertionError("Book long press unavailable");
        });
        waitForIdleSync(); Thread.sleep(150);
        capture("book-longpress-menu");
        String action = context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru") ? "Название и автор" : "Title & author";
        if (getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText(action).isEmpty()
                || field(activity, "current") != null) throw new AssertionError("Long press opened book instead of actions");
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        waitForIdleSync();
        long opening = android.os.SystemClock.elapsedRealtime();
        click(activity, "Сияние");
        awaitField(activity, "reading"); waitForIdleSync();
        Bundle timing = new Bundle();
        timing.putString("stream", "Book open to laid-out text: " + (android.os.SystemClock.elapsedRealtime() - opening) + " ms\n");
        sendStatus(0, timing);
        Books.Book book = (Books.Book) field(activity, "current");
        android.content.SharedPreferences prefs = context.getSharedPreferences("reader", 0);
        JSONArray notes = new JSONArray(prefs.getString("bookmarks." + book.id(), "[]"));
        for (int i = notes.length() - 1; i >= 0; i--)
            if (notes.optJSONObject(i) != null && notes.optJSONObject(i).optString("note").equals("Reader QA quote")) notes.remove(i);
        prefs.edit().putString("bookmarks." + book.id(), notes.toString()).apply();
        for (int theme : new int[]{0, 1, 2}) {
            runOnMainSync(() -> {
                ReaderOptions options = (ReaderOptions) field(activity, "options");
                options.put("theme", theme); options.put("size", 21); options.put("font", 0);
                options.put("indent", 20); options.put("paragraphGap", 8); options.put("line", 150);
                options.put("pages", false); options.put("alignment", 1);
                invoke(activity, "changedOptions");
                invoke(activity, "jump", new Class[]{int.class}, book.chapters().get(1).offset());
            });
            waitForIdleSync(); Thread.sleep(300);
            capture("reading-" + theme);
        }
        android.text.Spanned styled = (android.text.Spanned) ((TextView) field(activity, "body")).getText();
        int windowStart = (int) field(activity, "textStart"), windowEnd = (int) field(activity, "textEnd");
        if (!styled.toString().equals(book.text().substring(windowStart, windowEnd)) || styled.length() > 20000
                || styled.getSpans(0, styled.length(), android.text.style.StyleSpan.class).length == 0)
            throw new AssertionError("Text window changed content, grew unbounded or missed headings");
        android.text.Spanned fullStyles = ReaderText.style(context, book, (ReaderOptions) field(activity, "options"), 0, book.text().length());
        if (fullStyles.getSpans(0, fullStyles.length(), android.text.style.StyleSpan.class).length < 58)
            throw new AssertionError("Missing chapter heading styles");
        runOnMainSync(() -> invoke(activity, "paragraphActions", new Class[]{int.class}, book.chapters().get(1).offset() + 200));
        boolean russian = context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru");
        clickSystem(russian ? "Сохранить цитату" : "Save quote");
        typeSystem(0, "Reader QA quote");
        clickSystem(russian ? "Сохранить" : "Save");
        waitForIdleSync();
        styled = (android.text.Spanned) ((TextView) field(activity, "body")).getText();
        if (styled.getSpans(0, styled.length(), android.text.style.BackgroundColorSpan.class).length == 0)
            throw new AssertionError("Quote not highlighted");
        runOnMainSync(() -> {
            ReaderOptions options = (ReaderOptions) field(activity, "options");
            options.put("volumePages", true); options.put("pages", true);
            invoke(activity, "changedOptions");
        });
        waitForIdleSync();
        int before = ((ScrollView) field(activity, "textScroll")).getScrollY();
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_VOLUME_DOWN);
        waitForIdleSync();
        if (((ScrollView) field(activity, "textScroll")).getScrollY() <= before) throw new AssertionError("Volume did not turn page");
        before = ((ScrollView) field(activity, "textScroll")).getScrollY();
        tapReading(activity, .9f);
        if (((ScrollView) field(activity, "textScroll")).getScrollY() <= before) throw new AssertionError("Edge tap did not turn page");
        int anchor = (int) field(activity, "anchor");
        tapReading(activity, .5f);
        if (!(boolean) field(activity, "fullscreen")) throw new AssertionError("Center tap did not hide controls");
        waitForIdleSync();
        runOnMainSync(activity::onBackPressed);
        waitForIdleSync();
        if ((int) field(activity, "anchor") != anchor) throw new AssertionError("Fullscreen changed anchor");
        runOnMainSync(() -> invoke(activity, "jump", new Class[]{int.class}, book.text().length() / 2));
        waitForIdleSync();
        int oldStart = (int) field(activity, "textStart"), oldEnd = (int) field(activity, "textEnd");
        int[] expectedAnchor = {0};
        runOnMainSync(() -> {
            TextView text = (TextView) field(activity, "body");
            ScrollView scroll = (ScrollView) field(activity, "textScroll");
            int target = text.getHeight() - scroll.getHeight() - 10;
            expectedAnchor[0] = oldStart + text.getLayout().getLineStart(text.getLayout().getLineForVertical(target - text.getPaddingTop()));
            scroll.scrollTo(0, target);
        });
        waitForIdleSync();
        if ((int) field(activity, "textEnd") <= oldEnd || (int) field(activity, "anchor") != expectedAnchor[0])
            throw new AssertionError("Forward window transition skipped text");
        int shiftedStart = (int) field(activity, "textStart");
        runOnMainSync(() -> ((ScrollView) field(activity, "textScroll")).scrollTo(0, 0));
        waitForIdleSync();
        if ((int) field(activity, "textStart") >= shiftedStart || (int) field(activity, "anchor") != shiftedStart)
            throw new AssertionError("Backward window transition skipped text");
        runOnMainSync(() -> invoke(activity, "readerMenu"));
        waitForIdleSync(); Thread.sleep(200); capture("reading-menu");
        clickSystem(russian ? "Поиск в книге" : "Search book");
        Thread.sleep(800);
        boolean[] ime = {false};
        runOnMainSync(() -> {
            android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            ime[0] = insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
        });
        capture("reading-search-keyboard");
        if (!ime[0]) throw new AssertionError("Search keyboard is not visible");
        typeSystem(0, "Уллман");
        clickSystemResult("Уллман");
        runOnMainSync(() -> {
            invoke(activity, "shell");
            invoke(activity, "loadingScreen", new Class[]{String.class, String.class}, "Сияние", "Открываем книгу…");
        });
        waitForIdleSync(); Thread.sleep(200);
        android.graphics.Bitmap frame = getUiAutomation().takeScreenshot();
        Thread.sleep(180);
        android.graphics.Bitmap nextFrame = getUiAutomation().takeScreenshot();
        if (android.animation.ValueAnimator.areAnimatorsEnabled() && frame.sameAs(nextFrame))
            throw new AssertionError("Loading indicator does not animate");
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() && !frame.sameAs(nextFrame))
            throw new AssertionError("Loading indicator ignores reduced motion");
        capture("loading-animation");
        runOnMainSync(activity::finish);
    }
    private void tapReading(MainActivity activity, float fraction) {
        int[] location = new int[2];
        float[] point = new float[2];
        runOnMainSync(() -> {
            ScrollView scroll = (ScrollView) field(activity, "textScroll");
            scroll.getLocationOnScreen(location);
            point[0] = location[0] + scroll.getWidth() * fraction;
            point[1] = location[1] + scroll.getHeight() / 2f;
        });
        long time = android.os.SystemClock.uptimeMillis();
        sendPointerSync(android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, point[0], point[1], 0));
        sendPointerSync(android.view.MotionEvent.obtain(time, time + 60, android.view.MotionEvent.ACTION_UP, point[0], point[1], 0));
        waitForIdleSync();
    }
    private void visualCheck() throws Exception {
        Context context = getTargetContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("reader", 0);
        prefs.edit().putBoolean("grid", true).putBoolean("favorite.demo-en", true).putBoolean("favorite.demo-ru", true).apply();
        MainActivity activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitField(activity, "librarySearch");
        for (int theme : new int[]{0, 2}) {
            runOnMainSync(() -> {
                ReaderOptions options = (ReaderOptions) field(activity, "options");
                options.put("theme", theme);
                invoke(activity, "changedOptions");
                setField(activity, "filter", "favorite");
                invoke(activity, "library");
            });
            waitForIdleSync(); Thread.sleep(250);
            capture("visual-library-" + theme);
            if (find(activity.getWindow().getDecorView(), "Read") != null) throw new AssertionError("Redundant Read button");
            runOnMainSync(() -> invoke(activity, "filterDialog"));
            waitForIdleSync(); Thread.sleep(250);
            capture("visual-menu-" + theme);
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            waitForIdleSync();
            runOnMainSync(() -> invoke(activity, "settings", new Class[]{boolean.class}, false));
            waitForIdleSync(); Thread.sleep(250);
            capture("visual-settings-" + theme);
            String font = context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru") ? "С засечками" : "Serif";
            click(activity, font);
            runOnMainSync(() -> find(activity.getWindow().getDecorView(), font).requestRectangleOnScreen(new android.graphics.Rect(0, 0, 100, 250), true));
            waitForIdleSync(); Thread.sleep(250);
            capture("visual-selector-" + theme);
            boolean russian = context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru");
            click(activity, russian ? "Без засечек" : "Sans serif");
            if (((ReaderOptions) field(activity, "options")).number("font", 0) != 1) throw new AssertionError("Selector did not apply");
            click(activity, russian ? "Без засечек" : "Sans serif");
            click(activity, font);
            runOnMainSync(activity::onBackPressed);
            final boolean[] accepted = {false};
            runOnMainSync(() -> new ReaderDialogs(activity, (ReaderOptions) field(activity, "options"))
                    .setTitle("Collection settings").setMessage("Long labels and large system text must leave every action reachable. ".repeat(5))
                    .setPositiveButton("Save changes", (dialog, which) -> accepted[0] = true)
                    .setNeutralButton("Create collection", null).setNegativeButton("Cancel", null).show());
            waitForIdleSync(); Thread.sleep(200);
            capture("visual-dialog-" + theme);
            clickSystem("Save changes");
            if (!accepted[0]) throw new AssertionError("Dialog action inaccessible");
        }
        runOnMainSync(() -> invoke(activity, "settings", new Class[]{boolean.class}, true));
        waitForIdleSync();
        runOnMainSync(() -> ((ScrollView) field(activity, "settingsScroll")).scrollTo(0, 200));
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        runOnMainSync(activity::recreate);
        MainActivity restored = (MainActivity) waitForMonitorWithTimeout(monitor, 5000);
        removeMonitor(monitor);
        if (restored == null) throw new AssertionError("Activity not recreated");
        awaitField(restored, "settingsScroll");
        waitForIdleSync();
        if (!(boolean) field(restored, "settingsOpen") || !(boolean) field(restored, "settingsRsvp"))
            throw new AssertionError("Settings route lost on recreation");
        if (((ScrollView) field(restored, "settingsScroll")).getScrollY() < 150) throw new AssertionError("Settings scroll lost");
        runOnMainSync(restored::onBackPressed);
        awaitField(restored, "librarySearch");
        runOnMainSync(restored::finish);
        chromeVisualCheck();
    }
    private void chromeVisualCheck() throws Exception {
        Context context = getTargetContext();
        var prefs = context.getSharedPreferences("reader", 0);
        String oldOptions = prefs.getString("options", "{}");
        try {
            for (int scenario = 0; scenario < 4; scenario++) {
                int theme = scenario % 2 == 0 ? 0 : 2;
                shell("wm size " + (scenario == 3 ? "740x360" : "430x932"));
                shell("settings put system font_scale " + (scenario >= 2 ? "2.0" : "1.0"));
                shell("settings put global animator_duration_scale " + (scenario >= 2 ? "0" : "1"));
                prefs.edit().putString("options", "{\"theme\":" + theme + "}").commit();
                MainActivity library = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    awaitField(library, "librarySearch"); waitForIdleSync();
                    assertChrome(library, (ListView) field(library, "libraryList"));
                    assertTouchTargets(library.getWindow().getDecorView());
                    capture("chrome-library-" + scenario);
                } finally { runOnMainSync(library::finish); }
                CatalogActivity catalog = (CatalogActivity) startActivitySync(new Intent(context, CatalogActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    waitForIdleSync();
                    assertTouchTargets(catalog.getWindow().getDecorView());
                    capture("chrome-catalog-" + scenario);
                    boolean russian = context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru");
                    click(catalog, russian ? "Загрузки" : "Downloads");
                    ViewGroup root = (ViewGroup) field(catalog, "root");
                    TextView selected = find(root.getChildAt(root.getChildCount() - 1), russian ? "Загрузки" : "Downloads");
                    if (!(boolean) field(catalog, "downloads") || !selected.isSelected()) throw new AssertionError("Downloads tab did not select");
                    capture("chrome-downloads-" + scenario);
                } finally { runOnMainSync(catalog::finish); }
            }
        } finally {
            prefs.edit().putString("options", oldOptions).commit();
            shell("wm size 430x932");
            shell("settings put system font_scale 1.0");
            shell("settings put global animator_duration_scale 1");
        }
    }
    private void showcase() throws Exception {
        Context context = getTargetContext();
        var prefs = context.getSharedPreferences("reader", 0);
        String oldOptions = prefs.getString("options", "{}"); boolean oldGrid = prefs.getBoolean("grid", true);
        java.util.List<Books.Book> books = new Books(context).list();
        Books.Book ru = books.stream().filter(b -> b.id().equals("demo-ru")).findFirst().orElseThrow();
        Books.Book en = books.stream().filter(b -> b.id().equals("demo-en")).findFirst().orElseThrow();
        java.util.List<Books.Book> demo = java.util.Arrays.asList(
                new Books.Book("showcase-ru", ru.title(), "MJReader Originals", ru.text(), ru.chapters(), null),
                new Books.Book("showcase-en", en.title(), "MJReader Originals", en.text(), en.chapters(), null),
                new Books.Book("showcase-moon", "Письма с Луны", "MJReader Originals", ru.text()),
                new Books.Book("showcase-stars", "Город между звёздами", "MJReader Originals", ru.text()));
        MainActivity activity = null;
        try {
            prefs.edit().putString("options", "{\"theme\":0,\"size\":21,\"countdown\":false}").putBoolean("grid", true)
                    .putString("status.showcase-ru", "reading").putInt("anchor.showcase-ru", ru.text().length() / 3).apply();
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitField(activity, "librarySearch"); MainActivity screen = activity;
            runOnMainSync(() -> { setField(screen, "books", demo); setField(screen, "sort", 2); invoke(screen, "library"); });
            waitForIdleSync(); capture("showcase-library-dark");
            runOnMainSync(() -> { ((ReaderOptions) field(screen, "options")).put("theme", 2); invoke(screen, "changedOptions"); invoke(screen, "library"); });
            waitForIdleSync(); capture("showcase-library-light");
            runOnMainSync(() -> invoke(screen, "open", new Class[]{Books.Book.class, boolean.class}, demo.get(0), false));
            awaitField(screen, "body"); waitForIdleSync();
            runOnMainSync(() -> { ReaderOptions options = (ReaderOptions) field(screen, "options"); options.put("theme", 3); invoke(screen, "changedOptions"); invoke(screen, "jump", new Class[]{int.class}, 0); });
            waitForIdleSync(); capture("showcase-reading");
            runOnMainSync(() -> { ((ReaderOptions) field(screen, "options")).put("theme", 0); invoke(screen, "changedOptions"); setField(screen, "focus", true); invoke(screen, "reader"); });
            waitForIdleSync(); capture("showcase-rsvp");
            runOnMainSync(() -> invoke(screen, "settings", new Class[]{boolean.class}, false));
            waitForIdleSync(); capture("showcase-settings");
            runOnMainSync(() -> { setField(screen, "settingsOpen", false); invoke(screen, "library"); }); waitForIdleSync();
            activity = resize(screen, "800x900"); awaitField(activity, "librarySearch"); MainActivity wide = activity;
            runOnMainSync(() -> { setField(wide, "books", demo); setField(wide, "sort", 2); invoke(wide, "library"); });
            waitForIdleSync(); capture("showcase-fold");
        } finally {
            if (activity != null) { MainActivity end = activity; runOnMainSync(end::finish); }
            var editor = prefs.edit().putString("options", oldOptions).putBoolean("grid", oldGrid);
            for (String key : prefs.getAll().keySet()) if (key.contains("showcase-")) editor.remove(key);
            editor.apply(); shell("wm size 430x932");
        }
    }
    private void adaptiveCheck() throws Exception {
        Context context = getTargetContext();
        var prefs = context.getSharedPreferences("reader", 0);
        String oldOptions = prefs.getString("options", "{}"); boolean oldGrid = prefs.getBoolean("grid", true);
        java.util.List<Books.Book> fixtures = new java.util.ArrayList<>();
        MainActivity screen = null; CatalogActivity catalog = null;
        try {
            prefs.edit().putString("options", "{\"theme\":0}").putBoolean("grid", true).commit();
            Books store = new Books(context);
            for (int i = 0; i < 24; i++) {
                File source = new File(context.getCacheDir(), String.format(java.util.Locale.ROOT, "QA Fold %02d.txt", i));
                java.nio.file.Files.write(source.toPath(), ("QA Fold " + i + "\n" + "История начинается здесь. A new chapter begins. ".repeat(180)).getBytes(StandardCharsets.UTF_8));
                fixtures.add(store.importBook(source)); source.delete();
            }
            screen = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitField(screen, "librarySearch");
            MainActivity first = screen;
            runOnMainSync(() -> ((EditText) field(first, "librarySearch")).setText("QA Fold")); waitForIdleSync(); Thread.sleep(500);
            for (String size : new String[]{"360x840", "540x840", "800x900", "900x900", "1040x720", "360x840"}) {
                screen = resize(screen, size); awaitField(screen, "librarySearch"); waitForIdleSync();
                MainActivity current = screen;
                ListView list = (ListView) field(current, "libraryList");
                runOnMainSync(() -> list.setSelectionFromTop(2, -24)); waitForIdleSync();
                assertChrome(current, list);
                int columns = (int) call(field(current, "adapter"), "columns");
                int width = context.getResources().getConfiguration().screenWidthDp;
                if (width >= 720 && columns < 4 || width < 500 && columns != 2) throw new AssertionError("Adaptive grid: " + width + " / " + columns);
                if (width >= 500 && width < 600 && columns != 3 || width >= 900 && width < 1000 && columns < 5) throw new AssertionError("Grid did not adapt without recreation");
                View row = list.getChildAt(0);
                if (row == null || row.getHeight() < 150) throw new AssertionError("Collapsed book cards");
                int bookIndex = (int) field(current, "libraryBookIndex");
                ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false); runOnMainSync(current::recreate);
                screen = (MainActivity) waitForMonitorWithTimeout(monitor, 5000); removeMonitor(monitor); awaitField(screen, "librarySearch"); waitForIdleSync();
                if ((int) field(screen, "libraryBookIndex") != bookIndex) throw new AssertionError("Library position lost on recreation");
                assertTouchTargets(screen.getWindow().getDecorView()); capture("adaptive-library-" + size);
            }
            MainActivity current = screen;
            runOnMainSync(() -> invoke(current, "open", new Class[]{Books.Book.class, boolean.class}, fixtures.get(0), false));
            awaitField(current, "body"); waitForIdleSync();
            runOnMainSync(() -> { invoke(current, "jump", new Class[]{int.class}, 3000); invoke(current, "setFullscreen", new Class[]{boolean.class}, true); });
            waitForIdleSync(); int anchor = (int) field(current, "anchor");
            screen = resize(current, "800x900"); awaitField(screen, "body"); waitForIdleSync();
            if (Math.abs((int) field(screen, "anchor") - anchor) > 100 || !(boolean) field(screen, "fullscreen")) throw new AssertionError("Reading position/fullscreen lost on unfold");
            if (((TextView) field(screen, "body")).getWidth() > 710 * context.getResources().getDisplayMetrics().density) throw new AssertionError("Reader line too wide");
            capture("adaptive-reader-wide");
            screen = resize(screen, "740x900"); awaitField(screen, "body"); waitForIdleSync();
            if (Math.abs((int) field(screen, "anchor") - anchor) > 100 || !(boolean) field(screen, "fullscreen")) throw new AssertionError("Reader anchor lost on small resize");
            MainActivity opened = screen; runOnMainSync(opened::finish); screen = null;
            catalog = (CatalogActivity) startActivitySync(new Intent(context, CatalogActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync();
            CatalogActivity initial = catalog;
            runOnMainSync(() -> {
                java.util.List<Flibusta.Entry> entries = (java.util.List<Flibusta.Entry>) field(initial, "entries");
                for (int i = 0; i < 40; i++) entries.add(new Flibusta.Entry("Орбита · глава " + i, "MJReader", "История о новых мирах. A journey through distant worlds.", "https://example.test/book/" + i, "epub", true));
                setField(initial, "currentUrl", "https://example.test/opds"); setField(initial, "message", "Каталог · 40"); invoke(initial, "render");
                ((EditText) field(initial, "input")).setText("Черновик запроса");
                ((ListView) field(initial, "resultsList")).setSelectionFromTop(4, -20);
            }); waitForIdleSync();
            assertChrome(initial, (ListView) field(initial, "resultsList"));
            capture("adaptive-catalog-wide");
            ActivityMonitor monitor = addMonitor(CatalogActivity.class.getName(), null, false);
            shell("wm size 360x840"); catalog = (CatalogActivity) waitForMonitorWithTimeout(monitor, 5000); removeMonitor(monitor); waitForIdleSync();
            if (catalog == null || (boolean) field(catalog, "busy") || ((java.util.List<?>) field(catalog, "entries")).size() != 40) throw new AssertionError("Catalog refetched on fold");
            if (!((EditText) field(catalog, "input")).getText().toString().equals("Черновик запроса")) throw new AssertionError("Search draft lost");
            if (((ListView) field(catalog, "resultsList")).getFirstVisiblePosition() < 5) throw new AssertionError("Catalog scroll lost on fold");
            for (int theme : new int[]{0, 2}) {
                ReaderOptions options = new ReaderOptions("{\"theme\":" + theme + "}");
                android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable(); normal.setColor(options.accent());
                android.graphics.drawable.Drawable states = options.feedback(normal, 1);
                states.setState(new int[]{android.R.attr.state_enabled}); Object idle = states.getCurrent();
                for (int state : new int[]{android.R.attr.state_pressed, android.R.attr.state_hovered, android.R.attr.state_focused}) {
                    states.setState(new int[]{android.R.attr.state_enabled, state}); if (states.getCurrent() == idle) throw new AssertionError("Missing interaction feedback");
                }
                states.setState(new int[]{}); if (states.getCurrent() == idle) throw new AssertionError("Missing disabled feedback");
            }
        } finally {
            if (screen != null) { MainActivity end = screen; runOnMainSync(end::finish); }
            if (catalog != null) { CatalogActivity end = catalog; runOnMainSync(end::finish); }
            for (Books.Book book : fixtures) new File(context.getFilesDir(), "books/" + book.id() + ".json").delete();
            prefs.edit().putString("options", oldOptions).putBoolean("grid", oldGrid).commit();
            shell("wm size 430x932");
        }
    }
    private MainActivity resize(MainActivity activity, String size) throws Exception {
        boolean library = field(activity, "current") == null && !(boolean) field(activity, "settingsOpen");
        int before = (int) field(activity, "libraryBookIndex");
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        shell("wm size " + size);
        MainActivity resized = (MainActivity) waitForMonitorWithTimeout(monitor, 1500); removeMonitor(monitor);
        if (resized == null && !activity.isDestroyed()) resized = activity;
        if (resized == null) throw new AssertionError("No activity after resize to " + size);
        waitForIdleSync();
        if (library) {
            awaitField(resized, "librarySearch"); waitForIdleSync();
            int columns = (int) call(field(resized, "adapter"), "columns");
            int expected = before < 0 ? -1 : before / columns * columns;
            if ((int) field(resized, "libraryBookIndex") != expected) throw new AssertionError("Library anchor lost resizing to " + size);
        }
        return resized;
    }
    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) { input.readAllBytes(); }
    }
    private Object call(Object object, String method) {
        try { var member = object.getClass().getDeclaredMethod(method); member.setAccessible(true); return member.invoke(object); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private void assertChrome(Activity activity, ListView list) {
        runOnMainSync(() -> {
            TextView brand = find(activity.getWindow().getDecorView(), "MJ / READER");
            android.graphics.Rect visible = new android.graphics.Rect();
            if (brand == null || !brand.getGlobalVisibleRect(visible) || visible.height() < brand.getHeight())
                throw new AssertionError("Masthead scrolled away");
            int[] top = new int[2], body = new int[2], bottom = new int[2];
            brand.getLocationOnScreen(top); list.getLocationOnScreen(body);
            ViewGroup root = (ViewGroup) field(activity, "root");
            View dock = root.getChildAt(root.getChildCount() - 1); dock.getLocationOnScreen(bottom);
            if (body[1] < top[1] + brand.getHeight() || body[1] + list.getHeight() >= bottom[1])
                throw new AssertionError("Content overlaps masthead or bottom actions");
        });
    }
    private void assertTouchTargets(View view) {
        if (view instanceof Button && view.isShown() && (view.getWidth() + 1 < 48 * view.getResources().getDisplayMetrics().density
                || view.getHeight() + 1 < 48 * view.getResources().getDisplayMetrics().density)) throw new AssertionError("Small touch target: " + ((Button) view).getText());
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) assertTouchTargets(group.getChildAt(i));
    }
    private void setField(Object object, String name, Object value) {
        try {
            java.lang.reflect.Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true); field.set(object, value);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private void capture(String name) throws Exception {
        Thread.sleep(350);
        try (FileOutputStream screenshot = new FileOutputStream(new File(getTargetContext().getFilesDir(), "qa-" + name + ".png"))) {
            getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, screenshot);
        }
    }
    private void assertNoSpinner(View view) {
        if (view instanceof Spinner) throw new AssertionError("Native dropdown remains");
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) assertNoSpinner(group.getChildAt(i));
    }
    private void verifyCoverVariants(byte[] cover) throws Exception {
        String[] declarations = {
            "<metadata><meta name='cover' content='art'/></metadata><manifest><item id='art' href='images/front%20art.png'/></manifest>",
            "<manifest><item id='wrapper' href='text/title.xhtml'/></manifest><guide><reference type='cover' href='text/title.xhtml'/></guide>",
            "<manifest><item id='first' href='text/title.xhtml'/></manifest><spine><itemref idref='first'/></spine>",
            "<manifest><item id='art' href='images/front%20art.png' properties='cover-image'/></manifest>"
        };
        for (String declaration : declarations) {
            java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
            entries.put("OPS/images/front art.png", cover);
            entries.put("OPS/text/title.xhtml", "<html><body><svg><image xlink:href='../images/front%20art.png'/></svg></body></html>".getBytes(StandardCharsets.UTF_8));
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            org.w3c.dom.Document pack = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                    ("<package xmlns='http://www.idpf.org/2007/opf'>" + declaration + "</package>").getBytes(StandardCharsets.UTF_8)));
            if (!EpubCover.isImage(EpubCover.find(entries, pack, "OPS/book.opf"))) throw new AssertionError("Cover variant " + declaration);
        }
    }
    private String englishText(Books store) throws Exception {
        return store.list().stream().filter(book -> book.id().equals("demo-en")).findFirst().get().text();
    }
    private void typeSystem(int index, String text) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            java.util.List<AccessibilityNodeInfo> fields = new java.util.ArrayList<>();
            collectInputs(getUiAutomation().getRootInActiveWindow(), fields);
            if (fields.size() > index) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                if (fields.get(index).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) { waitForIdleSync(); return; }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Input not found");
    }
    private void collectInputs(AccessibilityNodeInfo node, java.util.List<AccessibilityNodeInfo> fields) {
        if (node == null) return;
        if ("android.widget.EditText".contentEquals(node.getClassName())) fields.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectInputs(node.getChild(i), fields);
    }
    private void clickSystemResult(String text) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                if ("android.widget.EditText".contentEquals(node.getClassName())) continue;
                while (node != null && !node.isClickable()) node = node.getParent();
                if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { waitForIdleSync(); return; }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Search result missing");
    }
    private void switchMode(MainActivity activity, boolean focus) throws Exception {
        String label = focus ? "RSVP" : getTargetContext().getString(R.string.reader);
        if (getTargetContext().getResources().getConfiguration().screenHeightDp < 400) {
            runOnMainSync(() -> invoke(activity, "readerMenu"));
            clickSystem(label);
        } else click(activity, label);
    }
    private Object field(Object object, String name) {
        try {
            java.lang.reflect.Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private void invoke(Object object, String name) { invoke(object, name, new Class[0]); }
    private void invoke(Object object, String name, Class[] types, Object... arguments) {
        try {
            java.lang.reflect.Method method = object.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true); method.invoke(object, arguments);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private void awaitField(Object object, String name) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (field(object, name) != null) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Missing field " + name);
    }
    private Switch findSwitch(View view, String text) {
        if (view instanceof Switch control && control.getText().toString().equals(text)) return control;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Switch found = findSwitch(group.getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private void clickSystem(String text) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                if (node.getText() != null && node.getText().toString().equalsIgnoreCase(text)) {
                    while (node != null && !node.isClickable()) node = node.getParent();
                    if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        waitForIdleSync(); return;
                    }
                }
            }
            if (root != null) scrollSystem(root);
            Thread.sleep(100);
        }
        throw new AssertionError("Missing system control " + text);
    }
    private boolean scrollSystem(AccessibilityNodeInfo node) {
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && scrollSystem(child)) return true;
        }
        return false;
    }
    private Books.Book checkImport(Books store, String name, byte[] bytes, String expected) throws Exception {
        ContentResolver resolver = getTargetContext().getContentResolver();
        Uri uri = createDocument(name, bytes);
        try {
            Books.Book book = store.importBook(uri);
            if (!book.text().contains(expected)) throw new AssertionError("Import content " + name);
            return book;
        } finally { resolver.delete(uri, null, null); }
    }
    private Uri createDocument(String name, byte[] bytes) throws Exception {
        ContentResolver resolver = getTargetContext().getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, java.util.UUID.randomUUID() + "-" + name);
        values.put(MediaStore.Downloads.MIME_TYPE, name.endsWith(".epub") ? "application/epub+zip" : "text/plain");
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new AssertionError("Download URI");
        try (OutputStream output = resolver.openOutputStream(uri)) { output.write(bytes); }
        return uri;
    }
    private void entry(ZipOutputStream zip, String path, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
    private TextView find(View view, String text) {
        if (view instanceof TextView label && !(view instanceof EditText) && label.getText().toString().equals(text)) return label;
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        return null;
    }
    private void awaitText(Activity activity, String text) throws Exception {
        for (int i = 0; i < 50; i++) {
            boolean[] found = {false};
            runOnMainSync(() -> found[0] = find(activity.getWindow().getDecorView(), text) != null);
            if (found[0]) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Missing text " + text);
    }
    private void click(Activity activity, String text) {
        runOnMainSync(() -> {
            View view = find(activity.getWindow().getDecorView(), text);
            if (view == null) throw new AssertionError("Missing control " + text);
            while (!view.isClickable() && view.getParent() instanceof View parent) view = parent;
            if (!view.performClick()) throw new AssertionError("Cannot click " + text);
        });
        waitForIdleSync();
    }
}
