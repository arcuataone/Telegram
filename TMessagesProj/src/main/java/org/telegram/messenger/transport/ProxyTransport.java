package org.telegram.messenger.transport;

public interface ProxyTransport {

    String getName();

    void start();

    void stop();

    boolean isRunning();

    TransportState getState();

    String getLastError();

    String getLocalAddress();

    int getLocalPort();
}