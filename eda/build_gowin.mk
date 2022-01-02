.PHONY: all clean synthesis run deploy

PROGRAMMER_CLI_DIR = $(dir $(shell which programmer_cli))

all: synthesis

$(BITSTREAM): $(SRCS)
	gw_sh ./project.tcl

synthesis: $(BITSTREAM)

run: $(BITSTREAM)
	if lsmod | grep ftdi_sio; then sudo modprobe -r ftdi_sio; fi
	cd $(PROGRAMMER_CLI_DIR); ./programmer_cli --device $(DEVICE) --run 2 --fsFile $(abspath $(BITSTREAM))

deploy: $(BITSTREAM)
	if lsmod | grep ftdi_sio; then sudo modprobe -r ftdi_sio; fi
	cd $(PROGRAMMER_CLI_DIR); ./programmer_cli --device $(DEVICE) --run 6 --fsFile $(abspath $(BITSTREAM))

clean:
	-@$(RM) -r impl
	-@$(RM) *.gprj *.user