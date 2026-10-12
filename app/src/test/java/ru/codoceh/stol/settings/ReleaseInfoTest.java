package ru.codoceh.stol.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReleaseInfoTest {

    @Test
    public void selectsOnlyTheOfficialArm64Apk() {
        ReleaseInfo release = ReleaseInfo.fromGithub("{\"tag_name\":\"v0.6\",\"assets\":["
                + "{\"browser_download_url\":\"https://example.test/other.apk\"},"
                + "{\"browser_download_url\":\"https://github.com/CodoCeh/kovkod/releases/download/v0.6/KovKod-0.6-arm64.apk\"}]}" );
        assertEquals("0.6", release.version);
        assertEquals("https://github.com/CodoCeh/kovkod/releases/download/v0.6/KovKod-0.6-arm64.apk",
                release.downloadUrl);
    }

    @Test
    public void rejectsMissingOrUntrustedAssets() {
        assertNull(ReleaseInfo.fromGithub("{\"tag_name\":\"v0.6\",\"assets\":[]}"));
        assertNull(ReleaseInfo.fromGithub("{\"tag_name\":\"v0.6\",\"browser_download_url\":\"http://github.com/CodoCeh/kovkod/releases/download/v0.6/x-arm64.apk\"}"));
    }

    @Test
    public void comparesDottedVersionsWithoutLexicographicMistakes() {
        assertTrue(ReleaseInfo.isNewer("0.10", "0.9"));
        assertTrue(ReleaseInfo.isNewer("1.0", "0.99"));
        assertFalse(ReleaseInfo.isNewer("0.5", "0.5"));
        assertFalse(ReleaseInfo.isNewer("0.4", "0.5"));
    }

    @Test
    public void picksTheNewestStableReleaseInsteadOfGithubLegacyLatest() {
        String releases = "[{\"tag_name\":\"v0.2\",\"browser_download_url\":\"https://github.com/CodoCeh/kovkod/releases/download/v0.2/KovKod-0.2-arm64.apk\"},"
                + "{\"tag_name\":\"v0.6\",\"browser_download_url\":\"https://github.com/CodoCeh/kovkod/releases/download/v0.6/KovKod-0.6-arm64.apk\"},"
                + "{\"tag_name\":\"v0.7\",\"prerelease\":true,\"browser_download_url\":\"https://github.com/CodoCeh/kovkod/releases/download/v0.7/KovKod-0.7-arm64.apk\"}]";
        assertEquals("0.6", ReleaseInfo.newestStableFromGithub(releases).version);
    }
}
