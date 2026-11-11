import com.mjreader.ReadingText;
public class ReadingTextCheck {
    public static void main(String[] args) {
        ReadingText ru = new ReadingText("Привет,\nмир!  ёж");
        if (ru.words.size() != 3 || ru.wordAt(8) != 1) throw new AssertionError("Russian positions");
        if (new ReadingText("один\u00a0two\u2003три").words.size() != 3) throw new AssertionError("Unicode spaces");
        ReadingText en = new ReadingText("Don't stop. Read-on!");
        if (en.words.size() != 3) throw new AssertionError("English tokenization");
        if (ReadingText.delay("конец.", 300) <= ReadingText.delay("слово", 300))
            throw new AssertionError("Sentence pause");
        String word = "«чтение»";
        if (!Character.isLetter(word.charAt(ReadingText.pivot(word)))) throw new AssertionError("Anchor");
        for (String text : new String[]{"Before. A star[1] guides Mira. After.", "Before. A star guides Mira.[1] After.",
                "Раньше. Звезда[1] указывает путь. Потом.", "Heading\nA star[1] guides Mira.\nNext"}) {
            int at = text.indexOf("[1]"); int[] sentence = ReadingText.sentence(text, at, at + 3);
            String excerpt = text.substring(sentence[0], sentence[1]);
            if (!excerpt.contains("[1]") || excerpt.contains("Before") || excerpt.contains("After") || excerpt.contains("Раньше")
                    || excerpt.contains("Потом") || excerpt.contains("Heading") || excerpt.contains("Next"))
                throw new AssertionError("Sentence boundaries: " + excerpt);
        }
        System.out.println("Russian, English, positions, punctuation and anchor checks passed");
    }
}
