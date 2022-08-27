# SPDX-License-Identifier: CC0-1.0

.PHONY: all clean synthesis run deploy rle

PROGRAMMER_CLI_DIR = $(dir $(shell which programmer_cli))
PYTHON3 ?= python3
SCRIPT_DIR ?= ../../script

# Set PROGRAMMER_CABLE to "--cable-index 0" to use GWU2X cable
PROGRAMMER_CABLE ?=

all: rle

$(BITSTREAM): $(SRCS)
	gw_sh ./project.tcl $(TARGET_NAME)

%.rle: %.fs
	$(PYTHON3) $(SCRIPT_DIR)/fs2rle.py $< $@

synthesis: $(BITSTREAM)

rle: $(BITSTREAM:.fs=.rle)

run: $(BITSTREAM)

	if lsmod | grep ftdi_sio; then sudo modprobe -r ftdi_sio; fi
	cd $(PROGRAMMER_CLI_DIR); ./programmer_cli $(PROGRAMMER_CABLE) --device $(DEVICE) --run 2 --fsFile $(abspath $(BITSTREAM))

deploy: $(BITSTREAM)
	if lsmod | grep ftdi_sio; then sudo modprobe -r ftdi_sio; fi
	cd $(PROGRAMMER_CLI_DIR); ./programmer_cli $(PROGRAMMER_CABLE) --device $(DEVICE) --run 6 --fsFile $(abspath $(BITSTREAM))

clean:
	-@$(RM) -r impl
	-@$(RM) *.gprj *.user