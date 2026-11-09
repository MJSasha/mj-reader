package com.mjreader;

import android.content.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.text.Html;
import android.util.AtomicFile;
import org.json.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class Books {
    private static final Object IMPORT_LOCK = new Object();
    record Chapter(String title, int offset) {}
    record Book(String id, String title, String author, String text, List<Chapter> chapters, byte[] cover) {
        Book(String id, String title, String author, String text) {
            this(id, title, author, text, Collections.singletonList(new Chapter(title, 0)), null);
        }
    }
    static final class DuplicateBook extends IOException {
        DuplicateBook() { super("Already in library"); }
    }
    private final Context context;
    private final File directory;
    Books(Context context) {
        this.context = context;
        directory = new File(context.getFilesDir(), "books");
        directory.mkdirs();
    }
    List<Book> list() throws Exception {
        List<Book> books = new ArrayList<>();
        File[] found = directory.listFiles((dir, name) -> name.endsWith(".json") || name.endsWith(".json.bak"));
        File[] files = found == null ? new File[0] : Arrays.stream(found)
                .map(file -> file.getName().endsWith(".bak") ? new File(directory, file.getName().substring(0, file.getName().length() - 4)) : file)
                .distinct().toArray(File[]::new);
        if (files.length > 0) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (File file : files) {
                try {
                    JSONObject json = new JSONObject(readBookFile(file));
                    books.add(new Book(file.getName().replace(".json", ""), json.getString("title"),
                            json.getString("author"), json.getString("text"), chapters(json),
                            json.has("cover") ? android.util.Base64.decode(json.getString("cover"), 0) : null));
                } catch (Exception ignored) { /* A damaged book must not hide the rest of the library. */ }
            }
        }
        for (String language : new String[]{"ru", "en"}) {
            try (InputStream input = context.getAssets().open("orbit-" + language + ".txt")) {
                String text = new String(read(input, 100_000), StandardCharsets.UTF_8);
                books.add(new Book("demo-" + language, text.substring(0, text.indexOf('\n')),
                        context.getString(R.string.demo_author), text));
            }
        }
        return books;
    }
    Book importBook(Uri uri) throws Exception {
        String name = "Book";
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && cursor.getString(column) != null) name = cursor.getString(column);
            }
        }
        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("No stream");
            bytes = read(input, 32_000_000);
        }
        return importBook(name, bytes);
    }
    Book importBook(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            return importBook(file.getName(), read(input, 32_000_000));
        }
    }
    private Book importBook(String name, byte[] bytes) throws Exception {
        synchronized (IMPORT_LOCK) {
        String title = name.replaceFirst("(?i)\\.(epub|txt)$", "");
        String author = "";
        String text;
        List<Chapter> chapters = new ArrayList<>();
        byte[] cover = null;
        String fingerprint = fingerprint(bytes);
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
            Map<String, byte[]> entries = new HashMap<>();
            int total = 0;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    byte[] content = read(zip, 32_000_000 - total);
                    total += content.length;
                    if (entries.size() >= 10000) throw new IOException("Too many entries");
                    entries.put(entry.getName(), content);
                }
            }
            Document container = xml(entries.get("META-INF/container.xml"));
            String packagePath = ((Element) container.getElementsByTagNameNS("*", "rootfile").item(0))
                    .getAttribute("full-path");
            Document pack = xml(entries.get(packagePath));
            title = metadata(pack, "title", title);
            author = metadata(pack, "creator", "");
            Map<String, String> manifest = new HashMap<>();
            NodeList items = pack.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                manifest.put(item.getAttribute("id"), item.getAttribute("href"));
            }
            cover = EpubCover.find(entries, pack, packagePath);
            StringBuilder result = new StringBuilder();
            Map<String, Integer> chapterOffsets = new HashMap<>();
            Map<String, String> chapterHtml = new HashMap<>();
            NodeList spine = pack.getElementsByTagNameNS("*", "itemref");
            for (int i = 0; i < spine.getLength(); i++) {
                Element item = (Element) spine.item(i);
                if ("no".equals(item.getAttribute("linear"))) continue;
                String href = manifest.get(item.getAttribute("idref"));
                if (href == null) throw new IOException("Missing chapter");
                String path = new java.net.URI(packagePath).resolve(href).getPath();
                byte[] chapter = entries.get(path);
                if (chapter == null) throw new IOException("Missing chapter");
                String html = decode(chapter).replaceAll("(?is)<(script|style|head)\\b[^>]*>.*?</\\1>", "");
                String chapterText = normalize(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString());
                if (!chapterText.isEmpty()) {
                    String heading = chapterText.split("\n", 2)[0];
                    java.util.regex.Matcher headingMatch = java.util.regex.Pattern.compile("(?is)<h[1-6]\\b[^>]*>(.*?)</h[1-6]>").matcher(html);
                    if (headingMatch.find()) heading = Html.fromHtml(headingMatch.group(1), 0).toString().trim();
                    if (heading.length() > 100) heading = heading.substring(0, 100) + "…";
                    chapterOffsets.put(path, result.length());
                    chapterHtml.put(path, html);
                    chapters.add(new Chapter(heading, result.length()));
                    result.append(chapterText).append("\n\n");
                }
            }
            text = result.toString();
            List<Chapter> navigation = navigation(entries, items, packagePath, chapterOffsets, chapterHtml, text);
            if (!navigation.isEmpty()) chapters = navigation;
        } else {
            if (!name.toLowerCase(Locale.ROOT).endsWith(".txt")) throw new IOException("Unsupported format");
            text = decode(bytes);
        }
        text = normalize(text);
        if (text.isEmpty() || text.indexOf('\0') >= 0) throw new IOException("Empty or binary book");
        if (chapters.isEmpty()) chapters.add(new Chapter(title, 0));
        String id = UUID.randomUUID().toString();
        for (Book existing : list()) {
            if (!existing.text().replaceAll("\\s+", " ").trim().equals(text.replaceAll("\\s+", " ").trim())) continue;
            if (existing.id().startsWith("demo-")) throw new DuplicateBook();
            // Parse metadata before deciding this is a duplicate: earlier imports may be incomplete.
            // Preserve the exact stored text so bookmarks and reading positions remain valid.
            if (!existing.text().equals(text)) {
                ReadingText oldText = new ReadingText(existing.text());
                ReadingText newText = new ReadingText(text);
                List<Chapter> mapped = new ArrayList<>();
                for (Chapter chapter : chapters) {
                    int word = Math.min(oldText.words.size() - 1, newText.wordAt(chapter.offset()));
                    mapped.add(new Chapter(chapter.title(), oldText.offsets.get(Math.max(0, word))));
                }
                chapters = mapped;
            }
            if (chapters.size() < existing.chapters().size()) chapters = existing.chapters();
            if (!EpubCover.isImage(cover)) cover = existing.cover();
            if (chapters.equals(existing.chapters()) && Arrays.equals(cover, existing.cover())) throw new DuplicateBook();
            id = existing.id();
            text = existing.text();
            break;
        }
        JSONObject json = new JSONObject().put("title", title).put("author", author).put("text", text).put("fingerprint", fingerprint);
        JSONArray chapterArray = new JSONArray();
        for (Chapter chapter : chapters) chapterArray.put(new JSONObject().put("title", chapter.title()).put("offset", chapter.offset()));
        json.put("chapters", chapterArray);
        if (cover != null) json.put("cover", android.util.Base64.encodeToString(cover, android.util.Base64.NO_WRAP));
        AtomicFile file = new AtomicFile(new File(directory, id + ".json"));
        FileOutputStream output = file.startWrite();
        try {
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception e) {
            file.failWrite(output);
            throw e;
        }
        return new Book(id, title, author, text, chapters, cover);
        }
    }
    private static List<Chapter> navigation(Map<String, byte[]> entries, NodeList items, String packagePath,
                                             Map<String, Integer> offsets, Map<String, String> chapterHtml, String text) {
        List<Chapter> result = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            boolean nav = Arrays.asList(item.getAttribute("properties").split(" ")).contains("nav");
            boolean ncx = item.getAttribute("media-type").equals("application/x-dtbncx+xml");
            if (!nav && !ncx) continue;
            try {
                String path = new java.net.URI(packagePath).resolve(item.getAttribute("href")).getPath();
                Document document = xml(entries.get(path));
                NodeList links = document.getElementsByTagNameNS("*", nav ? "a" : "navPoint");
                for (int n = 0; n < links.getLength(); n++) {
                    Element link = (Element) links.item(n);
                    String href, title;
                    if (nav) {
                        boolean inToc = false;
                        for (Node parent = link.getParentNode(); parent instanceof Element; parent = parent.getParentNode()) {
                            Element ancestor = (Element) parent;
                            if ("nav".equals(ancestor.getLocalName()) &&
                                    (ancestor.getAttributeNS("http://www.idpf.org/2007/ops", "type").contains("toc")
                                    || ancestor.getAttribute("role").equals("doc-toc"))) inToc = true;
                        }
                        if (!inToc) continue;
                        href = link.getAttribute("href"); title = link.getTextContent().trim();
                    } else {
                        Element content = (Element) link.getElementsByTagNameNS("*", "content").item(0);
                        if (content == null) continue;
                        href = content.getAttribute("src");
                        Node label = link.getElementsByTagNameNS("*", "text").item(0);
                        title = label == null ? href : label.getTextContent().trim();
                    }
                    java.net.URI target = new java.net.URI(path).resolve(href);
                    Integer base = offsets.get(target.getPath());
                    if (base == null || title.isEmpty()) continue;
                    int offset = base;
                    if (target.getFragment() != null) {
                        String html = chapterHtml.get(target.getPath());
                        java.util.regex.Matcher anchor = java.util.regex.Pattern.compile(
                                "(?is)<[^>]+\\b(?:id|name)\\s*=\\s*([\\x22'])" +
                                java.util.regex.Pattern.quote(target.getFragment()) + "\\1[^>]*>").matcher(html);
                        if (anchor.find()) {
                            String prefix = normalize(Html.fromHtml(html.substring(0, anchor.start()), Html.FROM_HTML_MODE_LEGACY).toString());
                            offset += prefix.length();
                            while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) offset++;
                        }
                    }
                    result.add(new Chapter(title, Math.min(offset, Math.max(0, text.length() - 1))));
                }
                if (!result.isEmpty()) return result;
            } catch (Exception ignored) { /* Fall back to readable spine headings when navigation is malformed. */ }
        }
        return result;
    }
    private static String readBookFile(File file) throws IOException {
        try (InputStream input = new AtomicFile(file).openRead()) {
            return new String(read(input, 96_000_000), StandardCharsets.UTF_8);
        }
    }
    private static String normalize(String text) {
        return text.replace("\uFEFF", "").replace("\u00A0", " ").replace("\r\n", "\n").trim();
    }
    private static List<Chapter> chapters(JSONObject json) {
        List<Chapter> result = new ArrayList<>();
        JSONArray array = json.optJSONArray("chapters");
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) result.add(new Chapter(item.optString("title"), item.optInt("offset")));
        }
        if (result.isEmpty()) result.add(new Chapter(json.optString("title"), 0));
        return result;
    }
    private static String fingerprint(byte[] bytes) throws Exception {
        return android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes), android.util.Base64.NO_WRAP);
    }
    private static String metadata(Document document, String name, String fallback) {
        Node node = document.getElementsByTagNameNS("*", name).item(0);
        return node == null ? fallback : node.getTextContent().trim();
    }
    static Document xml(byte[] bytes) throws Exception {
        if (bytes == null) throw new IOException("Missing EPUB metadata");
        // Validate declarations with Android's pull parser before building the DOM.
        org.xmlpull.v1.XmlPullParser scanner = android.util.Xml.newPullParser();
        scanner.setInput(new ByteArrayInputStream(bytes), null);
        int event;
        while ((event = scanner.nextToken()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.DOCDECL && scanner.getText().contains("["))
                throw new IOException("Internal XML entities are unsupported");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new StringReader("")));
        return builder.parse(new ByteArrayInputStream(bytes));
    }
    private static String decode(byte[] bytes) throws CharacterCodingException {
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE)
                || (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF)))
            return new String(bytes, StandardCharsets.UTF_16);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return Charset.forName("windows-1251").decode(ByteBuffer.wrap(bytes)).toString();
        }
    }
    private static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = input.read(buffer)) != -1) {
            if (output.size() + size > limit) throw new IOException("Book exceeds size limit");
            output.write(buffer, 0, size);
        }
        return output.toByteArray();
    }
}
