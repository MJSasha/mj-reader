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
    record Illustration(int offset, String source, String alt, byte[] bytes) {}
    record Book(String id, String title, String author, String text, List<Chapter> chapters, byte[] cover, List<Illustration> illustrations) {
        Book(String id, String title, String author, String text, List<Chapter> chapters, byte[] cover) {
            this(id, title, author, text, chapters, cover, List.of());
        }
        Book(String id, String title, String author, String text) {
            this(id, title, author, text, Collections.singletonList(new Chapter(title, 0)), null);
        }
    }
    static final class DuplicateBook extends IOException {
        DuplicateBook() { super("Already in library"); }
    }
    static final class DifferentBook extends IOException {
        DifferentBook() { super("Choose the original file for this book"); }
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
                            json.has("cover") ? android.util.Base64.decode(json.getString("cover"), 0) : null, illustrations(json)));
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
    Book importBook(Uri uri) throws Exception { return importBook(uri, null); }
    Book importBook(Uri uri, String expectedId) throws Exception {
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
        return importBook(name, bytes, expectedId);
    }
    Book importBook(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            return importBook(file.getName(), read(input, 32_000_000));
        }
    }
    private Book importBook(String name, byte[] bytes) throws Exception { return importBook(name, bytes, null); }
    private Book importBook(String name, byte[] bytes, String expectedId) throws Exception {
        synchronized (IMPORT_LOCK) {
        String title = name.replaceFirst("(?i)\\.(epub|txt|fb2(?:\\.zip)?)$", "");
        String author = "";
        String text;
        List<Chapter> chapters = new ArrayList<>();
        byte[] cover = null;
        List<Illustration> illustrations = new ArrayList<>();
        String fingerprint = fingerprint(bytes);
        Map<String, byte[]> entries = new HashMap<>();
        boolean fb2 = name.toLowerCase(Locale.ROOT).endsWith(".fb2");
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
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
            if (!entries.containsKey("META-INF/container.xml")) {
                List<byte[]> candidates = new ArrayList<>();
                for (var entry : entries.entrySet())
                    if (entry.getKey().toLowerCase(Locale.ROOT).endsWith(".fb2")) candidates.add(entry.getValue());
                if (candidates.size() != 1) throw new IOException("Archive must contain one FB2 book");
                bytes = candidates.get(0); fb2 = true;
            }
        }
        if (fb2) {
            Book parsed = Fb2.read(bytes, title);
            title = parsed.title(); author = parsed.author(); text = parsed.text();
            chapters = parsed.chapters(); cover = parsed.cover(); illustrations = parsed.illustrations();
        } else if (!entries.isEmpty()) {
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
                android.text.Spanned parsed = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
                String chapterText = normalize(parsed.toString());
                // Keep the original replacement character: every stored offset remains stable.
                android.text.style.ImageSpan[] images = parsed.getSpans(0, parsed.length(), android.text.style.ImageSpan.class);
                Arrays.sort(images, Comparator.comparingInt(parsed::getSpanStart));
                int imageOffset = 0;
                for (android.text.style.ImageSpan image : images) {
                    imageOffset = chapterText.indexOf('\uFFFC', imageOffset);
                    if (imageOffset < 0) break;
                    String source = EpubCover.resolve(path, image.getSource());
                    byte[] imageBytes = entries.get(source);
                    if (EpubCover.isImage(imageBytes))
                        illustrations.add(new Illustration(result.length() + imageOffset, source, imageAlt(html, image.getSource()), imageBytes));
                    imageOffset++;
                }
                if (!chapterText.isEmpty()) {
                    String heading = chapterText.split("\n", 2)[0];
                    java.util.regex.Matcher headingMatch = java.util.regex.Pattern.compile("(?is)<h[1-6]\\b[^>]*>(.*?)</h[1-6]>").matcher(html);
                    if (headingMatch.find()) {
                        String markup = headingMatch.group(1);
                        heading = Html.fromHtml(markup, 0).toString().trim();
                        if (cleanTitle(heading).isEmpty()) heading = imageAlt(markup, null);
                    }
                    heading = cleanTitle(heading);
                    if (heading.isEmpty()) heading = title + " · " + (chapters.size() + 1);
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
        boolean matched = false;
        for (Book existing : list()) {
            if (expectedId != null && !expectedId.equals(existing.id())) continue;
            if (!existing.text().replaceAll("\\s+", " ").trim().equals(text.replaceAll("\\s+", " ").trim())) continue;
            if (existing.id().startsWith("demo-")) throw new DuplicateBook();
            matched = true;
            // Parse metadata before deciding this is a duplicate: earlier imports may be incomplete.
            // Preserve the exact stored text so bookmarks and reading positions remain valid.
            if (!existing.text().equals(text)) {
                // Whitespace-only legacy repairs map image markers by order, without rewriting text.
                List<Illustration> mappedImages = new ArrayList<>();
                int oldImage = 0, newImage = 0;
                for (int offset = text.indexOf('\uFFFC'); offset >= 0; offset = text.indexOf('\uFFFC', offset + 1)) {
                    oldImage = existing.text().indexOf('\uFFFC', oldImage);
                    if (oldImage < 0) break;
                    while (newImage < illustrations.size() && illustrations.get(newImage).offset() < offset) newImage++;
                    if (newImage < illustrations.size() && illustrations.get(newImage).offset() == offset) {
                        Illustration image = illustrations.get(newImage);
                        mappedImages.add(new Illustration(oldImage, image.source(), image.alt(), image.bytes()));
                    }
                    oldImage++;
                }
                illustrations = mappedImages;
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
            if (illustrations.isEmpty()) illustrations = existing.illustrations();
            boolean sameImages = illustrations.size() == existing.illustrations().size();
            for (int i = 0; sameImages && i < illustrations.size(); i++) {
                Illustration a = illustrations.get(i), b = existing.illustrations().get(i);
                sameImages = a.offset() == b.offset() && a.alt().equals(b.alt()) && Arrays.equals(a.bytes(), b.bytes());
            }
            if (chapters.equals(existing.chapters()) && Arrays.equals(cover, existing.cover()) && sameImages) throw new DuplicateBook();
            id = existing.id();
            text = existing.text();
            break;
        }
        if (expectedId != null && !matched) throw new DifferentBook();
        JSONObject json = new JSONObject().put("title", title).put("author", author).put("text", text).put("fingerprint", fingerprint);
        JSONArray chapterArray = new JSONArray();
        for (Chapter chapter : chapters) chapterArray.put(new JSONObject().put("title", chapter.title()).put("offset", chapter.offset()));
        json.put("chapters", chapterArray);
        JSONObject images = new JSONObject();
        JSONArray placements = new JSONArray();
        for (Illustration image : illustrations) {
            if (!images.has(image.source())) images.put(image.source(), android.util.Base64.encodeToString(image.bytes(), android.util.Base64.NO_WRAP));
            placements.put(new JSONObject().put("offset", image.offset()).put("source", image.source()).put("alt", image.alt()));
        }
        json.put("images", images).put("illustrations", placements);
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
        return new Book(id, title, author, text, chapters, cover, illustrations);
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
                    title = cleanTitle(title);
                    if (title.isEmpty() && nav) {
                        NodeList images = link.getElementsByTagNameNS("*", "img");
                        if (images.getLength() > 0) title = cleanTitle(((Element) images.item(0)).getAttribute("alt"));
                    }
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
            if (item != null) {
                String title = cleanTitle(item.optString("title"));
                result.add(new Chapter(title.isEmpty() ? json.optString("title") + " · " + (i + 1) : title, item.optInt("offset")));
            }
        }
        if (result.isEmpty()) result.add(new Chapter(json.optString("title"), 0));
        return result;
    }
    private static String cleanTitle(String title) {
        return title.replace("\uFFFC", "").replaceAll("[\\s\\p{Z}]+", " ").trim();
    }
    private static String imageAlt(String html, String source) {
        java.util.regex.Matcher tags = java.util.regex.Pattern.compile("(?is)<img\\b[^>]*>").matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            if (source != null) {
                java.util.regex.Matcher src = java.util.regex.Pattern.compile("(?is)\\bsrc\\s*=\\s*([\\x22'])(.*?)\\1").matcher(tag);
                if (!src.find() || !Html.fromHtml(src.group(2), 0).toString().equals(source)) continue;
            }
            java.util.regex.Matcher alt = java.util.regex.Pattern.compile("(?is)\\balt\\s*=\\s*([\\x22'])(.*?)\\1").matcher(tag);
            if (alt.find()) return cleanTitle(Html.fromHtml(alt.group(2), 0).toString());
        }
        return "";
    }
    private static List<Illustration> illustrations(JSONObject json) {
        List<Illustration> result = new ArrayList<>();
        JSONObject assets = json.optJSONObject("images");
        JSONArray placements = json.optJSONArray("illustrations");
        if (assets == null || placements == null) return result;
        Map<String, byte[]> decoded = new HashMap<>();
        for (int i = 0; i < placements.length(); i++) {
            JSONObject item = placements.optJSONObject(i);
            if (item == null) continue;
            String source = item.optString("source");
            if (!decoded.containsKey(source)) decoded.put(source, android.util.Base64.decode(assets.optString(source), 0));
            result.add(new Illustration(item.optInt("offset"), source, item.optString("alt"), decoded.get(source)));
        }
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
            if (scanner.getDepth() > 128) throw new IOException("XML nesting exceeds limit");
            else if (event == org.xmlpull.v1.XmlPullParser.DOCDECL && scanner.getText().contains("["))
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
