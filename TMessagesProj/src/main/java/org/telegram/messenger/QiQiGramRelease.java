package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitHub release metadata. Kept independent of Android for validation tests. */
public final class QiQiGramRelease {
    public final int number;
    public final String version, notes, apkUrl, checksumUrl, digest;
    public final long size;
    private QiQiGramRelease(int n, String v, String text, String apk, String checksum, String hash, long bytes) {
        number = n; version = v; notes = text; apkUrl = apk; checksumUrl = checksum; digest = hash; size = bytes;
    }
    public static int versionNumber(String tag) {
        Matcher m = Pattern.compile("v?(0|[1-9][0-9]*)\\.([0-9]{2})").matcher(tag);
        if (!m.matches()) return -1;
        try {
            long major = Long.parseLong(m.group(1));
            if (major > Integer.MAX_VALUE / 100) return -1;
            long number = major * 100 + Integer.parseInt(m.group(2));
            return number <= Integer.MAX_VALUE ? (int) number : -1;
        } catch (NumberFormatException e) { return -1; }
    }
    public static boolean assetUrl(String url, String tag) {
        try {
            URI u = new URI(url);
            return "https".equals(u.getScheme()) && "github.com".equals(u.getHost()) && u.getUserInfo() == null
                    && u.getPort() == -1 && u.getPath().startsWith("/arcuataone/Telegram/releases/download/" + tag + "/");
        } catch (Exception e) { return false; }
    }
    public static QiQiGramRelease latest(JSONArray releases) {
        QiQiGramRelease latest = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.optJSONObject(i);
            if (r == null || r.optBoolean("draft", false)) continue;
            String tag = r.optString("tag_name", "");
            int number = versionNumber(tag);
            if (number < 0 || latest != null && number <= latest.number) continue;
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            String expected = "qiqigram-" + version + "-beta-arm64-v8a.apk";
            JSONArray assets = r.optJSONArray("assets");
            if (assets == null) continue;
            JSONObject apk = null; String checksum = "";
            for (int j = 0; j < assets.length(); j++) {
                JSONObject a = assets.optJSONObject(j);
                if (a == null || !"uploaded".equals(a.optString("state")) || !assetUrl(a.optString("browser_download_url"), tag)) continue;
                if (expected.equals(a.optString("name"))) apk = a;
                if ((expected + ".sha256").equals(a.optString("name"))) checksum = a.optString("browser_download_url");
            }
            if (apk == null) continue;
            long size = apk.optLong("size", 0);
            if (size <= 0 || size > 300L * 1024 * 1024) continue;
            String hash = apk.optString("digest", "");
            hash = hash.matches("sha256:[0-9a-fA-F]{64}") ? hash.substring(7).toLowerCase(java.util.Locale.US) : "";
            if (hash.isEmpty() && checksum.isEmpty()) continue;
            String notes = r.optString("body", "");
            if (notes.length() > 5000) notes = notes.substring(0, 5000) + "…";
            latest = new QiQiGramRelease(number, version, notes, apk.optString("browser_download_url"), checksum, hash, size);
        }
        return latest;
    }
}
