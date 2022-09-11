// See README.md for license details.

ThisBuild / scalaVersion     := "2.12.12"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "%ORGANIZATION%"

lazy val commonSettings = Seq (
  libraryDependencies ++= Seq(
    "edu.berkeley.cs" %% "chisel3" % "3.4.3",
    "edu.berkeley.cs" %% "chiseltest" % "0.3.2" % "test"
  ),
  scalacOptions ++= Seq(
    "-Xsource:2.11",
    "-language:reflectiveCalls",
    "-deprecation",
    "-feature",
    "-Xcheckinit"
  ),
  addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.4.3" cross CrossVersion.full),
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
)

lazy val fpga_samples = (project in file("./fpga_samples/chisel")).
  settings(
    commonSettings,
    name := "fpga_samples"
  )

lazy val atom_display = (project in file("./atom_display")).
  settings(
    commonSettings,
    name := "atom_display"
  ).
  dependsOn(fpga_samples)

lazy val root = (project in file("."))
  .aggregate(atom_display, fpga_samples)
