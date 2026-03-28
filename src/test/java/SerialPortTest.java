import org.lightsolutions.slcan.SLCanJava;
import org.lightsolutions.slcan.canbus.CanFrame;
import org.lightsolutions.slcan.serial.CanInterface;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class SerialPortTest {

    private static long lastFrameTimestampNanos = -1L;
    private static double distanceAccumulator = 0.0;

    /**
     * Test for AUDI A3 8P Instrument Cluster - USB2CANFD V1 WeAct
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        var canInterface = SLCanJava.openInterface(
                "COM7",
                CanInterface.NominalSpeed.S6_500K,
                CanInterface.DataSpeed.Y2_2M
        );
        System.out.println("Trying to send message to " + canInterface.getSerialPort().getPortName());

        canInterface.setOnDisconnectedHandler(System.out::println);

        double currentSpeedKmh = 0.0;
        int currentGear = 1;
        boolean isAccelerating = true;

        final double[] gearRatios = {0.0, 8.0, 14.0, 20.0, 28.0, 36.0, 43.0};

        int loopCounter = 0;

        while (true) {
            TimeUnit.MILLISECONDS.sleep(10);

            if (isAccelerating) {
                currentSpeedKmh += 0.08;
                if (currentSpeedKmh >= 240.0) {
                    currentSpeedKmh = 240.0;
                    isAccelerating = false;
                    System.out.println("--- V-MAX 240 km/h Starting to brake ---");
                }
            } else {
                currentSpeedKmh -= 0.15;
                if (currentSpeedKmh <= 0.0) {
                    currentSpeedKmh = 0.0;
                    currentGear = 1;
                    isAccelerating = true;
                    System.out.println("--- Stopped! Starting again ---");
                    TimeUnit.SECONDS.sleep(2);
                }
            }

            double currentRatio = gearRatios[currentGear];
            int currentRpm = (int) ((currentSpeedKmh / currentRatio) * 1000.0);

            if (isAccelerating && currentRpm > 6200 && currentGear < 6) {
                currentGear++;
                currentRpm = (int) ((currentSpeedKmh / gearRatios[currentGear]) * 1000.0);
            }

            if (!isAccelerating && currentRpm < 2500 && currentGear > 1) {
                currentGear--;
                currentRpm = (int) ((currentSpeedKmh / gearRatios[currentGear]) * 1000.0);
            }
            if (currentRpm < 850) {
                currentRpm = 850;
            }

            loopCounter++;
            if (loopCounter % 20 == 0) { // Co 200ms
                System.out.printf("Mode: %-10s | Gear: %d | Speed: %5.1f km/h | RPM: %4d RPM\n",
                        isAccelerating ? "ACCELERATING" : "BRAKING", currentGear, currentSpeedKmh, currentRpm);
            }

            final var wakeupData = new byte[8];
            Arrays.fill(wakeupData, (byte) 0xFF);
            canInterface.writeFrame(new CanFrame(0x575, 8, wakeupData, false, false, false, false, 0), _ -> {});

            final var rpmData = getRpmPayload(currentRpm);
            canInterface.writeFrame(new CanFrame(0x280, 8, rpmData, false, false, false, false, 0), _ -> {});

            final var speedometerData = getSpeedometerPayload(currentSpeedKmh);
            canInterface.writeFrame(new CanFrame(0x5A0, 8, speedometerData, false, false, false, false, 0), _ -> {});

            final var absData = getAbsPayload(currentSpeedKmh);
            canInterface.writeFrame(new CanFrame(0x1A0, 8, absData, false, false, false, false, 0), _ -> {});

            final var speedData = getSpeedPayload(currentSpeedKmh);
            canInterface.writeFrame(new CanFrame(0x1A0, 8, speedData, false, false, false, false, 0), _ -> {});

            final var ignitionData = new byte[]{(byte)0x11, 0, 0, 0, 0, 0, 0, 0};
            canInterface.writeFrame(new CanFrame(0x271, 8, ignitionData, false, false, false, false, 0), _ -> {});

            canInterface.writeFrame(getIlluminationFrame(), _ -> {});
        }
    }

    public static CanFrame getIlluminationFrame() {
        byte[] data = new byte[8];

        data[0] = (byte) 0x01;
        data[1] = (byte) 0xFF;
        data[2] = (byte) 0x00;
        data[3] = (byte) 0x00;

        return new CanFrame(0x3C0, 8, data, false, false, false, false, 0);
    }

    public static byte[] getSpeedometerPayload(double speedKmh) {
        if (speedKmh < 0) speedKmh = 0;
        if (speedKmh > 300) speedKmh = 300;

        int speedVal = (int) (speedKmh * 148.0);

        distanceAccumulator += (speedKmh / 3.6) * 0.01 * 50.0;

        if (distanceAccumulator > 30000) {
            distanceAccumulator -= 30000;
        }
        int distVal = (int) distanceAccumulator;

        final byte[] payload = new byte[8];
        payload[0] = (byte) 0xFF;

        payload[1] = (byte) (speedVal & 0xFF);
        payload[2] = (byte) ((speedVal >> 8) & 0xFF);

        payload[3] = (byte) 0x00;
        payload[4] = (byte) 0x00;

        payload[5] = (byte) (distVal & 0xFF);
        payload[6] = (byte) ((distVal >> 8) & 0xFF);

        payload[7] = (byte) 0xAD; // CRC

        return payload;
    }

    public static byte[] getAbsPayload(double speedKmh) {
        if (speedKmh < 0) speedKmh = 0;
        if (speedKmh > 300) speedKmh = 300;

        int speedVal = (int) (speedKmh * 100.0);
        final byte[] payload = new byte[8];

        payload[0] = (byte) 0x00; // Status ABS (0x00 = OK)
        payload[1] = (byte) 0x00;

        payload[2] = (byte) (speedVal & 0xFF);
        payload[3] = (byte) ((speedVal >> 8) & 0xFF);

        return payload;
    }

    public static byte[] getRpmPayload(int rpm) {
        if (rpm < 0) rpm = 0;
        if (rpm > 8000) rpm = 8000;

        final int rawValue = rpm * 4;
        final byte[] payload = new byte[8];

        payload[0] = (byte) 0x49;
        payload[1] = (byte) 0x00;

        // Obroty
        payload[2] = (byte) (rawValue & 0xFF);
        payload[3] = (byte) ((rawValue >> 8) & 0xFF);

        return payload;
    }

    public static byte[] getSpeedPayload(double speedKmh) {
        if (speedKmh < 0) speedKmh = 0;
        if (speedKmh > 300) speedKmh = 300;

        int rawValue = (int) (speedKmh * 100.0);

        final byte[] payload = new byte[8];

        payload[0] = (byte) 0x00;

        payload[1] = (byte) (rawValue & 0xFF);
        payload[2] = (byte) ((rawValue >> 8) & 0xFF);

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
        if (nanos < 1_000) { return nanos + "ns"; }
        if (nanos < 1_000_000) { return String.format("%.3fus", nanos / 1_000.0); }
        if (nanos < 1_000_000_000) { return String.format("%.3fms", nanos / 1_000_000.0); }
        return String.format("%.3fs", nanos / 1_000_000_000.0);
    }
}