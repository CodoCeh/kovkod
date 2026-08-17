package ru.codoceh.stol.about;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

import ru.codoceh.stol.R;

/**
 * Экран «О программе» (R10i): перечень чужих частей внутри сборки и полные тексты их
 * условий.
 *
 * <p>Всё, что видно на экране про сами части, приходит из реестра
 * {@code assets/about/attributions.json}; тексты условий лежат рядом в приложении, и сети
 * для их чтения не нужно — иначе обязательство закрывалось бы чужим сайтом, который
 * когда-нибудь переедет.
 *
 * <p>Экран разложен кодом, а не разметкой: он состоит из одного повторяющегося блока на
 * запись реестра, и отдельный файл разметки здесь был бы копией цикла.
 */
public class AboutActivity extends Activity {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        page.setPadding(pad, pad, pad, pad);
        page.setBackgroundColor(getColor(R.color.forge_bg));

        page.addView(text(R.string.about_title, 22, R.color.forge_text, true, 0));
        page.addView(text(R.string.about_intro, 15, R.color.forge_muted, false, dp(12)));

        List<Attribution> parts;
        try {
            parts = readRegistry();
        } catch (IOException | RuntimeException broken) {
            page.addView(text(R.string.about_error_broken_cause, 15, R.color.forge_text,
                    false, dp(28)));
            page.addView(text(R.string.about_error_install_action, 15, R.color.forge_muted,
                    false, dp(8)));
            page.addView(text(R.string.forged_mark, 13, R.color.forge_muted, false, dp(32)));
            setContentView(scrolled(page));
            return;
        }

        for (Attribution part : parts) {
            addPart(page, part);
        }
        addOffer(page);
        page.addView(text(R.string.forged_mark, 13, R.color.forge_muted, false, dp(32)));
        setContentView(scrolled(page));
    }

    /**
     * Письменное предложение выдать исходный код (R16.5). Показано целиком на самом
     * экране, а не спрятано за кнопкой: обещание, которого человек не видел, обещанием не
     * работает. За кнопкой — только перечень программ, он на сотни строк.
     */
    private void addOffer(LinearLayout page) {
        page.addView(text(R.string.about_offer_title, 17, R.color.forge_text, true, dp(32)));

        String offer;
        try {
            offer = asset(SourceOffer.OFFER_ASSET);
        } catch (IOException | RuntimeException unreadable) {
            page.addView(text(R.string.about_error_missing_cause, 14, R.color.forge_text,
                    false, dp(8)));
            page.addView(text(R.string.about_error_install_action, 14, R.color.forge_muted,
                    false, dp(4)));
            return;
        }
        page.addView(line(offer, 14, R.color.forge_text, false, dp(8)));

        Button list = new Button(this);
        list.setText(R.string.about_offer_action);
        list.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                showPackages();
            }
        });
        page.addView(list);
    }

    /** Перечень программ системы стола: по имени и версии человек находит исходный код. */
    private void showPackages() {
        String title = getString(R.string.about_offer_packages_title);
        StringBuilder all = new StringBuilder();
        try {
            for (String entry : SourceOffer.packages(asset(SourceOffer.PACKAGES_ASSET))) {
                if (all.length() > 0) {
                    all.append("\n");
                }
                all.append(entry);
            }
        } catch (IOException | RuntimeException unreadable) {
            say(title, getString(R.string.about_error_missing_cause) + "\n\n"
                    + getString(R.string.about_error_install_action));
            return;
        }
        say(title, all.toString());
    }

    private String asset(String path) throws IOException {
        InputStream stream = getAssets().open(path);
        try {
            return SourceOffer.read(stream);
        } finally {
            stream.close();
        }
    }

    private void addPart(LinearLayout page, final Attribution part) {
        page.addView(line(part.name(), 17, R.color.forge_text, true, dp(28)));
        page.addView(line(part.note(), 14, R.color.forge_muted, false, dp(4)));

        String facts = getString(R.string.about_field_author) + ": " + part.author()
                + "\n" + getString(R.string.about_field_license) + ": " + part.license()
                + "\n" + getString(R.string.about_field_changed) + ": "
                + getString(part.isModified() ? R.string.about_answer_yes
                        : R.string.about_answer_no);
        page.addView(line(facts, 14, R.color.forge_text, false, dp(8)));

        Button license = new Button(this);
        license.setText(R.string.about_action_license);
        license.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                showLicense(part);
            }
        });
        page.addView(license);

        Button source = new Button(this);
        source.setText(R.string.about_action_source);
        source.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                openSource(part);
            }
        });
        page.addView(source);
    }

    /** Полный текст условий — из приложения, а не из сети. */
    private void showLicense(Attribution part) {
        String text;
        try {
            text = licenseText(part);
        } catch (IOException unreadable) {
            say(part.name(), getString(R.string.about_error_missing_cause) + "\n\n"
                    + getString(R.string.about_error_install_action));
            return;
        }
        say(part.name(), text);
    }

    private void openSource(Attribution part) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(part.source())));
        } catch (ActivityNotFoundException nowhere) {
            say(part.name(), getString(R.string.about_error_browser_cause) + "\n\n"
                    + getString(R.string.about_error_browser_action));
        }
    }

    private String licenseText(Attribution part) throws IOException {
        AssetManager assets = getAssets();
        StringBuilder all = new StringBuilder();
        for (String path : part.licenseTexts()) {
            String[] inside = assets.list(path);
            if (inside != null && inside.length > 0) {
                for (String name : inside) {
                    append(all, assets, path + "/" + name);
                }
            } else {
                append(all, assets, path);
            }
        }
        if (all.length() == 0) {
            throw new IOException("нет текста условий для " + part.id());
        }
        return all.toString();
    }

    private void append(StringBuilder all, AssetManager assets, String path) throws IOException {
        if (all.length() > 0) {
            all.append("\n\n");
        }
        InputStream stream = assets.open(path);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            all.append(new String(buffer.toByteArray(), UTF_8));
        } finally {
            stream.close();
        }
    }

    private List<Attribution> readRegistry() throws IOException {
        InputStream stream = getAssets().open(Attributions.ASSET);
        try {
            return Attributions.read(stream);
        } finally {
            stream.close();
        }
    }

    private void say(String title, String body) {
        TextView view = line(body, 13, R.color.forge_text, false, 0);
        view.setTypeface(Typeface.MONOSPACE);
        int pad = dp(16);
        view.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scrolled(view))
                .setPositiveButton(R.string.about_action_close, null)
                .show();
    }

    private ScrollView scrolled(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private TextView text(int resource, int size, int color, boolean bold, int marginTop) {
        return line(getString(resource), size, color, bold, marginTop);
    }

    private TextView line(String value, int size, int color, boolean bold, int marginTop) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(getColor(color));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = marginTop;
        view.setLayoutParams(params);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
