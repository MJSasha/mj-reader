package com.mjreader;

import android.text.Spanned;
import android.text.style.URLSpan;
import org.w3c.dom.*;
import java.net.URI;
import java.util.*;

/** Resolve only local EPUB anchors. Never follows a book link onto the network. */
final class BookNotes {
    private final Map<String, byte[]> entries;
    private final Map<String, Map<String, String>> targets = new LinkedHashMap<>();
    private int noteCharacters;
    BookNotes(Map<String, byte[]> entries) { this.entries = entries; }
    List<Books.Note> read(Spanned parsed, String path, int base) {
        List<Books.Note> notes = new ArrayList<>();
        String original = parsed.toString(), raw = plain(original);
        int cursor = 0, normalized = 0;
        int leading = raw.length() - raw.replaceFirst("^\\s+", "").length();
        URLSpan[] links = parsed.getSpans(0, parsed.length(), URLSpan.class);
        Arrays.sort(links, Comparator.comparingInt(parsed::getSpanStart));
        for (URLSpan link : links) try {
            int rawStart = parsed.getSpanStart(link), rawEnd = parsed.getSpanEnd(link);
            if (rawStart < cursor) continue;
            normalized += plain(original.substring(cursor, rawStart)).length();
            int start = normalized - leading;
            normalized += plain(original.substring(rawStart, rawEnd)).length();
            int end = normalized - leading; cursor = rawEnd;
            URI target = new URI(path).resolve(link.getURL());
            if (target.isAbsolute() || target.getRawAuthority() != null || target.getFragment() == null || target.getQuery() != null) continue;
            String file = target.getPath();
            if (!targets.containsKey(file)) {
                targets.put(file, readTargets(entries.get(file)));
                if (targets.size() > 4) targets.remove(targets.keySet().iterator().next());
            }
            String note = targets.get(file).get(target.getFragment());
            if (note != null && !note.isEmpty() && start >= 0 && end > start && end <= raw.trim().length() && noteCharacters + note.length() <= 1_000_000) {
                notes.add(new Books.Note(base + start, base + end, note)); noteCharacters += note.length();
            }
        } catch (Exception ignored) { /* A malformed link does not prevent reading the chapter. */ }
        return notes;
    }
    private static String plain(String value) { return value.replace("\uFEFF", "").replace('\u00A0', ' ').replace("\r\n", "\n"); }
    private static Map<String, String> readTargets(byte[] bytes) {
        Map<String, String> found = new HashMap<>();
        try {
            NodeList elements = Books.xml(bytes).getElementsByTagName("*");
            int characters = 0;
            for (int i = 0; i < elements.getLength() && characters < 1_000_000; i++) {
                Element element = (Element) elements.item(i);
                String id = anchor(element);
                if (id.isEmpty()) continue;
                String value = targetText(element); characters += value.length(); found.put(id, value);
            }
        } catch (Exception ignored) { }
        return found;
    }
    private static String anchor(Element element) {
        String id = element.getAttribute("id");
        if (id.isEmpty()) id = element.getAttribute("name");
        if (id.isEmpty()) id = element.getAttributeNS("http://www.w3.org/XML/1998/namespace", "id");
        return id;
    }
    static String targetText(Element target) {
        if (!Set.of("a", "span", "sup", "em", "strong", "i", "b").contains(target.getLocalName())) return text(target);
        Node scope = target.getParentNode();
        while (scope instanceof Element element && !Set.of("p", "li", "div", "section", "body", "aside", "td", "title").contains(element.getLocalName()))
            scope = scope.getParentNode();
        String value = range(target, scope);
        // Some exporters put only the anchor/number in a paragraph, then the note in sibling paragraphs.
        if (value.codePoints().noneMatch(Character::isLetter) && scope instanceof Element element
                && Set.of("p", "title").contains(element.getLocalName())) value = range(target, scope.getParentNode());
        return value;
    }
    private static String range(Element target, Node scope) {
        StringBuilder output = new StringBuilder();
        append(target, output, false);
        Node node = target;
        while (node != scope && output.length() < 16000) {
            while (node != scope && node.getNextSibling() == null) {
                separator(node, output);
                node = node.getParentNode();
            }
            if (node == scope || node == null) break;
            node = node.getNextSibling();
            if (!append(node, output, true)) break;
        }
        return clean(output);
    }
    static String text(Node node) {
        StringBuilder output = new StringBuilder(); append(node, output, false);
        return clean(output);
    }
    private static String clean(StringBuilder output) {
        return output.toString().replace('\u00A0', ' ').replaceAll("[ \\t\\r]+", " ")
                .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }
    private static void separator(Node node, StringBuilder output) {
        if (output.length() < 16000 && node instanceof Element element && Set.of("p", "div", "section", "title", "br", "li", "v").contains(element.getLocalName()))
            output.append('\n');
    }
    private static boolean append(Node node, StringBuilder output, boolean stopAtAnchor) {
        if (output.length() >= 16000) return false;
        if (stopAtAnchor && node instanceof Element element && !anchor(element).isEmpty()) return false;
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String value = node.getNodeValue().replaceAll("\\s+", " ");
            output.append(value, 0, Math.min(value.length(), 16000 - output.length()));
        } else {
            separator(node, output);
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
                if (!append(child, output, stopAtAnchor)) return false;
            separator(node, output);
        }
        return true;
    }
}
