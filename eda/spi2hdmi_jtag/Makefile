# Makefile
# ATOM Display display controller
# Copyright (C) 2022 Kenta Ida
# SPDX-License-Identifier: GPL-3.0-or-later
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
# If you modify this Program, or any covered work, by linking or combining 
# it with GOWIN SDRAM Controller IP and/or GOWIN rPLL IP 
# (or a modified version of that library), 
# containing parts covered by the terms of [name of library's license], 
# the licensors of this Program grant you additional permission 
# to convey the resulting work.

TARGET_NAME ?= spi2hdmi_jtag
BITSTREAM := impl/pnr/$(TARGET_NAME).fs
SRCS := $(wildcard ../src/*.cst) $(wildcard ../src/*.sdc) $(wildcard ../src/*.sv) ../../rtl/m5stack_hdmi/video_generator.v ../src/$(TARGET_NAME).cst project.tcl
DEVICE ?= GW1NR-9C

include ../build_gowin.mk