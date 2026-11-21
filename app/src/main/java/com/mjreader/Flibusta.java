package com.mjreader;

import android.text.Html;
import org.w3c.dom.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** OPDS 1.x reader. Only explicit acquisition links become downloadable books. */
final class Flibusta {
    static final String DEFAULT = "https://flibusta.site/opds";
    static final int BOOK_LIMIT = 32_000_000;
    static final List<String> MIRRORS = Arrays.asList(DEFAULT, "https://www.flibusta.site/opds", "https://flibusta.is/opds");
    record Details(String language, String publisher, String issued, String identifier, List<String> genres, String size, long downloads) {}
    record Acquisition(String url, String format) {}
    record Entry(String title, String author, String summary, String url, String format, boolean book, Details details,
                 List<Acquisition> alternatives) {
        Entry(String title, String author, String summary, String url, String format, boolean book) {
            this(title, author, summary, url, format, book, List.of());
        }
        Entry(String title, String author, String summary, String url, String format, boolean book, List<Acquisition> alternatives) {
            this(title, author, summary, url, format, book, new Details("", "", "", "", List.of(), "", -1), alternatives);
        }
        Entry(String title, String author, String summary, String url, String format, boolean book, Details details) {
            this(title, author, summary, url, format, book, details, List.of());
        }
        String description() {
            return summary.replaceAll("(?im)^[ \\t]*(?:Формат|Format|Язык|Language|Размер|Size|Скачиваний|Скачано|Downloads|Download count)[ \\t]*:[^\\r\\n]*(?:\\r?\\n|$)", "").trim();
        }
    }
    record Feed(String title, List<Entry> entries, String next) {}
    record Loaded(Feed feed, String url, String base) {}
    static Loaded loadMirrors(String requested, List<String> bases) throws Exception { return loadMirrors(requested, bases, null); }
    private record Attempt(Loaded loaded, String base, Exception error) {}
    private static final Map<String, Cached> cache = new LinkedHashMap<>();
    private record Cached(Feed feed, long expires) {}
    static Loaded loadMirrors(String requested, List<String> bases, java.util.function.BiConsumer<String, Integer> progress) throws Exception {
        URL request = https(requested); int opds = request.getFile().indexOf("/opds");
        if (opds < 0) throw new IOException("Invalid OPDS path");
        String suffix = request.getFile().substring(opds + 5);
        List<String> remaining = new ArrayList<>(new LinkedHashSet<>(bases));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        ConnectionObserver observer = connection -> {
            connections.add(connection);
            if (closed.get()) { connection.disconnect(); throw new InterruptedIOException(); }
        };
        Exception failure = null; int attempts = 0;
        long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
        try {
            for (int round = 0; round < 3 && !remaining.isEmpty(); round++) {
                if (round > 0) Thread.sleep(250L * round);
                CompletionService<Attempt> results = new ExecutorCompletionService<>(pool);
                List<String> retry = new ArrayList<>();
                int next = 0, running = 0;
                long hedge = 0;
                while (next < remaining.size() || running > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now >= deadline) throw new SocketTimeoutException("Catalog time limit");
                    if (next < remaining.size() && running < 2 && (running == 0 || now >= hedge)) {
                        String base = catalog(remaining.get(next++)), url = base + suffix;
                        if (progress != null) progress.accept(https(base).getHost(), ++attempts);
                        results.submit(() -> {
                            try { return new Attempt(new Loaded(loadCached(url, observer), url, base), base, null); }
                            catch (Exception error) { return new Attempt(null, base, error); }
                        });
                        running++; hedge = now + 600;
                    }
                    Future<Attempt> done = results.poll(100, TimeUnit.MILLISECONDS);
                    if (done == null) continue;
                    running--; Attempt result = done.get();
                    if (result.loaded() != null) return result.loaded();
                    failure = result.error(); if (retryable(failure)) retry.add(result.base());
                    hedge = 0; // A definite failure needs no head start for the next mirror.
                }
                remaining = retry;
            }
        } finally {
            closed.set(true); pool.shutdownNow();
            for (HttpURLConnection connection : connections) connection.disconnect();
        }
        throw new IOException("Каталог недоступен / Catalog unavailable: " + (failure == null ? "" : failure.getMessage()), failure);
    }
    private static Feed loadCached(String url, ConnectionObserver observer) throws Exception {
        synchronized (cache) {
            Cached found = cache.get(url);
            if (found != null && found.expires() > android.os.SystemClock.elapsedRealtime()) return found.feed();
        }
        Feed feed = load(url, observer);
        synchronized (cache) {
            cache.put(url, new Cached(feed, android.os.SystemClock.elapsedRealtime() + 20000));
            while (cache.size() > 8) cache.remove(cache.keySet().iterator().next());
        }
        return feed;
    }
    static boolean retryable(Exception error) {
        return error instanceof SocketTimeoutException || error instanceof SocketException || error instanceof UnknownHostException || error instanceof EOFException || error instanceof java.net.ProtocolException
                || error instanceof HttpError http && (http.code == 408 || http.code == 429 || http.code >= 500);
    }
    static final class HttpError extends IOException {
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
    static List<String> bookUrls(String address, List<String> mirrors) throws IOException {
        URL original = https(address);
        List<String> urls = new ArrayList<>(); urls.add(address);
        if (flibustaBook(original)) for (String base : mirrors) {
            if (!MIRRORS.contains(base)) continue;
            String alternative = new URL(https(base), original.getFile()).toString();
            if (!urls.contains(alternative)) urls.add(alternative);
        }
        return urls;
    }
    private static boolean flibustaBook(URL url) {
        return MIRRORS.contains("https://" + url.getAuthority().toLowerCase(Locale.ROOT) + "/opds")
                && url.getPath().matches("/b/[0-9]+/(?:epub|fb2|txt)/opds");
    }
    static boolean preferFb2(Acquisition link) throws IOException {
        return link.format().equals("fb2") && flibustaBook(https(link.url()));
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
    static Feed load(String url) throws Exception { return load(url, null); }
    private static Feed load(String url, ConnectionObserver observer) throws Exception {
        HttpURLConnection connection = open(url, observer, 8000);
        try (InputStream input = connection.getInputStream()) {
            if (connection.getContentLengthLong() > 4_000_000) throw new IOException("Response too large");
            return parse(read(input, 4_000_000), connection.getURL().toString());
        } finally { connection.disconnect(); }
    }
    static Feed parse(byte[] data, String base) throws Exception {
        Element root = Books.xml(data).getDocumentElement();
        if (!"feed".equals(root.getLocalName()) || !"http://www.w3.org/2005/Atom".equals(root.getNamespaceURI()))
            throw new IOException("Сервер вернул не OPDS-каталог / Server did not return an OPDS catalog");
        List<Entry> entries = new ArrayList<>(); String next = "";
        for (Element link : children(root, "link")) if (link.getAttribute("rel").equals("next")) next = feedLink(base, link.getAttribute("href"));
        for (Element node : children(root, "entry")) {
            String title = text(node, "title"), author = "", summary = descriptionText(node, "content");
            if (summary.isEmpty()) summary = descriptionText(node, "summary");
            if (summary.length() > 16000) summary = summary.substring(0, 16000) + "…";
            List<String> names = new ArrayList<>(); for (Element person : children(node, "author")) names.add(text(person, "name"));
            author = String.join(", ", names);
            Map<String, Acquisition> acquisitions = new HashMap<>();
            String navigation = ""; boolean book = false;
            for (Element link : children(node, "link")) {
                String type = link.getAttribute("type").split(";", 2)[0].trim().toLowerCase(Locale.ROOT), rel = link.getAttribute("rel");
                if (rel.startsWith("http://opds-spec.org/acquisition")) {
                    book = true;
                    // Buying/borrowing links may lead to HTML forms, not a book file.
                    if (!(rel.equals("http://opds-spec.org/acquisition") || rel.equals("http://opds-spec.org/acquisition/open-access"))) continue;
                    String format = switch (type) {
                        case "application/epub+zip" -> "epub";
                        case "application/fb2+zip", "application/fb2+xml", "application/x-fictionbook+xml", "application/x-fictionbook" -> "fb2";
                        case "text/plain" -> "txt";
                        default -> "";
                    };
                    if (format.isEmpty()) continue;
                    String url = feedLink(base, link.getAttribute("href"));
                    if (!url.isEmpty()) acquisitions.put(format, new Acquisition(url, format));
                } else if (type.equals("application/atom+xml") && navigation.isEmpty() && !rel.equals("alternate") && !rel.equals("related"))
                    navigation = feedLink(base, link.getAttribute("href"));
            }
            List<String> genres = new ArrayList<>();
            for (Element category : children(node, "category")) {
                String label = category.getAttribute("label").trim();
                if (label.isEmpty()) label = category.getAttribute("term").trim();
                if (!label.isEmpty() && !genres.contains(label)) genres.add(label);
            }
            String language = dcText(node, "language");
            if (language.isEmpty()) language = summaryValue(summary, "Язык|Language");
            String issued = dcText(node, "issued");
            long downloads = -1;
            String count = summaryValue(summary, "Скачиваний|Скачано|Downloads|Download count");
            if (count.matches("[0-9 \\u00a0\\u202f]+")) {
                try { downloads = Long.parseLong(count.replaceAll("[ \\u00a0\\u202f]", "")); }
                catch (NumberFormatException ignored) { }
            }
            Details details = new Details(language, dcText(node, "publisher"), issued, dcText(node, "identifier"),
                    List.copyOf(genres), summaryValue(summary, "Размер|Size"), downloads);
            List<Acquisition> links = new ArrayList<>();
            for (String format : List.of("epub", "fb2", "txt")) if (acquisitions.containsKey(format)) links.add(acquisitions.get(format));
            if (!links.isEmpty()) {
                Acquisition primary = links.remove(0);
                entries.add(new Entry(title, author, summary, primary.url(), primary.format(), true, details, List.copyOf(links)));
            } else if (book) entries.add(new Entry(title, author, summary, "", "", true, details));
            else if (!navigation.isEmpty()) entries.add(new Entry(title, author, summary, navigation, "", false, details));
        }
        return new Feed(text(root, "title"), entries, next);
    }
    private static String dcText(Element node, String local) {
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element child && local.equals(child.getLocalName())
                    && ("http://purl.org/dc/terms/".equals(child.getNamespaceURI()) || "http://purl.org/dc/elements/1.1/".equals(child.getNamespaceURI())))
                return child.getTextContent().trim();
        return "";
    }
    private static String descriptionText(Element node, String name) {
        var values = children(node, name);
        if (values.isEmpty()) return "";
        Element value = values.get(0);
        if (!value.getAttribute("type").equals("xhtml")) return Html.fromHtml(value.getTextContent(), Html.FROM_HTML_MODE_LEGACY).toString().trim();
        StringBuilder text = new StringBuilder(); appendDescription(value, text); return text.toString().trim();
    }
    private static void appendDescription(Node node, StringBuilder text) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) text.append(node.getNodeValue());
        else {
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) appendDescription(child, text);
            if (node instanceof Element element && Set.of("br", "p", "div", "li", "h1", "h2", "h3").contains(element.getLocalName())) text.append('\n');
        }
    }
    private static String summaryValue(String summary, String labels) {
        var match = java.util.regex.Pattern.compile("(?im)^[ \\t]*(?:" + labels + ")[ \\t]*:[ \\t]*([^\\r\\n]+)").matcher(summary);
        return match.find() ? match.group(1).trim() : "";
    }
    static boolean sortByDownloads(List<Entry> entries) {
        if (entries.isEmpty() || !entries.stream().allMatch(Entry::book) || entries.stream().noneMatch(e -> e.details().downloads() >= 0)) return false;
        entries.sort(Comparator.comparingLong((Entry e) -> e.details().downloads()).reversed());
        return true;
    }
    private static List<Element> children(Element element, String local) {
        List<Element> result = new ArrayList<>();
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element child && local.equals(child.getLocalName()) && "http://www.w3.org/2005/Atom".equals(child.getNamespaceURI())) result.add(child);
        return result;
    }
    private static String text(Element element, String name) { var nodes = children(element, name); return nodes.isEmpty() ? "" : nodes.get(0).getTextContent().trim(); }
    private static String feedLink(String base, String href) {
        try { return resolve(base, href); }
        catch (IOException malformedLink) { return ""; }
    }
    static String resolve(String base, String path) throws IOException {
        path = path.trim();
        if (path.isEmpty()) return "";
        URL source = new URL(base);
        URL target = new URL(source, path.startsWith("?") ? source.getPath() + path : path);
        if (target.getProtocol().equals("http") && target.getUserInfo() == null && target.getHost().equalsIgnoreCase(source.getHost()))
            target = new URL("https", source.getHost(), source.getPort(), target.getFile());
        return https(target.toString()).toString();
    }
    static URL https(String value) throws IOException {
        URL url = new URL(value);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || url.getHost().isEmpty()) throw new IOException("Нужна HTTPS-ссылка / HTTPS required");
        return url;
    }
    interface ConnectionObserver { void opened(HttpURLConnection connection) throws IOException; }
    static HttpURLConnection open(String address, ConnectionObserver observer) throws IOException {
        return open(address, observer, 15000);
    }
    private static HttpURLConnection open(String address, ConnectionObserver observer, int readTimeout) throws IOException {
        for (int redirects = 0; redirects < 6; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) https(address).openConnection();
            connection.setConnectTimeout(4000); connection.setReadTimeout(readTimeout); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "MJReader/0.8 (Android; OPDS)");
            connection.setRequestProperty("Accept-Encoding", "identity");
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
