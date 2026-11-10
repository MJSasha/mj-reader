package com.mjreader;

import android.app.*;
import android.content.*;
import android.os.*;
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
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closing;
    static boolean active(String id) { Transfer t = active.get(id); return t != null && !t.cancelled; }
    static void command(Context context, String action, String id) {
        context.startForegroundService(new Intent(context, BookDownloadService.class).setAction(action).putExtra("id", id));
    }
    private static final class Transfer {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        void cancel() { cancelled = true; HttpURLConnection c = connection; if (c != null) c.disconnect(); }
        void check() throws IOException { if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Загрузка отменена / Download cancelled"); }
    }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, tr("Загрузка книг", "Book downloads"), NotificationManager.IMPORTANCE_LOW));
        startForeground(26, notification(tr("Подготовка загрузки…", "Preparing download…"), true));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        String id = intent.getStringExtra("id"), action = intent.getAction();
        if ("cancelAll".equals(action)) {
            for (Transfer transfer : active.values()) transfer.cancel();
            worker.execute(() -> stopSelf(startId)); return START_NOT_STICKY;
        }
        try { BookDownloads.dir(this, id); } catch (RuntimeException error) { stopSelf(startId); return START_NOT_STICKY; }
        if ("cancel".equals(action) || "delete".equals(action)) {
            Transfer transfer = active.get(id); if (transfer != null) transfer.cancel();
            worker.execute(() -> {
                try {
                    if ("delete".equals(action)) BookDownloads.delete(BookDownloads.dir(this, id));
                    else { JSONObject job = BookDownloads.get(this, id); if (!job.optString("state").equals("complete")) { job.put("state", "cancelled").put("rate", 0); BookDownloads.save(this, job); } }
                } catch (Exception error) { android.util.Log.e("MJDownload", "Cancel", error); }
                finally { stopSelf(startId); }
            });
        } else {
            Transfer transfer = new Transfer();
            if (active.putIfAbsent(id, transfer) == null) worker.execute(() -> {
                try { download(id, transfer); }
                finally { active.remove(id, transfer); stopSelf(startId); }
            });
            else worker.execute(() -> stopSelf(startId));
        }
        return START_NOT_STICKY;
    }
    private void download(String id, Transfer transfer) {
        File temporary = null;
        try {
            transfer.check(); JSONObject job = BookDownloads.get(this, id);
            if (job.optString("state").equals("complete")) return;
            File directory = BookDownloads.dir(this, id);
            File[] unfinished = directory.listFiles((dir, name) -> name.endsWith(".part"));
            if (unfinished != null) for (File file : unfinished) Files.deleteIfExists(file.toPath());
            if (directory.getUsableSpace() < 64_000_000) throw new IOException("Недостаточно места / Not enough space");
            job.put("state", "downloading").put("done", 0).put("total", -1).put("error", ""); BookDownloads.save(this, job);
            getSystemService(NotificationManager.class).notify(26, notification(job.getString("title"), true));
            temporary = transferFile(job, directory, transfer);
            long done = temporary.length();
            String format = job.getString("format");
            if (!format.equals("epub") && !format.equals("txt") && !format.equals("fb2")) throw new IOException("Unsupported format");
            String name = job.optString("title", "Book").replaceAll("[\\p{Cntrl}/\\\\]", " ").trim();
            if (name.isEmpty()) name = "Book";
            int end = Math.min(60, name.length());
            if (Character.isHighSurrogate(name.charAt(end - 1))) end--;
            File book = new File(directory, name.substring(0, end) + "." + format);
            Files.move(temporary.toPath(), book.toPath(), StandardCopyOption.REPLACE_EXISTING); temporary = null;
            job.put("state", "importing").put("done", done).put("total", done).put("rate", 0); BookDownloads.save(this, job);
            transfer.check();
            try { job.put("book", new Books(this).importBook(book).id()); }
            catch (Books.DuplicateBook ignored) { job.put("duplicate", true); }
            job.put("state", "complete"); BookDownloads.save(this, job);
            // The library is the durable copy; completed network payloads need no second copy.
            Files.deleteIfExists(book.toPath());
            sendBroadcast(new Intent(getPackageName() + ".BOOKS_IMPORTED").setPackage(getPackageName()));
            getSystemService(NotificationManager.class).notify(id.hashCode(), notification(tr("Добавлено в библиотеку: ", "Added to library: ") + job.getString("title"), false));
        } catch (Exception error) {
            try { JSONObject job = BookDownloads.get(this, id); job.put("state", transfer.cancelled || closing ? "cancelled" : "error").put("rate", 0).put("error", Objects.toString(error.getMessage(), error.toString())); BookDownloads.save(this, job); }
            catch (Exception ignored) { android.util.Log.e("MJDownload", "Save download error", error); }
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { }
        }
    }
    private File transferFile(JSONObject job, File directory, Transfer transfer) throws Exception {
        List<String> mirrors = getSharedPreferences("reader", 0).getString("catalog", "auto").equals("auto")
                ? Flibusta.mirrors(getSharedPreferences("reader", 0)) : Collections.emptyList();
        List<String> urls = Flibusta.bookUrls(job.getString("url"), mirrors);
        for (int attempt = 0; ; attempt++) {
            transfer.check();
            HttpURLConnection connection = null; File temporary = null;
            try {
                job.put("done", 0).put("total", -1).put("rate", 0); BookDownloads.save(this, job);
                connection = Flibusta.open(urls.get(attempt % urls.size()), c -> { transfer.connection = c; transfer.check(); });
                long total = connection.getContentLengthLong();
                if (total > Flibusta.BOOK_LIMIT) throw new IOException("Книга больше 32 МБ / Book exceeds 32 MB");
                String mime = Objects.toString(connection.getContentType(), "").toLowerCase(Locale.ROOT);
                if (mime.contains("text/html")) throw new IOException("Сайт вернул страницу вместо книги / Server returned a web page instead of a book");
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
                if (attempt >= 2 || (!Flibusta.retryable(error) && !(attempt + 1 < urls.size() && error instanceof Flibusta.HttpError))) throw error;
                Thread.sleep(300L * (attempt + 1));
            } finally {
                if (connection != null) connection.disconnect(); transfer.connection = null;
                if (temporary != null) Files.deleteIfExists(temporary.toPath());
            }
        }
    }
    private Notification notification(String text, boolean ongoing) {
        PendingIntent open = PendingIntent.getActivity(this, 26, new Intent(this, CatalogActivity.class).putExtra("downloads", true), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle("MJReader")
                .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(!ongoing).setOngoing(ongoing);
        if (ongoing) builder.addAction(new Notification.Action.Builder(null, tr("Отменить", "Cancel"), PendingIntent.getService(this, 27,
                new Intent(this, BookDownloadService.class).setAction("cancelAll"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        return builder.build();
    }
    @Override public void onTimeout(int startId, int type) { for (Transfer transfer : active.values()) transfer.cancel(); stopSelf(); }
    @Override public void onDestroy() {
        closing = true; for (Transfer transfer : active.values()) transfer.cancel(); worker.shutdown(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    private String tr(String ru, String en) { return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru") ? ru : en; }
}
