package org.telegram.messenger.transport;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.MieruProxySettings;
import org.telegram.utils.proxy.ProxySettings;

import java.io.File;

public class MieruController {
    private static final String PREF_MIGRATED = "mieru_profile_migrated";

    public static boolean isMieru(ProxySettings settings) {
        return settings != null && settings.getType() == ProxySettings.Type.MIERU;
    }

    public static boolean isEnabled() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        return preferences.getBoolean("proxy_enabled", false)
                && SharedConfig.currentProxy != null
                && isMieru(SharedConfig.currentProxy.settings);
    }

    public static void migrateLegacyProfile(Context context) {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        // loadConfig() does not restore the selected proxy. This method is also
        // called on every app start, so load the list even after legacy
        // migration has completed; otherwise currentProxy remains null and an
        // enabled Mieru profile is never started after a cold launch.
        SharedConfig.loadProxyList();
        if (preferences.getBoolean(PREF_MIGRATED, false)) {
            return;
        }
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (isMieru(info.settings)) {
                preferences.edit().putBoolean(PREF_MIGRATED, true).apply();
                return;
            }
        }

        File legacyConfig = new File(context.getFilesDir(), "transport/mieru.json");
        MieruProxySettings legacy = MieruProxySettings.fromLegacyConfig(legacyConfig);
        if (legacy == null || !legacy.isValid()) {
            preferences.edit().putBoolean(PREF_MIGRATED, true).apply();
            return;
        }

        ProxySettings settings = ProxySettings.builder()
                .setType(ProxySettings.Type.MIERU)
                .setAddress(legacy.address)
                .setPort(legacy.getStartPort())
                .setUser(legacy.username)
                .setPassword(legacy.password)
                .setSecret(legacy.toSecret())
                .build();
        SharedConfig.ProxyInfo info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
        SharedPreferences.Editor editor = preferences.edit().putBoolean(PREF_MIGRATED, true);
        if (preferences.getBoolean("mieru_enabled", true)) {
            SharedConfig.currentProxy = info;
            settings.toSharedPreferences(editor);
            editor.putBoolean("proxy_enabled", true);
        }
        editor.apply();
        SharedConfig.saveProxyList();
    }

    public static void apply(Context context, boolean enabled, ProxySettings settings) {
        if (enabled && isMieru(settings)) {
            start(context, settings);
            return;
        }
        stop();
        boolean useNativeProxy = enabled && settings != null && settings.getType() != ProxySettings.Type.MIERU;
        ConnectionsManager.setProxySettings(useNativeProxy, useNativeProxy ? settings : ProxySettings.EMPTY);
        postProxyChanged();
    }

    public static synchronized void start(Context context) {
        if (SharedConfig.currentProxy != null) {
            start(context, SharedConfig.currentProxy.settings);
        }
    }

    public static synchronized void start(Context context, ProxySettings settings) {
        if (!isMieru(settings) || !settings.isValid()) {
            FileLog.e("Cannot start Mieru: selected profile is invalid");
            return;
        }
        ProxyTransport current = TransportManager.getInstance().getActiveTransport();
        if (current instanceof MieruTransport) {
            MieruTransport active = (MieruTransport) current;
            if (active.getProxySettings().equals(settings) && active.isRunning()) {
                applyMieruProxy(active);
                return;
            }
        }

        MieruTransport transport = new MieruTransport(context.getApplicationContext(), settings);
        TransportManager.getInstance().setTransport(transport);
        TransportManager.getInstance().start();
        new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                if (transport.isRunning()) {
                    applyMieruProxy(transport);
                    return;
                }
                if (transport.getState() == TransportState.ERROR) {
                    FileLog.e("MieruTransport failed: " + transport.getLastError());
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            FileLog.e("Timed out waiting for MieruTransport");
        }, "MieruProxySetup").start();
    }

    private static void applyMieruProxy(MieruTransport transport) {
        ProxySettings localSocks = ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress(transport.getLocalAddress())
                .setPort(transport.getLocalPort())
                .setUser(transport.getSocksUsername())
                .setPassword(transport.getSocksPassword())
                .build();
        ConnectionsManager.setProxySettings(true, localSocks);
        AndroidUtilities.runOnUIThread(() -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    MessagesController.getInstance(account).loadRemoteFilters(true);
                }
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        }, 500);
    }

    public static synchronized void stop() {
        TransportManager.getInstance().stop();
    }

    private static void postProxyChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.proxySettingsChanged));
    }
}
