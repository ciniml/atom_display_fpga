// m5stackhdmi.scala
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
import spi.SPISlave
import spi.SPIData
import spi.SPIIO

object PresetVideoParams {
  val Default_1280_720_60 = new VideoParams(24, 20, 720, 5, 5, 220, 1280, 110, 40)
  val Default_1920_1080_30 = new VideoParams(24, 36, 1080, 4, 5, 148, 1920, 88, 44)
  val Generic_1024_768_60 = new VideoParams(24, 63, 768, 64, 5, 117, 1024, 117, 117)  // 351, 132
  val TwiHai_480_1920_60 = new VideoParams(24, 20, 1920, 10, 5, 51, 480, 51, 51)
  val Circular_480_480_60 = new VideoParams(24, 135, 480, 130, 5, 390, 480, 390, 390)
  val LowPixelClock_640_480_60_CVT = new VideoParams(24, 13, 480, 3, 4, 80, 640, 16, 64)  // Pixel clock = 23.75, (actual clock is 23.625)
  val LowPixelClock_640_480_60_CEA_861 = new VideoParams(24, 33, 480, 10, 2, 48, 640, 16, 96)  // Pixel clock = 25.175, (actual clock is 25.18)
  val LowPixelClock_ARGlass_640_400_59p94 = new VideoParams(24, 32, 400, 87, 6, 58, 640, 96, 64)  // 640x400 59.94Hz at Pixel clock = 27.000
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
    val videoClockConfig = Output(VideoClockConfig())
    val videoClockConfigValid = Output(Bool())
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
  val useTestPattern = false      // Write test pattern to SDRAM, then read it by FrameBufferReader.
  val useTestPatternDirect = false // Test pattern generator directly connected to async FIFO. not using SDRC, FrameBufferReader.
  val useSimpleWriter = false
  val enableCopyRect = false

  io.spi <> spiSlave.io.spi

  // Video config
  val videoConfig = RegInit(videoConfigType.default(defaultVideoParams))
  val videoConfigValid = RegInit(false.B)

  // Video clock config
  val videoClockConfig = RegInit(VideoClockConfig.default())
  val videoClockConfigValid = RegInit(false.B)

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
  } else if( !useTestPatternDirect ) {
    // Define video parameters for processor.
    // No front/back porches, double pixel heights for accessing frame buffer and off screen buffer.
    val processorVideoParams = new VideoParams(videoParams.pixelBits, videoParams.backPorchV, videoParams.pixelsV * 2, videoParams.frontPorchV, videoParams.pulseWidthV, videoParams.backPorchH, videoParams.pixelsH, videoParams.frontPorchH, videoParams.pulseWidthH)
    val readerParams = AXI4Params(sdrc.axi4Params.addressBits, sdrc.axi4Params.dataBits, AXI4ReadOnly, sdrc.axi4Params.maxBurstLength)
    val writerParams = AXI4Params(sdrc.axi4Params.addressBits, sdrc.axi4Params.dataBits, AXI4WriteOnly, sdrc.axi4Params.maxBurstLength)
    val processor = Module(new CommandProcessor(processorVideoParams, defaultVideoParams, sdrc.axi4Params, enableCopyRect))
    val streamReader = if(enableCopyRect) { Some(Module(new StreamReader(processorVideoParams, readerParams, 8))) } else { None }
    val streamWriter = Module(new StreamWriter(processorVideoParams, writerParams, 8))
    
    // Gate frame buffer access from command processor stream reader/writer to ensure the read from frame buffer for video signal generator is not disturbed.
    val enableFrameBufferAccess = !reader.io.active || RegNext(fifo.io.writeHalfFull, false.B)  // Enable frame buffer access if frame buffer reader is inactive and/or the FIFO has enough data.
    
    if( enableCopyRect ) {
      val demux = Module(new AXI4Demux(readerParams, 2))
      demux.io.in(0) <> reader.io.mem
      demux.io.in(1) <> WithAXI4Gate(streamReader.get.io.axi, enableFrameBufferAccess)
      sdrc.io.axi <> WithAXI4RegSlice(AXIChannelCombine(demux.io.out, WithAXI4Gate(streamWriter.io.axi, enableFrameBufferAccess)))
    } else {
      sdrc.io.axi <> WithAXI4RegSlice(AXIChannelCombine(reader.io.mem, WithAXI4Gate(streamWriter.io.axi, enableFrameBufferAccess)))
    }

    val spiSlaveData = Wire(Irrevocable(new SPIData))
    spiSlaveData <> Queue(spiSlave.io.receive, 2048)
    processor.io.data <> WithIrrevocableRegSlice(spiSlaveData)
    spiSlave.io.send <> WithIrrevocableRegSlice(processor.io.result)
    
    streamWriter.io.command <> processor.io.writerCommand
    streamWriter.io.data <> processor.io.writerData
    streamWriter.io.isBusy <> processor.io.writerIsBusy
    if( enableCopyRect ) {
      streamReader.get.io.command <> processor.io.readerCommand
      streamReader.get.io.data <> processor.io.readerData
      streamReader.get.io.isBusy <> processor.io.readerIsBusy
    } else {
      processor.io.readerCommand.ready := false.B
      processor.io.readerData.valid := false.B
      processor.io.readerData.bits.endOfLine := false.B
      processor.io.readerData.bits.startOfFrame := false.B
      processor.io.readerData.bits.pixelData := 0.U
      processor.io.readerIsBusy := true.B
    }

    reader.io.config <> processor.io.frameBufferConfig

    processorIsBusy := processor.io.isBusy

    // Update video config
    when(!videoConfigValid && processor.io.videoConfig.valid) {
      videoConfig := processor.io.videoConfig.bits
    }
    val videoConfigValid_1 = RegNext(processor.io.videoConfig.valid, false.B)
    val videoConfigValid_2 = RegNext(videoConfigValid_1, false.B)
    videoConfigValid := videoConfigValid_2 | videoConfigValid_1 | processor.io.videoConfig.valid

    // Update video clock config
    when(!videoClockConfigValid && processor.io.videoClockConfig.valid) {
      videoClockConfig := processor.io.videoClockConfig.bits
    }
    videoClockConfigValid := processor.io.videoClockConfig.valid
  }

  io.videoClockConfig := videoClockConfig
  io.videoClockConfigValid := videoClockConfigValid

  fifo.io.writeClock := clock
  fifo.io.writeReset := reset.asBool
  fifo.io.readClock := io.videoClock
  fifo.io.readReset := io.videoReset

  if( useTestPatternDirect ) {
    val tpg = Module(new TestPatternGenerator(defaultVideoParams.pixelBits, defaultVideoParams.pixelsH, defaultVideoParams.pixelsV))
    fifo.io.write <> WithIrrevocableRegSlice(tpg.io.data)
    sdrc.io.axi.ar.get.valid := false.B
    sdrc.io.axi.ar.get.bits.addr := 0.U
    sdrc.io.axi.ar.get.bits.len.get := 0.U
    sdrc.io.axi.aw.get.valid := false.B
    sdrc.io.axi.aw.get.bits.addr := 0.U
    sdrc.io.axi.aw.get.bits.len.get := 0.U
    sdrc.io.axi.w.get.valid := false.B
    sdrc.io.axi.w.get.bits.data := 0.U
    sdrc.io.axi.w.get.bits.last.get := false.B
    sdrc.io.axi.w.get.bits.strb := 0.U
    sdrc.io.axi.r.get.ready := false.B
    sdrc.io.axi.b.get.ready := false.B
    
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

    reader.io.mem.r.get.bits.last.get := false.B
    reader.io.mem.r.get.bits.data := 0.U
    reader.io.mem.r.get.bits.resp := AXI4Resp.OKAY
    reader.io.mem.r.get.valid := false.B
    reader.io.mem.ar.get.ready := false.B

    reader.io.data.ready := false.B
  } else {
    fifo.io.write <> WithIrrevocableRegSlice(reader.io.data)
  }


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