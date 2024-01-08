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
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._
import java.io.FileInputStream
import scala.collection.mutable
import _root_.util.AsyncFIFO
import sdram.SimSDRC
import video.{VideoParams, VideoSignal, VideoIO}
import spi.SPIIO
import _root_.util.Flushable
import scala.util.Random

class TestSPITransmitter() extends Module {
    val io = IO(new Bundle{
        val spi = SPIIO()
        val data = Flipped(Irrevocable(Flushable(UInt(8.W))))
    })

    val bitCount = RegInit(0.U(3.W))
    val spiClock = RegInit(true.B)
    val spiCs = RegInit(true.B)
    val spiMosi = WireDefault(false.B)
    val spiMiso = io.spi.miso
    val spiBuffer = RegInit(0.U(8.W))

    io.spi.cs := spiCs
    io.spi.mosi := spiMosi
    io.spi.sck := spiClock

    val dataLast = RegInit(false.B)
    val dataReady = WireDefault(false.B)
    io.data.ready := dataReady

    object State extends ChiselEnum {
        val Start, CheckBusy, StartCommand, NextByte, Transmit, Interval = Value
    }
    val state = RegInit(State.Start)

    switch(state) {
        is(State.Start) {
            spiCs := false.B
            bitCount := 0.U
            spiClock := true.B
            state := State.CheckBusy
        }
        is(State.CheckBusy) {
            spiClock := !spiClock
            spiMosi := true.B
            when(!spiClock) {
                bitCount := bitCount + 1.U
                val nextBuffer = Cat(spiBuffer(6, 0), spiMiso)
                spiBuffer := nextBuffer
                when(bitCount === 7.U) {
                    bitCount := 0.U
                    when(nextBuffer === 0xff.U) {
                        spiCs := true.B
                        state := State.StartCommand
                    }
                }
            }
        }
        is(State.StartCommand) {
            bitCount := bitCount + 1.U
            when(bitCount === 7.U) {
                state := State.NextByte
            }
        }
        is(State.NextByte) {
            spiCs := false.B
            dataReady := true.B
            spiMosi := spiBuffer(7)
            when(io.data.valid) {
                bitCount := 0.U
                spiBuffer := io.data.bits.data
                dataLast := io.data.bits.last
                state := State.Transmit
            }
        }
        is(State.Transmit) {
            spiClock := !spiClock
            spiMosi := spiBuffer(7)
            when(spiClock) {
                when(bitCount =/= 0.U) {
                    spiBuffer := Cat(spiBuffer(6, 0), spiMiso)
                }
            } .otherwise {
                bitCount := bitCount + 1.U
                when(bitCount === 7.U) {
                    when(dataLast) {
                        spiCs := true.B
                        state := State.Interval
                    } .otherwise {
                        state := State.NextByte
                    }
                }
            }
        }
        is(State.Interval) {
            bitCount := bitCount + 1.U
            when(bitCount === 7.U) {
                state := State.Start
            }
        }
    }
}

class M5StackHDMITestSystem() extends Module {
    val pixelBits = system.PresetVideoParams.pixelBits
    val io = IO(new Bundle {
        val dataInSync = Output(Bool())
        val video = new VideoIO(pixelBits)
        val command = Flipped(Irrevocable(Flushable(UInt(8.W))))
        val frameCounter = Output(UInt(32.W))
    })

    val testVideoParams = PresetVideoParams.Default_1280_720_60 //new VideoParams(pixelBits, 2, 4, 2, 2, 2, 1280, 4, 4)

    val dut = Module(new M5StackHDMI(defaultVideoParams = testVideoParams, useProbe = false, skipFrameBufferInitialization = true, disableDebugMessage = true))
    dut.io.videoClock := clock
    dut.io.videoReset := reset

    // Simulation memory
    val sdrc = Module(new SimSDRC(dut.sdramParams, 8*1024*1024/4, disableDebugMessage = true))
    dut.io.sdrc <> sdrc.io.sdrc

    val spiTransmitter = Module(new TestSPITransmitter())
    spiTransmitter.io.data <> io.command
    dut.io.spi <> spiTransmitter.io.spi
    
    dut.io.debugIn := 0.U

    io.dataInSync := dut.io.dataInSync
    io.video <> dut.io.video

    val frameCounter = RegInit(0.U(32.W)) 
    val vsyncPrev = RegNext(dut.io.video.vSync, false.B)
    when(!vsyncPrev && dut.io.video.vSync) {
        frameCounter := frameCounter + 1.U
    }
    io.frameCounter := frameCounter
}

class M5StackHDMITester extends AnyFlatSpec with ChiselScalatestTester with Matchers {
    val dutName = "M5StackHDMI"
    behavior of dutName

    it should "run_one_frame" in {
        test(new M5StackHDMITestSystem).withAnnotations(Seq(VerilatorBackendAnnotation, WriteFstAnnotation)) { c =>
            c.io.command.initSource().setSourceClock(c.clock)
            
            val totalCount = c.testVideoParams.totalCountsH*c.testVideoParams.totalCountsV
            c.clock.setTimeout(totalCount * 2)
            c.clock.step(1)
            while(!c.io.dataInSync.peek().litToBoolean) {
                c.clock.step(1)
            }
            c.io.dataInSync.expect((true.B))

            fork {
                (0 to c.testVideoParams.totalCountsH*c.testVideoParams.totalCountsV).foreach(i => {
                    c.io.dataInSync.expect(true.B, f"Data not sync at ${i}")
                    c.clock.step(1)
                })
            } .fork {
                val random = new Random()
                val break = new Breaks
                break.breakable {
                    for(i <- 0 to 100) {
                        val frameCounter = c.io.frameCounter.peek()
                        if( frameCounter == 2 ) {
                            break.break()
                        }
                        val doCopyRect = random.nextBoolean()
                        if( doCopyRect ) {
                            // Random copy rect
                            val startX = random.nextInt(1280/4)
                            val startY = random.nextInt(720/4)
                            val endX = random.nextInt(32) + 1 + startX
                            val endY = random.nextInt(32) + 1 + startY
                            val destStartX = random.nextInt(1280 - (endX + 1))
                            val destStartY = random.nextInt(720 - (endY + 1)) 
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> 0x23.U, _.last -> false.B))   // CopyRect
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (startX >> 8).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (startX & 0xff).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (startY >> 8).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (startY & 0xff).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (endX >> 8).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (endX & 0xff).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (endY >> 8).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (endY & 0xff).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (destStartX >> 8).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (destStartX & 0xff).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (destStartY >> 8).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (destStartY & 0xff).U, _.last -> true.B))
                            println(f"## ${i} COPYRECT ${startX} ${startY} ${endX} ${endY} ${destStartX} ${destStartY} \n")

                        } else {
                            // Random fill rect
                            val startX = random.nextInt(1280/2)
                            val startY = random.nextInt(720/2)
                            val endX = random.nextInt(64) + 1 + startX
                            val endY = random.nextInt(64) + 1 + startY
                            val color = random.nextInt(65536)
                            
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> 0x6a.U, _.last -> false.B))   // FillRect, RGB565
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (startX >> 8).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (startX & 0xff).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (startY >> 8).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (startY & 0xff).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (endX >> 8).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (endX & 0xff).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (endY >> 8).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (endY & 0xff).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (color >> 8).U, _.last -> false.B))
                            c.io.command.enqueue(Flushable(8.W).Lit(_.data -> (color & 0xff).U, _.last -> true.B))
                            println(f"## ${i} FILLRECT ${startX} ${startY} ${endX} ${endY} ${color} \n")
                        }
                    }
                }
            } .join()
        }
    }
}
