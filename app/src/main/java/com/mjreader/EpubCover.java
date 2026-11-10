package com.mjreader;

import android.graphics.BitmapFactory;
import android.text.Html;
import android.util.Base64;
import org.w3c.dom.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Resolves declared covers and cover-page image wrappers without network access. */
final class EpubCover {
    static boolean isImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        return bounds.outWidth > 0 && bounds.outHeight > 0;
    }

    static byte[] find(Map<String, byte[]> entries, Document pack, String packagePath) {
        Map<String, String> manifest = new HashMap<>();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        NodeList items = pack.getElementsByTagNameNS("*", "item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String path = resolve(packagePath, item.getAttribute("href"));
            manifest.put(item.getAttribute("id"), path);
            if (Arrays.asList(item.getAttribute("properties").split("\\s+")).contains("cover-image"))
                candidates.add(path);
        }
        NodeList metadata = pack.getElementsByTagNameNS("*", "meta");
        for (int i = 0; i < metadata.getLength(); i++) {
            Element meta = (Element) metadata.item(i);
            if ("cover".equalsIgnoreCase(meta.getAttribute("name"))) {
                String ref = meta.getAttribute("content");
                candidates.add(manifest.getOrDefault(ref, resolve(packagePath, ref)));
            }
        }
        NodeList references = pack.getElementsByTagNameNS("*", "reference");
        for (int i = 0; i < references.getLength(); i++) {
            Element ref = (Element) references.item(i);
            if (Arrays.asList(ref.getAttribute("type").split("\\s+")).contains("cover"))
                candidates.add(resolve(packagePath, ref.getAttribute("href")));
        }
        // Some EPUBs declare a cover page in their manifest without OPF cover metadata.
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String hint = (item.getAttribute("id") + " " + item.getAttribute("href")).toLowerCase(Locale.ROOT);
            if (hint.contains("cover") || hint.contains("front")) candidates.add(manifest.get(item.getAttribute("id")));
        }
        for (String path : candidates) {
            byte[] image = imageAt(entries, path, 0);
            if (image != null) return image;
        }
        Node first = pack.getElementsByTagNameNS("*", "itemref").item(0);
        if (first instanceof Element item) {
            String path = manifest.get(item.getAttribute("idref"));
            byte[] bytes = entries.get(path);
            if (bytes != null) {
                String html = new String(bytes, StandardCharsets.UTF_8).replaceAll("(?is)<head\\b[^>]*>.*?</head>", "");
                if (Html.fromHtml(html, 0).toString().trim().length() < 200) {
                    byte[] image = imageAt(entries, path, 0);
                    if (image != null) return image;
                }
            }
        }
        for (String path : new TreeSet<>(entries.keySet())) {
            if (path.matches("(?i).*(?:^|/)(?:cover|front)[^/]*\\.(?:png|jpe?g|webp)$")) {
                byte[] image = imageAt(entries, path, 0);
                if (image != null) return image;
            }
        }
        return null;
    }

    private static byte[] imageAt(Map<String, byte[]> entries, String path, int depth) {
        if (path == null || depth > 3) return null;
        byte[] bytes = entries.get(path);
        if (isImage(bytes)) return bytes;
        if (bytes == null) return null;
        String html = new String(bytes, StandardCharsets.UTF_8);
        Matcher images = Pattern.compile("(?is)<(?:img|image)\\b[^>]*?\\b(?:src|(?:xlink:)?href)\\s*=\\s*([\\x22'])(.*?)\\1").matcher(html);
        while (images.find()) {
            String href = Html.fromHtml(images.group(2), 0).toString();
            if (href.startsWith("data:image/") && href.contains(";base64,")) {
                try {
                    byte[] embedded = Base64.decode(href.substring(href.indexOf(',') + 1), Base64.DEFAULT);
                    if (isImage(embedded)) return embedded;
                } catch (IllegalArgumentException ignored) {}
            } else {
                byte[] image = imageAt(entries, resolve(path, href), depth + 1);
                if (image != null) return image;
            }
        }
        return null;
    }

    static String resolve(String base, String href) {
        try {
            URI target = new URI(base.replace(" ", "%20")).resolve(href.replace(" ", "%20")).normalize();
            return target.isAbsolute() || target.getAuthority() != null ? null : target.getPath();
        } catch (Exception invalidPath) { return null; }
    }
}
