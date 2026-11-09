package com.mjreader;

import android.text.Html;
import org.w3c.dom.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** OPDS 1.x reader. Only explicit acquisition links become downloadable books. */
final class Flibusta {
    static final String DEFAULT = "https://flibusta.site/opds";
    static final int BOOK_LIMIT = 32_000_000;
    static final List<String> MIRRORS = Arrays.asList(DEFAULT, "https://flibusta.is/opds");
    record Entry(String title, String author, String summary, String url, String format, boolean book) {}
    record Feed(String title, List<Entry> entries, String next) {}
    record Loaded(Feed feed, String url, String base) {}
    static Loaded loadMirrors(String requested, List<String> bases) throws Exception { return loadMirrors(requested, bases, null); }
    static Loaded loadMirrors(String requested, List<String> bases, java.util.function.BiConsumer<String, Integer> progress) throws Exception {
        URL request = https(requested); int opds = request.getFile().indexOf("/opds");
        if (opds < 0) throw new IOException("Invalid OPDS path");
        String suffix = request.getFile().substring(opds + 5);
        Exception failure = null; List<String> remaining = new ArrayList<>(bases); int attempt = 0;
        long deadline = android.os.SystemClock.elapsedRealtime() + 40000;
        for (int round = 0; round < 3 && !remaining.isEmpty(); round++) {
            if (round > 0) Thread.sleep(500L * round);
            List<String> retry = new ArrayList<>();
            for (String base : remaining) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                if (attempt > 0 && android.os.SystemClock.elapsedRealtime() >= deadline) break;
                String url = catalog(base) + suffix;
                if (progress != null) progress.accept(https(base).getHost(), ++attempt); else attempt++;
                try { return new Loaded(load(url), url, base); }
                catch (Exception error) { failure = error; if (retryable(error)) retry.add(base); }
            }
            remaining = retry;
        }
        throw new IOException("Каталог недоступен после повторных попыток / Catalog unavailable after retries: " + (failure == null ? "" : failure.getMessage()), failure);
    }
    private static boolean retryable(Exception error) {
        return error instanceof SocketTimeoutException || error instanceof SocketException || error instanceof UnknownHostException || error instanceof EOFException
                || error instanceof HttpError http && (http.code == 408 || http.code == 429 || http.code >= 500);
    }
    private static final class HttpError extends IOException {
        final int code;
        HttpError(int code, String host) { super("HTTP " + code + " · " + host); this.code = code; }
    }
    static List<String> mirrors(String last) {
        return mirrors(last, MIRRORS);
    }
    static List<String> mirrors(android.content.SharedPreferences prefs) {
        List<String> configured = new ArrayList<>(MIRRORS);
        for (String value : prefs.getString("catalog.mirrors", "").split("\n")) {
            if (value.trim().isEmpty()) continue;
            try { configured.add(catalog(value)); } catch (IOException ignored) { }
        }
        return mirrors(prefs.getString("catalog.last", ""), configured);
    }
    private static List<String> mirrors(String last, List<String> configured) {
        LinkedHashSet<String> bases = new LinkedHashSet<>();
        if (configured.contains(last)) bases.add(last);
        bases.addAll(configured); return new ArrayList<>(bases);
    }
    static HttpURLConnection openBook(String address, List<String> mirrors, ConnectionObserver observer) throws IOException {
        URL original = https(address);
        List<String> urls = new ArrayList<>(); urls.add(address);
        if (mirrors.stream().anyMatch(b -> b.startsWith("https://" + original.getHost() + "/"))) {
            for (String base : mirrors) {
                String alternative = new URL(new URL(base), original.getFile()).toString();
                if (!urls.contains(alternative)) urls.add(alternative);
            }
        }
        IOException failure = null;
        for (String url : urls) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            try { return open(url, observer); }
            catch (IOException error) {
                if (error instanceof InterruptedIOException && !(error instanceof SocketTimeoutException)) throw error;
                failure = error;
            }
        }
        throw failure == null ? new IOException("Download unavailable") : failure;
    }
    static String catalog(String value) throws IOException {
        URL url = https(value.trim());
        if (url.getQuery() != null || url.getRef() != null) throw new IOException("Нужен адрес OPDS-каталога без параметров / Enter an OPDS catalog URL");
        String normalized = url.toString().replaceAll("/+$", "");
        if (url.getPath().isEmpty() || url.getPath().equals("/")) normalized += "/opds";
        if (!normalized.endsWith("/opds")) throw new IOException("Адрес каталога должен заканчиваться на /opds / Catalog URL must end in /opds");
        return normalized;
    }
    static String searchUrl(String base, String query, boolean authors) throws IOException {
        if (query.trim().isEmpty() || query.length() > 200) throw new IOException("Введите название или автора / Enter a title or author");
        return catalog(base) + "/search?searchType=" + (authors ? "authors" : "books") + "&searchTerm=" + URLEncoder.encode(query.trim(), "UTF-8");
    }
    static Feed load(String url) throws Exception { return parse(get(url, 4_000_000), url); }
    static Feed parse(byte[] data, String base) throws Exception {
        Element root = Books.xml(data).getDocumentElement();
        if (!"feed".equals(root.getLocalName()) || !"http://www.w3.org/2005/Atom".equals(root.getNamespaceURI()))
            throw new IOException("Сервер вернул не OPDS-каталог / Server did not return an OPDS catalog");
        List<Entry> entries = new ArrayList<>(); String next = "";
        for (Element link : children(root, "link")) if (link.getAttribute("rel").equals("next")) next = resolve(base, link.getAttribute("href"));
        for (Element node : children(root, "entry")) {
            String title = text(node, "title"), author = "", summary = text(node, "content");
            if (summary.isEmpty()) summary = text(node, "summary");
            summary = Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY).toString().trim();
            if (summary.length() > 3000) summary = summary.substring(0, 3000) + "…";
            List<String> names = new ArrayList<>(); for (Element person : children(node, "author")) names.add(text(person, "name"));
            author = String.join(", ", names);
            String acquisition = "", navigation = "", format = ""; boolean book = false;
            for (Element link : children(node, "link")) {
                String type = link.getAttribute("type").toLowerCase(Locale.ROOT), rel = link.getAttribute("rel");
                if (rel.startsWith("http://opds-spec.org/acquisition")) {
                    book = true;
                    // Buying/borrowing links may lead to HTML forms, not a book file.
                    if (!(rel.equals("http://opds-spec.org/acquisition") || rel.equals("http://opds-spec.org/acquisition/open-access"))) continue;
                    if (type.equals("application/epub+zip")) { acquisition = resolve(base, link.getAttribute("href")); format = "epub"; }
                    else if (type.startsWith("text/plain") && format.isEmpty()) { acquisition = resolve(base, link.getAttribute("href")); format = "txt"; }
                } else if (type.startsWith("application/atom+xml") && navigation.isEmpty() && !rel.equals("alternate") && !rel.equals("related"))
                    navigation = resolve(base, link.getAttribute("href"));
            }
            if (!acquisition.isEmpty()) entries.add(new Entry(title, author, summary, acquisition, format, true));
            else if (book) entries.add(new Entry(title, author, summary, "", "", true));
            else if (!navigation.isEmpty()) entries.add(new Entry(title, author, summary, navigation, "", false));
        }
        return new Feed(text(root, "title"), entries, next);
    }
    private static List<Element> children(Element element, String local) {
        List<Element> result = new ArrayList<>();
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element child && local.equals(child.getLocalName()) && "http://www.w3.org/2005/Atom".equals(child.getNamespaceURI())) result.add(child);
        return result;
    }
    private static String text(Element element, String name) { var nodes = children(element, name); return nodes.isEmpty() ? "" : nodes.get(0).getTextContent().trim(); }
    static String resolve(String base, String path) throws IOException {
        if (path.trim().isEmpty()) return "";
        URL target = new URL(new URL(base), path);
        if (target.getProtocol().equals("http") && target.getHost().equalsIgnoreCase(new URL(base).getHost()))
            target = new URL("https", target.getHost(), target.getFile());
        return https(target.toString()).toString();
    }
    static URL https(String value) throws IOException {
        URL url = new URL(value);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || url.getHost().isEmpty()) throw new IOException("Нужна HTTPS-ссылка / HTTPS required");
        return url;
    }
    interface ConnectionObserver { void opened(HttpURLConnection connection) throws IOException; }
    static HttpURLConnection open(String address) throws IOException { return open(address, null); }
    static HttpURLConnection open(String address, ConnectionObserver observer) throws IOException {
        for (int redirects = 0; redirects < 6; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) https(address).openConnection();
            connection.setConnectTimeout(6000); connection.setReadTimeout(observer == null ? 12000 : 35000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "MJReader/0.8 (Android; OPDS)");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");
            try {
                if (observer != null) observer.opened(connection);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without location");
                    address = new URL(connection.getURL(), location).toString(); connection.disconnect(); continue;
                }
                if (code != 200) throw new HttpError(code, connection.getURL().getHost());
                return connection;
            } catch (IOException error) { connection.disconnect(); throw error; }
        }
        throw new IOException("Too many redirects");
    }
    static byte[] get(String url, int limit) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream()) {
            if (connection.getContentLengthLong() > limit) throw new IOException("Ответ слишком большой / Response too large");
            return read(input, limit);
        } finally { connection.disconnect(); }
    }
    static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (count > limit - output.size()) throw new IOException("Файл больше 32 МБ / File exceeds size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
