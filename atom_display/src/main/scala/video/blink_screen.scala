// SPDX-License-Identifier: BSL-1.0
// Copyright Kenta Ida 2022.
// Distributed under the Boost Software License, Version 1.0.
//    (See accompanying file LICENSE_1_0.txt or copy at
//          https://www.boost.org/LICENSE_1_0.txt)

package video

import chisel3._
import chisel3.util._

/**
  * A test pattern generator which generates video timing by itself. 
  * This module outputs video signal which shows colored tiles and bouncing box.
  * @param videoParams
  * @param frameRate Frame rate
  * @param rectSize bouncing box size in pixels.
  */
class BlinkScreenGenerator(videoParams: VideoParams, frameRate: Int = 60, rectSize: Int = 128) extends Module {
    val io = IO(new Bundle {
        val video = new VideoIO(videoParams.pixelBits)
        val blink = Output(Bool())
    })

    val totalCountsHMinus1 = videoParams.totalCountsH.U - 1.U
    val totalCountsVMinus1 = videoParams.totalCountsV.U - 1.U 
    val activeHLower = (videoParams.pulseWidthH + videoParams.backPorchH).U
    val activeHUpper = (videoParams.pulseWidthH + videoParams.backPorchH + videoParams.pixelsH).U
    val activeVLower = (videoParams.pulseWidthV + videoParams.backPorchV).U
    val activeVUpper = (videoParams.pulseWidthV + videoParams.backPorchV + videoParams.pixelsV).U

    val rectL = 0.U
    val rectT = 0.U
    val rectR = rectL + rectSize.U
    val rectB = rectT + rectSize.U

    val counterH = RegInit(0.U(videoParams.countHBits.W))
    val counterV = RegInit(0.U(videoParams.countVBits.W))
    val data = RegInit(0.U(videoParams.pixelBits.W))
    val dataEnable = Wire(Bool())
    val dataEnableReg = RegInit(false.B)
    val hSync = RegInit(true.B)
    val vSync = RegInit(true.B)
    val frameUpdate = WireDefault(false.B)

    when(counterH < totalCountsHMinus1) {
        counterH := counterH + 1.U
    } .otherwise {
        counterH := 0.U
        when( counterV < totalCountsVMinus1 ) {
            counterV := counterV + 1.U
        } .otherwise {
            counterV := 0.U
            frameUpdate := true.B
        }
    }
    data := 0xffff00.U
    dataEnableReg := dataEnable

    val frameCounter = RegInit(0.U(log2Ceil(frameRate).W))
    val blink = RegNext(frameCounter < (frameRate/2).U, false.B)
    when(frameUpdate) {
        when(frameCounter === (frameRate - 1).U ) {
            frameCounter := 0.U
        } .otherwise {
            frameCounter := frameCounter + 1.U
        }
    }

    def toPixelValue(value: UInt): UInt = {
        if( videoParams.pixelBits == 24 ) {         // 24bpp BGR888
            value
        } else if( videoParams.pixelBits == 16 ) {  // 1bpp BGR565
            Cat(value(23, 16+3), value(15, 8+2), value(7, 0+3))
        } else {                        // 8bpp BGR233
            Cat(value(23, 16+6), value(15, 8+5), value(7, 0+5))
        }
    }

    dataEnable := activeHLower <= counterH && counterH < activeHUpper &&
                  activeVLower <= counterV && counterV < activeVUpper
    
    val activeCounterH = Mux(dataEnable, counterH - activeHLower, 0.U)
    val activeCounterV = Mux(dataEnable, counterV - activeVLower, 0.U)

    when( blink && rectL <= activeCounterH && activeCounterH < rectR && rectT <= activeCounterV && activeCounterV < rectB ) {
        data := toPixelValue("xffffff".U)
    } .otherwise {
        data := toPixelValue("x000000".U)
    }
    
    hSync := !(counterH < videoParams.pulseWidthH.U)
    vSync := !(counterV < videoParams.pulseWidthV.U)
    
    io.blink := blink
    io.video.hSync := hSync
    io.video.vSync := vSync
    io.video.dataEnable := dataEnableReg
    io.video.pixelData := Mux(dataEnable, data, 0.U)
}