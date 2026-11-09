package com.mjreader;

import android.content.Context;
import android.graphics.Typeface;
import android.text.*;
import android.text.style.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/** Presentation spans never modify stored text or its bookmark offsets. */
final class ReaderText {
    static SpannableString style(Context context, Books.Book book, ReaderOptions options, int from, int to) {
        String fragment = book.text().substring(from, to);
        SpannableString text = new SpannableString(fragment);
        float density = context.getResources().getDisplayMetrics().density;
        List<int[]> headings = new ArrayList<>();
        for (Books.Chapter chapter : book.chapters()) {
            int start = chapter.offset() - from;
            if (start < 0 || start >= text.length() || chapter.title().trim().isEmpty()) continue;
            String pattern = Arrays.stream(chapter.title().trim().split("[\\s\\p{Z}]+"))
                    .map(Pattern::quote).collect(Collectors.joining("[\\s\\p{Z}]+"));
            Matcher match = Pattern.compile(pattern).matcher(text);
            match.region(start, Math.min(text.length(), start + 600));
            if (!match.lookingAt()) continue;
            int end = match.end();
            headings.add(new int[]{start, end});
            text.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new RelativeSizeSpan(1.18f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int indent = Math.round(options.number("indent", 20) * density);
        for (int start = 0; start < text.length();) {
            int end = fragment.indexOf('\n', start);
            if (end < 0) end = text.length();
            boolean heading = false;
            for (int[] range : headings) if (start >= range[0] && start < range[1]) { heading = true; break; }
            if (end > start && indent > 0 && !heading)
                text.setSpan(new LeadingMarginSpan.Standard(indent, 0), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = end + 1;
        }
        // EPUB paragraphs commonly contain a blank line. Give it a deliberate gap rather than a full text line.
        int paragraphGap = Math.round(options.number("paragraphGap", 8) * density);
        text.setSpan((LineHeightSpan) (value, start, end, spanstartv, v, fm) -> {
            boolean blank = end > start;
            for (int i = start; i < end; i++) if (!Character.isWhitespace(value.charAt(i))) { blank = false; break; }
            if (blank) { fm.ascent = -paragraphGap; fm.top = -paragraphGap; fm.descent = 0; fm.bottom = 0; }
        }, 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    static int[] window(String text, int anchor) {
        int start = Math.max(0, anchor - 6000);
        int paragraph = text.lastIndexOf('\n', start);
        if (paragraph >= 0 && start - paragraph < 2000) start = paragraph + 1;
        else if (start > 0) {
            int word = text.lastIndexOf(' ', start);
            if (word >= 0 && start - word < 100) start = word + 1;
        }
        int end = Math.min(text.length(), start + 18000);
        if (end < text.length()) {
            int next = text.indexOf('\n', end);
            if (next >= 0 && next - end < 1000) end = next + 1;
            else {
                int word = text.lastIndexOf(' ', end);
                if (word > start && end - word < 100) end = word + 1;
            }
        }
        return new int[]{start, end};
    }

    static Locale language(String text) {
        int cyrillic = 0, latin = 0;
        for (int i = 0; i < Math.min(4096, text.length()); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) cyrillic++;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
        }
        return Locale.forLanguageTag(cyrillic > latin ? "ru" : "en");
    }
}
