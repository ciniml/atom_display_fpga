// latency_checker.scala
// Display latency checker using ATOM Display
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
import _root_.util._

import java.io.FileInputStream
import scala.collection.mutable

@chiselName
class LatencyChecker(videoParams: VideoParams = PresetVideoParams.Default_1920_1080_30) extends Module {
  val io = IO(new Bundle{
    val video = new VideoIO(videoParams.pixelBits)
    val blink = Output(Bool())
  })

  val blinkScreen = Module(new BlinkScreenGenerator(videoParams, 30))
  io.video <> blinkScreen.io.video
  io.blink <> blinkScreen.io.blink
}

object ElaborateLatencyChecker extends App {
  (new ChiselStage).emitVerilog(new LatencyChecker(), Array(
    "-o", "latency_checker.v",
    "--target-dir", "rtl/latency_checker",
    //"--full-stacktrace",
  ))
}