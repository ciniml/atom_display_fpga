package system

import chisel3._
import chisel3.util._
import chisel3.experimental.chiselName
import chisel3.experimental.BundleLiterals._
import chisel3.stage.ChiselStage
import video._
import sdram._
import command._
import axi._
import _root_.util._

import java.io.FileInputStream
import scala.collection.mutable

object PresetVideoParams {
  val Default_1280_720_60 = new VideoParams(24, 20, 720, 5, 5, 220, 1280, 110, 40)
  val Generic_1024_768_60 = new VideoParams(24, 63, 768, 64, 5, 117, 1024, 117, 117)  // 351, 132
  val TwiHai_480_1920_60 = new VideoParams(24, 20, 1920, 10, 5, 51, 480, 51, 51)
  val Circular_480_480_60 = new VideoParams(24, 135, 480, 130, 5, 390, 480, 390, 390)
  val Maximum = new VideoParams(24, 511, 2048, 511, 511, 511, 2048, 511, 511) // Max counter size
}

@chiselName
class M5StackHDMI(defaultVideoParams: VideoParams = PresetVideoParams.Default_1280_720_60) extends Module {
  val videoParams = PresetVideoParams.Maximum
  val videoConfigType = VideoConfig(videoParams)
  val fullPageBurstLength = 256 * 2 / 4 // 256 [columns/row] * 2 [bytes/column] / 4 [bytes/address] (full page burst)
  val maxBurstPixels = 160 //(fullPageBurstLength * 4 / 12) * 4
  val maxBurstLength = maxBurstPixels * 3 / 4
  val reader = Module(new FrameBufferReader(videoParams.pixelBits, videoParams.pixelsH, videoParams.pixelsV, 32, maxBurstLength))
  val sdramParams = new SDRAMBridgeParams(reader.axiParams.addressBits - 2, 4, maxBurstLength)

  val io = IO(new Bundle{
    val videoClock = Input(Clock())
    val videoReset = Input(Bool())
    val video = new VideoIO(videoParams.pixelBits)
    val dataInSync = Output(Bool())
    val trigger = Output(Bool())
    val sdrc = new SDRCIO(sdramParams)
    val spi = Flipped(new SPIIO())
    val processorIsBusy = Output(Bool())
  })

  //val tpg = Module(new TestPatternGenerator(params.pixelBits, params.pixelsH, params.pixelsV))
  // Frame trigger from Video 
  val triggerFromVideo = Wire(Bool())
  val trigger = RegInit(false.B)
  val triggerSync = RegInit(false.B)
  triggerSync := triggerFromVideo
  trigger := triggerSync
  io.trigger := trigger
  val sdrc = Module(new SDRCBridge(sdramParams)) 
  val fifo = Module(new AsyncFIFO(new VideoSignal(videoParams.pixelBits), 12)) // 2^12 = 4096 [pixels] depth FIFO
  reader.io.trigger := trigger
  // val scaling = 2
  // reader.io.config.pixelsH := (params.pixelsH / scaling).U
  // reader.io.config.pixelsV := (params.pixelsV / scaling).U
  // reader.io.config.startX := 0.U
  // reader.io.config.startY := 0.U
  // reader.io.config.scaleX := scaling.U
  // reader.io.config.scaleY := scaling.U
  io.sdrc <> sdrc.io.sdrc

  val processorIsBusy = WireDefault(false.B)
  io.processorIsBusy := processorIsBusy

  // SPI Command interface
  val spiSlave = Module(new SPISlave())
  val useTestPattern = false
  val useSimpleWriter = false

  io.spi <> spiSlave.io.spi

  // Video config
  val videoConfig = RegInit(videoConfigType.default(defaultVideoParams))
  val videoConfigValid = RegInit(false.B)

  // Test frame buffer writer
  if( useTestPattern ) {
    val tpg = Module(new TestPatternGenerator(defaultVideoParams.pixelBits, defaultVideoParams.pixelsH, defaultVideoParams.pixelsV))
    val writer = Module(new StreamWriter(defaultVideoParams, sdrc.axi4Params))
    // Full screen transfer
    writer.io.command.valid := true.B
    writer.io.command.bits.addressOffset := 0.U
    writer.io.command.bits.startX := 0.U
    writer.io.command.bits.endXInclusive := (videoParams.pixelsH - 1).U
    writer.io.command.bits.startY := 0.U
    writer.io.command.bits.endYInclusive := (videoParams.pixelsV - 1).U
    writer.io.command.bits.doFill := false.B
    writer.io.command.bits.color := 0.U
    writer.io.data <> tpg.io.data
    sdrc.io.axi.aw.get <> writer.io.axi.aw.get
    sdrc.io.axi.w.get <> writer.io.axi.w.get
    sdrc.io.axi.b.get <> writer.io.axi.b.get
    reader.io.mem.ar.get <> sdrc.io.axi.ar.get
    reader.io.mem.r.get <> sdrc.io.axi.r.get
    spiSlave.io.receive.ready := true.B
    spiSlave.io.send.valid := false.B
    spiSlave.io.send.bits := 0.U

    val scaling = 1
    reader.io.config.pixelsH := (defaultVideoParams.pixelsH / scaling).U
    reader.io.config.pixelsV := (defaultVideoParams.pixelsV / scaling).U
    reader.io.config.startX := 0.U
    reader.io.config.startY := 0.U
    reader.io.config.scaleX := scaling.U
    reader.io.config.scaleY := scaling.U
  } else if( useSimpleWriter ) {
    val simpleWriter = Module(new SimpleWriter(defaultVideoParams, sdrc.axi4Params))
    simpleWriter.io.data <> spiSlave.io.receive
    sdrc.io.axi.aw.get <> simpleWriter.io.axi.aw.get
    sdrc.io.axi.w.get <> simpleWriter.io.axi.w.get
    sdrc.io.axi.b.get <> simpleWriter.io.axi.b.get
    reader.io.mem.ar.get <> sdrc.io.axi.ar.get
    reader.io.mem.r.get <> sdrc.io.axi.r.get
    //
    spiSlave.io.send.valid := false.B
    spiSlave.io.send.bits := 0.U
    //
    val scaling = 1
    reader.io.config.pixelsH := (defaultVideoParams.pixelsH / scaling).U
    reader.io.config.pixelsV := (defaultVideoParams.pixelsV / scaling).U
    reader.io.config.startX := 0.U
    reader.io.config.startY := 0.U
    reader.io.config.scaleX := scaling.U
    reader.io.config.scaleY := scaling.U
  } else {
    // Define video parameters for processor.
    // No front/back porches, double pixel heights for accessing frame buffer and off screen buffer.
    val processorVideoParams = new VideoParams(videoParams.pixelBits, videoParams.backPorchV, videoParams.pixelsV * 2, videoParams.frontPorchV, videoParams.pulseWidthV, videoParams.backPorchH, videoParams.pixelsH, videoParams.frontPorchH, videoParams.pulseWidthH)
    val readerParams = AXI4Params(sdrc.axi4Params.addressBits, sdrc.axi4Params.dataBits, AXI4ReadOnly, sdrc.axi4Params.maxBurstLength)
    val writerParams = AXI4Params(sdrc.axi4Params.addressBits, sdrc.axi4Params.dataBits, AXI4WriteOnly, sdrc.axi4Params.maxBurstLength)
    val processor = Module(new CommandProcessor(processorVideoParams, defaultVideoParams, sdrc.axi4Params))
    val streamReader = Module(new StreamReader(processorVideoParams, readerParams, 8))
    val streamWriter = Module(new StreamWriter(processorVideoParams, writerParams, 8))
    val demux = Module(new AXI4Demux(readerParams, 2))
    
    // Gate frame buffer access from command processor stream reader/writer to ensure the read from frame buffer for video signal generator is not disturbed.
    val enableFrameBufferAccess = !reader.io.active || RegNext(fifo.io.writeHalfFull, false.B)  // Enable frame buffer access if frame buffer reader is inactive and/or the FIFO has enough data.
    demux.io.in(0) <> reader.io.mem
    demux.io.in(1) <> WithAXI4Gate(streamReader.io.axi, enableFrameBufferAccess)
    sdrc.io.axi <> WithAXI4RegSlice(AXIChannelCombine(demux.io.out, WithAXI4Gate(streamWriter.io.axi, enableFrameBufferAccess)))

    val spiSlaveData = Wire(Irrevocable(new SPIData))
    spiSlaveData <> Queue(spiSlave.io.receive, 2048)
    processor.io.data <> WithIrrevocableRegSlice(spiSlaveData)
    spiSlave.io.send <> WithIrrevocableRegSlice(processor.io.result)
    
    streamWriter.io.command <> processor.io.writerCommand
    streamWriter.io.data <> processor.io.writerData
    streamWriter.io.isBusy <> processor.io.writerIsBusy
    streamReader.io.command <> processor.io.readerCommand
    streamReader.io.data <> processor.io.readerData
    streamReader.io.isBusy <> processor.io.readerIsBusy

    reader.io.config <> processor.io.frameBufferConfig

    processorIsBusy := processor.io.isBusy

    // Update video config
    when(!videoConfigValid && processor.io.videoConfig.valid) {
      videoConfig := processor.io.videoConfig.bits
    }
    val videoConfigValid_1 = RegNext(processor.io.videoConfig.valid, false.B)
    val videoConfigValid_2 = RegNext(videoConfigValid_1, false.B)
    videoConfigValid := videoConfigValid_2 | videoConfigValid_1 | processor.io.videoConfig.valid
  }

  fifo.io.writeClock := clock
  fifo.io.writeReset := reset.asBool
  fifo.io.readClock := io.videoClock
  fifo.io.readReset := io.videoReset

  fifo.io.write <> WithIrrevocableRegSlice(reader.io.data)


  withClockAndReset(io.videoClock, io.videoReset) {
    val videoSignalGenerator = Module(new VideoSignalGenerator(defaultVideoParams, videoParams))
    videoSignalGenerator.io.data <> fifo.io.read
    io.dataInSync <> videoSignalGenerator.io.dataInSync
    io.video <> videoSignalGenerator.io.video
    triggerFromVideo := videoSignalGenerator.io.triggerFrame
    videoSignalGenerator.io.config.bits := videoConfig
    videoSignalGenerator.io.config.valid := RegNext(RegNext(videoConfigValid, false.B), false.B)
  }
}

object ElaborateM5StackHDMI extends App {
  (new ChiselStage).emitVerilog(new M5StackHDMI(), Array(
    "-o", "video_generator.v",
    "--target-dir", "rtl/m5stack_hdmi",
    //"--full-stacktrace",
  ))
}