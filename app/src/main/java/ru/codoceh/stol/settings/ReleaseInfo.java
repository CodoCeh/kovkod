package ru.codoceh.stol.settings;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately small view of the public KovKod release record. */
final class ReleaseInfo {

    private static final Pattern TAG = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern DOWNLOAD = Pattern.compile(
            "\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    final String version;
    final String downloadUrl;

    private ReleaseInfo(String version, String downloadUrl) {
        this.version = version;
        this.downloadUrl = downloadUrl;
    }

    static ReleaseInfo fromGithub(String json) {
        Matcher tag = TAG.matcher(json);
        if (!tag.find()) {
            return null;
        }
        String version = tag.group(1).replaceFirst("^[vV]", "");
        for (Matcher asset = DOWNLOAD.matcher(json); asset.find();) {
            String url = asset.group(1).replace("\\/", "/");
            if (url.endsWith("-arm64.apk") && isOfficialDownload(url)) {
                return new ReleaseInfo(version, url);
            }
        }
        return null;
    }

    static ReleaseInfo newestStableFromGithub(String json) {
        ReleaseInfo newest = null;
        for (String release : topLevelObjects(json)) {
            if (release.matches("(?s).*\\\"(?:draft|prerelease)\\\"\\s*:\\s*true.*")) {
                continue;
            }
            ReleaseInfo candidate = fromGithub(release);
            if (candidate != null && (newest == null || isNewer(candidate.version, newest.version))) {
                newest = candidate;
            }
        }
        return newest;
    }

    static boolean isNewer(String candidate, String installed) {
        String[] left = candidate.split("\\.");
        String[] right = installed.split("\\.");
        int size = Math.max(left.length, right.length);
        for (int i = 0; i < size; i++) {
            int a = number(left, i);
            int b = number(right, i);
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static int number(String[] pieces, int index) {
        if (index >= pieces.length || !pieces[index].matches("[0-9]+")) {
            return -1;
        }
        try {
            return Integer.parseInt(pieces[index]);
        } catch (NumberFormatException tooLarge) {
            return -1;
        }
    }

    private static boolean isOfficialDownload(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                    && uri.getPath().startsWith("/CodoCeh/kovkod/releases/download/");
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static java.util.List<String> topLevelObjects(String json) {
        java.util.List<String> result = new java.util.ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;
        for (int i = 0; i < json.length(); i++) {
            char current = json.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
                continue;
            }
            if (current == '"') {
                quoted = true;
            } else if (current == '{') {
                if (depth++ == 0) {
                    start = i;
                }
            } else if (current == '}' && --depth == 0 && start >= 0) {
                result.add(json.substring(start, i + 1));
            }
        }
        return result;
    }
}
