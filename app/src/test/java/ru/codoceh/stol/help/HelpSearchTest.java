package ru.codoceh.stol.help;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class HelpSearchTest {

    private static HelpTopic topic(String id, String title, String keywords) {
        return new HelpTopic(id, title, "", "", keywords, HelpPreview.START, null, null);
    }

    private final List<HelpTopic> topics = Arrays.asList(
            topic("snap", "Окна и раскладки", "win z половина экрана"),
            topic("sound", "Звук", "динамик громкость"),
            topic("store", "Магазин программ", "установить"));

    @Test
    public void emptyQueryKeepsEverything() {
        assertEquals(3, HelpSearch.filter(topics, "  ").size());
        assertEquals(3, HelpSearch.filter(topics, null).size());
    }

    @Test
    public void matchesKeywordIgnoringCase() {
        List<HelpTopic> found = HelpSearch.filter(topics, "ГРОМКОСТЬ");
        assertEquals(1, found.size());
        assertEquals("sound", found.get(0).id);
    }

    @Test
    public void everyWordMustMatch() {
        assertEquals(1, HelpSearch.filter(topics, "win половина").size());
        assertEquals(0, HelpSearch.filter(topics, "win громкость").size());
    }

    @Test
    public void nothingFound() {
        assertEquals(0, HelpSearch.filter(topics, "самолёт").size());
    }
}
