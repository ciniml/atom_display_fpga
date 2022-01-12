# SPDX-License-Identifier: CC0-1.0

.PHONY: all clean

TARGET ?= atomdisplay
BITSTREAM_RLE = ../eda/$(TARGET)/impl/pnr/$(TARGET).rle
BITSTREAM_HEADER = ./libraries/M5GFX/src/lgfx/v1/panel/Panel_M5HDMI_FS.h

all: $(BITSTREAM_RLE)
	cp $(BITSTREAM_RLE) fs_bitstream.rle
	xxd -i fs_bitstream.rle > fs_bitstream.h
	echo -n "static constexpr const " > $(BITSTREAM_HEADER)
	cat fs_bitstream.h >> $(BITSTREAM_HEADER)
	platformio run -t upload

$(BITSTREAM_RLE):
	cd ../eda/$(TARGET); make

clean:
	-@$(RM) fs_bitstream.*
	-@$(RM) -r .pio