# SPDX-License-Identifier: CC0-1.0

TARGET_NAME ?= atomdisplay
BITSTREAM := impl/pnr/$(TARGET_NAME).fs
SRCS := $(wildcard ../src/*.cst) $(wildcard ../src/*.sdc) $(wildcard ../src/*.sv) ../../atom_display/rtl/m5stack_hdmi/video_generator.v ../src/$(TARGET_NAME).cst project.tcl
DEVICE ?= GW1NR-9C

include ../build_gowin.mk