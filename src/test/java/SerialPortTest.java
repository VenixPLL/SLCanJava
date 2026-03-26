import org.lightsolutions.slcan.SLCanJava;
import org.lightsolutions.slcan.serial.CanInterface;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class SerialPortTest {

    static void main() throws Exception {
        System.out.println("Serial port list test");
        System.out.println(Arrays.toString(SLCanJava.getAvailableSerialPorts()));
        System.out.println("Serial port listener test");
        SLCanJava.listenForSerialPortChanges(arr -> {
            System.out.println(Arrays.toString(arr));
            throw new RuntimeException(); // Quiet exception in there causes the listener to stop
        });

        System.out.println("Serial port interface test");

        try (var canInterface = SLCanJava.openInterface("COM7", CanInterface.NominalSpeed.S8_1M, CanInterface.DataSpeed.Y5_5M)) {
            System.out.println("Trying to send message to " + canInterface.getSerialPort().getPortName());

            CompletableFuture<CanInterface.Status> future = new CompletableFuture<>();

            canInterface.writeString("t1234AABBCCDD\r", future::complete);

            CanInterface.Status status = future.get(2, TimeUnit.SECONDS);
            System.out.println("Status: " + status);
        }
    }

}
