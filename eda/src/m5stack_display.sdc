//Copyright (C)2014-2021 GOWIN Semiconductor Corporation.
//All rights reserved.
//File Title: Timing Constraints file
//GOWIN Version: 1.9.7.01 Beta
//Created Time: 2021-03-13 07:59:33
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
