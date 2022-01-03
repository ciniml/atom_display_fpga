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

    logic clock;
    logic clock_video;
    logic reset_n;
    logic reset_video;
    logic [2:0] reset_video_sync = '1;
    logic led = 0;
    logic data_in_sync;
    logic lock_main;
    logic lock_video = 1;
    logic lock_sdram;
    logic trigger;
    logic processor_is_busy;
    logic [$clog2(74_250_000)-1:0] led_counter = 0;
    logic data_enable; /* synthesis syn_keep=true */
    assign RGB_DE = data_enable;

    assign HDMI_LED_B = led;
    assign HDMI_LED_W = processor_is_busy;
    //assign LED[2] = led; //I_sdrc_rst_n;
    assign SYS_LED_B = data_in_sync; //!O_sdrc_busy_n;
    assign SYS_LED_R = BUS_SPI_CS;

    assign RGB_IDCK = clock_video;
    logic [31:0] reset_reg = '1;

    always_ff @(posedge CLK_IN_50M) begin
        if( led_counter < 'd50_000_000 ) begin
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
    assign reset_video = reset_video_sync[0];
    always_ff @(posedge clock_video) begin
        reset_video_sync[2] <= !reset_n;
        reset_video_sync[1:0] <= reset_video_sync[2:1];
    end

    assign I_sdrc_clk = clock;
    assign I_sdrc_rst_n = reset_n && lock_sdram;
    assign clock_video = CLK_IN_74M25;

    assign lock_main = lock_sdram;
    //assign lock_sdram = 1;
    sdram_rpll sdram_rpll_i(
        .clkout(I_sdram_clk),
        //.clkoutp(clock),
        .clkoutd(clock),
        .lock(lock_sdram),
        .clkin(CLK_IN_50M) //input clkin
    );

    wire [23:0] video_data;
    assign RGB_OUT = video_data; //{video_data[15:11], 3'b0, video_data[10:5], 2'b0, video_data[4:0], 3'b0};

    M5StackHDMI video_generator_i (
        .reset(!reset_n),
        .clock(clock),
        .io_videoClock(clock_video),
        .io_videoReset(reset_video),
        .io_video_pixelData(video_data),
        .io_video_hSync(RGB_HSYNC),
        .io_video_vSync(RGB_VSYNC),
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
