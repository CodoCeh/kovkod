package ru.codoceh.stol.help;

/** Одна карточка помощи: короткий ответ, шаги и, если есть, действие «Попробовать». */
public final class HelpTopic {

    public final String id;
    public final String title;
    public final String summary;
    public final String steps;
    public final String keywords;
    public final HelpPreview preview;
    public final HelpAction action;
    public final String actionLabel;

    public HelpTopic(String id, String title, String summary, String steps, String keywords,
                     HelpPreview preview, HelpAction action, String actionLabel) {
        this.id = id;
        this.title = title;
        this.summary = summary;
        this.steps = steps;
        this.keywords = keywords;
        this.preview = preview;
        this.action = action;
        this.actionLabel = actionLabel;
    }
}
