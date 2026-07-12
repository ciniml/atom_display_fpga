# GOWIN EDA project script for the Veryl port of the ATOM Display design.
# Run from a build directory:
#   cd build && gw_sh ../project.tcl
# Sources: Veryl-emitted SystemVerilog (veryl/target, design subdirs only;
# tests are emitted at the target root and excluded), the GOWIN IP cores,
# the adapted top.sv, and the original pin/timing constraints.
# SPDX-License-Identifier: CC0-1.0

set THIS_DIR   [file dirname [file normalize [info script]]]
set CHISEL_SRC ${THIS_DIR}/../atomdisplay/src
set VERYL_OUT  ${THIS_DIR}/../../veryl/target

set_option -output_base_name atomdisplay_veryl
set_device -name GW1NR-9C GW1NR-LV9QN88C6/I5

set_option -verilog_std sysv2017
set_option -vhdl_std vhd2008
set_option -print_all_synthesis_warning 1
set_option -place_option 2
set_option -route_option 2

set_option -use_jtag_as_gpio 1
set_option -use_sspi_as_gpio 1
set_option -use_mspi_as_gpio 1

# Veryl design output (subdirectories only: axi/command/sdram/spi/system/util/video)
foreach f [lsort [glob ${VERYL_OUT}/*/*.sv]] {
    add_file -type verilog [file normalize $f]
}

# GOWIN IP cores (shared with the Chisel project)
add_file -type verilog [file normalize ${CHISEL_SRC}/ip/SDRAM_controller_top_SIP/SDRAM_controller_top_SIP.v]
add_file -type verilog [file normalize ${CHISEL_SRC}/ip/sdram_rpll/sdram_rpll.v]
add_file -type verilog [file normalize ${CHISEL_SRC}/ip/dvi_rpll/dvi_rpll.v]
add_file -type verilog [file normalize ${CHISEL_SRC}/ip/dvi_dcs/dvi_dcs.v]

# Adapted top and the original constraints
add_file -type verilog [file normalize ${THIS_DIR}/src/top.sv]
add_file -type cst [file normalize ${CHISEL_SRC}/atomdisplay.cst]
add_file -type sdc [file normalize ${CHISEL_SRC}/m5stack_display.sdc]

run all
