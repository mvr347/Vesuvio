package net.lovelace.vesuvio;

import net.lovelace.vesuvio.i18n.Russian;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RussianFallbackTest {

    @Test
    void translatedKeyIsTranslated() {
        assertEquals("высота шага", Russian.label("stepHeight"));
    }

    @Test
    void missingCamelCaseKeyBecomesReadableWords() {
        assertEquals("some new detail", Russian.label("someNewDetail"));
        assertEquals("some new detail", Russian.label("SOME_NEW_DETAIL"));
    }

    @Test
    void singleWordsAndSentencesAreLeftAlone() {
        assertEquals("Untranslated", Russian.label("Untranslated"));
        assertEquals("Не ключ, а фраза", Russian.label("Не ключ, а фраза"));
        assertEquals("3.14", Russian.label("3.14"));
    }
}
