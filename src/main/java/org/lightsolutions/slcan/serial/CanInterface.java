package org.lightsolutions.slcan.serial;

import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;
import lombok.Getter;
import lombok.Setter;
import org.lightsolutions.slcan.canbus.CanFrame;
import org.lightsolutions.slcan.canbus.CanVersion;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Threaded SLCAN serial interface.
 * <p>
 * Incoming bytes are buffered in the serial listener thread, and complete frames
 * are pushed to an internal queue. A dedicated processor thread decodes those
 * frames and dispatches ACK/NACK statuses to pending command callbacks.
 */
public class CanInterface implements SerialPortEventListener, AutoCloseable {

    public enum Status {
        OK,
        NOK
    }

    public enum NominalSpeed {
        S4_125K("S4"),
        S5_250K("S5"),
        S6_500K("S6"),
        S7_800K("S7"),
        S8_1M("S8");

        public final String cmd;

        NominalSpeed(final String cmd) {
            this.cmd = cmd;
        }
    }

    public enum DataSpeed {
        Y1_1M("Y1"),
        Y2_2M("Y2"),
        Y3_3M("Y3"),
        Y4_4M("Y4"),
        Y5_5M("Y5");

        public final String cmd;

        DataSpeed(final String cmd) {
            this.cmd = cmd;
        }
    }

    private static final Consumer<Status> NO_OP_CALLBACK = _ -> {};
    private static final int BAUD_RATE = 115200;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = 1;
    private static final int PARITY_NONE = 0;
    private static final byte CARRIAGE_RETURN = '\r';
    private static final byte NACK = 0x07;
    private static final int RX_BUFFER_CAPACITY = 16384;

    @Getter
    private final SerialPort serialPort;

    private final ConcurrentLinkedQueue<Consumer<Status>> pendingCallbacks = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<byte[]> messageQueue = new LinkedBlockingQueue<>(50000);
    private final byte[] rxBuffer = new byte[RX_BUFFER_CAPACITY];
    private final Thread processorThread;

    private int rxBufferLength = 0;
    private volatile boolean isRunning = true;

    @Setter
    private BiConsumer<CanInterface,CanFrame> frameHandler;

    /**
     * Opens and initializes the SLCAN interface, then starts the processing thread.
     *
     * @param serialPort serial device wrapper
     * @param nomSpeed nominal CAN bitrate command
     * @param dataSpeed CAN FD data bitrate command
     * @throws SerialPortException when port open/write operations fail
     */
    public CanInterface(
            final SerialPort serialPort,
            final NominalSpeed nomSpeed,
            final DataSpeed dataSpeed
    ) throws SerialPortException, ExecutionException, InterruptedException {
        this.serialPort = serialPort;
        this.serialPort.openPort();
        this.serialPort.setParams(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY_NONE);

        this.processorThread = new Thread(this::processQueue, "CAN-Processor-Thread");
        this.processorThread.start();

        this.serialPort.addEventListener(this);
        final var future = new CompletableFuture<Status>();

        this.writeString("C\r");
        this.writeString(nomSpeed.cmd + "\r");
        this.writeString(dataSpeed.cmd + "\r");
        this.writeString("O\r", future::complete);

        final var result = future.get();
        if(result != Status.OK) throw new RuntimeException("Failed to initialize CAN interface, Interface cannot be put into ready state!");

        assert this.serialPort.isOpened();
    }

    /**
     * Sends a command and enqueues a callback that is resolved on ACK/NACK.
     *
     * @param str command to send (typically terminated with {@code \r})
     * @param callback callback invoked on command status, may be null
     * @throws SerialPortException when write fails
     */
    public void writeString(final String str, final Consumer<Status> callback) throws SerialPortException {
        this.pendingCallbacks.offer(callback != null ? callback : NO_OP_CALLBACK);
        this.serialPort.writeBytes(str.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Sends a command without a caller-provided callback.
     *
     * @param str command to send (typically terminated with {@code \r})
     * @throws SerialPortException when write fails
     */
    public void writeString(final String str) throws SerialPortException {
        this.writeString(str, NO_OP_CALLBACK);
    }

    /**
     * Buffers bytes from the serial event stream and extracts complete frames.
     *
     * @param event serial event from JSSC
     */
    @Override
    public void serialEvent(final SerialPortEvent event) {
        if (!event.isRXCHAR() || event.getEventValue() <= 0) {
            return;
        }

        try {
            final byte[] newBytes = this.serialPort.readBytes();
            if (newBytes == null || newBytes.length == 0) {
                return;
            }

            if (rxBufferLength + newBytes.length > rxBuffer.length) {
                System.err.println("[CAN ERROR] Buffer overflow!");
                rxBufferLength = 0;
            }

            System.arraycopy(newBytes, 0, rxBuffer, rxBufferLength, newBytes.length);
            rxBufferLength += newBytes.length;

            int scanStart = 0;
            for (int i = 0; i < rxBufferLength; i++) {
                if (rxBuffer[i] == CARRIAGE_RETURN) {
                    int frameLength = i - scanStart;
                    if (frameLength > 0) {
                        byte[] frame = new byte[frameLength];
                        System.arraycopy(rxBuffer, scanStart, frame, 0, frameLength);
                        messageQueue.offer(frame);
                    } else {
                        messageQueue.offer(new byte[0]);
                    }
                    scanStart = i + 1;
                } else if (rxBuffer[i] == NACK) {
                    messageQueue.offer(new byte[] { NACK });
                    scanStart = i + 1;
                }
            }

            if (scanStart > 0) {
                rxBufferLength -= scanStart;
                if (rxBufferLength > 0) {
                    System.arraycopy(rxBuffer, scanStart, rxBuffer, 0, rxBufferLength);
                }
            }
        } catch (final SerialPortException e) {
            System.err.println("Serial port decoding failure: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Processes incoming frames and dispatches ACK/NACK statuses to pending callbacks.
     */
    private void processQueue() {
        try {
            while (this.isRunning) {
                final byte[] frameBytes = messageQueue.take();

                if (frameBytes.length == 0) {
                    final Consumer<Status> callback = pendingCallbacks.poll();
                    if (callback != null) callback.accept(Status.OK);
                    continue;
                }

                if (frameBytes.length == 1 && frameBytes[0] == NACK) {
                    final Consumer<Status> callback = pendingCallbacks.poll();
                    if (callback != null) callback.accept(Status.NOK);
                    continue;
                }

                final byte type = frameBytes[0];

                if (this.isCanFrameType(type)) {
                    try {
                        final var frame = CanFrame.decode(frameBytes);
                        if(this.frameHandler != null) this.frameHandler.accept(this, frame);
                    } catch (Exception e) {
                        System.err.println("[CAN ERROR] Failed to parse CAN frame: " + e.getMessage());
                    }
                } else if (type == 'V' || type == 'v') {
                    final var version = CanVersion.decode(frameBytes);
                    if (version != null) {
                        System.out.println("[CAN] Version: " + version.versionString());
                    }
                } else {
                    System.out.println("[CAN UNKNOWN] Unknown frame type: " + (char) type);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Checks whether the frame type prefix matches a known SLCAN CAN frame marker.
     * @param type first frame byte
     * @return true when it is a known CAN frame type
     */
    private boolean isCanFrameType(byte type) {
        return type == 't' || type == 'T' || type == 'r' || type == 'R'
                || type == 'd' || type == 'D' || type == 'b' || type == 'B';
    }

    /**
     * Stops processing, sends CAN close command, and closes the serial port.
     *
     * @throws Exception when close operations fail
     */
    @Override
    public void close() throws Exception {
        this.isRunning = false;
        this.processorThread.interrupt();
        this.writeString("C\r");
        if (this.serialPort != null && this.serialPort.isOpened()) {
            this.serialPort.removeEventListener();
            this.serialPort.closePort();
        }
    }
}
