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

create_clock -name CLK_IN_74M25 -period 13.468 -waveform {0 6.734} [get_ports {CLK_IN_74M25}]
create_clock -name SPI_SCK -period 12.5 -waveform {0 6.25} [get_ports {BUS_SPI_SCK}]
//create_generated_clock -name clock_sdram -source [get_ports {CLK_IN_74M25}] -master_clock CLK_IN_74M25 -divide_by 17 -multiply_by 32 [get_nets {I_sdram_clk}]
create_generated_clock -name clock_main -source [get_ports {CLK_IN_74M25}] -master_clock CLK_IN_74M25 -divide_by 17 -multiply_by 16 [get_nets {clock}]
set_false_path -from [get_regs {video_generator_i/fifo/index_*_s*}] -to [get_regs {video_generator_i/fifo/rIndexGrayReg_*_s*}] 
set_false_path -from [get_clocks {clock_main}] -to [get_clocks {CLK_IN_74M25}] 
set_false_path -from [get_clocks {CLK_IN_74M25}] -to [get_clocks {clock_main}] 
set_false_path -from [get_clocks {SPI_SCK}] -to [get_clocks {clock_main}] 
set_false_path -from [get_clocks {clock_main}] -to [get_clocks {SPI_SCK}] 
set_false_path -from [get_pins {video_generator_i/spiSlave/input__*/Q}] -to [get_pins {video_generator_i/spiSlave/data_*/D}]
set_false_path -from [get_pins {video_generator_i/spiSlave/readySend*/Q}] -to [get_pins {video_generator_i/spiSlave/validReceiveSync*/D}]
