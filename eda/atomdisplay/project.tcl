set base_name [lindex $argv 0]
set_option -output_base_name ${base_name}
set_device -name GW1NR-9C GW1NR-LV9QN88C6/I5

set_option -verilog_std sysv2017
set_option -vhdl_std vhd2008
set_option -print_all_synthesis_warning 1
set_option -place_option 1
set_option -route_option 2

set_option -use_jtag_as_gpio 1

add_file -type verilog [file normalize ../../atom_display/rtl/m5stack_hdmi/video_generator.v]
add_file -type verilog [file normalize ../ip/SDRAM_controller_top_SIP/SDRAM_controller_top_SIP.v]
add_file -type verilog [file normalize ../ip/sdram_rpll/sdram_rpll.v]
add_file -type verilog [file normalize ../src/top.sv]
add_file -type cst [file normalize ../src/${base_name}.cst]
add_file -type sdc [file normalize ../src/m5stack_display.sdc]

run all