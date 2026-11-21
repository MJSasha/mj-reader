package com.mjreader;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Direct HTTPS transfers, serialized to keep parsing and disk use bounded. */
public class BookDownloadService extends Service {
    private static final String CHANNEL = "book_downloads";
    private static final Map<String, Transfer> active = new ConcurrentHashMap<>();
    // Record new requests promptly even while one of the serialized transfers is slow.
    private static final ExecutorService commands = Executors.newSingleThreadExecutor();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closing;
    static boolean active(String id) { Transfer t = active.get(id); return t != null && !t.cancelled; }
    static void command(Context context, String action, String id) {
        context.startForegroundService(new Intent(context, BookDownloadService.class).setAction(action).putExtra("id", id));
    }
    static void enqueue(Context context, Flibusta.Entry entry) {
        ArrayList<String> urls = new ArrayList<>(), formats = new ArrayList<>();
        long length = (long) entry.title().length() + entry.author().length() + entry.url().length();
        for (Flibusta.Acquisition alternative : entry.alternatives()) {
            length += alternative.url().length() + alternative.format().length();
            urls.add(alternative.url()); formats.add(alternative.format());
        }
        if (length > 32000) throw new IllegalArgumentException("Слишком большая запись каталога / Catalog entry too large");
        context.startForegroundService(new Intent(context, BookDownloadService.class).setAction("enqueue")
                .putExtra("title", entry.title()).putExtra("author", entry.author())
                .putExtra("url", entry.url()).putExtra("format", entry.format())
                .putStringArrayListExtra("urls", urls).putStringArrayListExtra("formats", formats));
    }
    private static Flibusta.Entry entry(Intent intent) {
        ArrayList<String> urls = intent.getStringArrayListExtra("urls"), formats = intent.getStringArrayListExtra("formats");
        List<Flibusta.Acquisition> alternatives = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) alternatives.add(new Flibusta.Acquisition(urls.get(i), formats.get(i)));
        return new Flibusta.Entry(intent.getStringExtra("title"), intent.getStringExtra("author"), "",
                intent.getStringExtra("url"), intent.getStringExtra("format"), true, alternatives);
    }
    private static final class Transfer {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        void cancel() {
            cancelled = true;
            HttpURLConnection c = connection;
            if (c != null) CompletableFuture.runAsync(c::disconnect);
        }
        void check() throws IOException { if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Загрузка отменена / Download cancelled"); }
    }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, tr("Загрузка книг", "Book downloads"), NotificationManager.IMPORTANCE_LOW));
        startForeground(26, notification(tr("Подготовка загрузки…", "Preparing download…"), true));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        commands.execute(() -> handleCommand(intent, startId));
        return START_NOT_STICKY;
    }
    private void handleCommand(Intent intent, int startId) {
        if (closing) return;
        String action = intent.getAction();
        if ("cancelAll".equals(action)) {
            for (Transfer transfer : active.values()) transfer.cancel();
            worker.execute(() -> stopSelf(startId)); return;
        }
        Flibusta.Entry entry;
        String id;
        try {
            entry = "enqueue".equals(action) ? entry(intent) : null;
            id = entry == null ? intent.getStringExtra("id") : BookDownloads.create(this, entry);
            BookDownloads.dir(this, id);
        } catch (Exception error) {
            reportCommandError(error);
            worker.execute(() -> stopSelf(startId));
            return;
        }
        if ("cancel".equals(action) || "delete".equals(action)) {
            Transfer transfer = active.get(id); if (transfer != null) transfer.cancel();
            worker.execute(() -> {
                try {
                    if ("delete".equals(action)) {
                        synchronized (BookDownloads.LOCK) { BookDownloads.delete(BookDownloads.dir(this, id)); }
                    }
                    else { JSONObject job = BookDownloads.get(this, id); if (!job.optString("state").equals("complete")) { job.put("state", "cancelled").put("rate", 0); BookDownloads.save(this, job); } }
                } catch (Exception error) { android.util.Log.e("MJDownload", "Cancel", error); }
                finally { stopSelf(startId); }
            });
        } else {
            Transfer transfer = new Transfer();
            if (active.compute(id, (key, previous) -> previous == null || previous.cancelled ? transfer : previous) == transfer) worker.execute(() -> {
                try {
                    if (closing) transfer.cancel();
                    // A pending Remove command can have deleted the prepared record.
                    if (entry != null && !transfer.cancelled) BookDownloads.create(this, entry, true);
                    download(id, transfer);
                } catch (Exception error) { reportCommandError(error); }
                finally { active.remove(id, transfer); stopSelf(startId); }
            });
            else worker.execute(() -> stopSelf(startId));
        }
    }
    private void reportCommandError(Exception error) {
        android.util.Log.e("MJDownload", "Download command", error);
        new Handler(Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(this,
                Objects.toString(error.getMessage(), error.toString()), android.widget.Toast.LENGTH_LONG).show());
    }
    private void download(String id, Transfer transfer) {
        try {
            transfer.check(); JSONObject job = BookDownloads.get(this, id);
            if (job.optString("state").equals("complete")) return;
            File directory = BookDownloads.dir(this, id);
            File[] unfinished = directory.listFiles((dir, name) -> name.endsWith(".part"));
            if (unfinished != null) for (File file : unfinished) Files.deleteIfExists(file.toPath());
            if (directory.getUsableSpace() < 64_000_000) throw new IOException("Недостаточно места / Not enough space");
            getSystemService(NotificationManager.class).notify(26, notification(job.getString("title"), true));
            importDownload(job, directory, transfer);
            job.put("state", "complete"); BookDownloads.save(this, job);
            try {
                sendBroadcast(new Intent(getPackageName() + ".BOOKS_IMPORTED").setPackage(getPackageName()));
                getSystemService(NotificationManager.class).notify(id.hashCode(), notification(tr("Добавлено в библиотеку: ", "Added to library: ") + job.getString("title"), false));
            } catch (RuntimeException error) { android.util.Log.w("MJDownload", "Report completed import", error); }
        } catch (Exception error) {
            try {
                JSONObject job = BookDownloads.get(this, id);
                if (!job.optString("state").equals("complete")) {
                    job.put("state", transfer.cancelled || closing ? "cancelled" : "error")
                            .put("rate", 0).put("error", Objects.toString(error.getMessage(), error.toString()));
                    BookDownloads.save(this, job);
                }
            }
            catch (Exception ignored) { android.util.Log.e("MJDownload", "Save download error", error); }
        }
    }

    private void importDownload(JSONObject job, File directory, Transfer transfer) throws Exception {
        List<Flibusta.Acquisition> links = new ArrayList<>();
        links.add(new Flibusta.Acquisition(job.getString("url"), job.getString("format")));
        JSONArray alternatives = job.optJSONArray("alternatives");
        if (alternatives != null) for (int i = 0; i < alternatives.length(); i++) {
            JSONObject link = alternatives.getJSONObject(i);
            links.add(new Flibusta.Acquisition(link.getString("url"), link.getString("format")));
        }
        // Flibusta serves FB2 from a ready ZIP. Its EPUB is generated by a separate converter.
        for (int i = 1; i < links.size(); i++) if (Flibusta.preferFb2(links.get(i))) {
            links.add(0, links.remove(i));
            break;
        }
        String name = job.optString("title", "Book").replaceAll("[\\p{Cntrl}/\\\\]", " ").trim();
        if (name.isEmpty()) name = "Book";
        int end = Math.min(60, name.length());
        if (Character.isHighSurrogate(name.charAt(end - 1))) end--;
        name = name.substring(0, end);
        Exception failure = new IOException("Нет EPUB, FB2 или TXT / No EPUB, FB2 or TXT available");
        for (Flibusta.Acquisition link : links) {
            transfer.check();
            String format = link.format();
            if (!Set.of("epub", "fb2", "txt").contains(format)) throw new IOException("Unsupported format");
            job.put("state", "downloading").put("downloadFormat", format).put("error", "");
            File temporary;
            try { temporary = transferFile(job, directory, transfer, link.url()); }
            catch (IOException error) { failure = error; continue; }
            File book = new File(directory, name + "." + format);
            try {
                long done = temporary.length();
                Files.move(temporary.toPath(), book.toPath(), StandardCopyOption.REPLACE_EXISTING);
                job.put("state", "importing").put("done", done).put("total", done).put("rate", 0);
                BookDownloads.save(this, job);
                transfer.check();
                try {
                    job.put("book", new Books(this).importBook(book).id());
                    return;
                } catch (Books.DuplicateBook duplicate) {
                    job.put("book", duplicate.id).put("duplicate", true);
                    return;
                } catch (Exception error) { failure = error; }
            } finally {
                discard(temporary);
                discard(book);
            }
        }
        transfer.check();
        throw failure;
    }

    private static void discard(File file) {
        try { Files.deleteIfExists(file.toPath()); }
        catch (IOException error) { android.util.Log.w("MJDownload", "Remove transfer file", error); }
    }

    private File transferFile(JSONObject job, File directory, Transfer transfer, String url) throws Exception {
        List<String> mirrors = getSharedPreferences("reader", 0).getString("catalog", "auto").equals("auto")
                ? Flibusta.mirrors(getSharedPreferences("reader", 0)) : Collections.emptyList();
        Queue<String> pending = new ArrayDeque<>(Flibusta.bookUrls(url, mirrors));
        for (int attempt = 0; ; attempt++) {
            transfer.check();
            String address = pending.remove();
            HttpURLConnection connection = null; File temporary = null;
            try {
                job.put("done", 0).put("total", -1).put("rate", 0); BookDownloads.save(this, job);
                connection = Flibusta.open(address, c -> { transfer.connection = c; transfer.check(); });
                long total = connection.getContentLengthLong();
                if (total > Flibusta.BOOK_LIMIT) throw new IOException("Книга больше 32 МБ / Book exceeds 32 MB");
                String mime = Objects.toString(connection.getContentType(), "").toLowerCase(Locale.ROOT);
                if (mime.contains("text/html") || mime.contains("application/xhtml+xml"))
                    throw new WebPageResponse();
                job.put("total", total); BookDownloads.save(this, job);
                temporary = File.createTempFile("book-", ".part", directory);
                long done = 0, last = android.os.SystemClock.elapsedRealtime(), lastBytes = 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        transfer.check();
                        if (count > Flibusta.BOOK_LIMIT - done) throw new IOException("Книга больше 32 МБ / Book exceeds 32 MB");
                        output.write(buffer, 0, count); done += count;
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - last >= 500) {
                            job.put("done", done).put("rate", (done - lastBytes) * 1000 / Math.max(1, now - last));
                            BookDownloads.save(this, job); last = now; lastBytes = done;
                        }
                    }
                    output.getFD().sync();
                }
                transfer.check();
                if (done == 0 || (total >= 0 && done != total)) throw new EOFException("Неполная загрузка / Incomplete download");
                File complete = temporary; temporary = null; return complete;
            } catch (IOException error) {
                transfer.check();
                if (Flibusta.retryable(error)) pending.add(address);
                else if (!(error instanceof Flibusta.HttpError || error instanceof WebPageResponse)) throw error;
                if (attempt >= 2 || pending.isEmpty()) throw error;
            } finally {
                if (connection != null) connection.disconnect(); transfer.connection = null;
                if (temporary != null) discard(temporary);
            }
            Thread.sleep(300L * (attempt + 1));
        }
    }
    private static final class WebPageResponse extends IOException {
        WebPageResponse() { super("Сайт вернул страницу вместо книги / Server returned a web page instead of a book"); }
    }
    private Notification notification(String text, boolean ongoing) {
        PendingIntent open = PendingIntent.getActivity(this, 26, new Intent(this, CatalogActivity.class).putExtra("downloads", true), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("MJReader")
                .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(!ongoing).setOngoing(ongoing);
        if (ongoing) builder.addAction(new Notification.Action.Builder(null, tr("Отменить", "Cancel"), PendingIntent.getService(this, 27,
                new Intent(this, BookDownloadService.class).setAction("cancelAll"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        return builder.build();
    }
    @Override public void onTimeout(int startId, int type) { closing = true; for (Transfer transfer : active.values()) transfer.cancel(); stopSelf(); }
    @Override public void onDestroy() {
        closing = true; for (Transfer transfer : active.values()) transfer.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    private String tr(String ru, String en) { return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru") ? ru : en; }
}
