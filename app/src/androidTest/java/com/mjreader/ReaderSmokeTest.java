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
    private String updateFixture;
    private boolean updatesOnly, visualOnly, sampleOnly, readerOnly, benchmarkOnly, catalogOnly, liveCatalog, adaptiveOnly, showcaseOnly;
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
        updateFixture = arguments == null ? null : arguments.getString("updateApk");
        updatesOnly = arguments != null && arguments.getString("updates", "false").equals("true");
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            AppUpdates.prefs(context).edit().putBoolean("automatic", false).commit();
            if (updatesOnly) { new UpdateCheck(this).run(updateFixture); result.putString("stream", "PASS: OTA release selection, checksum/signature/version rejection, daily check limit, themed UI and persisted download cancellation\n"); finish(Activity.RESULT_OK, result); return; }
            if (showcaseOnly) { showcase(); result.putString("stream", "PASS: original demonstration screenshots\n"); finish(Activity.RESULT_OK, result); return; }
            if (adaptiveOnly) { narrowScreenCheck(); adaptiveCheck(); pagesCheck(); result.putString("stream", "PASS: narrow 320/360 dp and Flip 7 proportions, RU/EN labels, phone/Fold/tablet resize, grid, reader position/fullscreen, EPUB illustration repair, exact page seams, one/two-page reflow, catalog retention, hover/focus and touch targets\n"); finish(Activity.RESULT_OK, result); return; }
            if (catalogOnly) { new CatalogCheck(this).run(liveCatalog); result.putString("stream", "PASS: OPDS failover, HTTPS EPUB download, cover/chapters import, cancellation/retry, background import, themed UI and recreation\n"); finish(Activity.RESULT_OK, result); return; }
            if (benchmarkOnly) {
                benchmarkCheck(result);
                finish(Activity.RESULT_OK, result); return;
            }
            if (readerOnly) {
                readerCheck();
                result.putString("stream", "PASS: bounded text windows, long-press book menu, typography, Dusk, quotes, volume/arrow paging, fullscreen anchor and search keyboard\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (sampleOnly) {
                sampleCheck();
                result.putString("stream", "PASS: supplied EPUB cover, 63 chapter anchors, legacy metadata repair, duplicate detection and reading position\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (visualOnly) {
                noteSpotlightCheck();
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
            fb2Check(store, epub.cover());
            openingAndNotesCheck(store);
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
            // Reimport is an action on this book, not a second unrestricted Add book flow.
            Uri repairSource = createDocument("repair-menu.epub", buffer.toByteArray());
            Uri wrongSource = createDocument("different-book.txt", "This is a different edition and must not be added.".getBytes(StandardCharsets.UTF_8));
            int beforeReimport = store.list().size();
            try {
                try { store.importBook(wrongSource, epub.id()); throw new AssertionError("Different book accepted for repair"); }
                catch (Books.DifferentBook expected) { }
                if (store.list().size() != beforeReimport) throw new AssertionError("Rejected repair added a book");
                JSONObject damaged = new JSONObject(new String(java.nio.file.Files.readAllBytes(legacyFile.toPath()), StandardCharsets.UTF_8));
                damaged.remove("cover");
                java.nio.file.Files.write(legacyFile.toPath(), damaged.toString().getBytes(StandardCharsets.UTF_8));
                prefs.edit().putInt("anchor." + epub.id(), 73).putString("bookmarks." + epub.id(), "[{\"offset\":73,\"note\":\"Keep me\"}]").commit();
                IntentFilter documentPicker = new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);
                documentPicker.addCategory(Intent.CATEGORY_OPENABLE);
                documentPicker.addDataType("*/*");
                ActivityMonitor picker = addMonitor(documentPicker,
                        new ActivityResult(Activity.RESULT_OK, new Intent().setData(repairSource)), true);
                try {
                    runOnMainSync(() -> invoke(activity, "bookMenu", new Class[]{Books.Book.class}, epub));
                    capture("book-reimport-menu");
                    clickSystem("Reimport");
                    for (int i = 0; i < 100; i++) {
                        waitForIdleSync();
                        if (picker.getHits() > 0 && !(boolean) field(activity, "importing") && field(activity, "current") != null) break;
                        Thread.sleep(100);
                    }
                    if (picker.getHits() != 1) throw new AssertionError("Reimport did not open the document picker");
                } finally { removeMonitor(picker); }
                Books.Book repaired = (Books.Book) field(activity, "current");
                if (repaired == null || !repaired.id().equals(epub.id()) || !repaired.text().equals(epub.text()) || repaired.cover() == null
                        || store.list().size() != beforeReimport || prefs.getInt("anchor." + epub.id(), 0) != 73
                        || !prefs.getString("bookmarks." + epub.id(), "").contains("Keep me"))
                    throw new AssertionError("Reimport menu failed to preserve the book or restore metadata");
                runOnMainSync(() -> invoke(activity, "library"));
                ActivityMonitor canceled = addMonitor(documentPicker, new ActivityResult(Activity.RESULT_CANCELED, null), true);
                try {
                    runOnMainSync(() -> invoke(activity, "bookMenu", new Class[]{Books.Book.class}, epub));
                    clickSystem("Reimport"); waitForIdleSync();
                    if (field(activity, "reimportId") != null || (boolean) field(activity, "importing"))
                        throw new AssertionError("Canceled reimport left pending state");
                } finally { removeMonitor(canceled); }
            } finally {
                context.getContentResolver().delete(repairSource, null, null);
                context.getContentResolver().delete(wrongSource, null, null);
            }
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
            if ((int) field(reopened, "anchor") != ruAnchor) throw new AssertionError("Relaunch lost position: " + ruAnchor + " -> " + field(reopened, "anchor"));
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
                runOnMainSync(() -> ((ScrollView) field(reopened, "settingsScroll")).fullScroll(View.FOCUS_DOWN));
                waitForIdleSync(); Thread.sleep(200); capture("settings-updates-" + theme);
                runOnMainSync(reopened::onBackPressed);
                runOnMainSync(() -> ((EditText) field(reopened, "librarySearch")).setText("Emulator EPUB"));
                runOnMainSync(() -> ((ListView) field(reopened, "libraryList")).setSelection(1));
                waitForIdleSync();
                Thread.sleep(300);
                capture("covers-" + theme);
            }
            runOnMainSync(reopened::finish);
            result.putString("stream", "PASS: batch/invalid-file imports, targeted reimport menu/cancel/wrong file, duplicates, FB2/FB2.ZIP encodings/images/chapters/repair/XML rejection, EPUB covers/navigation, rename/collections/favorite/status/delete/undo, in-book search, RU/EN RSVP, search library, bookmark, jump history, reflow anchor, per-book settings, relaunch position\n");
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
        awaitField(activity, "body");
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
        awaitPages(activity);
        int before = (int) field(activity, "anchor");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_VOLUME_DOWN);
        waitForIdleSync();
        awaitPages(activity);
        if ((int) field(activity, "anchor") <= before) throw new AssertionError("Volume did not turn page");
        before = (int) field(activity, "anchor");
        click(activity, activity.getString(R.string.next_page));
        awaitPages(activity);
        if ((int) field(activity, "anchor") <= before) throw new AssertionError("Arrow did not turn page");
        int anchor = (int) field(activity, "anchor");
        tapReading(activity, .5f);
        if (!(boolean) field(activity, "fullscreen")) throw new AssertionError("Center tap did not hide controls");
        waitForIdleSync();
        runOnMainSync(activity::onBackPressed);
        waitForIdleSync();
        if ((int) field(activity, "anchor") != anchor) throw new AssertionError("Fullscreen changed anchor");
        runOnMainSync(() -> {
            ((ReaderOptions) field(activity, "options")).put("pages", false);
            invoke(activity, "jump", new Class[]{int.class}, book.text().length() / 2);
        });
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
            View scroll = (View) field(activity, "pageReader");
            if (scroll == null) scroll = (View) field(activity, "textScroll");
            scroll.getLocationOnScreen(location);
            point[0] = location[0] + scroll.getWidth() * fraction;
            point[1] = location[1] + scroll.getHeight() / 2f;
        });
        long time = android.os.SystemClock.uptimeMillis();
        sendPointerSync(android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, point[0], point[1], 0));
        sendPointerSync(android.view.MotionEvent.obtain(time, time + 60, android.view.MotionEvent.ACTION_UP, point[0], point[1], 0));
        waitForIdleSync();
    }
    private void libraryConvenienceCheck() throws Exception {
        Context context = getTargetContext(); var prefs = context.getSharedPreferences("reader", 0);
        String[] keys = {"opened.demo-en", "status.demo-en", "anchor.demo-en", "deleted.demo-en"};
        var saved = new java.util.HashMap<String, Object>();
        for (String key : keys) if (prefs.contains(key)) saved.put(key, prefs.getAll().get(key));
        MainActivity activity = null;
        try {
            prefs.edit().putLong(keys[0], Long.MAX_VALUE).putString(keys[1], "reading").putInt(keys[2], 120).putBoolean(keys[3], false).commit();
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitField(activity, "librarySearch"); MainActivity screen = activity;
            runOnMainSync(() -> {
                View resume = screen.getWindow().getDecorView().findViewWithTag("continue-reading");
                if (resume == null || !resume.getContentDescription().toString().contains("The Quiet Orbit")) throw new AssertionError("Missing most recent reading book");
                ((EditText) field(screen, "librarySearch")).setText("no-matching-book-qa");
                if (resume.isShown()) throw new AssertionError("Resume card obscures search results");
                invoke(screen, "library"); // The same rebuild also runs when a Fold window changes size.
                View rebuilt = screen.getWindow().getDecorView().findViewWithTag("continue-reading");
                if (rebuilt == null || rebuilt.isShown()) throw new AssertionError("Search/resume state lost on window rebuild");
            }); waitForIdleSync();
            click(screen, "Clear search"); waitForIdleSync();
            runOnMainSync(() -> {
                View resume = screen.getWindow().getDecorView().findViewWithTag("continue-reading");
                if (!resume.isShown()) throw new AssertionError("Resume card not restored after search");
                resume.performClick();
            });
            awaitField(screen, "reading"); waitForIdleSync();
            if (!((Books.Book) field(screen, "current")).id().equals("demo-en") || Math.abs((int) field(screen, "anchor") - 120) > 100)
                throw new AssertionError("Resume did not restore the saved book/position");
            runOnMainSync(() -> invoke(screen, "library")); waitForIdleSync();
            click(screen, "Reading"); waitForIdleSync();
            if (!field(screen, "filter").equals("reading")) throw new AssertionError("Quick reading filter failed");
            runOnMainSync(() -> {
                prefs.edit().putBoolean("deleted.demo-en", true).commit();
                setField(screen, "filter", "all"); invoke(screen, "library");
                View resume = screen.getWindow().getDecorView().findViewWithTag("continue-reading");
                if (resume != null && resume.getContentDescription().toString().contains("The Quiet Orbit")) throw new AssertionError("Deleted book offered for resume");
            });
        } finally {
            if (activity != null) { MainActivity closing = activity; runOnMainSync(closing::finish); }
            var editor = prefs.edit();
            for (String key : keys) {
                Object value = saved.get(key);
                if (value instanceof String text) editor.putString(key, text);
                else if (value instanceof Long number) editor.putLong(key, number);
                else if (value instanceof Integer number) editor.putInt(key, number);
                else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                else editor.remove(key);
            }
            editor.commit();
        }
    }
    private void textSizePreviewCheck() throws Exception {
        Context context = getTargetContext(); var prefs = context.getSharedPreferences("reader", 0);
        String saved = prefs.getString("options", "{}");
        Books.Book book = new Books(context).list().stream().filter(b -> b.id().equals("demo-en")).findFirst().orElseThrow();
        try {
            for (int scenario = 0; scenario < 4; scenario++) {
                boolean pages = scenario < 2; int theme = scenario % 2 == 0 ? 0 : 2;
                shell("wm size " + (scenario == 3 ? "740x360" : "430x932"));
                shell("settings put system font_scale " + (scenario >= 2 ? "2.0" : "1.0"));
                prefs.edit().putString("options", "{\"theme\":" + theme + ",\"size\":21,\"pages\":" + pages + "}").commit();
                MainActivity screen = startStableMain(context);
                AlertDialog[] dialog = {null};
                try {
                    awaitField(screen, "librarySearch");
                    runOnMainSync(() -> invoke(screen, "open", new Class[]{Books.Book.class, boolean.class}, book, false));
                    awaitField(screen, "reading"); if (pages) awaitPages(screen); else awaitField(screen, "body"); waitForIdleSync();
                    int anchor = (int) field(screen, "anchor");
                    runOnMainSync(() -> dialog[0] = (AlertDialog) call(screen, "quickAppearance")); waitForIdleSync();
                    runOnMainSync(() -> {
                        TextView preview = dialog[0].getWindow().getDecorView().findViewWithTag("text-size-preview");
                        SeekBar slider = findSeekBar(dialog[0].getWindow().getDecorView());
                        if (preview == null || slider == null || preview.getText().length() == 0) throw new AssertionError("Missing live book excerpt");
                        float before = preview.getTextSize();
                        slider.setPressed(true); slider.setProgress(24); // A held drag previews 34 sp without reflowing the book.
                        if (preview.getTextSize() <= before || ((ReaderOptions) field(screen, "options")).number("size", 0) != 21)
                            throw new AssertionError("Drag preview is stale or reflows the book before release");
                        slider.setPressed(false);
                    }); waitForIdleSync();
                    clickSystem("Increase Text size"); waitForIdleSync();
                    if (((ReaderOptions) field(screen, "options")).number("size", 0) != 35) throw new AssertionError("Size not applied");
                    runOnMainSync(() -> {
                        View preview = dialog[0].getWindow().getDecorView().findViewWithTag("text-size-preview");
                        View slider = findSeekBar(dialog[0].getWindow().getDecorView());
                        for (View view : new View[]{preview, slider}) {
                            android.graphics.Rect visible = new android.graphics.Rect();
                            if (!view.getGlobalVisibleRect(visible) || visible.height() < view.getHeight()) throw new AssertionError("Text and slider must both be visible");
                        }
                        if (dialog[0].getWindow().getAttributes().dimAmount != 0) throw new AssertionError("Book darkened behind size panel");
                    });
                    capture("type-preview-" + scenario); clickSystem("Done");
                    if ((int) field(screen, "anchor") != anchor) throw new AssertionError("Size preview moved reading position");
                    runOnMainSync(() -> invoke(screen, "settings", new Class[]{boolean.class}, false)); waitForIdleSync();
                    TextView[] preview = {null};
                    runOnMainSync(() -> {
                        if (find(screen.getWindow().getDecorView(), "Appearance").getLineCount() > 1) throw new AssertionError("Settings tab label wraps mid-word");
                        preview[0] = screen.getWindow().getDecorView().findViewWithTag("text-size-preview");
                        View panel = (View) preview[0].getParent();
                        panel.requestRectangleOnScreen(new android.graphics.Rect(0, 0, panel.getWidth(), panel.getHeight()), true);
                    }); waitForIdleSync();
                    clickSystem("Decrease Text size"); waitForIdleSync();
                    if (screen.getWindow().getDecorView().findViewWithTag("text-size-preview") != preview[0]) throw new AssertionError("Settings rebuilt while adjusting size");
                    if (((ReaderOptions) field(screen, "options")).number("size", 0) != 34) throw new AssertionError("Settings size not saved");
                    capture("type-settings-" + scenario);
                } finally {
                    runOnMainSync(() -> { if (dialog[0] != null) dialog[0].dismiss(); screen.finish(); });
                }
            }
        } finally {
            prefs.edit().putString("options", saved).commit();
            shell("wm size 430x932"); shell("settings put system font_scale 1.0");
        }
    }
    private SeekBar findSeekBar(View view) {
        if (view instanceof SeekBar slider) return slider;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            SeekBar found = findSeekBar(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private void awaitDialogKeyboard(View decor) throws Exception {
        int[] previous = null; int stable = 0;
        // A cold CI keyboard can start after the dialog's first layout. Its animation
        // moves the entire window; only compare scrolling after that movement settles.
        for (int retry = 0; retry < 100; retry++) {
            int[] geometry = new int[5];
            runOnMainSync(() -> {
                android.view.WindowInsets insets = decor.getRootWindowInsets();
                geometry[0] = insets != null && insets.isVisible(android.view.WindowInsets.Type.ime()) ? 1 : 0;
                int[] location = new int[2]; decor.getLocationOnScreen(location);
                geometry[1] = location[0]; geometry[2] = location[1];
                geometry[3] = decor.getWidth(); geometry[4] = decor.getHeight();
            });
            stable = geometry[0] == 1 && java.util.Arrays.equals(previous, geometry) ? stable + 1 : 0;
            if (stable >= 5) return;
            previous = geometry; Thread.sleep(50);
        }
        throw new AssertionError("Dialog keyboard did not become visible with stable bounds");
    }
    private void pinnedDialogCloseCheck() throws Exception {
        Context context = getTargetContext();
        try {
            for (int scenario = 0; scenario < 4; scenario++) {
                shell("wm size " + (scenario == 2 ? "740x360" : scenario == 3 ? "320x747" : "360x840"));
                shell("settings put system font_scale " + (scenario == 1 || scenario == 2 ? "2.0" : "1.0"));
                MainActivity screen = startStableMain(context);
                AlertDialog[] dialog = {null}; boolean[] action = {false}; int[] dismissed = {0};
                int mode = scenario;
                try {
                    awaitField(screen, "librarySearch");
                    runOnMainSync(() -> {
                        ReaderDialogs builder = new ReaderDialogs(screen, new ReaderOptions("{\"theme\":" + (mode % 2 == 0 ? 0 : 2) + "}"))
                                .setTitle("A long book title and a long list of chapters")
                                .setMessage("Long content must scroll independently of the close button. ".repeat(120));
                        if (mode == 0 || mode == 1) builder.setNegativeButton("Delete", (d, w) -> action[0] = true)
                                .setPositiveButton("Save", (d, w) -> action[0] = true);
                        else if (mode == 2) builder.setNegativeButton("Cancel", null);
                        else { EditText input = new EditText(screen); input.setText("Search draft"); builder.setView(input); }
                        dialog[0] = builder.show(); dialog[0].setOnDismissListener(d -> dismissed[0]++);
                    });
                    waitForIdleSync(); Thread.sleep(300);
                    View decor = dialog[0].getWindow().getDecorView();
                    if (scenario == 3) awaitDialogKeyboard(decor);
                    View close = decor.findViewWithTag("dialog-close");
                    ScrollView scroll = decor.findViewWithTag("dialog-scroll");
                    int[] before = new int[2]; runOnMainSync(() -> close.getLocationOnScreen(before));
                    runOnMainSync(() -> scroll.scrollTo(0, scroll.getChildAt(0).getHeight())); waitForIdleSync();
                    runOnMainSync(() -> {
                        int[] after = new int[2]; close.getLocationOnScreen(after);
                        android.graphics.Rect visible = new android.graphics.Rect();
                        if (!close.getGlobalVisibleRect(visible) || visible.height() != close.getHeight()
                                || visible.width() != close.getWidth() || before[1] != after[1])
                            throw new AssertionError("Close button moved or was clipped while scrolling: scenario=" + mode
                                    + " before=" + java.util.Arrays.toString(before) + " after=" + java.util.Arrays.toString(after)
                                    + " visible=" + visible + " size=" + close.getWidth() + "x" + close.getHeight());
                        if (scroll.getScrollY() <= 0) throw new AssertionError("Dialog body did not scroll");
                        if (close.getContentDescription() == null) throw new AssertionError("Close has no accessible name");
                        assertTouchTargets(close);
                    });
                    capture("dialog-pinned-close-" + scenario);
                    // Click without an accessibility helper that could scroll the action into view.
                    runOnMainSync(close::performClick); waitForIdleSync();
                    if (dialog[0].isShowing() || dismissed[0] != 1 || action[0])
                        throw new AssertionError("Close must dismiss once, without saving or deleting");
                } finally { runOnMainSync(() -> { if (dialog[0] != null) dialog[0].dismiss(); screen.finish(); }); }
            }
        } finally {
            shell("wm size 430x932"); shell("settings put system font_scale 1.0");
        }
    }
    private void visualCheck() throws Exception {
        libraryConvenienceCheck();
        textSizePreviewCheck();
        pinnedDialogCloseCheck();
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
                    var book = new Flibusta.Entry("Станция «Тишина»", "MJReader Originals", "История о маяке на орбите. A new chapter begins. ".repeat(12),
                            "https://example.test/orbit.epub", "epub", true,
                            new Flibusta.Details("ru", "MJReader Originals", "2026", "urn:mjreader:orbit", java.util.List.of("Научная фантастика"), "564 Kb", 1234));
                    runOnMainSync(() -> {
                        ((java.util.List<Flibusta.Entry>) field(catalog, "entries")).add(book);
                        invoke(catalog, "render");
                    });
                    waitForIdleSync();
                    assertTouchTargets(catalog.getWindow().getDecorView());
                    capture("chrome-catalog-" + scenario);
                    boolean russian = context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru");
                    ViewGroup before = (ViewGroup) field(catalog, "root");
                    TextView tab = find(before.getChildAt(before.getChildCount() - 1), russian ? "Каталог" : "Catalog");
                    if (scenario < 2 && tab.getHeight() > 52 * context.getResources().getDisplayMetrics().density) throw new AssertionError("Catalog tab is too tall");
                    runOnMainSync(() -> invoke(catalog, "bookDetails", new Class[]{Flibusta.Entry.class}, book));
                    waitForIdleSync(); capture("chrome-book-details-" + scenario);
                    clickSystem(russian ? "Закрыть" : "Close");
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
            prefs.edit().putString("options", "{\"theme\":0,\"size\":21,\"countdown\":false,\"pages\":true}").putBoolean("grid", true)
                    .putLong("opened.showcase-ru", System.currentTimeMillis()).putString("status.showcase-ru", "reading").putInt("anchor.showcase-ru", ru.text().length() / 3).apply();
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitField(activity, "librarySearch"); MainActivity screen = activity;
            runOnMainSync(() -> { setField(screen, "books", demo); setField(screen, "sort", 2); invoke(screen, "library"); });
            waitForIdleSync(); capture("showcase-library-dark");
            runOnMainSync(() -> { ((ReaderOptions) field(screen, "options")).put("theme", 2); invoke(screen, "changedOptions"); invoke(screen, "library"); });
            waitForIdleSync(); capture("showcase-library-light");
            runOnMainSync(() -> invoke(screen, "open", new Class[]{Books.Book.class, boolean.class}, demo.get(0), false));
            awaitPages(screen); waitForIdleSync();
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
    private void narrowScreenCheck() throws Exception {
        Context context = getTargetContext(); var prefs = context.getSharedPreferences("reader", 0);
        String saved = prefs.getString("options", "{}");
        try {
            for (int scenario = 0; scenario < 4; scenario++) {
                boolean large = scenario == 3;
                // 1080x2520 is the Flip 7 inner-panel resolution; 420 dpi is a test density, not an OEM default.
                shell("wm size " + (scenario == 1 ? "1080x2520" : scenario == 2 ? "320x747" : "360x840"));
                shell("wm density " + (scenario == 1 ? "420" : "160"));
                shell("settings put system font_scale " + (large ? "2.0" : "1.0"));
                shell("cmd locale set-app-locales com.mjreader --locales " + (scenario == 1 ? "en-US" : "ru"));
                prefs.edit().putString("options", "{\"theme\":" + (scenario == 1 || scenario == 2 ? 2 : 0) + ",\"pages\":true}").commit();
                MainActivity screen = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    awaitField(screen, "librarySearch"); waitForIdleSync();
                    assertChrome(screen, (ListView) field(screen, "libraryList"));
                    assertTouchTargets(screen.getWindow().getDecorView());
                    ViewGroup root = (ViewGroup) field(screen, "root");
                    assertReadableActions(root.getChildAt(root.getChildCount() - 1));
                    capture("narrow-library-" + scenario);
                    Books.Book book = new Books(context).list().stream().filter(b -> b.id().equals("demo-en")).findFirst().orElseThrow();
                    runOnMainSync(() -> invoke(screen, "open", new Class[]{Books.Book.class, boolean.class}, book, false));
                    ReaderPages pages = awaitPages(screen); waitForIdleSync();
                    if (pages.columns() != 1) throw new AssertionError("Narrow reader must show one page");
                    assertTouchTargets(screen.getWindow().getDecorView()); capture("narrow-reader-" + scenario);
                    int anchor = (int) field(screen, "anchor");
                    runOnMainSync(() -> { ((ReaderOptions) field(screen, "options")).put("pages", false); invoke(screen, "changedOptions"); });
                    awaitField(screen, "body"); waitForIdleSync();
                    if ((int) field(screen, "anchor") != anchor) throw new AssertionError("Narrow reading mode switch moved anchor");
                    capture("narrow-scroll-" + scenario);
                    runOnMainSync(() -> invoke(screen, "settings", new Class[]{boolean.class}, false)); waitForIdleSync();
                    assertTouchTargets(screen.getWindow().getDecorView());
                    TextView tab = find(screen.getWindow().getDecorView(), scenario == 1 ? "Appearance" : "Оформление");
                    if (tab == null || tab.getLineCount() != 1) throw new AssertionError("Narrow settings tab wraps");
                    capture("narrow-settings-" + scenario);
                    runOnMainSync(() -> ((ScrollView) field(screen, "settingsScroll")).fullScroll(View.FOCUS_DOWN));
                    waitForIdleSync(); Thread.sleep(200);
                    View updates = screen.getWindow().getDecorView().findViewWithTag("updates-entry");
                    android.graphics.Rect visible = new android.graphics.Rect();
                    if (updates == null || !updates.isClickable() || !updates.getGlobalVisibleRect(visible) || visible.height() != updates.getHeight())
                        throw new AssertionError("Updates entry clipped on narrow screen");
                    assertTouchTargets(updates); capture("narrow-updates-" + scenario);
                } finally { runOnMainSync(screen::finish); }
                CatalogActivity catalog = (CatalogActivity) startActivitySync(new Intent(context, CatalogActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    runOnMainSync(() -> {
                        ((java.util.List<Flibusta.Entry>) field(catalog, "entries")).add(new Flibusta.Entry("Станция «Тишина»", "MJReader Originals",
                                "История о маяке на орбите. A new chapter begins.", "https://example.test/orbit.epub", "epub", true));
                        invoke(catalog, "render");
                    }); waitForIdleSync();
                    assertChrome(catalog, (ListView) field(catalog, "resultsList"));
                    assertTouchTargets(catalog.getWindow().getDecorView());
                    assertReadableActions(catalog.getWindow().getDecorView());
                    runOnMainSync(() -> {
                        ViewGroup root = (ViewGroup) field(catalog, "root");
                        Button browse = (Button) find(root, catalog.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru") ? "Разделы" : "Browse");
                        ReaderOptions theme = (ReaderOptions) field(catalog, "theme");
                        var normal = (android.graphics.drawable.GradientDrawable) browse.getBackground().getCurrent();
                        if (normal.getColor().getDefaultColor() != theme.surface()) throw new AssertionError("Browse lacks button surface");
                    });
                    capture("narrow-catalog-" + scenario);
                    runOnMainSync(() -> invoke(catalog, "switchTab", new Class[]{boolean.class}, true)); waitForIdleSync();
                    assertReadableActions(catalog.getWindow().getDecorView()); capture("narrow-downloads-" + scenario);
                } finally { runOnMainSync(catalog::finish); }
            }
        } finally {
            prefs.edit().putString("options", saved).commit();
            shell("wm size 430x932"); shell("wm density 160"); shell("settings put system font_scale 1.0");
            shell("cmd locale set-app-locales com.mjreader --locales en-US");
        }
    }
    private void assertReadableActions(View view) {
        if (!view.isShown()) return;
        if (view instanceof Button button && button.getText().length() > 0) {
            android.graphics.Rect visible = new android.graphics.Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() == button.getHeight()) {
                android.text.Layout layout = button.getLayout(); String text = button.getText().toString();
                for (int line = 0; line < layout.getLineCount(); line++) {
                    int end = layout.getLineEnd(line);
                    if (layout.getEllipsisCount(line) > 0 || end > 0 && end < text.length()
                            && Character.isLetterOrDigit(text.charAt(end - 1)) && Character.isLetterOrDigit(text.charAt(end)))
                        throw new AssertionError("Action label split or clipped: " + text);
                }
            }
        }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) assertReadableActions(group.getChildAt(i));
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
            if (field(screen, "pageReader") != null || ((TextView) field(screen, "body")).getWidth() > 710 * context.getResources().getDisplayMetrics().density) throw new AssertionError("Default scrolling or bounded width lost");
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
    private ReaderPages awaitPages(MainActivity activity) throws Exception {
        waitForIdleSync();
        for (int i = 0; i < 100; i++) {
            ReaderPages[] found = {null};
            runOnMainSync(() -> { ReaderPages view = (ReaderPages) field(activity, "pageReader"); if (view != null && view.ready()) found[0] = view; });
            if (found[0] != null) return found[0];
            Thread.sleep(50);
        }
        throw new AssertionError("Pages were not laid out");
    }
    private void pagesCheck() throws Exception {
        Context context = getTargetContext();
        var prefs = context.getSharedPreferences("reader", 0);
        String oldOptions = prefs.getString("options", "{}");
        Books store = new Books(context);
        Books.Book book = null;
        MainActivity screen = null;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                entry(zip, "META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>");
                entry(zip, "OPS/book.opf", "<package><metadata><title>Illustrated Orbit</title></metadata><manifest><item id='a' href='chapter.xhtml'/></manifest><spine><itemref idref='a'/></spine></package>");
                entry(zip, "OPS/chapter.xhtml", "<html><body><h1><img src='art.png' alt='The observatory'/></h1><p>" +
                        "Maya opened the observatory. Stars filled the window. ".repeat(240) + "</p><p><img src='art.png' alt='The stars'/></p><p>The last page.</p></body></html>");
                android.graphics.Bitmap art = android.graphics.Bitmap.createBitmap(600, 240, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(art); canvas.drawColor(0xff18334b);
                android.graphics.Paint paint = new android.graphics.Paint(); paint.setColor(0xffc7ff68);
                canvas.drawCircle(300, 120, 80, paint);
                zip.putNextEntry(new ZipEntry("OPS/art.png")); art.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry(); art.recycle();
            }
            book = checkImport(store, "illustrated-orbit.epub", buffer.toByteArray(), "Maya opened");
            if (!book.chapters().get(0).title().equals("The observatory") || book.illustrations().size() != 2)
                throw new AssertionError("Image heading / inline images lost");
            for (Books.Illustration image : book.illustrations()) if (book.text().charAt(image.offset()) != '\uFFFC') throw new AssertionError("Image offset changed");
            File stored = new File(context.getFilesDir(), "books/" + book.id() + ".json");
            JSONObject legacy = new JSONObject(new String(java.nio.file.Files.readAllBytes(stored.toPath()), StandardCharsets.UTF_8));
            legacy.remove("images"); legacy.remove("illustrations");
            legacy.getJSONArray("chapters").getJSONObject(0).put("title", "\uFFFC");
            java.nio.file.Files.write(stored.toPath(), legacy.toString().getBytes(StandardCharsets.UTF_8));
            prefs.edit().putInt("anchor." + book.id(), 73).putString("bookmarks." + book.id(), "[{\"offset\":73,\"note\":\"Saved\"}]").commit();
            Books.Book repaired = checkImport(store, "illustrated-repair.epub", buffer.toByteArray(), "Maya opened");
            if (!repaired.id().equals(book.id()) || !repaired.text().equals(book.text()) || repaired.illustrations().size() != 2
                    || prefs.getInt("anchor." + book.id(), 0) != 73 || !prefs.getString("bookmarks." + book.id(), "").contains("Saved"))
                throw new AssertionError("Repair changed identity/progress or lost images");
            book = repaired;
            prefs.edit().putString("options", "{\"pages\":true,\"size\":21,\"volumePages\":true}").putInt("anchor." + book.id(), 0).commit();
            screen = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitField(screen, "librarySearch");
            MainActivity opened = screen; Books.Book fixture = book;
            runOnMainSync(() -> invoke(opened, "open", new Class[]{Books.Book.class, boolean.class}, fixture, false));
            ReaderPages pages = awaitPages(screen);
            if (pages.columns() != 1) throw new AssertionError("Phone should show one page");
            android.text.Spanned first = (android.text.Spanned) pages.visiblePages().get(0).layout().getText();
            ReaderText.Picture[] pictures = first.getSpans(0, pages.visiblePages().get(0).end(), ReaderText.Picture.class);
            if (pictures.length == 0 || pictures[0].bitmap == null) throw new AssertionError("Visible illustration not decoded");
            capture("pages-illustration-dark");
            checkEdgeTaps(opened);

            for (int theme : new int[]{2, 0}) {
                runOnMainSync(() -> {
                    ((ReaderOptions) field(opened, "options")).put("theme", theme); invoke(opened, "changedOptions");
                }); awaitPages(screen);
                runOnMainSync(() -> {
                    ViewGroup dock = (ViewGroup) field(opened, "footer");
                    ViewGroup actions = (ViewGroup) dock.getChildAt(dock.getChildCount() - 1);
                    for (int i = 0; i < actions.getChildCount(); i++) if (actions.getChildAt(i) instanceof Button button) {
                        var background = (android.graphics.drawable.LayerDrawable) button.getBackground();
                        android.graphics.Rect icon = background.getDrawable(1).getBounds();
                        if (Math.abs(icon.centerX() - button.getWidth() / 2) > 1 || Math.abs(icon.centerY() - button.getHeight() / 2) > 1)
                            throw new AssertionError("Action icon is outside the center of its hit target");
                        button.setHovered(true);
                    }
                });
                capture("pages-hover-" + theme);
                checkPageAnimation(opened, "phone-" + theme);
            }
            // Every forward turn starts exactly after the last displayed line; back returns exactly.
            for (int i = 0; i < 12; i++) {
                pages = awaitPages(screen); int start = pages.start(), stop = pages.end();
                if (stop >= book.text().length()) break;
                ReaderPages view = pages;
                runOnMainSync(() -> view.turn(1)); pages = awaitPages(screen);
                if (pages.start() != stop || pages.end() <= stop) throw new AssertionError("Skipped/repeated page seam");
                runOnMainSync(() -> view.turn(-1)); pages = awaitPages(screen);
                if (pages.start() != start) throw new AssertionError("Back page changed anchor");
                runOnMainSync(() -> view.turn(1)); awaitPages(screen);
            }
            int saved = (int) field(screen, "anchor");
            screen = resize(screen, "800x900"); pages = awaitPages(screen);
            if (pages.columns() != 2 || pages.start() != saved || pages.visiblePages().size() != 2
                    || pages.visiblePages().get(0).end() != pages.visiblePages().get(1).start()) throw new AssertionError("Broken unfolded spread");
            capture("pages-spread-dark");
            checkEdgeTaps(screen);
            checkPageAnimation(screen, "spread-dark");
            ReaderPages unfolded = pages;
            runOnMainSync(() -> unfolded.turn(-1)); pages = awaitPages(screen);
            if (pages.end() != saved) throw new AssertionError("Backward reflow must end at former anchor");
            runOnMainSync(() -> unfolded.turn(1)); pages = awaitPages(screen);
            if (pages.start() != saved) throw new AssertionError("Forward after reflow lost former anchor");
            runOnMainSync(() -> unfolded.turn(-1));
            android.animation.ValueAnimator interrupted = awaitPageAnimation(unfolded);
            runOnMainSync(interrupted::pause);
            saved = unfolded.start();
            screen = resize(screen, "740x900"); pages = awaitPages(screen);
            if (pages.start() != saved || pages.columns() != 2) throw new AssertionError("Small resize lost spread position");
            screen = resize(screen, "430x932"); pages = awaitPages(screen);
            if (pages.start() != saved || pages.columns() != 1) throw new AssertionError("Fold lost position");
            MainActivity normal = screen;
            runOnMainSync(() -> { invoke(normal, "setFullscreen", new Class[]{boolean.class}, true); }); awaitPages(screen);
            runOnMainSync(() -> { invoke(normal, "setFullscreen", new Class[]{boolean.class}, false); }); awaitPages(screen);
            if ((int) field(screen, "anchor") != saved) throw new AssertionError("Fullscreen lost page position");
            runOnMainSync(() -> invoke(normal, "settings", new Class[]{boolean.class}, false));
            click(normal, "Pages"); click(normal, "Scroll");
            runOnMainSync(normal::onBackPressed); waitForIdleSync();
            awaitField(normal, "body"); Thread.sleep(200); waitForIdleSync();
            if (field(normal, "pageReader") != null || (int) field(normal, "anchor") != saved)
                throw new AssertionError("Switch to scroll lost position");
            runOnMainSync(() -> {
                View footer = (View) field(normal, "footer");
                if (find(footer, normal.getString(R.string.next_page)) != null || find(footer, normal.getString(R.string.previous_page)) != null)
                    throw new AssertionError("Scroll must not show page arrows");
                ScrollView scroll = (ScrollView) field(normal, "textScroll");
                scroll.scrollBy(0, 200);
            }); waitForIdleSync();
            checkControlsMotion(normal);
            capture("scroll-no-arrows-dark");
            int scrolled = (int) field(normal, "anchor");
            if (scrolled <= saved) throw new AssertionError("Continuous scrolling no longer works");
            runOnMainSync(() -> invoke(normal, "settings", new Class[]{boolean.class}, false));
            click(normal, "Scroll"); click(normal, "Pages");
            runOnMainSync(normal::onBackPressed); pages = awaitPages(normal);
            if (pages.start() != scrolled) throw new AssertionError("Switch to pages lost position");
            runOnMainSync(normal::finish); screen = null;
            // Large text, paper theme, short landscape and reduced motion.
            for (String size : new String[]{"430x932", "740x360"}) {
                shell("wm size " + size); shell("settings put system font_scale 2.0"); shell("settings put global animator_duration_scale 0");
                prefs.edit().putString("options", "{\"pages\":true,\"theme\":2,\"size\":21}").putInt("anchor." + book.id(), 0).commit();
                screen = startStableMain(context);
                awaitField(screen, "librarySearch"); MainActivity large = screen;
                runOnMainSync(() -> invoke(large, "open", new Class[]{Books.Book.class, boolean.class}, fixture, false));
                pages = awaitPages(screen);
                if (pages.columns() != 1 || pages.end() <= pages.start()) throw new AssertionError("Large-text pagination failed");
                assertTouchTargets(screen.getWindow().getDecorView());
                runOnMainSync(() -> {
                    int anchor = (int) field(large, "anchor");
                    invoke(large, "setFullscreen", new Class[]{boolean.class}, true);
                    if (field(large, "controlsTransition") != null)
                        throw new AssertionError("Controls ignore reduced motion");
                    invoke(large, "setFullscreen", new Class[]{boolean.class}, false);
                    if (field(large, "controlsTransition") != null || (int) field(large, "anchor") != anchor)
                        throw new AssertionError("Reduced-motion controls changed position");
                });
                pages = awaitPages(large);
                capture("pages-large-light-" + size);
                ReaderPages still = pages; int nextPage = pages.end();
                runOnMainSync(() -> still.turn(1)); pages = awaitPages(large);
                if (field(pages, "pageTurn") != null || pages.start() != nextPage)
                    throw new AssertionError("Reduced motion must switch pages immediately");
                runOnMainSync(large::finish); screen = null;
            }
        } finally {
            if (screen != null) { MainActivity end = screen; runOnMainSync(end::finish); }
            if (book != null) new File(context.getFilesDir(), "books/" + book.id() + ".json").delete();
            prefs.edit().putString("options", oldOptions).commit();
            shell("wm size 430x932"); shell("settings put system font_scale 1.0"); shell("settings put global animator_duration_scale 1");
        }
    }
    private void checkEdgeTaps(MainActivity activity) throws Exception {
        checkControlsMotion(activity);
        for (boolean fullscreen : new boolean[]{false, true}) {
            ReaderPages pages = awaitPages(activity);
            int start = pages.start(), end = pages.end();
            tapReading(activity, .9f); pages = awaitPages(activity);
            if (pages.start() != end || (boolean) field(activity, "fullscreen") != fullscreen)
                throw new AssertionError("Right edge must turn forward without toggling panels");
            tapReading(activity, .1f); pages = awaitPages(activity);
            if (pages.start() != start || (boolean) field(activity, "fullscreen") != fullscreen)
                throw new AssertionError("Left edge must return without toggling panels");
            tapReading(activity, .5f); pages = awaitPages(activity);
            if (pages.start() != start || (boolean) field(activity, "fullscreen") == fullscreen)
                throw new AssertionError("Center must toggle panels without turning a page");
        }
    }
    private void checkControlsMotion(MainActivity activity) throws Exception {
        int anchor = (int) field(activity, "anchor");
        runOnMainSync(() -> {
            invoke(activity, "setFullscreen", new Class[]{boolean.class}, true);
            if (field(activity, "controlsTransition") == null)
                throw new AssertionError("Center toggle should animate controls");
        });
        awaitControlsMotion(activity);
        runOnMainSync(() -> {
            if (((View) field(activity, "header")).getVisibility() != View.GONE
                    || ((View) field(activity, "footer")).getVisibility() != View.GONE)
                throw new AssertionError("Hidden controls still occupy reading space");
            // Reverse immediately twice, including before the next rendered frame.
            invoke(activity, "setFullscreen", new Class[]{boolean.class}, false);
            invoke(activity, "setFullscreen", new Class[]{boolean.class}, true);
            invoke(activity, "setFullscreen", new Class[]{boolean.class}, false);
        });
        awaitControlsMotion(activity);
        runOnMainSync(() -> {
            for (String name : new String[]{"header", "footer"}) {
                View panel = (View) field(activity, name);
                if (!panel.isShown() || panel.getAlpha() != 1f || panel.getTranslationY() != 0f)
                    throw new AssertionError("Interrupted control animation left a displaced panel");
            }
            if ((int) field(activity, "anchor") != anchor)
                throw new AssertionError("Control animation moved the reading anchor");
        });
    }
    private MainActivity startStableMain(Context context) throws Exception {
        MainActivity initial = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        MainActivity previous = null;
        int stable = 0;
        for (int i = 0; i < 200; i++) {
            MainActivity[] current = {null};
            runOnMainSync(() -> {
                MainActivity candidate = (MainActivity) ((java.lang.ref.WeakReference<?>) field(initial, "active")).get();
                if (candidate != null && !candidate.isDestroyed() && !candidate.isFinishing()
                        && candidate.hasWindowFocus() && field(candidate, "librarySearch") != null)
                    current[0] = candidate;
            });
            if (current[0] != null && current[0] == previous) {
                if (++stable >= 5) return current[0];
            } else { previous = current[0]; stable = 0; }
            Thread.sleep(50);
        }
        throw new AssertionError("MainActivity did not settle after display/font configuration change");
    }
    private void awaitControlsMotion(MainActivity activity) throws Exception {
        waitForIdleSync();
        for (int i = 0; i < 100; i++) {
            boolean[] done = {false};
            runOnMainSync(() -> done[0] = field(activity, "controlsTransition") == null);
            if (done[0]) { waitForIdleSync(); return; }
            Thread.sleep(20);
        }
        throw new AssertionError("Controls transition did not finish");
    }
    private android.animation.ValueAnimator awaitPageAnimation(ReaderPages pages) throws Exception {
        for (int i = 0; i < 100; i++) {
            android.animation.ValueAnimator[] animation = {null};
            runOnMainSync(() -> {
                Object turn = field(pages, "pageTurn");
                if (turn != null) animation[0] = (android.animation.ValueAnimator) field(turn, "animator");
            });
            if (animation[0] != null) return animation[0];
            Thread.sleep(10);
        }
        throw new AssertionError("Missing page slide animation");
    }
    private void checkPageAnimation(MainActivity activity, String name) throws Exception {
        ReaderPages view = awaitPages(activity);
        int start = view.start(), end = view.end();
        for (int direction : new int[]{1, -1}) {
            runOnMainSync(() -> {
                String label = activity.getString(direction > 0 ? R.string.next_page : R.string.previous_page);
                TextView arrow = find((View) field(activity, "footer"), label);
                if (arrow == null || !arrow.performClick()) throw new AssertionError("Missing page arrow");
            });
            android.animation.ValueAnimator animation = awaitPageAnimation(view);
            android.graphics.Bitmap[] frames = new android.graphics.Bitmap[2];
            runOnMainSync(() -> {
                animation.pause();
                if (animation.getDuration() != 180) throw new AssertionError("Expected short slide");
                for (int i = 0; i < 2; i++) {
                    animation.setCurrentFraction(i == 0 ? 0f : .42f);
                    frames[i] = android.graphics.Bitmap.createBitmap(view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                    view.draw(new android.graphics.Canvas(frames[i]));
                }
            });
            if (frames[0].sameAs(frames[1]) || view.ready()) throw new AssertionError("Turn has no visible intermediate frame");
            frames[0].recycle(); frames[1].recycle();
            capture("page-turn-" + name + "-" + direction);
            runOnMainSync(animation::resume); awaitPages(activity);
            if (view.start() != (direction > 0 ? end : start)) throw new AssertionError("Animated turn changed page seam");
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
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i); assertTouchTargets(child);
            if (!(child instanceof Button) || !child.isShown()) continue;
            for (int j = i + 1; j < group.getChildCount(); j++) {
                View other = group.getChildAt(j);
                if (!(other instanceof Button) || !other.isShown()) continue;
                int horizontal = Math.max(child.getLeft() - other.getRight(), other.getLeft() - child.getRight());
                int vertical = Math.max(child.getTop() - other.getBottom(), other.getTop() - child.getBottom());
                if (Math.max(horizontal, vertical) + 1 < 8 * view.getResources().getDisplayMetrics().density)
                    throw new AssertionError("Adjacent actions lack spacing: " + ((Button) child).getText() + " / " + ((Button) other).getText());
            }
        }
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
                if ((node.getText() != null && node.getText().toString().equalsIgnoreCase(text))
                        || (node.getContentDescription() != null && node.getContentDescription().toString().equalsIgnoreCase(text))) {
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
    private void noteBoundariesCheck() throws Exception {
        String[] layouts = {
            "<h1>Notes</h1><a name='n1'/>First note.<a name='n2'/>Second note.",
            "<div><h1>Notes</h1><span id='n1'>1</span><p>First note.</p><p>More detail.</p><span id='n2'>2</span><p>Second note.</p></div>",
            "<p><sup><a id='n1'>1</a></sup> First note. <sup><a id='n2'>2</a></sup> Second note.</p>",
            "<p><a xml:id='n1'>1</a></p><p>First note.</p><p>More detail.</p><p><a xml:id='n2'>2</a></p><p>Second note.</p>",
            "<aside id='n1'><p>First note.</p><p>More detail.</p></aside><aside id='n2'><p>Second note.</p></aside>",
            "<p id='n1'>First note.</p><p id='n2'>Second note.</p>"
        };
        for (String layout : layouts) {
            byte[] document = ("<html xmlns='http://www.w3.org/1999/xhtml'><body>" + layout + "</body></html>").getBytes(StandardCharsets.UTF_8);
            var links = android.text.Html.fromHtml("<p>Text <a href='notes.xhtml#n1'>[1]</a> then <a href='notes.xhtml#n2'>[2]</a>.</p>", 0);
            var notes = new BookNotes(java.util.Map.of("notes.xhtml", document)).read(links, "chapter.xhtml", 0);
            if (notes.size() != 2 || !notes.get(0).text().contains("First note.") || notes.get(0).text().contains("Second")
                    || notes.get(0).text().contains("Notes") || !notes.get(1).text().contains("Second note.") || notes.get(1).text().contains("First"))
                throw new AssertionError("Footnote boundary: " + layout + " -> " + notes);
            if (notes.get(0).text().contains("1First")) throw new AssertionError("Note number merged with paragraph");
            if (layout.contains("More detail.") && !notes.get(0).text().contains("\nMore detail.")) throw new AssertionError("Footnote paragraphs lost");
            for (int i = 0; i < notes.size(); i++) if (!links.toString().substring(notes.get(i).start(), notes.get(i).end()).equals("[" + (i + 1) + "]"))
                throw new AssertionError("Footnote range moved");
        }
    }
    private void noteSpotlightCheck() throws Exception {
        Context context = getTargetContext(); var prefs = context.getSharedPreferences("reader", 0);
        String saved = prefs.getString("options", "{}");
        try {
            for (int scenario = 0; scenario < 4; scenario++) {
                shell("wm size " + (scenario == 1 ? "800x900" : scenario == 3 ? "740x360" : "360x840"));
                shell("settings put system font_scale " + (scenario >= 2 ? "2.0" : "1.0"));
                shell("settings put global animator_duration_scale " + (scenario == 3 ? "0" : "1"));
                prefs.edit().putString("options", "{\"size\":19,\"theme\":" + (scenario % 2 * 2) + ",\"pages\":" + (scenario < 2) + "}").commit();
                String paragraph = "The evening was calm. Mira followed the distant light [1] across the quiet water until she reached the old lighthouse. Tomorrow she would return.\n\n";
                String text = paragraph.repeat(80);
                java.util.List<Books.Note> notes = new java.util.ArrayList<>();
                for (int at = text.indexOf("[1]"); at >= 0; at = text.indexOf("[1]", at + 3))
                    notes.add(new Books.Note(at, at + 3, "The lighthouse guided travelers safely home.\n\n".repeat(scenario >= 2 ? 30 : 1)));
                Books.Book book = new Books.Book("note-spotlight-" + scenario, "Lighthouse", "MJReader Originals", text,
                        java.util.List.of(new Books.Chapter("Lighthouse", 0)), null, java.util.List.of(), notes);
                MainActivity screen = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    awaitField(screen, "librarySearch");
                    runOnMainSync(() -> invoke(screen, "open", new Class[]{Books.Book.class, boolean.class}, book, false));
                    int offset;
                    if (scenario < 2) {
                        ReaderPages pages = awaitPages(screen);
                        ReaderPages.Page page = pages.visiblePages().get(scenario == 1 ? 1 : 0);
                        offset = notes.stream().filter(n -> ReadingText.sentence(text, n.start(), n.end())[0] >= page.start() && n.end() < page.end()).findFirst().orElseThrow().start();
                        int[] sentence = ReadingText.sentence(text, offset, offset + 3);
                        android.graphics.RectF bounds = new android.graphics.RectF();
                        pages.selection(sentence[0], sentence[1]).computeBounds(bounds, true);
                        if (bounds.isEmpty() || scenario == 1 && bounds.left < pages.getWidth() / 2f)
                            throw new AssertionError("Sentence highlight missing from the second page");
                    } else { awaitField(screen, "body"); Thread.sleep(250); offset = notes.get(0).start(); }
                    int anchor = (int) field(screen, "anchor");
                    runOnMainSync(() -> invoke(screen, "showNote", new Class[]{int.class}, offset));
                    waitForIdleSync(); Thread.sleep(250);
                    AlertDialog dialog = (AlertDialog) field(screen, "notePopup");
                    View decor = dialog.getWindow().getDecorView();
                    View close = decor.findViewWithTag("dialog-close");
                    View backdrop = decor.findViewWithTag("note-spotlight");
                    ScrollView scroll = decor.findViewWithTag("dialog-scroll");
                    android.graphics.Rect visible = new android.graphics.Rect();
                    capture("note-spotlight-" + scenario);
                    if (backdrop == null || !backdrop.getContentDescription().toString().startsWith("Mira followed")
                            || !close.getGlobalVisibleRect(visible) || visible.height() != close.getHeight() || scroll.getHeight() < 24)
                        throw new AssertionError("Note context, close button or text viewport missing: scenario=" + scenario + " viewport=" + scroll.getHeight() + " close=" + visible + "/" + close.getHeight() + " header=" + ((View) close.getParent()).getHeight() + " card=" + decor.findViewWithTag("note-card").getHeight() + " context=" + (backdrop == null ? "null" : backdrop.getContentDescription()));
                    capture("note-spotlight-" + scenario);
                    if (scenario >= 2) {
                        runOnMainSync(() -> scroll.scrollTo(0, scroll.getChildAt(0).getHeight())); waitForIdleSync();
                        if (scroll.getScrollY() <= 0 || !close.getGlobalVisibleRect(visible) || visible.height() != close.getHeight())
                            throw new AssertionError("Long note cannot scroll with a pinned close button: scenario=" + scenario + " scroll=" + scroll.getScrollY() + " close=" + visible + "/" + close.getHeight());
                    }
                    if (scenario == 1) runOnMainSync(() -> invoke(screen, "shell"));
                    else if (scenario == 2) runOnMainSync(dialog::onBackPressed);
                    else runOnMainSync(backdrop::performClick);
                    waitForIdleSync();
                    if (dialog.isShowing() || field(screen, "notePopup") != null || (int) field(screen, "anchor") != anchor)
                        throw new AssertionError("Dismissing/rebuilding note changed reading position or retained snapshot");
                } finally { runOnMainSync(screen::finish); }
            }
        } finally {
            prefs.edit().putString("options", saved).commit();
            shell("wm size 430x932"); shell("settings put system font_scale 1.0"); shell("settings put global animator_duration_scale 1");
        }
    }
    private void noteHitCheck() {
        String text = "Mira [1] [2] follows the stars.";
        var notes = java.util.List.of(new Books.Note(5, 8, "First"), new Books.Note(9, 12, "Second"));
        Books.Book book = new Books.Book("hit-test", "Notes", "", text, java.util.List.of(), null, java.util.List.of(), notes);
        android.text.TextPaint paint = new android.text.TextPaint(); paint.setTextSize(10);
        android.text.StaticLayout layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length(), paint, 300).build();
        for (Books.Note note : notes) {
            float x = (layout.getPrimaryHorizontal(note.start()) + layout.getPrimaryHorizontal(note.end())) / 2;
            if (ReaderText.noteHit(book, layout, 0, 0, text.length(), x, 0, 1) != note.start())
                throw new AssertionError("Overlapping hit targets chose the wrong note");
        }
        float right = layout.getPrimaryHorizontal(12);
        if (ReaderText.noteHit(book, layout, 0, 0, text.length(), right + 6, 3, 1) != 9
                || ReaderText.noteHit(book, layout, 0, 0, text.length(), 290, 3, 1) != -1)
            throw new AssertionError("Expanded note target bounds");
    }
    private void openingAndNotesCheck(Books store) throws Exception {
        noteBoundariesCheck();
        noteHitCheck();
        Context context = getTargetContext();
        var prefs = context.getSharedPreferences("reader", 0); String previous = prefs.getString("options", "{}");
        String fb2 = "<FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0' xmlns:l='http://www.w3.org/1999/xlink'><description><title-info><book-title>Footnotes FB2</book-title></title-info></description>"
                + "<body><section><p>Mira saw the light <a l:href='#note-1'>[1]</a> and returned home.</p></section></body>"
                + "<body name='notes'><section id='note-1'><title><p>1</p></title><p>The original lighthouse note.</p></section></body></FictionBook>";
        Books.Book fiction = checkImport(store, "notes.fb2", fb2.getBytes(StandardCharsets.UTF_8), "[1]");
        if (fiction.notes().size() != 1 || !fiction.notes().get(0).text().contains("original lighthouse")
                || !fiction.text().substring(fiction.notes().get(0).start(), fiction.notes().get(0).end()).equals("[1]")) throw new AssertionError("FB2 footnote offsets");
        String inlineFb2 = fb2.replace("<section id='note-1'><title><p>1</p></title><p>The original lighthouse note.</p></section>",
                "<a id='note-1'/><p>The original lighthouse note.</p><a id='note-2'/><p>Another note.</p>");
        Books.Book inlineFiction = Fb2.read(inlineFb2.getBytes(StandardCharsets.UTF_8), "Inline notes");
        if (inlineFiction.notes().size() != 1 || !inlineFiction.notes().get(0).text().equals("The original lighthouse note."))
            throw new AssertionError("FB2 standalone anchor captured adjacent note");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "META-INF/container.xml", "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>");
            entry(zip, "book.opf", "<package><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Footnotes EPUB</dc:title></metadata><manifest><item id='a' href='a.xhtml'/></manifest><spine><itemref idref='a'/></spine></package>");
            entry(zip, "a.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml'><body><h1>The lighthouse</h1><p>Mira read <a href='notes.xhtml#n1'>[1]</a> before sunrise.</p><p>" + "A new day at the observatory. ".repeat(150) + "</p></body></html>");
            entry(zip, "notes.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml'><body><h1>Notes</h1><a name='n1'/><p>A star helps Mira find the shore.</p><a name='n2'/><p>Another note must stay hidden.</p></body></html>");
        }
        Uri uri = createDocument("notes.epub", bytes.toByteArray());
        MainActivity screen = null;
        try {
            prefs.edit().putString("options", "{\"pages\":true,\"size\":10}").commit();
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/epub+zip").setPackage(context.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (context.getPackageManager().resolveActivity(view, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) == null)
                throw new AssertionError("EPUB Open with handler missing");
            for (String mime : new String[]{"application/epub+zip", "application/x-fictionbook+xml", "application/fb2+zip", "text/plain", "application/octet-stream"})
                if (context.getPackageManager().resolveActivity(new Intent(view).setDataAndType(uri, mime), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) == null)
                    throw new AssertionError("Open with handler missing: " + mime);
            screen = (MainActivity) startActivitySync(view); MainActivity active = screen;
            awaitField(screen, "current"); ReaderPages pages = awaitPages(screen);
            Books.Book book = (Books.Book) field(screen, "current");
            if (!book.title().equals("Footnotes EPUB") || book.notes().size() != 1) throw new AssertionError("External EPUB import / footnote");
            if (Math.abs(((android.text.TextPaint) field(pages, "paint")).getTextSize() / context.getResources().getDisplayMetrics().scaledDensity - 10) > .1)
                throw new AssertionError("10 sp pagination font");
            Books.Note note = book.notes().get(0);
            if (!note.text().equals("A star helps Mira find the shore.")) throw new AssertionError("Imported entire notes section: " + note.text());
            if (!book.text().substring(note.start(), note.end()).equals("[1]")) throw new AssertionError("EPUB footnote offsets");
            int count = store.list().size();
            for (int variant = 0; variant < 4; variant++) {
                boolean paged = variant % 2 == 0; int theme = variant / 2 * 2;
                runOnMainSync(() -> { ((ReaderOptions) field(active, "options")).put("theme", theme); ((ReaderOptions) field(active, "options")).put("pages", paged); invoke(active, "changedOptions"); });
                if (paged) awaitPages(screen); else { awaitField(screen, "body"); Thread.sleep(250); waitForIdleSync(); }
                int anchor = (int) field(screen, "anchor");
                float[] point = new float[2];
                runOnMainSync(() -> {
                    View textView = (View) field(active, paged ? "pageReader" : "body");
                    android.text.Layout layout; int from, top, margin;
                    if (paged) {
                        ReaderPages.Page page = ((ReaderPages) textView).visiblePages().get(0);
                        layout = page.layout(); from = page.sourceStart(); top = Math.round(12 * context.getResources().getDisplayMetrics().density) - page.top();
                        margin = Math.round(8 * context.getResources().getDisplayMetrics().density);
                    } else { layout = ((TextView) textView).getLayout(); from = (int) field(active, "textStart"); top = textView.getPaddingTop(); margin = textView.getPaddingLeft(); }
                    int at = note.end() - from, line = layout.getLineForOffset(at); int[] location = new int[2]; textView.getLocationOnScreen(location);
                    // Tap outside the printed marker, including at the 10 sp minimum.
                    point[0] = location[0] + margin + layout.getPrimaryHorizontal(at) + 6 * context.getResources().getDisplayMetrics().density;
                    point[1] = location[1] + top + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f;
                });
                long now = android.os.SystemClock.uptimeMillis();
                sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, point[0], point[1], 0));
                sendPointerSync(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, point[0], point[1], 0)); waitForIdleSync();
                boolean shown = false;
                for (int retry = 0; retry < 40 && !shown; retry++) {
                    AccessibilityNodeInfo window = getUiAutomation().getRootInActiveWindow();
                    shown = window != null && !window.findAccessibilityNodeInfosByText("A star helps Mira").isEmpty();
                    if (!shown) Thread.sleep(50);
                }
                if (!shown) throw new AssertionError("Footnote tap did not open text in " + paged);
                capture((paged ? "note-pages-" : "note-scroll-") + theme); clickSystem("OK");
                // Accessibility clicks finish before the dialog window returns input focus.
                boolean focused = false;
                for (int retry = 0; retry < 100 && !focused; retry++) {
                    boolean[] reading = {false};
                    runOnMainSync(() -> reading[0] = active.getWindow().getDecorView().hasWindowFocus());
                    focused = reading[0];
                    if (!focused) Thread.sleep(50);
                }
                if (!focused) throw new AssertionError("Reader did not regain focus after closing the note");
                if ((int) field(screen, "anchor") != anchor || (boolean) field(screen, "fullscreen")) throw new AssertionError("Note changed reading position/panels");
            }
            runOnMainSync(() -> {
                ((ReaderOptions) field(active, "options")).put("size", 12); invoke(active, "changedOptions");
                invoke(active, "quickAppearance");
            });
            clickSystem("Decrease Text size"); clickSystem("Decrease Text size"); clickSystem("Decrease Text size");
            if (((ReaderOptions) field(active, "options")).number("size", 0) != 10) throw new AssertionError("Quick appearance must reach and stop at 10 sp");
            clickSystem("Done");
            // Reimport restores links without replacing text or bookmarks.
            File file = new File(context.getFilesDir(), "books/" + book.id() + ".json");
            for (boolean missing : new boolean[]{false, true}) {
                JSONObject legacy = new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                if (missing) legacy.remove("notes");
                else legacy.getJSONArray("notes").getJSONObject(0).put("text", "Notes: first note, second note, entire section");
                java.nio.file.Files.write(file.toPath(), legacy.toString().getBytes(StandardCharsets.UTF_8));
                Books.Book repaired = store.importBook(uri, book.id());
                if (!repaired.id().equals(book.id()) || !repaired.text().equals(book.text()) || !repaired.notes().equals(book.notes()))
                    throw new AssertionError("Note repair changed original text or retained old note");
            }
            runOnMainSync(() -> invoke(active, "jump", new Class[]{int.class}, 400)); Thread.sleep(300); waitForIdleSync();
            int saved = (int) field(active, "anchor");
            runOnMainSync(() -> context.startActivity(new Intent(view).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)));
            Thread.sleep(500); waitForIdleSync(); awaitField(screen, "body");
            if (!((Books.Book) field(screen, "current")).id().equals(book.id()) || store.list().size() != count) throw new AssertionError("External duplicate created a new book");
            if ((int) field(screen, "anchor") != saved) throw new AssertionError("External duplicate lost progress");
            Intent share = new Intent(Intent.ACTION_SEND).setType("application/epub+zip").putExtra(Intent.EXTRA_STREAM, uri).setPackage(context.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (context.getPackageManager().resolveActivity(share, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) == null) throw new AssertionError("Share handler missing");
            runOnMainSync(() -> context.startActivity(share)); Thread.sleep(500); waitForIdleSync();
            if (store.list().size() != count) throw new AssertionError("Share created duplicate");
        } finally {
            if (screen != null) { MainActivity closing = screen; runOnMainSync(closing::finish); }
            context.getContentResolver().delete(uri, null, null); prefs.edit().putString("options", previous).commit();
        }
    }
    private void fb2Check(Books store, byte[] cover) throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?><FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0' xmlns:l='http://www.w3.org/1999/xlink'>"
                + "<description><title-info><book-title>Маяк FB2</book-title><author><first-name>Мира</first-name><middle-name>А.</middle-name><last-name>Лунная</last-name></author>"
                + "<author><nickname>MJReader</nickname></author><annotation><p>Not reading text</p></annotation><coverpage><image l:href='#cover'/></coverpage></title-info>"
                + "<document-info><author><nickname>Not the author</nickname></author></document-info></description>"
                + "<body><section><title><p>Первая глава</p></title><p>Мира <emphasis>увидела</emphasis> маяк &amp; звёзды.</p><image l:href='#cover' title='Маяк'/>"
                + "<section><title><p>Вторая глава</p></title><poem><stanza><v>Строка один</v><v>Строка два</v></stanza></poem><p>Конец истории.</p></section></section></body>"
                + "<body name='notes'><section><title><p>Примечания</p></title><p>Справка о маяке.</p></section></body><binary id='cover' content-type='image/png'>"
                + android.util.Base64.encodeToString(cover, android.util.Base64.NO_WRAP) + "</binary></FictionBook>";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        Books.Book book = checkImport(store, "original.fb2", bytes, "Мира увидела маяк & звёзды.");
        if (!book.title().equals("Маяк FB2") || !book.author().equals("Мира А. Лунная, MJReader")
                || !java.util.Arrays.equals(book.cover(), cover) || book.chapters().size() != 3 || book.illustrations().size() != 1
                || book.text().contains("Not reading") || !book.text().contains("Строка один\n\nСтрока два") || !book.text().contains("Справка о маяке."))
            throw new AssertionError("FB2 content/metadata/cover/chapters");
        for (Books.Chapter chapter : book.chapters())
            if (!book.text().substring(chapter.offset()).startsWith(chapter.title())) throw new AssertionError("FB2 chapter offset");
        Books.Illustration image = book.illustrations().get(0);
        if (book.text().charAt(image.offset()) != '\uFFFC' || !image.alt().equals("Маяк")) throw new AssertionError("FB2 image offset");
        Books.Book persisted = store.list().stream().filter(b -> b.id().equals(book.id())).findFirst().orElseThrow();
        if (!persisted.text().equals(book.text()) || persisted.illustrations().size() != 1) throw new AssertionError("FB2 persisted assets");
        try { checkImport(store, "duplicate.fb2", bytes, "Мира"); throw new AssertionError("FB2 duplicate accepted"); }
        catch (Books.DuplicateBook expected) { }
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) { entry(zip, "book.fb2", xml); }
        try { checkImport(store, "duplicate.fb2.zip", archive.toByteArray(), "Мира"); throw new AssertionError("Zipped FB2 duplicate accepted"); }
        catch (Books.DuplicateBook expected) { }
        File stored = new File(getTargetContext().getFilesDir(), "books/" + book.id() + ".json");
        JSONObject legacy = new JSONObject(new String(java.nio.file.Files.readAllBytes(stored.toPath()), StandardCharsets.UTF_8));
        legacy.remove("cover"); legacy.remove("images"); legacy.remove("illustrations");
        java.nio.file.Files.write(stored.toPath(), legacy.toString().getBytes(StandardCharsets.UTF_8));
        var prefs = getTargetContext().getSharedPreferences("reader", 0);
        prefs.edit().putInt("anchor." + book.id(), 12).putString("bookmarks." + book.id(), "[{\"offset\":12}]").commit();
        Books.Book repaired = checkImport(store, "repair.fb2.zip", archive.toByteArray(), "Мира");
        if (!repaired.id().equals(book.id()) || !repaired.text().equals(book.text()) || repaired.cover() == null || repaired.illustrations().size() != 1
                || prefs.getInt("anchor." + book.id(), 0) != 12 || !prefs.getString("bookmarks." + book.id(), "").contains("12")) throw new AssertionError("FB2 repair changed position");
        for (String encoding : new String[]{"windows-1251", "UTF-16"}) {
            String encoded = xml.replace("UTF-8", encoding).replace("Конец истории.", "Конец истории. " + encoding);
            checkImport(store, "encoding.fb2", encoded.getBytes(Charset.forName(encoding)), "Мира увидела маяк & звёзды.");
        }
        int count = store.list().size();
        for (String invalid : new String[]{"<not-a-book/>", "<FictionBook><body/>",
                "<!DOCTYPE FictionBook [<!ENTITY bad SYSTEM 'file:///private'>]><FictionBook><body><p>&bad;</p></body></FictionBook>",
                "<FictionBook><body>" + "<section>".repeat(130) + "too deep" + "</section>".repeat(130) + "</body></FictionBook>"}) {
            try { checkImport(store, "invalid.fb2", invalid.getBytes(StandardCharsets.UTF_8), ""); throw new AssertionError("Invalid FB2 accepted"); }
            catch (IOException | org.xml.sax.SAXException | org.xmlpull.v1.XmlPullParserException expected) { }
        }
        if (store.list().size() != count) throw new AssertionError("Invalid FB2 altered library");
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
        values.put(MediaStore.Downloads.MIME_TYPE, name.endsWith(".epub") ? "application/epub+zip" : name.endsWith(".zip") ? "application/zip" : name.endsWith(".fb2") ? "application/x-fictionbook+xml" : "text/plain");
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
        if (view instanceof TextView label && !(view instanceof EditText) && (label.getText().toString().equals(text) || text.equals(label.getContentDescription()))) return label;
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
