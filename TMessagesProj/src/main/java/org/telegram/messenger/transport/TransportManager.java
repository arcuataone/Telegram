package org.telegram.messenger.transport;

public class TransportManager {

    private static volatile TransportManager instance;

    private ProxyTransport activeTransport;

    private TransportManager() {
    }

    public static TransportManager getInstance() {
        if (instance == null) {
            synchronized (TransportManager.class) {
                if (instance == null) {
                    instance = new TransportManager();
                }
            }
        }
        return instance;
    }

    public synchronized void setTransport(ProxyTransport transport) {
        if (activeTransport != null) {
            activeTransport.stop();
        }

        activeTransport = transport;
    }

    public synchronized void start() {
        if (activeTransport != null) {
            activeTransport.start();
        }
    }

    public synchronized void stop() {
        if (activeTransport != null) {
            activeTransport.stop();
            activeTransport = null;
        }
    }

    public ProxyTransport getActiveTransport() {
        return activeTransport;
    }

    public boolean isRunning() {
        return activeTransport != null && activeTransport.isRunning();
    }
}