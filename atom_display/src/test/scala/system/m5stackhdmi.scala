// SPDX-License-Identifier: BSL-1.0
// Copyright Kenta Ida 2024.
// Distributed under the Boost Software License, Version 1.0.
//    (See accompanying file LICENSE_1_0.txt or copy at
//          https://www.boost.org/LICENSE_1_0.txt)

package system

import chiseltest._
import scala.util.control.Breaks
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers 

import chisel3._
import chisel3.util._
import java.io.FileInputStream
import scala.collection.mutable
import _root_.util.AsyncFIFO
import sdram.SimSDRC
import video.VideoParams
import video.VideoSignal
import video.VideoIO

class M5StackHDMITestSystem() extends Module {
    val pixelBits = system.PresetVideoParams.pixelBits
    val io = IO(new Bundle {
        val dataInSync = Output(Bool())
        val video = new VideoIO(pixelBits)
    })

    val testVideoParams = PresetVideoParams.Default_1280_720_60 //new VideoParams(pixelBits, 2, 4, 2, 2, 2, 1280, 4, 4)

    val dut = Module(new M5StackHDMI(defaultVideoParams = testVideoParams, useProbe = false, skipFrameBufferInitialization = true, disableDebugMessage = true))
    dut.io.videoClock := clock
    dut.io.videoReset := reset

    // Simulation memory
    val sdrc = Module(new SimSDRC(dut.sdramParams, 8*1024*1024/4, disableDebugMessage = true))
    dut.io.sdrc <> sdrc.io.sdrc

    dut.io.spi.cs := true.B
    dut.io.spi.mosi := true.B
    dut.io.spi.sck := true.B
    
    dut.io.debugIn := 0.U

    io.dataInSync := dut.io.dataInSync
    io.video <> dut.io.video
}

class M5StackHDMITester extends AnyFlatSpec with ChiselScalatestTester with Matchers {
    val dutName = "M5StackHDMI"
    behavior of dutName

    it should "run_one_frame" in {
        test(new M5StackHDMITestSystem).withAnnotations(Seq(VerilatorBackendAnnotation, WriteFstAnnotation)) { c =>
            val totalCount = c.testVideoParams.totalCountsH*c.testVideoParams.totalCountsV
            c.clock.setTimeout(totalCount * 2)
            c.clock.step(1)
            while(!c.io.dataInSync.peek().litToBoolean) {
                c.clock.step(1)
            }
            c.io.dataInSync.expect((true.B))
            (0 to c.testVideoParams.totalCountsH*c.testVideoParams.totalCountsV).foreach(i => {
                c.io.dataInSync.expect(true.B, f"Data not sync at ${i}")
                c.clock.step(1)
            })
        }
    }
}
