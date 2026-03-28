## SLCAN Protocol Description
> Please upgrade to the latest firmware version to support more baud rates

> Using the virtual serial, the command is as follows:
- `O[CR]` - Opens the CAN channel
- `C[CR]` - Close the CAN channel
- `S0[CR]` - Set the nominal bit rate to 10k
- `S1[CR]` - Set the nominal bit rate to 20k
- `S2[CR]` - Set the nominal bit rate to 50k
- `S3[CR]` - Set the nominal bit rate to 100k
- `S4[CR]` - Set the nominal bit rate to 125k (default)
- `S5[CR]` - Set the nominal bit rate to 250k
- `S6[CR]` - Set the nominal bit rate to 500k
- `S7[CR]` - Set the nominal bit rate to 800k
- `S8[CR]` - Set the nominal bit rate to 1M
- `S9[CR]` - Set the nominal bit rate to 83.3k
- `SA[CR]` - Set the nominal bit rate to 75k
- `SB[CR]` - Set the nominal bit rate to 62.5k
- `SC[CR]` - Set the nominal bit rate to 33.3k
- `SD[CR]` - Set the nominal bit rate to 5k
- `Sxxyy[CR]` - Custom nominal bit rate (60/2=30Mhz CAN clock) [xx=seg1(hex,0x02\~0xff), yy=seg2(hex,0x02\~0x80)]
- `Sddxxyy[CR]` - Custom nominal bit rate ([60/div]Mhz CAN clock) [dd=div(hex,0x01\~0xff), xx=seg1(hex,0x02\~0xff), yy=seg2(hex,0x02\~0x80)]
- `Y1[CR]` - Set the CANFD data segment bit rate to 1M
- `Y2[CR]` - Set CANFD data segment bit rate to 2M (default)
- `Y3[CR]` - Set the CANFD data segment bit rate to 3M
- `Y4[CR]` - Set the CANFD data segment bit rate to 4M
- `Y5[CR]` - Set the CANFD data segment bit rate to 5M
- `Yxxyy[CR]` - Custom CANFD data segment bit rate (60Mhz CAN clock) [xx=seg1(hex,0x01\~0x20), yy=seg2(hex,0x01\~0x10)]
- `Yddxxyy[CR]` - Custom CANFD data segment bit rate ([60/div]Mhz CAN clock) [dd=div(hex,0x01\~0x20), xx=seg1(hex,0x01\~0x20), yy=seg2(hex,0x01\~0x10)]
- `M0[CR]` - Set to normal mode (default)
- `M1[CR]` - Set to silent mode
- `A0[CR]` - Turn off automatic retransmission (default)
- `A1[CR]` - Enable automatic retransmission (not recommended, may crash)
- `tIIILDD...[CR] `- Transfer data frame (standard ID) [ID, length, data]
- `TIIIIIIIILDD...[CR] `- Transfer data frame (extended ID) [ID, length, data]
- `rIIIL[CR]` - Transfer remote frame (standard ID) [ID, length]
- `RIIIIIIIIL[CR]` - Transfer remote frame (extended ID) [ID, length]
- `dIIILDD...[CR] `- Transmit CANFD standard frames (without BRS enabled) [ID, length, data]
- `DIIIIIIIILDD...[CR] `- Transmit CANFD extended frames (without BRS enabled) [ID, length, data]
- `bIIILDD...[CR] `- Transmit CANFD standard frames (BRS enabled) [ID, length, data]
- `BIIIIIIIILDD...[CR] `- Transmit CANFD extended frames (BRS enable) [ID, length, data]
- `V[CR]` - Reads the firmware version
- `E[CR]` - Read the failure state
- `X[CR]` - Enter firmware upgrade mode

`[CR]` : `0x0D` (hex), `\r` (ascii)

**A status statement is returned after the command is sent**
- [CR]: transmission successful
- 0x07: transmission failed

For mode changes (`M0`/`M1`), this ACK/NACK confirms whether the interface accepted the normal/silent mode switch.

**Note**  
The CANFD message length is as follows (in hex):
- `0-8` : Same as standard CAN
- `9` : length = 12
- `A` : length = 16
- `B` : length = 20
- `C` : length = 24
- `D` : length = 32
- `E` : length = 48
- `F` : length = 64

**cangaroo is located in `Tools/cangaroo`**  
**The documentation for calculating custom Bitrate Settings is located in `Doc/CAN Bitrate Calculate_波特率计算.xlsx`**
