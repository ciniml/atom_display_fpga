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
    output logic RGB_IDCK,
    output logic RGB_DE,
    output logic RGB_HSYNC,
    output logic RGB_VSYNC,
    output logic [23:0] RGB_OUT,

    // Test LED
    output wire HDMI_LED_B,
    output wire HDMI_LED_W,
    output wire HDMI_LED_B_ALT,
    output wire HDMI_LED_W_ALT,
    output wire SYS_LED_B,

    // MCU Interface (SPI)
    inout  wire BUS_SPI_MISO,
    input  wire BUS_SPI_MOSI,
    input  wire BUS_SPI_SCK,
    input  wire BUS_SPI_CS,
    
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
    inout  wire [15:0] IO_sdram_dq,

    // Module detection
    input wire IS_MODULE_DISPLAY_N,

    // I2S
    output logic I2S_I_SCK,
    output logic I2S_I_WS,
    output logic I2S_I_SDO,
    output logic I2S_I_MCLK,

    // Other signals
    input wire F_G12,
    output wire F_G5,
    input wire F_G15,
    input wire F_G0,    // pull-up, to determine AtomDisplay/M5Display L=M5
    input wire F_G2
    //input wire F_G16,
    //input wire F_G17,
    //input wire F_G25,
    //input wire F_G26
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

    logic clock;              /* synthesis syn_keep=1 */
    logic clock_video;        /* synthesis syn_keep=1 */    // video signal clock.
    logic clock_video_nondiv; /* synthesis syn_keep=1 */    // video clock generator output (non divided)
    logic clock_video_half;                                 // video clock generator output (half)
    
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

    logic [15:0] video_clock_input_divider;
    logic [15:0] video_clock_output_divider;
    logic [15:0] video_clock_feedback_divider;
    logic        video_clock_use_half_clock = 0;
    logic        video_clock_config_valid;

    logic [35:0]  debugIn;
    logic        probeOut;
    logic drainer_led_out;

    assign HDMI_LED_B = led | drainer_led_out;
    assign HDMI_LED_W = reset_n;
    assign HDMI_LED_B_ALT = led;
    assign HDMI_LED_W_ALT = reset_n;
    assign SYS_LED_B = reset_n;    /* only available in ATOM Display */

    //assign RGB_IDCK = clock_video_nondiv;   // HDMI pixel clock is always PLL non-div output.
    logic [31:0] reset_reg = '1;

    IODELAY #(
        .C_STATIC_DLY(0)
    ) iodelay_idck (
        .DI(clock_video_nondiv),
        .SDTAP(0),
        .SETN(0),
        .VALUE(0),
        .DO(RGB_IDCK),
        .DF()
    );

    // always_ff @(posedge clock_video) begin
    //     if( led_counter < 'd74_250_000 ) begin
    //         led_counter <= led_counter + 1;
    //     end
    //     else begin
    //         led_counter <= 0;
    //         led <= !led;
    //     end
    // end
    always_ff @(posedge clock) begin
        if( !reset_n ) begin
            led_counter <= 0;
        end
        else begin 
            if( led_counter < 'd62_500_000 ) begin
                led_counter <= led_counter + 1;
            end
            else begin
                led_counter <= 0;
                led <= !led;
            end
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

    dvi_rpll dvi_rpll_i(
        .clkout(clock_video_nondiv),
        //.clkoutp(clock),
        .clkoutd(clock_video_half),
        .lock(lock_video),
        .reset(dvi_rpll_reset),
        .clkin(CLK_IN_74M25), //input clkin
        .fbdsel(dvi_rpll_fbdsel), //input [5:0] fbdsel
        .idsel(dvi_rpll_idsel), //input [5:0] idsel
        .odsel(dvi_rpll_odsel) //input [5:0] odsel
    );

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
    // assign dvi_rpll_fbdsel = 7'd64 - 7'd2;
    // assign dvi_rpll_idsel = 7'd64 - 7'd1;
    // assign dvi_rpll_odsel = 7'd64 - (7'd4 >> 1);
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
    
    // I2S connection
    assign I2S_I_MCLK = clock_video;
    assign I2S_I_SCK = F_G12;
    assign I2S_I_SDO = F_G15;
    assign I2S_I_WS  = F_G0;

    logic is_m5display = 0;
    logic is_m5display_lock = 0;
    always_ff @(posedge clock_video) begin
        if( reset_video ) begin
            is_m5display <= 0;
            is_m5display_lock <= 0;
        end
        else begin
            is_m5display <= is_m5display_lock ? is_m5display : !IS_MODULE_DISPLAY_N;
            is_m5display_lock <= 1;
        end
    end

    // Swap pins for M5Display
    localparam int BITS_PER_PIXEL = 16;
    logic [BITS_PER_PIXEL-1:0] video_data_native;
    logic [23:0] video_data;
    logic [23:0] video_data_sdr;
    logic video_hsync_sdr;
    logic video_vsync_sdr;
    logic video_de; /* synthesis syn_keep=true */
    logic video_de_sdr;
    logic video_hsync, video_vsync;

    generate
        if( BITS_PER_PIXEL == 16 ) begin
            logic [4:0] video_data_native_r;
            logic [5:0] video_data_native_g;
            logic [4:0] video_data_native_b;
            logic [7:0] video_data_r;
            logic [7:0] video_data_g;
            logic [7:0] video_data_b;

            assign video_data_native_r = video_data_native[15:11];
            assign video_data_native_g = video_data_native[10:5];
            assign video_data_native_b = video_data_native[4:0];
            assign video_data_r = {video_data_native_r, video_data_native_r[4:2]};
            assign video_data_g = {video_data_native_g, video_data_native_g[4:3]};
            assign video_data_b = {video_data_native_b, video_data_native_b[4:2]};
            assign video_data = {video_data_b, video_data_g, video_data_r};
        end
        else begin
            assign video_data = video_data_native;
        end
    endgenerate

    // assign video_data_sdr[13:0] = video_data[13:0]; 
    // assign video_data_sdr[18] = is_m5display ? video_data[14] : video_data[18];
    // assign video_data_sdr[23] = is_m5display ? video_data[15] : video_data[23];
    // assign video_data_sdr[17] = is_m5display ? video_data[16] : video_data[17];
    // assign video_data_sdr[22] = is_m5display ? video_data[17] : video_data[22];
    // assign video_data_sdr[16] = is_m5display ? video_data[18] : video_data[16];
    // assign video_data_sdr[21] = is_m5display ? video_data[19] : video_data[21];
    // assign video_data_sdr[15] = is_m5display ? video_data[20] : video_data[15];
    // assign video_data_sdr[20] = is_m5display ? video_data[21] : video_data[20];
    // assign video_data_sdr[14] = is_m5display ? video_data[22] : video_data[14];
    // assign video_data_sdr[19] = is_m5display ? video_data[23] : video_data[19];

    // assign video_hsync_sdr = is_m5display ? video_vsync : video_hsync;
    // assign video_vsync_sdr = is_m5display ? video_hsync : video_vsync;

    always @(posedge clock_video) begin
        video_data_sdr[13:0] <= video_data[13:0]; 
        video_data_sdr[18] <= is_m5display ? video_data[14] : video_data[18];
        video_data_sdr[23] <= is_m5display ? video_data[15] : video_data[23];
        video_data_sdr[17] <= is_m5display ? video_data[16] : video_data[17];
        video_data_sdr[22] <= is_m5display ? video_data[17] : video_data[22];
        video_data_sdr[16] <= is_m5display ? video_data[18] : video_data[16];
        video_data_sdr[21] <= is_m5display ? video_data[19] : video_data[21];
        video_data_sdr[15] <= is_m5display ? video_data[20] : video_data[15];
        video_data_sdr[20] <= is_m5display ? video_data[21] : video_data[20];
        video_data_sdr[14] <= is_m5display ? video_data[22] : video_data[14];
        video_data_sdr[19] <= is_m5display ? video_data[23] : video_data[19];

        video_hsync_sdr <= is_m5display ? video_vsync : video_hsync;
        video_vsync_sdr <= is_m5display ? video_hsync : video_vsync;
        video_de_sdr <= video_de;
    end
    // generate
    //     for(genvar i = 0; i < 24; i++) begin
    //         logic rgb_out_reg = 0;
    //         always @(posedge clock_video_nondiv) rgb_out_reg <= video_data_sdr[i];
    //         OBUF obuf_video_data(
    //             .O(RGB_OUT[i]),
    //             .I(rgb_out_reg)
    //         );
    //     end
    // endgenerate
    // logic rgb_hsync_reg = 0;
    // always @(posedge clock_video_nondiv) rgb_hsync_reg <= video_hsync_sdr;
    // OBUF obuf_video_hsync(
    //     .O(RGB_HSYNC),
    //     .I(rgb_hsync_reg)
    // );
    // logic rgb_vsync_reg = 0;
    // always @(posedge clock_video_nondiv) rgb_vsync_reg <= video_vsync_sdr;
    // OBUF obuf_video_vsync(
    //     .O(RGB_VSYNC),
    //     .I(rgb_vsync_reg)
    // );
    // logic rgb_de_reg = 0;
    // always @(posedge clock_video_nondiv) rgb_de_reg <= video_de_sdr;
    // OBUF obuf_video_de(
    //     .O(RGB_DE),
    //     .I(rgb_de_reg)
    // );
    // always_ff @(posedge clock_video_nondiv) begin
    //     RGB_OUT <= video_data_sdr;
    //     RGB_HSYNC <= video_hsync_sdr;
    //     RGB_VSYNC <= video_vsync_sdr;
    //     RGB_DE <= video_de_sdr;
    // end

    generate
        for(genvar i = 0; i < 24; i++) begin
            ODDR video_data_ddr(
                .Q0(RGB_OUT[i]),
                .Q1(),
                .D0(video_data_sdr[i]),
                .D1(video_data_sdr[i]),
                .TX(1),
                .CLK(clock_video)
            );
        end
        ODDR video_hsync_ddr(
            .Q0(RGB_HSYNC),
            .Q1(),
            .D0(video_hsync_sdr),
            .D1(video_hsync_sdr),
            .TX(1),
            .CLK(clock_video)
        );
        ODDR video_vsync_ddr(
            .Q0(RGB_VSYNC),
            .Q1(),
            .D0(video_vsync_sdr),
            .D1(video_vsync_sdr),
            .TX(1),
            .CLK(clock_video)
        );
        ODDR video_de_ddr(
            .Q0(RGB_DE),
            .Q1(),
            .D0(video_de_sdr),
            .D1(video_de_sdr),
            .TX(1),
            .CLK(clock_video)
        );
    endgenerate

    logic [3:0] dvi_clock_select = 4'b0001;
    always_ff @(posedge clock) begin
        if( !reset_n ) begin
            dvi_clock_select <= 4'b0001;
        end
        else begin
            if( video_clock_config_valid ) begin
                dvi_clock_select <= video_clock_use_half_clock ? 4'b0010 : 4'b0001;
            end
        end
    end
    dvi_dcs dvi_clock_select_inst(
        .clkout(clock_video),
        .clksel( dvi_clock_select ),  // clock select
        .clk0(clock_video_nondiv),  // video non divided clock
        .clk1(clock_video_half),    // video half clock
        .clk2(0),
        .clk3(0)
    );

    logic io_spi_miso;
    IOBUF iobuf_spi_miso (
        .O(),
        .I(io_spi_miso),
        .OEN(BUS_SPI_CS),
        .IO(BUS_SPI_MISO)
    );

    M5StackHDMI video_generator_i (
        .reset(!reset_n),
        .clock(clock),
        .io_videoClock(clock_video),
        .io_videoReset(reset_video),
        .io_video_pixelData(video_data_native),
        .io_video_hSync(video_hsync),
        .io_video_vSync(video_vsync),
        .io_video_dataEnable(video_de),
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
        .io_spi_miso(io_spi_miso),
        .io_spi_mosi(BUS_SPI_MOSI),
        .io_spi_sck (BUS_SPI_SCK),
        .io_spi_cs  (BUS_SPI_CS),
        .io_processorIsBusy(processor_is_busy),
        .io_videoClockConfig_inputDivider(video_clock_input_divider),
        .io_videoClockConfig_outputDivider(video_clock_output_divider),
        .io_videoClockConfig_feedbackDivider(video_clock_feedback_divider),
        .io_videoClockConfig_useHalfClock(video_clock_use_half_clock),
        .io_videoClockConfigValid(video_clock_config_valid),
        .io_debugIn(debugIn),
        .io_probeOut(probeOut),
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

    // FF drainer to check the resource utilization problem.
    //flipflop_drainer flipflop_drainer(.clk(clock), .out(drainer_led_out)); // Output the result of additions to a led so it does not get optimized out.

    // debug configuration
    // cargo run -- --port /dev/ttyACM0 --signal I_sdrc_rd_n:1 --signal I_sdrc_wr_n:1 --signal O_sdrc_busy_n:1 --signal reserved:1 --signal I_sdrc_data:32 --csv output.csv
    // assign debugIn[0] = !I_sdrc_rd_n;
    // assign debugIn[1] = !I_sdrc_wr_n;
    // assign debugIn[2] = !O_sdrc_busy_n;
    // assign debugIn[3] = 0;
    // assign debugIn[35:4] = I_sdrc_data;
    // cargo run -- --port /dev/ttyACM0 --signal I_sdrc_rd_n:1 --signal I_sdrc_wr_n:1 --signal O_sdrc_busy_n:1 --signal reserved:1 --signal I_sdrc_addr:32 --csv output.csv --count 1
    assign debugIn[0] = !I_sdrc_rd_n;
    assign debugIn[1] = !I_sdrc_wr_n;
    assign debugIn[2] = !O_sdrc_busy_n;
    assign debugIn[3] = 0;
    assign debugIn[35:4] = I_sdrc_addr;
    // assign debugIn[0] = O_sdrc_rd_valid && O_sdrc_data != 32'hffffffff;
    // assign debugIn[1] = O_sdrc_wrd_ack;
    // assign debugIn[2] = !O_sdrc_busy_n;
    // assign debugIn[3] = 0;
    // assign debugIn[35:4] = O_sdrc_data;
    // cargo run -- --port /dev/ttyACM0 --signal video_de:1 --signal video_hsync:1 --signal video_vsync:1 --signal reserved:1 --signal video_data:24 --signal reserved:8 --csv output.csv
    // assign debugIn[0] = video_de;
    // assign debugIn[1] = video_hsync;
    // assign debugIn[2] = video_vsync;
    // assign debugIn[3] = 0;
    // assign debugIn[27:4] = video_data_native; //I_sdrc_addr[3:0];
    // assign debugIn[35:28] = 0;
    assign F_G5 = probeOut;
endmodule

`default_nettype wire
