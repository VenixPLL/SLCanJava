package org.lightsolutions.slcan.serial.port;

import lombok.RequiredArgsConstructor;
import org.lightsolutions.slcan.SLCanJava;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Listens for changes in available serial ports.
 */
@RequiredArgsConstructor
public class SerialPortListener implements Runnable {

    private static final long POLL_INTERVAL_MS = 100L;

    private final Consumer<String[]> callback;
    private String[] lastData;
    private volatile boolean running = true;

    @Override
    public void run() {
        while (this.running) {
            try {
                this.pollOnce();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.running = false;
            } catch (RuntimeException e) {
                this.running = false;
            }
        }
    }

    /**
     * Polls the serial port list once.
     * @throws InterruptedException If the thread is interrupted
     */
    private void pollOnce() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
        final String[] currentData = SLCanJava.getAvailableSerialPorts();

        if (this.lastData == null) {
            this.lastData = currentData;
            return;
        }

        if (Arrays.equals(currentData, this.lastData)) {
            return;
        }

        this.lastData = currentData;
        this.callback.accept(currentData);
    }

    /**
     * Stops the listener.
     */
    public void stop() {
        this.running = false;
    }
}
