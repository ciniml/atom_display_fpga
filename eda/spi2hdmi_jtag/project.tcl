# project.tcl
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

set base_name [lindex $argv 0]
set_option -output_base_name ${base_name}
set_device -name GW1NR-9C GW1NR-LV9QN88C6/I5

set_option -verilog_std sysv2017
set_option -vhdl_std vhd2008
set_option -print_all_synthesis_warning 1
set_option -place_option 1
set_option -route_option 2

add_file -type verilog [file normalize ../../atom_display/rtl/m5stack_hdmi/video_generator.v]
add_file -type verilog [file normalize ../ip/SDRAM_controller_top_SIP/SDRAM_controller_top_SIP.v]
add_file -type verilog [file normalize ../ip/sdram_rpll/sdram_rpll.v]
add_file -type verilog [file normalize ../src/top.sv]
add_file -type cst [file normalize ../src/${base_name}.cst]
add_file -type sdc [file normalize ../src/m5stack_display.sdc]

run all