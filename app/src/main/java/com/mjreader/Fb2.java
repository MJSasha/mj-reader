package com.mjreader;

import org.w3c.dom.*;
import java.io.IOException;
import java.util.*;

/** FictionBook content enters the same full-text/asset store as EPUB. */
final class Fb2 {
    private final StringBuilder text = new StringBuilder();
    private final List<Books.Chapter> chapters = new ArrayList<>();
    private final List<Books.Illustration> illustrations = new ArrayList<>();
    private final Map<String, byte[]> images = new HashMap<>();

    static Books.Book read(byte[] bytes, String fallback) throws Exception {
        Element root = Books.xml(bytes).getDocumentElement();
        if (!"FictionBook".equals(root.getLocalName()) ||
                !("http://www.gribuser.ru/xml/fictionbook/2.0".equals(root.getNamespaceURI()) || root.getNamespaceURI() == null))
            throw new IOException("Not a FictionBook document");
        Fb2 book = new Fb2();
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element binary) || !"binary".equals(binary.getLocalName())) continue;
            if (!binary.getAttribute("content-type").startsWith("image/")) continue;
            try {
                byte[] image = android.util.Base64.decode(binary.getTextContent(), android.util.Base64.DEFAULT);
                if (EpubCover.isImage(image)) book.images.put(binary.getAttribute("id"), image);
            } catch (IllegalArgumentException ignored) { /* A damaged image must not hide readable text. */ }
        }
        Element info = child(child(root, "description"), "title-info");
        String title = value(info, "book-title");
        if (title.isEmpty()) title = fallback;
        List<String> authors = new ArrayList<>();
        if (info != null) for (Node node = info.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element person) || !"author".equals(person.getLocalName())) continue;
            String author = clean(value(person, "first-name") + " " + value(person, "middle-name") + " " + value(person, "last-name"));
            if (author.isEmpty()) author = value(person, "nickname");
            if (!author.isEmpty()) authors.add(author);
        }
        byte[] cover = book.images.get(reference(child(child(info, "coverpage"), "image")));
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element body && "body".equals(body.getLocalName())) book.append(body);
        String content = book.text.toString().trim();
        if (content.isEmpty()) throw new IOException("Empty FictionBook body");
        book.chapters.removeIf(chapter -> chapter.offset() >= content.length());
        return new Books.Book("", title, String.join(", ", authors), content, book.chapters, cover, book.illustrations);
    }
    private void append(Node node) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String part = node.getNodeValue().replace('\u00A0', ' ').replace("\uFEFF", "").replaceAll("\\s+", " ");
            if ((text.length() == 0 || Character.isWhitespace(text.charAt(text.length() - 1))) && part.startsWith(" ")) part = part.substring(1);
            text.append(part);
            return;
        }
        if (!(node instanceof Element element)) return;
        String name = element.getLocalName();
        boolean block = Set.of("body", "section", "title", "subtitle", "p", "v", "stanza", "poem", "epigraph", "cite", "text-author", "empty-line", "tr").contains(name);
        if (block) paragraph();
        int start = text.length();
        if ("image".equals(name)) {
            String source = reference(element);
            byte[] bytes = images.get(source);
            text.append('\uFFFC');
            if (bytes != null) illustrations.add(new Books.Illustration(start, source, clean(element.getAttribute("title")), bytes));
        } else {
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) append(child);
        }
        if ("title".equals(name)) {
            String heading = clean(text.substring(start).replace("\uFFFC", ""));
            if (heading.isEmpty()) heading = clean(Optional.ofNullable(child(element, "image")).map(image -> image.getAttribute("title")).orElse(""));
            if (!heading.isEmpty()) chapters.add(new Books.Chapter(heading, start));
        }
        if (block) paragraph();
        else if ("td".equals(name) || "th".equals(name)) text.append(' ');
    }
    private void paragraph() {
        while (text.length() > 0 && Character.isWhitespace(text.charAt(text.length() - 1))) text.setLength(text.length() - 1);
        if (text.length() > 0) text.append("\n\n");
    }
    private static Element child(Element parent, String name) {
        if (parent != null) for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && name.equals(element.getLocalName())) return element;
        return null;
    }
    private static String value(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : clean(element.getTextContent());
    }
    private static String clean(String value) { return value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim(); }
    private static String reference(Element image) {
        if (image == null) return "";
        String href = image.getAttributeNS("http://www.w3.org/1999/xlink", "href");
        if (href.isEmpty()) href = image.getAttribute("href");
        return href.startsWith("#") ? href.substring(1) : "";
    }
}
