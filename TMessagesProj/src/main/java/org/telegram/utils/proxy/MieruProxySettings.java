package org.telegram.utils.proxy;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MieruProxySettings {
    public static final int DEFAULT_PORT = 22010;
    public static final String DEFAULT_PORT_RANGE = "22010-22025";
    public static final String DEFAULT_PROTOCOL = "TCP";
    public static final String DEFAULT_MULTIPLEXING = "MULTIPLEXING_LOW";

    private static final Pattern PORT_RANGE = Pattern.compile("^([0-9]{1,5})(?:-([0-9]{1,5}))?$");
    private static final Set<String> PROTOCOLS = new HashSet<>(Arrays.asList("TCP", "UDP"));
    private static final Set<String> MULTIPLEXING_LEVELS = new HashSet<>(Arrays.asList(
            "MULTIPLEXING_OFF", "MULTIPLEXING_LOW", "MULTIPLEXING_MIDDLE", "MULTIPLEXING_HIGH"));

    public final String address;
    public final String username;
    public final String password;
    public final String portRange;
    public final String protocol;
    public final String multiplexing;

    public MieruProxySettings(String address, String username, String password, String portRange, String protocol, String multiplexing) {
        this.address = address == null ? "" : address.trim();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.portRange = portRange == null ? "" : portRange.trim();
        this.protocol = protocol == null ? "" : protocol.trim().toUpperCase();
        this.multiplexing = multiplexing == null ? "" : multiplexing.trim().toUpperCase();
    }

    public static MieruProxySettings defaults() {
        return new MieruProxySettings("", "", "", DEFAULT_PORT_RANGE, DEFAULT_PROTOCOL, DEFAULT_MULTIPLEXING);
    }

    public static MieruProxySettings fromProxySettings(ProxySettings settings) {
        MieruProxySettings extras = fromSecret(settings.getSecret());
        return new MieruProxySettings(settings.getAddress(), settings.getUser(), settings.getPassword(),
                extras.portRange, extras.protocol, extras.multiplexing);
    }

    public static MieruProxySettings fromLegacyConfig(File file) {
        try {
            byte[] bytes;
            try (FileInputStream input = new FileInputStream(file)) {
                bytes = new byte[(int) file.length()];
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) break;
                    offset += count;
                }
            }
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray profiles = root.optJSONArray("profiles");
            JSONObject profile = profiles != null && profiles.length() > 0 ? profiles.getJSONObject(0) : null;
            if (profile == null) {
                return null;
            }
            JSONObject user = profile.optJSONObject("user");
            JSONArray servers = profile.optJSONArray("servers");
            JSONObject server = servers != null && servers.length() > 0 ? servers.getJSONObject(0) : null;
            JSONArray bindings = server != null ? server.optJSONArray("portBindings") : null;
            JSONObject binding = bindings != null && bindings.length() > 0 ? bindings.getJSONObject(0) : null;
            JSONObject multiplexing = profile.optJSONObject("multiplexing");
            if (user == null || server == null || binding == null) {
                return null;
            }
            String ports = binding.optString("portRange", "");
            if (TextUtils.isEmpty(ports) && binding.has("port")) {
                ports = Integer.toString(binding.optInt("port"));
            }
            String address = server.optString("ipAddress", server.optString("domainName", ""));
            return new MieruProxySettings(address, user.optString("name", ""), user.optString("password", ""),
                    ports, binding.optString("protocol", DEFAULT_PROTOCOL),
                    multiplexing != null ? multiplexing.optString("level", DEFAULT_MULTIPLEXING) : DEFAULT_MULTIPLEXING);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static MieruProxySettings fromSecret(String secret) {
        MieruProxySettings defaults = defaults();
        if (TextUtils.isEmpty(secret)) {
            return defaults;
        }
        try {
            JSONObject json = new JSONObject(secret);
            MieruProxySettings result = new MieruProxySettings("", "", "",
                    json.optString("portRange", defaults.portRange),
                    json.optString("protocol", defaults.protocol),
                    json.optString("multiplexing", defaults.multiplexing));
            return result.isValidTransport() ? result : defaults;
        } catch (JSONException e) {
            return defaults;
        }
    }

    public String toSecret() {
        JSONObject json = new JSONObject();
        try {
            json.put("portRange", portRange);
            json.put("protocol", protocol);
            json.put("multiplexing", multiplexing);
        } catch (JSONException ignored) {
        }
        return json.toString();
    }

    public boolean isValid() {
        return !TextUtils.isEmpty(address) && !TextUtils.isEmpty(username) && !TextUtils.isEmpty(password) && isValidTransport();
    }

    public boolean isValidTransport() {
        if (!PROTOCOLS.contains(protocol) || !MULTIPLEXING_LEVELS.contains(multiplexing)) {
            return false;
        }
        Matcher matcher = PORT_RANGE.matcher(portRange);
        if (!matcher.matches()) {
            return false;
        }
        int start = Integer.parseInt(matcher.group(1));
        int end = matcher.group(2) == null ? start : Integer.parseInt(matcher.group(2));
        return start > 0 && end <= 65535 && start <= end;
    }

    public int getStartPort() {
        return getStartPort(portRange);
    }

    public static int getStartPort(String portRange) {
        Matcher matcher = PORT_RANGE.matcher(portRange == null ? "" : portRange.trim());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
