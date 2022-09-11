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
    input  wire BUS_BUSY
);
    logic clock_video;
    logic reset_video;
    logic [2:0] reset_video_sync = '1;
    logic led = 0;
    logic [$clog2(74_250_000)-1:0] led_counter = 0;
    logic data_enable;
    logic blink;
    assign RGB_DE = data_enable;

    assign HDMI_LED_B = led;
    assign HDMI_LED_W = blink;
    assign SYS_LED_B = 1;
    assign SYS_LED_R = 1;
    assign BUS_SPI_MISO = blink;
    
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

    assign reset_video = reset_video_sync[0];
    always_ff @(posedge clock_video) begin
        reset_video_sync[2] <= 0;
        reset_video_sync[1:0] <= reset_video_sync[2:1];
    end

    assign clock_video = CLK_IN_74M25;

    logic [23:0] video_data;
    assign RGB_OUT = video_data;

    logic video_hsync, video_vsync;
    assign RGB_HSYNC = video_vsync;
    assign RGB_VSYNC = video_hsync;

    LatencyChecker latency_checker_inst (
        .reset(reset_video),
        .clock(clock_video),
        .io_video_pixelData(video_data),
        .io_video_hSync(video_hsync),
        .io_video_vSync(video_vsync),
        .io_video_dataEnable(data_enable),
        .io_blink(blink),
        .*
    );
endmodule

`default_nettype wire
