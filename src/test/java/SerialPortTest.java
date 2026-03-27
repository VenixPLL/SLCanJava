import org.lightsolutions.slcan.SLCanJava;
import org.lightsolutions.slcan.canbus.CanFrame;
import org.lightsolutions.slcan.serial.CanInterface;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class SerialPortTest {

    private static long lastFrameTimestampNanos = -1L;

    static void main() throws Exception {
        System.out.println("Serial port list test");
        System.out.println(Arrays.toString(SLCanJava.getAvailableSerialPorts()));

        System.out.println("Serial port listener test");
        SLCanJava.listenForSerialPortChanges(arr -> {
            System.out.println(Arrays.toString(arr));
            throw new RuntimeException(); // Quiet exception here causes the listener to stop.
        });

        System.out.println("Serial port interface test");

        var canInterface = SLCanJava.openInterface(
                "COM7",
                CanInterface.NominalSpeed.S6_500K,
                CanInterface.DataSpeed.Y2_2M
        );
        System.out.println("Trying to send message to " + canInterface.getSerialPort().getPortName());

        canInterface.setOnDisconnectedHandler(System.out::println);

        canInterface.setFrameHandler((ignoredInterface, frame) -> {
            long elapsedNanos = getElapsedNanosSincePreviousFrame(frame);
            String elapsedText = elapsedNanos < 0 ? "first frame" : formatElapsed(elapsedNanos) + " ago";
            System.out.println("RX CAN Frame: " + frame.id() + " " + Arrays.toString(frame.data()) + " " + elapsedText);
        });

        boolean increasing = true;
        int rpmValue = 0;

        while (true) {
            // AUDI A3 8P SIEMENS Instrument Cluster WakeUP and RPM Signal.
            TimeUnit.MILLISECONDS.sleep(10);

            final var wakeupData = new byte[8];
            Arrays.fill(wakeupData, (byte) 0xFF);

            final var wakeup = new CanFrame(0x575, 8, wakeupData, false, false, false, false,0);
            canInterface.writeFrame(wakeup, _ -> {});

            final var rpmData = getRpmPayload(rpmValue);
            final var rpm = new CanFrame(0x280, 8, rpmData, false, false, false, false,0);
            canInterface.writeFrame(rpm, _ -> {
            });

            if (increasing) {
                rpmValue += 8;
            } else {
                rpmValue -= 8;
            }

            if (rpmValue >= 8000) {
                increasing = false;
            }
            if (rpmValue <= 0) {
                increasing = true;
            }
        }
    }

    public static byte[] getRpmPayload(int rpm) {
        if (rpm < 0) {
            rpm = 0;
        }
        if (rpm > 8000) {
            rpm = 8000;
        }

        final int rawValue = rpm * 4;
        final byte[] payload = new byte[8];
        payload[2] = (byte) (rawValue & 0xFF);
        payload[3] = (byte) ((rawValue >> 8) & 0xFF);

        return payload;
    }

    private static long getElapsedNanosSincePreviousFrame(CanFrame frame) {
        long currentTimestamp = frame.timestamp();
        if (currentTimestamp <= 0) {
            currentTimestamp = System.nanoTime();
        }

        if (lastFrameTimestampNanos < 0) {
            lastFrameTimestampNanos = currentTimestamp;
            return -1L;
        }

        long elapsed = currentTimestamp - lastFrameTimestampNanos;
        lastFrameTimestampNanos = currentTimestamp;
        return Math.max(elapsed, 0L);
    }

    private static String formatElapsed(long nanos) {
        if (nanos < 1_000) {
            return nanos + "ns";
        }
        if (nanos < 1_000_000) {
            return String.format("%.3fus", nanos / 1_000.0);
        }
        if (nanos < 1_000_000_000) {
            return String.format("%.3fms", nanos / 1_000_000.0);
        }
        return String.format("%.3fs", nanos / 1_000_000_000.0);
    }
}
