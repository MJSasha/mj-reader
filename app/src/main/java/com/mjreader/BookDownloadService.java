package com.mjreader;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.os.*;
import android.text.format.Formatter;
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
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, tr(this, "Загрузка книг", "Book downloads"), NotificationManager.IMPORTANCE_LOW));
        startForeground(26, notification(this, null, 0, -1, 0));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        commands.execute(() -> handleCommand(intent, startId));
        return START_REDELIVER_INTENT;
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
            if ("recover".equals(action) && (active.containsKey(id) || !BookDownloads.pending(BookDownloads.get(this, id).optString("state")))) {
                worker.execute(() -> stopSelf(startId));
                return;
            }
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
            File[] unfinished = directory.listFiles((dir, name) -> name.endsWith(".part") && !name.equals("book.part"));
            if (unfinished != null) for (File file : unfinished) Files.deleteIfExists(file.toPath());
            if (directory.getUsableSpace() < 64_000_000) throw new IOException("Недостаточно места / Not enough space");
            getSystemService(NotificationManager.class).notify(26, notification(this, job.getString("title"), 0, -1, 0));
            importDownload(job, directory, transfer);
            job.put("state", "complete"); BookDownloads.save(this, job);
            try {
                sendBroadcast(new Intent(getPackageName() + ".BOOKS_IMPORTED").setPackage(getPackageName()));
            } catch (RuntimeException error) { android.util.Log.w("MJDownload", "Report completed import", error); }
        } catch (Exception error) {
            try {
                JSONObject job = BookDownloads.get(this, id);
                if (!job.optString("state").equals("complete")) {
                    File part = new File(BookDownloads.dir(this, id), "book.part");
                    if (transfer.cancelled || closing || !strongETag(job.optString("partialETag"))
                            || part.length() >= job.optLong("partialTotal", -1)) {
                        discard(part);
                        job.put("done", 0);
                    }
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
            job.put("state", "downloading").put("downloadFormat", format).put("error", "").put("rate", 0).put("done", 0).put("total", -1);
            BookDownloads.save(this, job);
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
        Deque<String> pending = new ArrayDeque<>(Flibusta.bookUrls(url, mirrors));
        File partial = new File(directory, "book.part");
        for (int attempt = 0; ; attempt++) {
            transfer.check();
            String address = pending.remove();
            HttpURLConnection connection = null;
            long offset = partial.length(), previousTotal = job.optLong("partialTotal", -1);
            String source = job.optString("partialUrl"), validator = job.optString("partialETag");
            boolean resumable = offset > 0 && offset < previousTotal && previousTotal <= Flibusta.BOOK_LIMIT
                    && strongETag(validator);
            try {
                connection = Flibusta.open(address, c -> {
                    transfer.connection = c; transfer.check();
                    if (resumable && source.equals(c.getURL().toString())) {
                        c.setRequestProperty("Range", "bytes=" + offset + "-");
                        c.setRequestProperty("If-Range", validator);
                    }
                });
                boolean append = connection.getResponseCode() == 206;
                long length = connection.getContentLengthLong(), total = append ? previousTotal : length;
                String etag = connection.getHeaderField("ETag");
                String expectedRange = "bytes " + offset + "-" + (total - 1) + "/" + total;
                if (append && (!resumable || !source.equals(connection.getURL().toString())
                        || !expectedRange.equals(connection.getHeaderField("Content-Range"))
                        || length >= 0 && length != total - offset || etag != null && !validator.equals(etag)))
                    throw new InvalidRange();
                if (total > Flibusta.BOOK_LIMIT) throw new IOException("Книга больше 32 МБ / Book exceeds 32 MB");
                String mime = Objects.toString(connection.getContentType(), "").toLowerCase(Locale.ROOT);
                if (mime.contains("text/html") || mime.contains("application/xhtml+xml") || mime.contains("application/json"))
                    throw new WebPageResponse();
                long done = append ? offset : 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial, append)) {
                    // Truncate before publishing a new validator so a killed process cannot join different editions.
                    job.put("partialUrl", connection.getURL().toString()).put("partialTotal", total)
                            .put("partialETag", append ? validator : etag)
                            .put("total", total).put("done", done).put("rate", 0);
                    BookDownloads.save(this, job);
                    long last = android.os.SystemClock.elapsedRealtime(), lastBytes = done;
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        transfer.check();
                        if (count > Flibusta.BOOK_LIMIT - done) throw new IOException("Книга больше 32 МБ / Book exceeds 32 MB");
                        if (total >= 0 && count > total - done) throw new InvalidRange();
                        output.write(buffer, 0, count); done += count;
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - last >= 500) {
                            long sample = (done - lastBytes) * 1000 / Math.max(1, now - last);
                            long previous = job.optLong("rate");
                            long rate = previous > 0 ? (previous * 7 + sample * 3) / 10 : sample;
                            job.put("done", done).put("rate", rate);
                            BookDownloads.save(this, job);
                            getSystemService(NotificationManager.class).notify(26, notification(this, job.optString("title"), done, total, rate));
                            last = now; lastBytes = done;
                        }
                    }
                    output.getFD().sync();
                }
                transfer.check();
                if (done == 0 || (total >= 0 && done != total)) throw new EOFException("Неполная загрузка / Incomplete download");
                return partial;
            } catch (IOException error) {
                transfer.check();
                if (error instanceof InvalidRange || resumable && error instanceof Flibusta.HttpError http && http.code == 416) {
                    Files.deleteIfExists(partial.toPath());
                    pending.addFirst(address);
                } else if (Flibusta.retryable(error)) {
                    // Retry this validated representation before switching mirrors.
                    if (attempt == 0 && strongETag(job.optString("partialETag")) && partial.length() > 0) pending.addFirst(address);
                    else pending.addLast(address);
                } else if (!(error instanceof Flibusta.HttpError || error instanceof WebPageResponse)) throw error;
                if (attempt >= 2 || pending.isEmpty()) throw error;
            } finally {
                if (connection != null) connection.disconnect(); transfer.connection = null;
            }
            Thread.sleep(300L * (attempt + 1));
        }
    }
    private static boolean strongETag(String value) {
        return value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                && value.substring(1, value.length() - 1).matches("[!#-~]*");
    }
    private static final class InvalidRange extends IOException {
        InvalidRange() { super("Неверный диапазон файла / Invalid book range"); }
    }
    private static final class WebPageResponse extends IOException {
        WebPageResponse() { super("Сайт вернул страницу вместо книги / Server returned a web page instead of a book"); }
    }
    static Notification notification(Context context, String title, long done, long total, long rate) {
        Intent destination = new Intent(context, CatalogActivity.class).putExtra(CatalogActivity.EXTRA_DOWNLOADS, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 26, destination, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean knownSize = total > 0;
        int percent = knownSize ? (int) Math.min(99, done * 100 / total) : 0;
        String progress = Formatter.formatShortFileSize(context, done);
        if (knownSize) progress = percent + "% · " + progress + tr(context, " из ", " of ") + Formatter.formatShortFileSize(context, total);
        if (rate > 0) {
            progress += " · " + Formatter.formatShortFileSize(context, rate) + tr(context, "/с", "/s");
            long seconds = knownSize ? (total - done) / rate : 0;
            if (seconds >= 5 && seconds <= 3600) progress += seconds < 60
                    ? " · ≈ " + ((seconds + 4) / 5 * 5) + tr(context, " с", " s")
                    : " · ≈ " + ((seconds + 30) / 60) + tr(context, " мин", " min");
        }
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title == null ? tr(context, "Загрузка книги", "Book download") : title)
                .setContentText(done == 0 && !knownSize ? tr(context, "Подготовка загрузки…", "Preparing download…") : progress)
                .setSubText(tr(context, "Загрузки", "Downloads")).setContentIntent(open)
                .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setShowWhen(false).setOnlyAlertOnce(true).setOngoing(true);
        if (Build.VERSION.SDK_INT >= 36) {
            boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            builder.setStyle(new Notification.ProgressStyle()
                    .setProgressSegments(List.of(new Notification.ProgressStyle.Segment(100).setColor(ReaderOptions.brand(!night))))
                    .setProgress(percent).setProgressIndeterminate(!knownSize)
                    .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_notification)));
        } else builder.setProgress(100, percent, !knownSize);
        return builder.build();
    }
    @Override public void onTimeout(int startId, int type) { closing = true; for (Transfer transfer : active.values()) transfer.cancel(); stopSelf(); }
    @Override public void onDestroy() {
        closing = true; for (Transfer transfer : active.values()) transfer.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    private static String tr(Context context, String ru, String en) { return context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru") ? ru : en; }
}
