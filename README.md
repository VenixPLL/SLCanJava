# SLCanJava

Java library for interfacing with SLCAN-compatible CAN/CAN-FD adapters over a serial port.

This project provides a lightweight API to:
- discover serial ports,
- open and initialize an SLCAN interface,
- send SLCAN commands asynchronously with ACK/NACK callbacks,
- receive and decode CAN/CAN-FD frames,
- handle hardware disconnect events.

Tested on hardware:
- USB2CANFD V1 (WeAct Studio): https://github.com/WeActStudio/WeActStudio.USB2CANFDV1

## Features

- SLCAN serial interface implementation (`CanInterface`) with internal RX buffering and frame queue processing.
- CAN/CAN-FD frame model + encoder/decoder (`CanFrame`).
- Version frame parser (`CanVersion`).
- Serial port discovery helpers (`SLCanJava`).
- Optional serial port change listener (`SerialPortListener`).

Supported frame prefixes for decode/processing:
- Classic CAN: `t`, `T`, `r`, `R`
- CAN-FD: `d`, `D`, `b`, `B`
- Version responses: `V`, `v`

## Requirements

- Java 25 (project is currently configured with `maven.compiler.source/target = 25`)
- Maven 3.9+
- SLCAN-compatible adapter connected via USB/UART

## Dependencies

- `io.github.java-native:jssc:2.10.2`
- `org.projectlombok:lombok:1.18.44` (provided)

## Installation / Build

```bash
mvn clean package
```

## Quick Start

```java
import org.lightsolutions.slcan.SLCanJava;
import org.lightsolutions.slcan.canbus.CanFrame;
import org.lightsolutions.slcan.serial.CanInterface;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class Example {
    public static void main(String[] args) throws Exception {
        System.out.println("Available ports: " + Arrays.toString(SLCanJava.getAvailableSerialPorts()));

        try (CanInterface can = SLCanJava.openInterface(
                "COM7",
                CanInterface.NominalSpeed.S8_1M,
                CanInterface.DataSpeed.Y5_5M,
                CanInterface.OperationMode.SILENT
        )) {
            can.setOnDisconnectedHandler(reason ->
                    System.err.println("Interface disconnected: " + reason));

            can.setFrameHandler((iface, frame) ->
                    System.out.println("RX id=0x" + Integer.toHexString(frame.id()) +
                            " dlc=" + frame.dlc() +
                            " fd=" + frame.isFd()));

            // Send raw SLCAN command
            can.writeString("t1234AABBCCDD\\r", status ->
                    System.out.println("Write status: " + status));

            // Or build a frame and encode it
            CanFrame tx = new CanFrame(
                    0x123,
                    8,
                    new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD,
                            0x11, 0x22, 0x33, 0x44},
                    false, false, false, false
            );
            
            can.writeFrame(tx, status -> System.out.println("Frame send status: " + status));

            // Switch mode at runtime and confirm ACK/NACK from adapter
            can.setSilentMode(false, status -> System.out.println("Silent mode change: " + status));
        }
    }
}
```
