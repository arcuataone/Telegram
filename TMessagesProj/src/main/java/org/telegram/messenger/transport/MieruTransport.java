package org.telegram.messenger.transport;

import android.content.Context;
import android.os.SystemClock;

import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestTimeDelegate;
import org.telegram.utils.proxy.MieruProxySettings;
import org.telegram.utils.proxy.ProxySettings;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


public class MieruTransport implements ProxyTransport {

    private static final String LOCAL_ADDRESS = "127.0.0.1";
    private static final long START_TIMEOUT_MS = 10_000;
    private static final long PING_TIMEOUT_MS = 20_000;
    private static final ExecutorService proxyChecks = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "MieruProxyCheck"));
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
                File dir = new File(context.getFilesDir(), "transport");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IllegalStateException("Can't create transport directory");
                }

                Process client = startClient(new File(dir, "mieru.json"), true);
                process = client;

                if (!waitForSocks(client)) {
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

                int exitCode = client.waitFor();

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

    private Process startClient(File config, boolean logOutput) throws Exception {
        allocatePorts();
        socksPassword = UUID.randomUUID().toString().replace("-", "");
        writeConfig(config);

        File binary = MieruBinary.getExecutable(context);
        ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "run");
        builder.environment().put("MIERU_CONFIG_JSON_FILE", config.getAbsolutePath());
        builder.redirectErrorStream(true);
        Process client = builder.start();

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (logOutput) {
                        FileLog.d("Mieru: " + line);
                    }
                }
            } catch (Exception e) {
                if (logOutput && state != TransportState.STOPPED) {
                    FileLog.e(e);
                }
            }
        }, "MieruLog").start();
        return client;
    }

    private void writeConfig(File config) throws Exception {
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

    }

    private boolean waitForSocks(Process client) {
        long deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS;

        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                client.exitValue();
                return false;
            } catch (IllegalThreadStateException ignored) {
                // The client is still running.
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

    public static void checkProxy(Context context, ProxySettings settings, ConnectionsManager connections,
                                  RequestTimeDelegate delegate) {
        proxyChecks.execute(() -> {
            long ping = -1;
            try {
                ProxyTransport active = TransportManager.getInstance().getActiveTransport();
                if (active instanceof MieruTransport && active.isRunning()
                        && settings.equals(((MieruTransport) active).getProxySettings())) {
                    ping = ((MieruTransport) active).measurePing(connections);
                } else {
                    ping = new MieruTransport(context, settings).measurePingWithTemporaryClient(connections);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // A failed probe should not change the active proxy.
            }
            delegate.run(ping);
        });
    }

    private long measurePingWithTemporaryClient(ConnectionsManager connections) throws Exception {
        File config = File.createTempFile("mieru-probe-", ".json", context.getCacheDir());
        Process client = null;
        try {
            client = startClient(config, false);
            return waitForSocks(client) ? measurePing(connections) : -1;
        } finally {
            if (client != null) {
                client.destroy();
            }
            config.delete();
        }
    }

    private long measurePing(ConnectionsManager connections) throws InterruptedException {
        ProxySettings socks = getLocalProxySettings();
        if (!socks.isValid()) {
            return -1;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicLong ping = new AtomicLong(-1);
        connections.checkProxy(socks, time -> {
            ping.set(time < 0 ? -1 : Math.max(1, time));
            done.countDown();
        });
        return done.await(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS) ? ping.get() : -1;
    }

    ProxySettings getLocalProxySettings() {
        return ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress(getLocalAddress())
                .setPort(getLocalPort())
                .setUser(getSocksUsername())
                .setPassword(getSocksPassword())
                .build();
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