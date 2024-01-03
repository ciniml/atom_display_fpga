// m5stack_display.sdc
// ATOM Display display controller
// Copyright (C) 2022 Kenta Ida
// SPDX-License-Identifier: GPL-3.0-or-later
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.
//
// If you modify this Program, or any covered work, by linking or combining 
// it with GOWIN SDRAM Controller IP and/or GOWIN rPLL IP 
// (or a modified version of that library), 
// containing parts covered by the terms of [name of library's license], 
// the licensors of this Program grant you additional permission 
// to convey the resulting work.

# Main clocks
create_clock -name CLK_IN_50M -period 20.0 -waveform {0 10.0} [get_ports {CLK_IN_50M}]
create_generated_clock -name clock_main -source [get_ports {CLK_IN_50M}] -master_clock CLK_IN_50M -divide_by 20 -multiply_by 25 [get_nets {clock}]
#create_generated_clock -name clock_main -source [get_ports {CLK_IN_50M}] -master_clock CLK_IN_50M -divide_by 5 -multiply_by 7 [get_nets {clock}]
#create_generated_clock -name clock_main -source [get_ports {CLK_IN_50M}] -master_clock CLK_IN_50M -divide_by 5 -multiply_by 8 [get_nets {clock}]

# Video clocks
create_clock -name CLK_IN_74M25 -period 13.468 -waveform {0 6.734} [get_ports {CLK_IN_74M25}]
#create_clock -name CLK_IN_74M25 -period 11.111 -waveform {0 6.734} [get_ports {CLK_IN_74M25}]
create_generated_clock -name clock_video -source [get_ports {CLK_IN_74M25}] -master_clock CLK_IN_74M25 -divide_by 1 -multiply_by 1 [get_nets {clock_video}]
create_generated_clock -name clock_video_nondiv -source [get_ports {CLK_IN_74M25}] -master_clock CLK_IN_74M25 -divide_by 1 -multiply_by 2 [get_nets {clock_video_nondiv}]

# SPI Clock
create_clock -name SPI_SCK -period 12.5 -waveform {0 6.25} [get_ports {BUS_SPI_SCK}]

#set_false_path -from [get_regs {video_generator_i/fifo/index_*_s*}] -to [get_regs {video_generator_i/fifo/rIndexGrayReg_*_s*}] 
#set_false_path -from [get_clocks {clock_main}] -to [get_clocks {clock_video}] 
#set_false_path -from [get_clocks {clock_video}] -to [get_clocks {clock_main}] 
#set_false_path -from [get_clocks {SPI_SCK}] -to [get_clocks {clock_main}] 
set_clock_groups -asynchronous -group [get_clocks {clock_main}] -group [get_clocks {clock_video}]
set_clock_groups -asynchronous -group [get_clocks {clock_main}] -group [get_clocks {SPI_SCK}]
#set_false_path -from [get_clocks {clock_main}] -to [get_clocks {SPI_SCK}] 
#set_false_path -from [get_pins {video_generator_i/spiSlave/input__*/Q}] -to [get_pins {video_generator_i/spiSlave/data_*/D}]
#set_false_path -from [get_pins {video_generator_i/spiSlave/readySend*/Q}] -to [get_pins {video_generator_i/spiSlave/validReceiveSync*/D}]
#set_false_path -to [get_pins {video_generator_i/triggerSync_*/D}]