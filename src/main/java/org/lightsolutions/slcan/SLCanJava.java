package org.lightsolutions.slcan;

import jssc.SerialPort;
import jssc.SerialPortList;
import org.lightsolutions.slcan.serial.CanInterface;
import org.lightsolutions.slcan.serial.port.SerialPortListener;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class SLCanJava {

    public static final Logger LOGGER = Logger.getLogger(SLCanJava.class.getName());

    /**
     * Opens a serial port.
     * @param serialPort The serial port to open
     * @return The serial port object
     * @throws IOException If the serial port is not found
     */
    public static CanInterface openInterface(final String serialPort, CanInterface.NominalSpeed nominalSpeed, CanInterface.DataSpeed dataSpeed) throws Exception {
        return openInterface(serialPort, nominalSpeed, dataSpeed, CanInterface.OperationMode.NORMAL);
    }

    /**
     * Opens a serial port.
     * @param serialPort The serial port to open
     * @param operationMode CAN controller mode (normal/silent)
     * @return The serial port object
     * @throws IOException If the serial port is not found
     */
    public static CanInterface openInterface(
            final String serialPort,
            final CanInterface.NominalSpeed nominalSpeed,
            final CanInterface.DataSpeed dataSpeed,
            final CanInterface.OperationMode operationMode
    ) throws Exception {
        if(SerialPortList.getPortNames(serialPort).length == 0) throw new IOException("Serial port not found");
        final var port = new SerialPort(serialPort);
        return new CanInterface(port, nominalSpeed, dataSpeed, operationMode);
    }

    /**
     * Returns a list of available serial ports.
     * @return A list of available serial ports
     */
    public static String[] getAvailableSerialPorts(){
        return SerialPortList.getPortNames();
    }

    /**
     * Starts a thread that will call the callback every time the serial port list changes.
     *
     * @param callback The callback to call
     */
    public static void listenForSerialPortChanges(final Consumer<String[]> callback){
        final SerialPortListener listener;
        try (var service = Executors.newSingleThreadExecutor()) {
            listener = new SerialPortListener(callback);
            service.execute(listener);
        }
    }

}
