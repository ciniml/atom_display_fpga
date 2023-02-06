# ATOM Display Latency Checker Firmware

## Overview

This firmware is to measure internal latency of display.

## Requirements

* `platformio` Shell command
  * https://docs.platformio.org/en/latest/core/installation.html#piocore-install-shell-commands
* RLE compressed FPGA bitstream
  * You can generate it by running `make` within a GOWIN EDA project directory. (`eda/atomdisplay`)
* GOWIN EDA 1.9.8 or above (if you want to re-generate bitstream.)
* Seeed Grove Light Sensor
  * Connect it to the Grove-compatible port of the M5ATOM.

## How to run the latency checker firmware.

1. Connect ATOM Display to the host PC and HDMI display.
2. Check the device name of the ATOM Display. (e.g. `/dev/ttyACM0` )
3. Modify `upload_port` and `monitor_port` linese in the `platformio.ini` to the device name of ATOM Display.
4. run `make` at the `test` directory.

![BUILDING](./figure/atom_display_build.gif)