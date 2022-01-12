// simple_writer.scala
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
import video.VideoParams
import sdram.SDRAMBridgeParams
import axi._

class SimpleWriter(videoParams: VideoParams, axiParams: AXI4Params) extends Module {

    val io = IO(new Bundle{
        val data = Flipped(Decoupled(new SPIData()))
        val axi = new AXI4IO(new AXI4Params(axiParams.addressBits, axiParams.dataBits, AXI4WriteOnly, axiParams.maxBurstLength))
    })
    val addressBits = log2Ceil(videoParams.frameBytes) + 1
    val addressCounter = RegInit(0.U(addressBits.W))
    val cancelOngoingTransfer = RegInit(false.B)
    val writeBurstPixels = 256
    val writeBurstLength = writeBurstPixels * 2 / 4
    val pixelCounter = RegInit(0.U(16.W))
    val pixelsRemaining = RegInit(0.U(log2Ceil(writeBurstPixels + 1).W))
    val awValid = RegInit(false.B)
    val awReady = Wire(Bool())
    val awAddr = RegInit(0.U(addressBits.W))
    val wValid = RegInit(false.B)
    val wReady = Wire(Bool())
    val wData = RegInit(0.U(32.W))
    val wLast = RegInit(false.B)
    val dataFifoEnq = Wire(Decoupled(UInt(32.W)))
    
    val dataFifo = withReset(this.reset.asBool() || cancelOngoingTransfer) { Queue(dataFifoEnq, 16) }

    // width converter
    val dataFifoData = Reg(UInt(32.W))
    val dataFifoValid = RegInit(false.B)
    val dataFifoPhase = RegInit("b0001".U(4.W))
    when( dataFifoValid ) {
        dataFifoValid := false.B
    }
    when( io.data.valid && io.data.ready ) {
        dataFifoData := Cat(io.data.bits.data, dataFifoData(31, 8))
        when( io.data.bits.first ) {
            dataFifoPhase := "b0010".U
            cancelOngoingTransfer := pixelsRemaining > 0.U
        } .otherwise {
            dataFifoPhase := Mux(dataFifoPhase(3), "b0001".U, dataFifoPhase << 1.U)
        }
        dataFifoValid := dataFifoPhase(3)
    }
    io.data.ready := !cancelOngoingTransfer && (!dataFifo.valid || dataFifoEnq.ready)
    dataFifoEnq.valid := dataFifoValid
    dataFifoEnq.bits := dataFifoData

    when(awValid && awReady) {
        awValid := false.B
    }

    when( io.data.valid && io.data.bits.first ) {
        addressCounter := 0.U
    } .elsewhen(addressCounter < videoParams.frameBytes.U) {
        when(pixelsRemaining === 0.U && (!awValid || awReady)) {
            awAddr := addressCounter
            awValid := true.B
            addressCounter := addressCounter + (writeBurstPixels * 2).U
            pixelsRemaining := writeBurstPixels.U
        }
    } .otherwise {
        addressCounter := 0.U
    }

    when(wValid && wReady) {
        wValid := false.B
    }
    when(pixelsRemaining > 0.U && (dataFifo.valid && dataFifo.ready || cancelOngoingTransfer)) {
        wData := dataFifo.bits
        wLast := pixelsRemaining === 2.U
        wValid := true.B
        pixelsRemaining := pixelsRemaining - 2.U
        when( pixelsRemaining === 2.U ) {
            cancelOngoingTransfer := false.B
        }
    }
    dataFifo.ready := !wValid || wReady
    

    val aw = io.axi.aw.get
    val w = io.axi.w.get
    val b = io.axi.b.get
    aw.valid := awValid
    awReady := aw.ready
    aw.bits.addr := awAddr
    aw.bits.len.get := (writeBurstLength - 1).U
    w.valid := wValid
    wReady := w.ready
    w.bits.data := wData
    w.bits.last.get := wLast
    w.bits.strb := "b1111".U
    b.ready := true.B
}
