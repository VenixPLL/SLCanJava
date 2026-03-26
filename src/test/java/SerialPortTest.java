import org.lightsolutions.slcan.SLCanJava;
import org.lightsolutions.slcan.serial.CanInterface;

import java.util.Arrays;
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

        var canInterface = SLCanJava.openInterface("COM7", CanInterface.NominalSpeed.S8_1M, CanInterface.DataSpeed.Y5_5M);
        System.out.println("Trying to send message to " + canInterface.getSerialPort().getPortName());

        canInterface.setOnDisconnectedHandler(System.out::println);

        while(true) {
            TimeUnit.MILLISECONDS.sleep(100);
            canInterface.writeString("t1234AABBCCDD\r", status -> {
                System.out.println("Callback: " + status.name());
            });
        }

    }

}
