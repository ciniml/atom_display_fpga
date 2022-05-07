// command_processor.scala
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

package command

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import video._
import sdram._
import axi._
import chisel3.experimental.BundleLiterals._
import _root_.util._

class CommandProcessor(videoParams: VideoParams, defaultVideoParams: VideoParams, axiParams: AXI4Params) extends Module {
    assert(videoParams.pixelBits == 24)
    assert(axiParams.dataBits == 32)
    
    object Commands {
        val Nop = 0x00
        val ReadBufCount = 0x02
        val ReadID = 0x04
        val SetScreenScale = 0x18
        val SetScreenOrigin = 0x19
        val CopyRect = 0x23
        val SetResolutionV = 0xb0
        val SetResolutionH = 0xb1
    }

    object CommandTypes {
        val SetCoord = 0x28
        val WriteRaw = 0x40
        val DrawPixel = 0x60
        val FillRect = 0x68
    }
    object CommandNumbers {
        val ColumnAddressSet = 0x02
        val RowAddressSet = 0x03
    }

    val videoSignalType = new VideoSignal(videoParams.pixelBits)
    val videoConfigType = VideoConfig(videoParams)
    val streamReaderCommandType = StreamReaderCommand(videoParams, axiParams)
    val streamWriterCommandType = StreamWriterCommand(videoParams, axiParams)
    val frameBufferConfigType = new FrameBufferReaderConfig(videoParams.pixelBits, videoParams.pixelsH, videoParams.pixelsV)
    val io = IO(new Bundle{
        val data = Flipped(Irrevocable(new SPIData()))
        val result = Irrevocable(UInt(8.W))
        val isBusy = Output(Bool())

        val readerCommand = Irrevocable(streamReaderCommandType)
        val readerData = Flipped(Irrevocable(videoSignalType))
        val readerIsBusy = Input(Bool())

        val writerCommand = Irrevocable(streamWriterCommandType)
        val writerData = Irrevocable(videoSignalType)
        val writerIsBusy = Input(Bool())

        val frameBufferConfig = Output(frameBufferConfigType)
        val videoConfig = Valid(videoConfigType)
    })
    
    val spiDemux = Module(new IrrevocableUnsafeDemux(new SPIData(), 4))
    val spiSelect = WireDefault(0.U)
    spiDemux.io.in <> io.data
    spiDemux.io.select := spiSelect
    
    // Video Config
    val videoConfig = RegInit(videoConfigType.default(defaultVideoParams))
    val videoConfigValid = RegInit(false.B)
    io.videoConfig.bits := videoConfig
    io.videoConfig.valid := videoConfigValid
    videoConfigValid := false.B

    // Pixel data streams
    val pixelDataStreams = 4
    val pixelDataMux = Module(new IrrevocableUnsafeMux(UInt(videoParams.pixelBits.W), pixelDataStreams))
    val pixelDataSelect = WireDefault(pixelDataStreams.U)
    val pixelDataRemainingBytes = RegInit(0.U(log2Ceil(videoParams.frameBytes + 1).W))
    val frameBufferReaderStreamIndex = pixelDataStreams - 1
    
    pixelDataMux.io.select := pixelDataSelect
    //  index 1: RGB332 -> index 0: BGR888
    pixelDataMux.io.in(0).valid <> spiDemux.io.out(1).valid
    pixelDataMux.io.in(0).ready <> spiDemux.io.out(1).ready
    pixelDataMux.io.in(0).bits  := RGB332ToBGR888(spiDemux.io.out(1).bits.data)
    //  index 2: RGB565 -> index 1: BGR888
    val pixelDataWidth8to16 = Module(WidthConverter(8, 16))
    pixelDataWidth8to16.io.enq.valid <> spiDemux.io.out(2).valid
    pixelDataWidth8to16.io.enq.ready <> spiDemux.io.out(2).ready
    pixelDataWidth8to16.io.enq.bits.body <> spiDemux.io.out(2).bits.data
    pixelDataWidth8to16.io.enq.bits.last := false.B
    pixelDataMux.io.in(1).valid <> pixelDataWidth8to16.io.deq.valid
    pixelDataMux.io.in(1).ready <> pixelDataWidth8to16.io.deq.ready
    pixelDataMux.io.in(1).bits := RGB565ToBGR888(SwapByteOrder(pixelDataWidth8to16.io.deq.bits.body, 2))   // WidthConverter puts the bytes from LSB to MSB, so we have to swap bytes to restore RGB565 stream.
    //  index 3: RGB888 -> index 2: BGR888
    val pixelDataWidth8to24 = Module(WidthConverter(8, 24))
    pixelDataWidth8to24.io.enq.valid <> spiDemux.io.out(3).valid
    pixelDataWidth8to24.io.enq.ready <> spiDemux.io.out(3).ready
    pixelDataWidth8to24.io.enq.bits.body <> spiDemux.io.out(3).bits.data
    pixelDataWidth8to24.io.enq.bits.last := false.B
    pixelDataMux.io.in(2).valid <> pixelDataWidth8to24.io.deq.valid
    pixelDataMux.io.in(2).ready <> pixelDataWidth8to24.io.deq.ready
    pixelDataMux.io.in(2).bits := pixelDataWidth8to24.io.deq.bits.body  // WidthConverter puts the bytes from LSB to MSB, so R,G,B,R,... bytes stream is already converted to BGR888 stream.
    //  Frame buffer reader -> index 3: BGR888
    val readerDataQueue = Module(new PacketQueue(Flushable(io.readerData.bits.pixelData), 2048))
    readerDataQueue.io.write.valid := io.readerData.valid
    io.readerData.ready := readerDataQueue.io.write.ready
    readerDataQueue.io.write.bits.body := io.readerData.bits.pixelData
    readerDataQueue.io.write.bits.last := io.readerData.bits.endOfLine

    pixelDataMux.io.in(3).valid <> readerDataQueue.io.read.valid
    pixelDataMux.io.in(3).ready <> readerDataQueue.io.read.ready
    pixelDataMux.io.in(3).bits := readerDataQueue.io.read.bits.body

    // Command stream (index 0)
    val dataReg = WithIrrevocableRegSlice(spiDemux.io.out(0))
    val dataReady = WireDefault(false.B)
    val dataValid = WireDefault(dataReg.valid)
    val dataFirst = WireDefault(dataReg.bits.first)
    val dataBody = WireDefault(dataReg.bits.data)
    dataReg.ready := dataReady

    // Result stream
    val resultValid = RegInit(false.B)
    val resultReady = WireDefault(io.result.ready)
    val resultData = RegInit(0.U(8.W))
    io.result.valid := resultValid
    io.result.bits := resultData
    val resultBusy = WireDefault(false.B)
    val resultOutputValid = RegInit(false.B)
    val resultOutputReady = WireDefault(false.B)
    val resultOutputData = RegInit(0.U(8.W))

    // Reader command
    val readerCommand = Reg(streamReaderCommandType)
    val readerCommandValid = RegInit(false.B)
    val readerCommandReady = io.readerCommand.ready
    io.readerCommand.valid := readerCommandValid
    io.readerCommand.bits := readerCommand
    val readerIsBusyPrev = RegInit(false.B)
    readerIsBusyPrev := io.readerIsBusy
    when(readerCommandValid && readerCommandReady) {
        readerCommandValid := false.B
    }

    // Writer command
    val writerCommand = Reg(streamWriterCommandType)
    val writerCommandValid = RegInit(false.B)
    val writerCommandReady = io.writerCommand.ready
    io.writerCommand.valid := writerCommandValid
    io.writerCommand.bits := writerCommand
    val writerIsBusyPrev = RegInit(false.B)
    writerIsBusyPrev := io.writerIsBusy
    when(writerCommandValid && writerCommandReady) {
        writerCommandValid := false.B
    }

    // Pixel data from pixel data stream
    val pixelDataSlice = WithIrrevocableRegSlice(WithIrrevocableRegSlice(pixelDataMux.io.out))
    io.writerData.valid <> pixelDataSlice.valid 
    io.writerData.ready <> pixelDataSlice.ready 
    io.writerData.bits.pixelData <> pixelDataSlice.bits
    io.writerData.bits.startOfFrame := false.B
    io.writerData.bits.endOfLine := false.B

    def toCommandType(command: UInt): UInt = {
        command & 0xf8.U
    }
    def toCommandNumber(command: UInt): UInt = {
        command & 0x07.U
    }

    // Command and current parameters
    val command = RegInit(0.U(8.W))
    val commandType = toCommandType(command)
    val skipToFirst = RegInit(false.B)
    val currentColor = RegInit(0.U(videoParams.pixelBits.W))
    val currentXStart = RegInit(0.U(videoParams.countHBits.W))
    val currentYStart = RegInit(0.U(videoParams.countHBits.W))
    val currentXEnd = RegInit(0.U(videoParams.countHBits.W))
    val currentYEnd = RegInit(0.U(videoParams.countHBits.W))
    val currentWidth = RegNext(currentXEnd - currentXStart + 1.U)
    val currentHeight = RegNext(currentYEnd - currentYStart + 1.U)
    val currentAreaPixels = RegNext(currentWidth * currentHeight)
    val pixelStreamIndex = RegInit(0.U(log2Ceil(pixelDataMux.n).W))

    // Frame buffer configuration
    val frameBufferConfig = RegInit(WireInit(frameBufferConfigType.Lit(_.startX -> 0.U, _.startY -> 0.U, _.scaleX -> 1.U, _.scaleY -> 1.U, _.pixelsV -> defaultVideoParams.pixelsV.U, _.pixelsH -> defaultVideoParams.pixelsH.U )))
    io.frameBufferConfig := frameBufferConfig

    object State extends ChiselEnum {
        val sClear, sClearWait, sIdle, sParamFetch, sPreDecode, sPostDecode, sExecute, sResult = Value
    }

    val state = RegInit(State.sClear)
    io.isBusy := state =/= State.sIdle

    val maxParameterBytes = 12
    val parameterCounterBits = log2Ceil(maxParameterBytes + 1)
    val params = RegInit(VecInit((0 to maxParameterBytes - 1).map(_ => 0.U(8.W))))
    val paramsFetched = RegInit(0.U(parameterCounterBits.W))
    val remainingParamsToFetch = RegInit(0.U(parameterCounterBits.W))

    // For result 
    val maxResultBytes = 4
    val resultCounterBits = log2Ceil(maxResultBytes + 1)
    val resultsSent = RegInit(0.U(resultCounterBits.W))
    val isLastResult = RegInit(false.B)

    // Parameter decode
    def checkCoordError(x: UInt, y: UInt): Bool = {
        x >= videoParams.pixelsH.U || y >= videoParams.pixelsV.U
    }
    def checkRectError(xStart: UInt, xEnd: UInt, yStart: UInt, yEnd: UInt): Bool = {
        xStart > xEnd || yStart > yEnd || checkRectRangeError(xStart, xEnd, yStart, yEnd)
    }
    def checkRectRangeError(xStart: UInt, xEnd: UInt, yStart: UInt, yEnd: UInt): Bool = {
        xStart >= videoParams.pixelsH.U || xEnd >= videoParams.pixelsH.U || yStart >= videoParams.pixelsV.U || yEnd >= videoParams.pixelsV.U
    }
    val coordStart = WireDefault( (params(0) << 8) | params(1) )
    val coordEnd = WireDefault( (params(2) << 8) | params(3) )

    val xStart = WireDefault( (params(0) << 8) | params(1) )
    val yStart = WireDefault( (params(2) << 8) | params(3) )
    val xEnd = WireDefault( (params(4) << 8) | params(5) )
    val yEnd = WireDefault( (params(6) << 8) | params(7) )
    val fillColorRGB332 = WireDefault(  params(8) )
    val fillColorRGB565 = WireDefault( (params(8) << 8) | params(9) )
    val fillColorRGB888 = WireDefault( (params(8) << 16) | (params(9) << 8) | params(10) )
    val rectRangeError = RegNext(checkRectRangeError(xStart, xEnd, yStart, yEnd))
    val rectError = RegNext(checkRectError(xStart, xEnd, yStart, yEnd))
    val xDestStart = WireDefault( (params(8) << 8) | params(9) )
    val yDestStart = WireDefault( (params(10) << 8) | params(11) )
    val xDestEnd = RegNext(xDestStart + xEnd - xStart)
    val yDestEnd = RegNext(yDestStart + yEnd - yStart)
    val destRangeError = RegNext(checkRectRangeError(xDestStart, xDestEnd, yDestStart, yDestEnd))

    val coordError = RegNext(checkCoordError(xStart, yStart))
    val pixelFillColorRGB332 = WireDefault(  params(4) )
    val pixelFillColorRGB565 = WireDefault( (params(4) << 8) | params(5) )
    val pixelFillColorRGB888 = WireDefault( (params(4) << 16) | (params(5) << 8) | params(6) )

    // Scaling command parameters
    val scalingX = params(0)    // Scaling factor (X)
    val scalingY = params(1)    // Scaling factor (Y)
    val scaledLogicalWidth = (params(2) << 8) | params(3)     // logical screen width after scaled.
    val scaledLogicalHeight = (params(4) << 8) | params(5)    // logical screen height after scaled.
    
    // Screen origin parameters
    val screenOriginX = (params(0) << 8) | params(1)
    val screenOriginY = (params(2) << 8) | params(3)

    // Screen resolution parameters
    val screenResolutionParamsV = WireDefault(videoConfig)
    screenResolutionParamsV.pulseWidthV := params(0) << 8 | params(1)
    screenResolutionParamsV.backPorchV := params(2) << 8 | params(3)
    screenResolutionParamsV.pixelsV := params(4) << 8 | params(5)
    screenResolutionParamsV.frontPorchV := params(6) << 8 | params(7)
    val screenResolutionParamsH = WireDefault(videoConfig)
    screenResolutionParamsH.pulseWidthH := params(0) << 8 | params(1)
    screenResolutionParamsH.backPorchH := params(2) << 8 | params(3)
    screenResolutionParamsH.pixelsH := params(4) << 8 | params(5)
    screenResolutionParamsH.frontPorchH := params(6) << 8 | params(7)
    

    // Parameter checksum
    val parameterChecksum = RegInit(0.U(8.W))
    val checksumIsOk = parameterChecksum === 0xff.U

    // Number of cycles required to complete pre-decode.
    // Some parameters requre to complete calculation for some cycles (e.g. destError requires 2 cycles).
    val preDecodeCycles = 2
    val preDecodeCounter = RegInit(0.U(log2Ceil(preDecodeCycles).W))

    def RGB565ToBGR888(rgb565: UInt): UInt = {
        Cat(rgb565(5-1, 0), rgb565(5-1, 5-3), rgb565(6+5-1, 5), rgb565(6+5-1, 6+5-2), rgb565(5+6+5-1, 6+5), rgb565(5+6+5-1, 5+6+5-3))
    }
    def RGB332ToBGR888(rgb332: UInt): UInt = {
        Cat(rgb332(2-1, 0), rgb332(2-1, 0), rgb332(2-1, 0), rgb332(2-1, 0), rgb332(3+2-1, 2), rgb332(3+2-1, 2), rgb332(3+2-1, 3+2-2), rgb332(3+3+2-1, 3+2), rgb332(3+3+2-1, 3+2), rgb332(3+3+2-1, 3+3+2-2))
    }
    def RGB888ToBGR888(rgb888: UInt): UInt = {
        Cat(rgb888(7, 0), rgb888(15, 8), rgb888(23, 16))
    }
    def SwapByteOrder(value: UInt, bytes: Int): UInt = {
        Cat((0 to bytes-1).map(i => value(8*(i+1)-1, 8*i)))
    }

    switch( state ) {
        is(State.sClear) {
            state := State.sClearWait
            writerCommand.startX := 0.U
            writerCommand.endXInclusive := (videoParams.pixelsH - 1).U
            writerCommand.startY := 0.U
            writerCommand.endYInclusive := (videoParams.pixelsV - 1).U
            writerCommand.doFill := true.B
            writerCommand.color := 0.U
            writerCommandValid := true.B
        }
        is(State.sClearWait) {
            when(writerIsBusyPrev && !io.writerIsBusy) {
                state := State.sIdle
            }
        }
        is(State.sIdle) {
            dataReady := true.B
            preDecodeCounter := (preDecodeCycles - 1).U
            parameterChecksum := dataBody   // Clear checksum
            when( dataValid && (!skipToFirst || dataFirst) ) {
                paramsFetched := 0.U
                val validCommand = WireDefault(false.B)
                val paramsToFetch = WireDefault(0.U(parameterCounterBits.W))
                val resultsToSend = WireDefault(0.U(resultCounterBits.W))
                val commandNumber = WireDefault(toCommandNumber(dataBody))
                when(dataBody === Commands.ReadID.U) {
                    resultsToSend := 4.U
                    validCommand := true.B
                } .elsewhen( dataBody === Commands.CopyRect.U ) { // COPYRECT
                    paramsToFetch := 12.U
                    validCommand := true.B
                } .elsewhen( dataBody === Commands.SetScreenScale.U ) { // SET_SCREEN_SCALE
                    paramsToFetch := 7.U    // [Scale X] [Scale Y] [Logical Width (2byte)] [Logical Height (2byte)] [Check Sum]
                    validCommand := true.B
                } .elsewhen( dataBody === Commands.SetScreenOrigin.U ) { // SET_SCREEN_ORIGIN
                    paramsToFetch := 5.U    // [Screen Origin X (2byte)] [Screen Origin Y (2byte)] [Check Sum]
                    validCommand := true.B
                } .elsewhen( dataBody === Commands.SetResolutionV.U || dataBody === Commands.SetResolutionH.U) {
                    paramsToFetch := 9.U
                    validCommand := true.B
                } .otherwise {
                    
                    switch(toCommandType(dataBody)) {
                        is( CommandTypes.SetCoord.U ) {  // RA_SET, CA_SET
                            when( commandNumber === CommandNumbers.ColumnAddressSet.U || commandNumber === CommandNumbers.RowAddressSet.U ) {
                                paramsToFetch := 4.U
                                validCommand := true.B
                            }
                        }
                        is( CommandTypes.DrawPixel.U ) {  // DRAW_PIXEL
                            when( (dataBody & 7.U) <= 3.U ) {
                                paramsToFetch := 4.U + (dataBody & 7.U)
                                validCommand := true.B
                            }
                        }
                        is( CommandTypes.FillRect.U ) {  // FILL_RECT
                            when( (dataBody & 7.U) <= 3.U ) {
                                paramsToFetch := 8.U + (dataBody & 7.U)
                                validCommand := true.B
                            }
                        }
                        is( CommandTypes.WriteRaw.U ) {  // WRITE_RAW
                            when( 0.U < (dataBody & 7.U) && (dataBody & 7.U) <= 3.U ) {
                                paramsToFetch := 0.U
                                validCommand := true.B
                            }
                        }
                    }
                }
                when(validCommand) {
                    command := dataBody
                    remainingParamsToFetch := paramsToFetch
                    resultsSent := 0.U
                    isLastResult := false.B
                    when(resultsToSend > 0.U) { // This command does not have anything to execute, just returns a result.
                        state := State.sResult
                    } .elsewhen( paramsToFetch === 0.U ) {  // This command does not have any parameters. Transit to PreDecode.
                        state := State.sPreDecode
                    } .otherwise {
                        state := State.sParamFetch
                    }
                }
                skipToFirst := !validCommand
            }
        }
        is(State.sParamFetch) {
            dataReady := true.B
            when( dataValid ) {
                parameterChecksum := parameterChecksum + dataBody
                params(paramsFetched) := dataBody
                paramsFetched := paramsFetched + 1.U
                remainingParamsToFetch := remainingParamsToFetch - 1.U
                when( remainingParamsToFetch === 1.U ) {
                    state := State.sPreDecode
                }
            }
        }
        is(State.sPreDecode) {
            preDecodeCounter := preDecodeCounter - 1.U
            when(preDecodeCounter === 0.U) {
                state := State.sPostDecode
            }
        }
        is(State.sPostDecode) {
            val commandType = WireDefault(toCommandType(command))
            val commandNumber = WireDefault(toCommandNumber(command))
            when(command === Commands.CopyRect.U ) {    // COPYRECT
                when( rectRangeError || destRangeError )  {
                    state := State.sIdle
                    skipToFirst := true.B
                } .otherwise {
                    state := State.sExecute
                    // Read command
                    readerCommand.startX := xStart
                    readerCommand.endXInclusive := xEnd
                    readerCommand.startY := yStart
                    readerCommand.endYInclusive := yEnd 
                    readerCommandValid := true.B
                    // Writer command
                    writerCommand.startX := xDestStart
                    writerCommand.endXInclusive := xDestEnd
                    writerCommand.startY := yDestStart
                    writerCommand.endYInclusive := yDestEnd
                    writerCommand.doFill := false.B
                    writerCommand.color := 0.U
                    writerCommandValid := true.B
                    //
                    pixelStreamIndex := frameBufferReaderStreamIndex.U // Input from the reader.
                }
            } .elsewhen(command === Commands.SetScreenScale.U ) { // SET_SCREEN_SCALE
                when( !checksumIsOk ) {
                    state := State.sIdle
                    skipToFirst := true.B
                } .otherwise {
                    state := State.sIdle
                    // Update scaling configuration
                    frameBufferConfig.scaleX := scalingX
                    frameBufferConfig.scaleY := scalingY
                    frameBufferConfig.pixelsH := scaledLogicalWidth
                    frameBufferConfig.pixelsV := scaledLogicalHeight
                }
            } .elsewhen(command === Commands.SetScreenOrigin.U ) { // SET_SCREEN_ORIGIN
                when( !checksumIsOk ) {
                    state := State.sIdle
                    skipToFirst := true.B
                } .otherwise {
                    state := State.sIdle
                    // Update screen configuration
                    frameBufferConfig.startX := screenOriginX
                    frameBufferConfig.startY := screenOriginY
                }
            } .elsewhen(command === Commands.SetResolutionV.U || command === Commands.SetResolutionH.U ) { // SET_RESOLUTION
                when( !checksumIsOk ) {
                    state := State.sIdle
                    skipToFirst := true.B
                } .otherwise {
                    state := State.sIdle
                    // Update screen resolution
                    when( command === Commands.SetResolutionV.U ) {
                        videoConfig := screenResolutionParamsV
                    } .otherwise {
                        videoConfig := screenResolutionParamsH
                        videoConfigValid := true.B
                    }
                }
            } .otherwise {
                switch( commandType ) {
                    is( CommandTypes.SetCoord.U ) {  // CA_SET, RA_SET
                        state := State.sIdle
                        when( commandNumber === CommandNumbers.ColumnAddressSet.U ) {
                            currentXStart := coordStart
                            currentXEnd := coordEnd
                        } .otherwise {
                            currentYStart := coordStart
                            currentYEnd := coordEnd
                        }
                    }
                    is( CommandTypes.DrawPixel.U ) {  // DRAW_PIXEL
                        when( coordError ) {
                            state := State.sIdle
                            skipToFirst := true.B
                        } .otherwise {
                            state := State.sExecute
                            val newColor = MuxLookup(command & 7.U, currentColor, Seq(0.U -> currentColor, 1.U -> RGB332ToBGR888(pixelFillColorRGB332), 2.U -> RGB565ToBGR888(pixelFillColorRGB565), 3.U -> RGB888ToBGR888(pixelFillColorRGB888)))
                            writerCommand.startX := xStart
                            writerCommand.endXInclusive := xStart
                            writerCommand.startY := yStart
                            writerCommand.endYInclusive := yStart
                            writerCommand.doFill := true.B
                            writerCommand.color := newColor
                            currentColor := newColor
                            writerCommandValid := true.B
                        }
                    }
                    is( CommandTypes.FillRect.U ) {  // FILL_RECT
                        when( rectError ) {
                            state := State.sIdle
                            skipToFirst := true.B
                        } .otherwise {
                            state := State.sExecute
                            val newColor = MuxLookup(command & 7.U, currentColor, Seq(0.U -> currentColor, 1.U -> RGB332ToBGR888(fillColorRGB332), 2.U -> RGB565ToBGR888(fillColorRGB565), 3.U -> RGB888ToBGR888(fillColorRGB888)))
                            writerCommand.startX := xStart
                            writerCommand.endXInclusive := xEnd
                            writerCommand.startY := yStart
                            writerCommand.endYInclusive := yEnd
                            writerCommand.doFill := true.B
                            writerCommand.color := newColor
                            currentColor := newColor
                            writerCommandValid := true.B
                        }
                    }
                    is( CommandTypes.WriteRaw.U ) {  // WRITE_RAW
                        state := State.sExecute
                        writerCommand.startX := currentXStart
                        writerCommand.endXInclusive := currentXEnd
                        writerCommand.startY := currentYStart
                        writerCommand.endYInclusive := currentYEnd
                        writerCommand.doFill := false.B
                        writerCommand.color := 0.U
                        writerCommandValid := true.B
                        pixelStreamIndex := commandNumber - 1.U // Select pixel stream
                        pixelDataRemainingBytes := MuxLookup(commandNumber, 0.U, Seq(1.U -> currentAreaPixels, 2.U -> currentAreaPixels*2.U, 3.U -> currentAreaPixels*3.U))
                    }
                }
            }
        }
        is(State.sExecute) {
            when( spiDemux.io.in.valid && spiDemux.io.in.ready && pixelDataRemainingBytes > 0.U) {
                pixelDataRemainingBytes := pixelDataRemainingBytes - 1.U
            }
            when(command === Commands.CopyRect.U) {
                pixelDataSelect := frameBufferReaderStreamIndex.U   // Input from the frame buffer reader
                when( (readerIsBusyPrev || writerIsBusyPrev) && !(io.readerIsBusy || io.writerIsBusy) ) {
                    // Both reader and writer has finished its work at this cycle.
                    state := State.sIdle
                }
            } .otherwise {
                switch( commandType ) {
                    is( CommandTypes.DrawPixel.U ) {  // DRAW_PIXEL
                        when(writerIsBusyPrev && !io.writerIsBusy) {
                            state := State.sIdle
                        }
                    }
                    is( CommandTypes.FillRect.U ) {  // FILL_RECT
                        when(writerIsBusyPrev && !io.writerIsBusy) {
                            state := State.sIdle
                        }
                    }
                    is( CommandTypes.WriteRaw.U ) {  // WRITE_RAW
                        when(pixelDataRemainingBytes > 0.U) {
                            spiSelect := pixelStreamIndex + 1.U // Input pixel data to pixel stream.
                        }
                        pixelDataSelect := pixelStreamIndex
                        when(writerIsBusyPrev && !io.writerIsBusy) {
                            state := State.sIdle
                        }
                    }
                }
            }
        }
        is(State.sResult) {
            when( resultOutputValid && resultOutputReady ) {
                resultOutputValid := false.B
                when( isLastResult ) {
                    state := State.sIdle
                }
            }
            when( command === Commands.ReadID.U ) {
                when( (!resultOutputValid || resultOutputReady) && resultsSent < 4.U ) {
                    resultOutputValid := true.B
                    resultOutputData := MuxLookup(resultsSent, 0.U, Seq(0.U -> 'H'.U, 1.U -> 'D'.U, 2.U -> 0.U, 3.U -> 3.U))
                    resultsSent := resultsSent + 1.U
                    isLastResult := resultsSent === 3.U
                }
            }
        }
    }

    // Result process
    val isPrevStateResult = RegNext(state === State.sResult, false.B)
    resultBusy := (state =/= State.sIdle && (state =/= State.sResult || !isPrevStateResult)) || (io.data.valid && state === State.sIdle && io.data.bits.data =/= 0x00.U)
    object ResultState extends ChiselEnum {
        val sIdle, sEnsureSendBusy, sBusy = Value
    }
    val resultState = RegInit(ResultState.sIdle)
    when(resultValid && resultReady) {
        resultValid := false.B
    }
    switch(resultState) {
        is( ResultState.sIdle ) {
            when(resultBusy) {
                when( !resultValid || resultReady ) {
                    resultData := 0x00.U    // BUSY
                    resultValid := true.B
                    resultState := ResultState.sBusy
                } .otherwise {
                    resultState := ResultState.sEnsureSendBusy
                }
            } .otherwise {
                when( !resultValid || resultReady ) {
                    resultValid := true.B
                    resultOutputReady := !resultValid || resultReady
                    when( resultOutputValid && resultOutputReady ) {
                        resultData := resultOutputData
                    } .otherwise {
                        resultData := 0xff.U    // IDLE
                    }
                }
            }
        }
        is( ResultState.sEnsureSendBusy ) {
            when( !resultValid || resultReady ) {
                resultData := 0x00.U    // BUSY
                resultValid := true.B
                resultState := ResultState.sBusy
            }
        }
        is( ResultState.sBusy ) {
            when( !resultValid || resultReady ) {
                resultValid := true.B
                resultData := Mux(resultBusy, 0x00.U, 0xff.U)
                when( !resultBusy ) {
                    resultState := ResultState.sIdle
                }
            }
        }
    }
}