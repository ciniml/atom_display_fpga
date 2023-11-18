set SRC_DIR       [lindex $argv 0]
set RTL_DIR       [lindex $argv 1]
set TARGET        [lindex $argv 2]
set DEVICE_FAMILY [lindex $argv 3]
set DEVICE_PART   [lindex $argv 4]
set PROJECT_NAME  [lindex $argv 5]
set IO_CONSTRAINT [lindex $argv 6]

set_option -output_base_name ${PROJECT_NAME}
set_device -name $DEVICE_FAMILY $DEVICE_PART
set_device -name GW1NR-9C GW1NR-LV9QN88C6/I5

set_option -verilog_std sysv2017
set_option -vhdl_std vhd2008
set_option -print_all_synthesis_warning 1
set_option -place_option 2
set_option -route_option 2

set_option -use_jtag_as_gpio 1
set_option -use_sspi_as_gpio 1
set_option -use_mspi_as_gpio 1

add_file -type verilog [file normalize ${RTL_DIR}/m5stack_hdmi/video_generator.v]
add_file -type verilog [file normalize ${SRC_DIR}/ip/SDRAM_controller_top_SIP/SDRAM_controller_top_SIP.v]
add_file -type verilog [file normalize ${SRC_DIR}/ip/sdram_rpll/sdram_rpll.v]
add_file -type verilog [file normalize ${SRC_DIR}/ip/dvi_rpll/dvi_rpll.v]
add_file -type verilog [file normalize ${SRC_DIR}/ip/dvi_dcs/dvi_dcs.v]
#add_file -type verilog [file normalize ${SRC_DIR}/ip/dvi_clkdiv/dvi_clkdiv.v]
add_file -type verilog [file normalize ${SRC_DIR}/top.sv]
add_file -type cst [file normalize ${SRC_DIR}/${IO_CONSTRAINT}.cst]
add_file -type sdc [file normalize ${SRC_DIR}/m5stack_display.sdc]

run all