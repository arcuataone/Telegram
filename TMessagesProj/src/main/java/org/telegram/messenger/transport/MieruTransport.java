package org.telegram.messenger.transport;

import android.content.Context;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.telegram.messenger.FileLog;
import org.telegram.utils.proxy.MieruProxySettings;
import org.telegram.utils.proxy.ProxySettings;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;


public class MieruTransport implements ProxyTransport {

    private static final String LOCAL_ADDRESS = "127.0.0.1";
    private volatile int localPort;
    private volatile int rpcPort;
    private static final String SOCKS_USERNAME = "qiqigram";
    private volatile String socksPassword;
    private final Context context;
    private final ProxySettings proxySettings;

    private volatile TransportState state = TransportState.STOPPED;
    private volatile String lastError;

    private Process process;
    private Thread workerThread;
    private void allocatePorts() throws Exception {
        InetAddress localhost = InetAddress.getByName(LOCAL_ADDRESS);

        try (
                ServerSocket socksSocket = new ServerSocket(0, 50, localhost);
                ServerSocket rpcSocket = new ServerSocket(0, 50, localhost)
        ) {
            localPort = socksSocket.getLocalPort();
            rpcPort = rpcSocket.getLocalPort();
        }

        FileLog.d(
                "Mieru allocated ports: SOCKS5="
                        + localPort
                        + ", RPC="
                        + rpcPort
        );
    }
    public MieruTransport(Context context, ProxySettings proxySettings) {
        this.context = context.getApplicationContext();
        this.proxySettings = proxySettings;
    }

    public ProxySettings getProxySettings() {
        return proxySettings;
    }

    @Override
    public String getName() {
        return "Mieru";
    }

    @Override
    public synchronized void start() {
        if (state == TransportState.STARTING ||
                state == TransportState.RUNNING) {
            return;
        }

        state = TransportState.STARTING;
        lastError = null;

        workerThread = new Thread(() -> {
            try {
                File binary = MieruBinary.getExecutable(context);
                allocatePorts();
                socksPassword = UUID.randomUUID().toString().replace("-", "");

                File config = writeConfig();

                FileLog.d("Mieru binary: " + binary.getAbsolutePath());
                FileLog.d("Mieru config: " + config.getAbsolutePath());

                ProcessBuilder pb = new ProcessBuilder(
                        binary.getAbsolutePath(),
                        "run"
                );

                pb.environment().put(
                        "MIERU_CONFIG_JSON_FILE",
                        config.getAbsolutePath()
                );

                pb.redirectErrorStream(true);

                process = pb.start();

                // Читаем stdout/stderr Mieru в отдельном потоке.
                Thread logThread = new Thread(() -> {
                    try {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream())
                        );

                        String line;
                        while ((line = reader.readLine()) != null) {
                            FileLog.d("Mieru: " + line);
                        }
                    } catch (Exception e) {
                        if (state != TransportState.STOPPED) {
                            FileLog.e(e);
                        }
                    }
                }, "MieruLog");

                logThread.start();

                if (!waitForSocks(10000)) {
                    throw new IllegalStateException(
                            "Mieru SOCKS5 listener did not start"
                    );
                }

                state = TransportState.RUNNING;

                FileLog.d(
                        "MieruTransport RUNNING on "
                                + LOCAL_ADDRESS
                                + ":"
                                + localPort
                );

                int exitCode = process.waitFor();

                if (state != TransportState.STOPPED) {
                    lastError = "Mieru exited with code " + exitCode;
                    state = TransportState.ERROR;

                    FileLog.e(lastError);
                }

            } catch (Exception e) {
                lastError = e.toString();
                state = TransportState.ERROR;

                FileLog.e(e);
            }
        }, "MieruTransport");

        workerThread.start();
    }

    private File writeConfig() throws Exception {
        File dir = new File(context.getFilesDir(), "transport");

        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException(
                    "Can't create transport directory"
            );
        }

        File config = new File(dir, "mieru.json");

        MieruProxySettings profileSettings = MieruProxySettings.fromProxySettings(proxySettings);
        JSONObject profile = new JSONObject();
        profile.put("profileName", "telegram");
        profile.put("user", new JSONObject()
                .put("name", proxySettings.getUser())
                .put("password", proxySettings.getPassword()));
        profile.put("servers", new JSONArray().put(new JSONObject()
                .put("ipAddress", proxySettings.getAddress())
                .put("domainName", "")
                .put("portBindings", new JSONArray().put(new JSONObject()
                        .put("portRange", profileSettings.portRange)
                        .put("protocol", profileSettings.protocol)))));
        profile.put("multiplexing", new JSONObject().put("level", profileSettings.multiplexing));

        JSONObject configJson = new JSONObject();
        configJson.put("profiles", new JSONArray().put(profile));
        configJson.put("activeProfile", "telegram");
        configJson.put("rpcPort", rpcPort);
        configJson.put("socks5Port", localPort);
        configJson.put("socks5Authentication", new JSONArray().put(new JSONObject()
                .put("user", SOCKS_USERNAME)
                .put("password", socksPassword)));
        configJson.put("loggingLevel", "INFO");
        configJson.put("socks5ListenLAN", false);
        String json = configJson.toString(2);

        try (FileOutputStream out = new FileOutputStream(config)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }

        return config;
    }

    private boolean waitForSocks(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (process == null || !process.isAlive()) {
                return false;
            }

            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(
                                LOCAL_ADDRESS,
                                localPort
                        ),
                        200
                );

                return true;
            } catch (Exception ignored) {
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    @Override
    public synchronized void stop() {
        state = TransportState.STOPPED;

        if (process != null) {
            process.destroy();

            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            process = null;
        }

        workerThread = null;
        localPort = 0;
        rpcPort = 0;
        socksPassword = null;
        FileLog.d("MieruTransport STOPPED");
    }

    @Override
    public boolean isRunning() {
        return state == TransportState.RUNNING;
    }

    @Override
    public TransportState getState() {
        return state;
    }

    @Override
    public String getLastError() {
        return lastError;
    }

    @Override
    public String getLocalAddress() {
        return LOCAL_ADDRESS;
    }

    @Override
    public int getLocalPort() {
        return localPort;
    }

    public String getSocksUsername() {
        return SOCKS_USERNAME;
    }

    public String getSocksPassword() {
        return socksPassword;
    }
}