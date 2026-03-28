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

    public enum OperationMode {
        NORMAL("M0"),
        SILENT("M1");

        public final String cmd;

        OperationMode(final String cmd) {
            this.cmd = cmd;
        }
    }

    private static final Consumer<Status> NO_OP_CALLBACK = _ -> {};
    private static final int BAUD_RATE = 115200;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = 1;
    private static final int PARITY_NONE = 0;
    private static final long COMMAND_ACK_TIMEOUT_MS = 1000;
    private static final byte CARRIAGE_RETURN = '\r';
    private static final byte NACK = 0x07;
    private static final int RX_BUFFER_CAPACITY = 16384;

    @Getter
    private final SerialPort serialPort;

    private final ConcurrentLinkedQueue<Consumer<Status>> pendingCallbacks = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<byte[]> messageQueue = new LinkedBlockingQueue<>(50000);
    private final byte[] rxBuffer = new byte[RX_BUFFER_CAPACITY];
    private final Thread processorThread;
    private final ExecutorService writeExecutor;

    private int rxBufferLength = 0;
    private volatile boolean isRunning = true;
    @Getter
    private volatile OperationMode operationMode;

    @Setter
    private BiConsumer<CanInterface,CanFrame> frameHandler;

    @Setter
    private Consumer<String> onDisconnectedHandler;

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
        this(serialPort, nomSpeed, dataSpeed, OperationMode.NORMAL);
    }

    /**
     * Opens and initializes the SLCAN interface, then starts the processing thread.
     *
     * @param serialPort serial device wrapper
     * @param nomSpeed nominal CAN bitrate command
     * @param dataSpeed CAN FD data bitrate command
     * @param operationMode initial interface mode (normal or silent)
     * @throws SerialPortException when port open/write operations fail
     */
    public CanInterface(
            final SerialPort serialPort,
            final NominalSpeed nomSpeed,
            final DataSpeed dataSpeed,
            final OperationMode operationMode
    ) throws SerialPortException, ExecutionException, InterruptedException {
        this.serialPort = serialPort;
        this.serialPort.openPort();
        this.serialPort.setParams(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY_NONE);

        // WRITE
        this.writeExecutor = Executors.newSingleThreadExecutor(r -> {
            final var t = new Thread(r, "CAN-Writer-Thread-" + this.serialPort.getPortName());
            t.setDaemon(true);
            return t;
        });

        // READ
        this.processorThread = new Thread(this::processQueue, "CAN-Processor-Thread-" + serialPort.getPortName());
        this.processorThread.start();

        this.serialPort.addEventListener(this);
        this.sendCommandAndAwaitAck("C\r", "close channel");
        this.sendCommandAndAwaitAck(nomSpeed.cmd + "\r", "set nominal speed");
        this.sendCommandAndAwaitAck(dataSpeed.cmd + "\r", "set data speed");
        this.sendCommandAndAwaitAck(operationMode.cmd + "\r", "set operation mode");
        this.sendCommandAndAwaitAck("O\r", "open channel");
        this.operationMode = operationMode;

        assert this.serialPort.isOpened();
    }

    /**
     * Switches operation mode between normal and silent.
     *
     * @param silent true for silent mode, false for normal mode
     * @param callback callback invoked when ACK/NACK is received
     */
    public void setSilentMode(final boolean silent, final Consumer<Status> callback) {
        final OperationMode targetMode = silent ? OperationMode.SILENT : OperationMode.NORMAL;
        this.writeString(targetMode.cmd + "\r", status -> {
            if (status == Status.OK) {
                this.operationMode = targetMode;
            }
            if (callback != null) {
                callback.accept(status);
            }
        });
    }

    /**
     * Switches operation mode between normal and silent without a callback.
     *
     * @param silent true for silent mode, false for normal mode
     */
    public void setSilentMode(final boolean silent) {
        this.setSilentMode(silent, NO_OP_CALLBACK);
    }

    /**
     * Callback invoked when the serial port is disconnected.
     * @param reason reason for disconnection
     */
    private void handleHardwareDisconnect(String reason) {
        if (!this.isRunning) return;

        System.err.println("[CAN HARDWARE] Interface disconnected: " + reason);
        this.isRunning = false;
        this.processorThread.interrupt();
        this.writeExecutor.shutdownNow();

        try {
            if (this.serialPort != null) {
                this.serialPort.removeEventListener();
                if (this.serialPort.isOpened()) {
                    this.serialPort.closePort();
                }
            }
        } catch (Exception ignored) {}

        if (this.onDisconnectedHandler != null) {
            this.onDisconnectedHandler.accept(reason);
        }
    }

    /**
     * Sends a command asynchronously with a safety timeout.
     * Prevents the application from freezing if JSSC gets permanently blocked
     * by the OS during a surprise USB removal.
     *
     * @param str command to send (typically terminated with {@code \r})
     * @param callback callback invoked on command status, may be null
     */
    public void writeString(final String str, final Consumer<Status> callback) {
        final Consumer<Status> actualCallback = callback != null ? callback : NO_OP_CALLBACK;
        if (!this.isRunning) {
            throw new IllegalStateException("Interface disconnected!");
        }

        this.pendingCallbacks.offer(actualCallback);

        CompletableFuture.supplyAsync(() -> {
                    try {
                        return this.serialPort.writeBytes(str.getBytes(StandardCharsets.US_ASCII));
                    } catch (SerialPortException e) {
                        throw new CompletionException(e);
                    }
                }, this.writeExecutor)
                .orTimeout(500, TimeUnit.MILLISECONDS)
                .whenComplete((result, ex) -> {
                    if (ex != null || (result != null && !result)) {

                        this.pendingCallbacks.remove(actualCallback);
                        actualCallback.accept(Status.NOK);

                        final String reason = ex != null ? ex.getMessage() : "Native write returned false";
                        this.handleHardwareDisconnect("Writing fault (Surprise Removal) - " + reason);
                    }
                });
    }

    /**
     * Sends a command without a caller-provided callback.
     *
     * @param str command to send (typically terminated with {@code \r})
     */
    public void writeString(final String str) {
        this.writeString(str, NO_OP_CALLBACK);
    }

    /**
     * Sends a parsed CAN frame with zero-allocation overhead.
     *
     * @param frame CanFrame to send
     * @param callback callback invoked on command status may be null
     */
    public void writeFrame(final CanFrame frame, final Consumer<Status> callback) {
        final Consumer<Status> actualCallback = callback != null ? callback : NO_OP_CALLBACK;

        if (!this.isRunning) {
            throw new IllegalStateException("Interface disconnected!");
        }

        this.pendingCallbacks.offer(actualCallback);

        CompletableFuture.supplyAsync(() -> {
                    try {
                        return this.serialPort.writeBytes(frame.encodeAsBytes());
                    } catch (SerialPortException e) {
                        throw new CompletionException(e);
                    }
                }, this.writeExecutor)
                .orTimeout(500, TimeUnit.MILLISECONDS)
                .whenComplete((result, ex) -> {
                    if (ex != null || (result != null && !result)) {
                        this.pendingCallbacks.remove(actualCallback);
                        actualCallback.accept(Status.NOK);
                        final String reason = ex != null ? ex.getMessage() : "Native write returned false";
                        this.handleHardwareDisconnect("Writing fault - " + reason);
                    }
                });
    }

    /**
     * Sends a parsed CAN frame without a caller-provided callback.
     * @param frame CanFrame to send
     */
    public void writeFrame(final CanFrame frame) {
        this.writeFrame(frame, NO_OP_CALLBACK);
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

            if (this.rxBufferLength + newBytes.length > this.rxBuffer.length) {
                System.err.println("[ERROR] Buffer overflow!");
                this.rxBufferLength = 0;
            }

            System.arraycopy(newBytes, 0, this.rxBuffer, this.rxBufferLength, newBytes.length);
            this.rxBufferLength += newBytes.length;

            int scanStart = 0;
            for (int i = 0; i < this.rxBufferLength; i++) {
                if (this.rxBuffer[i] == CARRIAGE_RETURN) {
                    int frameLength = i - scanStart;
                    if (frameLength > 0) {
                        byte[] frame = new byte[frameLength];
                        System.arraycopy(this.rxBuffer, scanStart, frame, 0, frameLength);
                        this.messageQueue.offer(frame);
                    } else {
                        this.messageQueue.offer(new byte[0]);
                    }
                    scanStart = i + 1;
                } else if (this.rxBuffer[i] == NACK) {
                    this.messageQueue.offer(new byte[] { NACK });
                    scanStart = i + 1;
                }
            }

            if (scanStart > 0) {
                this.rxBufferLength -= scanStart;
                if (this.rxBufferLength > 0) {
                    System.arraycopy(this.rxBuffer, scanStart, this.rxBuffer, 0, this.rxBufferLength);
                }
            }
        } catch (final SerialPortException e) {
            this.handleHardwareDisconnect("Reading fault - " + e.getMessage());
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
     * Sends a command and blocks until ACK/NACK is received.
     *
     * @param command command string terminated with CR
     * @param operation operation description used in exceptions
     */
    private void sendCommandAndAwaitAck(final String command, final String operation)
            throws ExecutionException, InterruptedException {
        final var future = new CompletableFuture<Status>();
        this.writeString(command, future::complete);
        try {
            final var result = future.get(COMMAND_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result != Status.OK) {
                throw new RuntimeException("Failed to " + operation + " (NACK)");
            }
        } catch (TimeoutException e) {
            throw new RuntimeException("Timeout waiting for ACK while trying to " + operation, e);
        }
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

        try {
            this.writeString("C\r");
        } catch(Exception ignored) {}

        this.writeExecutor.shutdown();

        if (this.serialPort != null && this.serialPort.isOpened()) {
            this.serialPort.removeEventListener();
            this.serialPort.closePort();
        }
    }
}
