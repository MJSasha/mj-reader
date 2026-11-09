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
