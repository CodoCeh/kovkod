package ru.codoceh.stol.help;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Поиск по карточкам помощи: каждое слово запроса должно встретиться в карточке. */
public final class HelpSearch {

    private HelpSearch() {
    }

    public static List<HelpTopic> filter(List<HelpTopic> topics, String query) {
        List<HelpTopic> found = new ArrayList<>();
        String[] words = query == null ? new String[0] : query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        for (HelpTopic topic : topics) {
            String haystack = (topic.title + " " + topic.summary + " " + topic.steps + " " + topic.keywords)
                    .toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String word : words) {
                if (!word.isEmpty() && !haystack.contains(word)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                found.add(topic);
            }
        }
        return found;
    }
}
