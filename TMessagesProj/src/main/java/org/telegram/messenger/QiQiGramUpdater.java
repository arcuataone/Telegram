package org.telegram.messenger;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.telegram.messenger.LocaleController.getString;

public final class QiQiGramUpdater {
    private static final String API = "https://api.github.com/repos/arcuataone/Telegram/releases?per_page=100";
    private static final AtomicBoolean checking = new AtomicBoolean(), downloading = new AtomicBoolean();
    private static File pendingInstall;
    private QiQiGramUpdater() {}
    private static SharedPreferences prefs() { return MessagesController.getGlobalMainSettings(); }
    public static boolean autoEnabled() { return prefs().getBoolean("qiqigram_auto_updates", true); }
    public static void toggleAuto() { prefs().edit().putBoolean("qiqigram_auto_updates", !autoEnabled()).apply(); }
    public static void onResume(BaseFragment fragment) {
        if (!alive(fragment)) return;
        if (pendingInstall != null && canInstall(fragment.getParentActivity())) {
            File file = pendingInstall; pendingInstall = null; install(fragment, file); return;
        }
        long elapsed = System.currentTimeMillis() - prefs().getLong("qiqigram_update_checked", 0);
        if (autoEnabled() && (elapsed < 0 || elapsed >= 24L * 60 * 60 * 1000)) check(fragment, false);
    }
    private static boolean alive(BaseFragment f) {
        return f.getParentActivity() != null && !f.getParentActivity().isFinishing();
    }
    private static void message(BaseFragment f, int text) {
        if (alive(f)) f.showDialog(new AlertDialog.Builder(f.getParentActivity()).setTitle(getString(R.string.QiqigramUpdates))
                .setMessage(getString(text)).setPositiveButton(getString(R.string.OK), null).create());
    }
    public static void check(BaseFragment f, boolean manual) {
        if (!alive(f)) return;
        if (!checking.compareAndSet(false, true)) { if (manual) message(f, R.string.QiqigramUpdateChecking); return; }
        prefs().edit().putLong("qiqigram_update_checked", System.currentTimeMillis()).apply();
        AlertDialog progress = manual ? new AlertDialog(f.getParentActivity(), 3) : null;
        AtomicBoolean cancelled = new AtomicBoolean();
        if (progress != null) { progress.setOnCancelListener(d -> cancelled.set(true)); f.showDialog(progress); }
        new Thread(() -> {
            QiQiGramRelease release = null; boolean error = false;
            try {
                release = QiQiGramRelease.latest(new JSONArray(read(API, 2 * 1024 * 1024)));
                if (release == null) throw new IOException("No compatible release");
            } catch (Exception e) { error = true; }
            QiQiGramRelease result = release; boolean failed = error;
            AndroidUtilities.runOnUIThread(() -> {
                checking.set(false);
                if (progress != null) progress.dismiss();
                if (cancelled.get() || !alive(f) || !manual && !autoEnabled()) return;
                if (failed) { if (manual) message(f, R.string.QiqigramUpdateCheckFailed); }
                else if (result.number > BuildConfig.QIQIGRAM_BUILD_NUMBER) offer(f, result);
                else if (manual) message(f, R.string.QiqigramUpdateCurrent);
            });
        }, "QiQiGramUpdateCheck").start();
    }
    private static void offer(BaseFragment f, QiQiGramRelease r) {
        String text = LocaleController.formatString(R.string.QiqigramUpdateAvailable, r.version)
                + (r.notes.isEmpty() ? "" : "\n\n" + r.notes);
        f.showDialog(new AlertDialog.Builder(f.getParentActivity()).setTitle(getString(R.string.QiqigramUpdates)).setMessage(text)
                .setPositiveButton(getString(R.string.QiqigramUpdateInstall), (d, w) -> download(f, r))
                .setNeutralButton(getString(R.string.QiqigramUpdateDownload), (d, w) -> Browser.openUrl(f.getParentActivity(), r.apkUrl, false))
                .setNegativeButton(getString(R.string.QiqigramUpdateLater), null).create());
    }
    private static HttpURLConnection connect(String url) throws IOException {
        for (int redirects = 0; redirects < 6; redirects++) {
            URL u = new URL(url);
            String host = u.getHost();
            if (!"https".equals(u.getProtocol()) || !(host.equals("api.github.com") || host.equals("github.com")
                    || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com"))) throw new IOException("Untrusted update URL");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "QiQiGram-Android");
            c.setRequestProperty("Accept", host.equals("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
            int code = c.getResponseCode();
            if (code == 200) return c;
            String location = c.getHeaderField("Location"); c.disconnect();
            if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && location != null) { url = new URL(u, location).toString(); continue; }
            throw new IOException("Update HTTP " + code);
        }
        throw new IOException("Too many redirects");
    }
    private static String read(String url, int limit) throws IOException {
        HttpURLConnection c = connect(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new IOException("Response too large"); out.write(b, 0, n); }
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(Locale.US, "%02x", b & 255)); return s.toString();
    }
    private static void download(BaseFragment f, QiQiGramRelease r) {
        if (!alive(f)) return;
        if (!downloading.compareAndSet(false, true)) { message(f, R.string.QiqigramUpdateDownloading); return; }
        AlertDialog progress = new AlertDialog(f.getParentActivity(), 1);
        progress.setTitle(getString(R.string.QiqigramUpdateDownloading));
        AtomicBoolean cancel = new AtomicBoolean();
        progress.setOnCancelListener(d -> cancel.set(true));
        progress.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.Cancel), (d, w) -> cancel.set(true));
        f.showDialog(progress);
        new Thread(() -> {
            File part = null, target = null; boolean success = false;
            try {
                String expected = r.digest;
                if (expected.isEmpty()) {
                    String checksum = read(r.checksumUrl, 4096).trim();
                    if (!checksum.matches("(?s)^[0-9a-fA-F]{64}(?:\\s.*)?$")) throw new IOException("Invalid checksum");
                    expected = checksum.substring(0, 64).toLowerCase(Locale.US);
                }
                File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache/updates");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Update directory unavailable");
                // Only one download may run; keep disk usage bounded to one APK.
                File[] old = dir.listFiles(); if (old != null) for (File file : old) if (file.isFile()) file.delete();
                target = new File(dir, "qiqigram-" + r.version + ".apk"); part = new File(dir, target.getName() + ".part");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                HttpURLConnection c = connect(r.apkUrl);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] b = new byte[64 * 1024]; int n, last = -1; long total = 0;
                    while ((n = in.read(b)) != -1) {
                        if (cancel.get()) throw new InterruptedIOException();
                        total += n; if (total > r.size) throw new IOException("APK size mismatch");
                        out.write(b, 0, n); digest.update(b, 0, n);
                        int percent = (int) (total * 100 / r.size);
                        if (percent != last) { last = percent; AndroidUtilities.runOnUIThread(() -> { if (!cancel.get() && alive(f)) progress.setProgress(percent); }); }
                    }
                    if (total != r.size) throw new IOException("Incomplete APK");
                    out.getFD().sync();
                } finally { c.disconnect(); }
                if (!expected.equals(hex(digest.digest()))) throw new IOException("APK checksum mismatch");
                verifyPackage(part);
                if (cancel.get()) throw new InterruptedIOException();
                if (!part.renameTo(target)) throw new IOException("Cannot save APK");
                success = true;
            } catch (Exception e) { /* Never install an incomplete or unverified APK. */ }
            finally { if (part != null) part.delete(); }
            File apk = target; boolean ready = success;
            AndroidUtilities.runOnUIThread(() -> {
                downloading.set(false); progress.dismiss();
                if (cancel.get() || !alive(f)) return;
                if (ready) install(f, apk); else message(f, R.string.QiqigramUpdateDownloadFailed);
            });
        }, "QiQiGramUpdateDownload").start();
    }
    @SuppressWarnings("deprecation")
    private static void verifyPackage(File file) throws IOException {
        PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
        try {
            String name = ApplicationLoader.applicationContext.getPackageName();
            PackageInfo apk = pm.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            PackageInfo current = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES);
            if (apk == null || !name.equals(apk.packageName) || apk.signatures == null || current.signatures == null
                    || apk.signatures.length == 0 || apk.signatures.length != current.signatures.length) throw new IOException("Wrong APK package");
            for (Signature signature : current.signatures) {
                boolean match = false; for (Signature other : apk.signatures) if (Arrays.equals(signature.toByteArray(), other.toByteArray())) match = true;
                if (!match) throw new IOException("Wrong APK signature");
            }
        } catch (PackageManager.NameNotFoundException e) { throw new IOException(e); }
    }
    private static boolean canInstall(Activity a) { return Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls(); }
    private static void install(BaseFragment f, File apk) {
        if (!alive(f)) return;
        Activity a = f.getParentActivity();
        if (!canInstall(a)) {
            pendingInstall = apk;
            f.showDialog(new AlertDialog.Builder(a).setTitle(getString(R.string.QiqigramUpdates)).setMessage(getString(R.string.QiqigramUpdatePermission))
                    .setPositiveButton(getString(R.string.PermissionOpenSettings), (d, w) -> {
                        try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))); }
                        catch (Exception e) { message(f, R.string.QiqigramUpdateInstallFailed); }
                    }).setNegativeButton(getString(R.string.Cancel), (d, w) -> pendingInstall = null).create());
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(FileProvider.getUriForFile(a, a.getPackageName() + ".provider", apk), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(intent);
        } catch (Exception e) { message(f, R.string.QiqigramUpdateInstallFailed); }
    }
}
