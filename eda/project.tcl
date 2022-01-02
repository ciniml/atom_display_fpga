set_option -output_base_name stop_watch
set_device -name GW1N-4B GW1N-LV4LQ144C6/I5

set_option -verilog_std sysv2017
set_option -vhdl_std vhd2008
set_option -print_all_synthesis_warning 1

add_file -type verilog [file normalize ../../rtl/segment_led/segment_led.sv]
add_file -type verilog [file normalize ../../rtl/segment_led/seven_segment_with_dp.sv]
add_file -type verilog [file normalize ../../rtl/switch_input/debounce.sv]
add_file -type verilog [file normalize ../../rtl/switch_input/bounce_detector.sv]
add_file -type verilog [file normalize ../../rtl/util/timer_counter.sv]
add_file -type verilog [file normalize src/top.sv]
add_file -type cst [file normalize src/runber.cst]
add_file -type sdc [file normalize src/runber.sdc]

run all