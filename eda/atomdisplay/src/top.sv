// top.sv
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

`default_nettype none
module top (
    input wire CLK_IN_74M25,
    input wire CLK_IN_50M,
    
    // Video signals
    output wire RGB_IDCK,
    output wire RGB_DE,
    output wire RGB_HSYNC,
    output wire RGB_VSYNC,
    output wire [23:0] RGB_OUT,

    // Test LED
    output wire HDMI_LED_B,
    output wire HDMI_LED_W,
    output wire SYS_LED_B,
    output wire SYS_LED_R,

    // MCU Interface (SPI)
    output wire BUS_SPI_MISO,
    input  wire BUS_SPI_MOSI,
    input  wire BUS_SPI_SCK,
    input  wire BUS_SPI_CS,
    input  wire BUS_BUSY,
    
    // Embedded SDRAM
    output wire O_sdram_clk,
    output wire O_sdram_cke,
    output wire O_sdram_cs_n,
    output wire O_sdram_cas_n,
    output wire O_sdram_ras_n,
    output wire O_sdram_wen_n,
    output wire [1:0] O_sdram_dqm,
    output wire [11:0] O_sdram_addr,
    output wire [1:0] O_sdram_ba,
    inout  wire [15:0] IO_sdram_dq
);
    // SDRAM Controller signals
    logic I_sdrc_rst_n;
    logic I_sdrc_clk;
    logic I_sdram_clk;
    logic I_sdrc_selfrefresh;
    logic I_sdrc_power_down;
    logic I_sdrc_wr_n;
    logic I_sdrc_rd_n;
    logic [20:0] I_sdrc_addr;
    logic [6:0] I_sdrc_data_len;
    logic [3:0] I_sdrc_dqm;
    logic [31:0] O_sdrc_data;
    logic [31:0] I_sdrc_data;
    logic O_sdrc_init_done;
    logic O_sdrc_busy_n;
    logic O_sdrc_rd_valid;
    logic O_sdrc_wrd_ack;

    logic clock; /* synthesis syn_keep=1 */
    logic clock_video;
    //logic clock_video_x5;
    logic reset_n;
    logic reset_video;
    logic [2:0] reset_video_sync = '1;
    logic dvi_rpll_reset;
    logic [5:0] dvi_rpll_fbdsel;
    logic [5:0] dvi_rpll_idsel;
    logic [5:0] dvi_rpll_odsel;

    logic led = 0;
    logic data_in_sync;
    logic lock_main;
    logic lock_video;
    logic lock_sdram;
    logic trigger;
    logic processor_is_busy;
    logic [$clog2(74_250_000)-1:0] led_counter = 0;
    logic data_enable; /* synthesis syn_keep=true */

    logic [15:0] video_clock_input_divider;
    logic [15:0] video_clock_output_divider;
    logic [15:0] video_clock_feedback_divider;
    logic        video_clock_config_valid;

    assign RGB_DE = data_enable;

    assign HDMI_LED_B = led;
    assign HDMI_LED_W = processor_is_busy;
    //assign LED[2] = led; //I_sdrc_rst_n;
    assign SYS_LED_B = data_in_sync; //!O_sdrc_busy_n;
    assign SYS_LED_R = BUS_SPI_CS;

    assign RGB_IDCK = clock_video;
    logic [31:0] reset_reg = '1;

    always_ff @(posedge clock_video) begin
        if( led_counter < 'd74_250_000 ) begin
            led_counter <= led_counter + 1;
        end
        else begin
            led_counter <= 0;
            led <= !led;
        end
    end

    always_ff @(posedge clock) begin
        if( !lock_main ) begin
            reset_reg <= '1;
        end
        else begin
            reset_reg <= reset_reg >> 1;
        end
    end
    assign reset_n = !reset_reg[0];
    assign reset_video = reset_video_sync[0] && !lock_video;
    always_ff @(posedge clock_video) begin
        reset_video_sync[2] <= !reset_n;
        reset_video_sync[1:0] <= reset_video_sync[2:1];
    end

    assign I_sdrc_clk = clock;
    assign I_sdrc_rst_n = reset_n && lock_sdram;
    assign clock_video = CLK_IN_74M25;

    dvi_rpll dvi_rpll_i(
        .clkout(clock_video),
        //.clkoutp(clock),
        //.clkoutd(clock_video),
        .lock(lock_video),
        .reset(dvi_rpll_reset),
        .clkin(CLK_IN_74M25), //input clkin
        .fbdsel(dvi_rpll_fbdsel), //input [5:0] fbdsel
        .idsel(dvi_rpll_idsel), //input [5:0] idsel
        .odsel(dvi_rpll_odsel) //input [5:0] odsel
    );

    // dvi_clkdiv dvi_clkdiv_i(
    //     .clkout(clock_video), //output clkout
    //     .hclkin(clock_video_x5), //input hclkin
    //     .resetn(reset_n) //input resetn
    // );

    assign lock_main = lock_sdram;
    //assign lock_sdram = 1;
    sdram_rpll sdram_rpll_i(
        .clkout(I_sdram_clk),
        //.clkoutp(clock),
        .clkoutd(clock),
        .lock(lock_sdram),
        .clkin(CLK_IN_50M) //input clkin
    );

    // Video clock configuration sequence.
    // assign dvi_rpll_reset = 0;
    // assign dvi_rpll_fbdsel = 7'd64 - 7'd4;
    // assign dvi_rpll_idsel = 7'd64 - 7'd4;
    // assign dvi_rpll_odsel = 7'd64 - (7'd8 >> 1);
    always_ff @(posedge clock) begin
        if( !reset_n ) begin
            dvi_rpll_reset <= 1;
            dvi_rpll_fbdsel <= 7'd64 - 7'd4;
            dvi_rpll_idsel <= 7'd64 - 7'd4;
            dvi_rpll_odsel <= 7'd64 - (7'd8 >> 1);
        end
        else begin
            dvi_rpll_reset <= video_clock_config_valid;
            if( video_clock_config_valid ) begin
                dvi_rpll_fbdsel <= 7'd64 - {1'b0, video_clock_feedback_divider[5:0]};
                dvi_rpll_idsel <= 7'd64 - {1'b0, video_clock_input_divider[5:0]};
                dvi_rpll_odsel <= 7'd64 - {2'b00, video_clock_output_divider[5:1]};
            end
        end
    end
     

    logic [23:0] video_data;
    assign RGB_OUT[13:0] = video_data[13:0]; 
    assign RGB_OUT[18] = video_data[14];
    assign RGB_OUT[23] = video_data[15];
    assign RGB_OUT[17] = video_data[16];
    assign RGB_OUT[22] = video_data[17];
    assign RGB_OUT[16] = video_data[18];
    assign RGB_OUT[21] = video_data[19];
    assign RGB_OUT[15] = video_data[20];
    assign RGB_OUT[20] = video_data[21];
    assign RGB_OUT[14] = video_data[22];
    assign RGB_OUT[19] = video_data[23];

    logic video_hsync, video_vsync;
    assign RGB_HSYNC = video_vsync;
    assign RGB_VSYNC = video_hsync;

    M5StackHDMI video_generator_i (
        .reset(!reset_n),
        .clock(clock),
        .io_videoClock(clock_video),
        .io_videoReset(reset_video),
        .io_video_pixelData(video_data),
        .io_video_hSync(video_hsync),
        .io_video_vSync(video_vsync),
        .io_video_dataEnable(data_enable),
        .io_dataInSync(data_in_sync),
        .io_sdrc_selfRefresh(I_sdrc_selfrefresh),
        .io_sdrc_powerDown(I_sdrc_power_down),
        .io_sdrc_wr_n(I_sdrc_wr_n),
        .io_sdrc_rd_n(I_sdrc_rd_n),
        .io_sdrc_addr(I_sdrc_addr),
        .io_sdrc_dataLen(I_sdrc_data_len),
        .io_sdrc_dqm(I_sdrc_dqm),
        .io_sdrc_dataRead(O_sdrc_data),
        .io_sdrc_dataWrite(I_sdrc_data),
        .io_sdrc_initDone(O_sdrc_init_done),
        .io_sdrc_busy_n(O_sdrc_busy_n),
        .io_sdrc_rdValid(O_sdrc_rd_valid),
        .io_sdrc_wrdAck(O_sdrc_wrd_ack),
        .io_trigger(trigger),
        .io_spi_miso(BUS_SPI_MISO),
        .io_spi_mosi(BUS_SPI_MOSI),
        .io_spi_sck (BUS_SPI_SCK),
        .io_spi_cs  (BUS_SPI_CS),
        .io_processorIsBusy(processor_is_busy),
        .io_videoClockConfig_inputDivider(video_clock_input_divider),
        .io_videoClockConfig_outputDivider(video_clock_output_divider),
        .io_videoClockConfig_feedbackDivider(video_clock_feedback_divider),
        .io_videoClockConfigValid(video_clock_config_valid),
        .*
    );

    SDRAM_controller_top_SIP sdram_controller_i (
        .O_sdram_clk(O_sdram_clk), //output O_sdram_clk
        .O_sdram_cke(O_sdram_cke), //output O_sdram_cke
        .O_sdram_cs_n(O_sdram_cs_n), //output O_sdram_cs_n
        .O_sdram_cas_n(O_sdram_cas_n), //output O_sdram_cas_n
        .O_sdram_ras_n(O_sdram_ras_n), //output O_sdram_ras_n
        .O_sdram_wen_n(O_sdram_wen_n), //output O_sdram_wen_n
        .O_sdram_dqm(O_sdram_dqm), //output [1:0] O_sdram_dqm
        .O_sdram_addr(O_sdram_addr), //output [11:0] O_sdram_addr
        .O_sdram_ba(O_sdram_ba), //output [1:0] O_sdram_ba
        .IO_sdram_dq(IO_sdram_dq), //inout [15:0] IO_sdram_dq

        .I_sdrc_rst_n(I_sdrc_rst_n), //input I_sdrc_rst_n
        .I_sdrc_clk(I_sdrc_clk), //input I_sdrc_clk
        .I_sdram_clk(I_sdram_clk), //input I_sdram_clk

        .I_sdrc_selfrefresh(I_sdrc_selfrefresh), //input I_sdrc_selfrefresh
        .I_sdrc_power_down(I_sdrc_power_down), //input I_sdrc_power_down

        .I_sdrc_wr_n(I_sdrc_wr_n), //input I_sdrc_wr_n
        .I_sdrc_rd_n(I_sdrc_rd_n), //input I_sdrc_rd_n
        .I_sdrc_addr(I_sdrc_addr), //input [20:0] I_sdrc_addr
        .I_sdrc_data_len(I_sdrc_data_len), //input [6:0] I_sdrc_data_len
        .I_sdrc_dqm(I_sdrc_dqm), //input [3:0] I_sdrc_dqm
        .O_sdrc_data(O_sdrc_data), //output [31:0] O_sdrc_data
        .I_sdrc_data(I_sdrc_data), //input [31:0] I_sdrc_data
        .O_sdrc_init_done(O_sdrc_init_done), //output O_sdrc_init_done
        .O_sdrc_busy_n(O_sdrc_busy_n), //output O_sdrc_busy_n
        .O_sdrc_rd_valid(O_sdrc_rd_valid), //output O_sdrc_rd_valid
        .O_sdrc_wrd_ack(O_sdrc_wrd_ack) //output O_sdrc_wrd_ack
    );
endmodule

`default_nettype wire
