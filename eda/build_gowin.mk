.PHONY: all clean synthesis run deploy rle

PROGRAMMER_CLI_DIR = $(dir $(shell which programmer_cli))
PYTHON3 ?= python3
SCRIPT_DIR ?= ../../script

all: rle

$(BITSTREAM): $(SRCS)
	gw_sh ./project.tcl $(TARGET_NAME)

%.rle: %.fs
	$(PYTHON3) $(SCRIPT_DIR)/fs2rle.py $< $@

synthesis: $(BITSTREAM)

rle: $(BITSTREAM:.fs=.rle)

run: $(BITSTREAM)
	if lsmod | grep ftdi_sio; then sudo modprobe -r ftdi_sio; fi
	cd $(PROGRAMMER_CLI_DIR); ./programmer_cli --device $(DEVICE) --run 2 --fsFile $(abspath $(BITSTREAM))

deploy: $(BITSTREAM)
	if lsmod | grep ftdi_sio; then sudo modprobe -r ftdi_sio; fi
	cd $(PROGRAMMER_CLI_DIR); ./programmer_cli --device $(DEVICE) --run 6 --fsFile $(abspath $(BITSTREAM))

clean:
	-@$(RM) -r impl
	-@$(RM) *.gprj *.user