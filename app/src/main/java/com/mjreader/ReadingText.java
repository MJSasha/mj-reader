package com.mjreader;

import java.util.*;
import java.util.regex.*;

public final class ReadingText {
    public final String text;
    public final List<String> words = new ArrayList<>();
    public final List<Integer> offsets = new ArrayList<>();
    public ReadingText(String text) {
        this.text = text;
        Matcher matcher = Pattern.compile("[^\\s\\p{Z}]+").matcher(text);
        while (matcher.find()) {
            words.add(matcher.group());
            offsets.add(matcher.start());
        }
    }
    public int wordAt(int offset) {
        int index = Collections.binarySearch(offsets, offset);
        return Math.max(0, index >= 0 ? index : -index - 2);
    }
    /** Bound sentence work to the paragraph around a reference, never the entire book. */
    public static int[] sentence(String text, int start, int end) {
        int low = Math.max(0, start - 2000), high = Math.min(text.length(), end + 2000);
        for (int i = start - 1; i >= low; i--) if (text.charAt(i) == '\n') { low = i + 1; break; }
        for (int i = end; i < high; i++) if (text.charAt(i) == '\n') { high = i; break; }
        if (low > 0 && Character.isLowSurrogate(text.charAt(low))) low++;
        if (high < text.length() && Character.isLowSurrogate(text.charAt(high))) high--;
        int probe = start;
        while (probe > low && Character.isWhitespace(text.charAt(probe - 1))) probe--;
        if (probe > low) probe--; // A reference after punctuation belongs to the preceding sentence.
        java.text.BreakIterator sentences = java.text.BreakIterator.getSentenceInstance(Locale.ROOT);
        StringBuilder fragment = new StringBuilder(text.substring(low, high));
        for (int i = start; i < end; i++) fragment.setCharAt(i - low, ' ');
        sentences.setText(fragment.toString());
        int from = sentences.preceding(probe - low + 1), to = sentences.following(probe - low);
        from = from < 0 ? low : low + from;
        to = to < 0 ? high : low + to;
        from = Math.min(from, start); to = Math.max(to, end);
        while (from < start && Character.isWhitespace(text.charAt(from))) from++;
        while (to > end && Character.isWhitespace(text.charAt(to - 1))) to--;
        return new int[]{from, to};
    }
    public static int pivot(String word) {
        int[] points = word.codePoints().toArray();
        List<Integer> letters = new ArrayList<>();
        for (int i = 0; i < points.length; i++) {
            if (Character.isLetterOrDigit(points[i])) letters.add(i);
        }
        if (letters.isEmpty()) return 0;
        return word.offsetByCodePoints(0, letters.get(Math.min(letters.size() - 1, letters.size() / 3)));
    }
    public static long delay(String word, int wpm) {
        double factor = word.matches(".*[.!?…][”’\"»)]*$") ? 2.2 :
                word.matches(".*[,;:—][”’\"»)]*$") ? 1.5 : 1;
        return (long) (60000.0 / Math.max(100, wpm) * factor);
    }
}
