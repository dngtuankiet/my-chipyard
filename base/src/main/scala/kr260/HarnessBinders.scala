package chipyard.base.kr260

import chisel3._

import freechips.rocketchip.jtag.{JTAGIO}
import freechips.rocketchip.subsystem.{PeripheryBusKey}
import freechips.rocketchip.tilelink.{TLBundle}
import freechips.rocketchip.diplomacy.{LazyRawModuleImp}
import org.chipsalliance.diplomacy.nodes.{HeterogeneousBag}
import sifive.blocks.devices.uart.{UARTPortIO, UARTParams}
import sifive.blocks.devices.jtag.{JTAGPins, JTAGPinsFromPort}
import sifive.blocks.devices.pinctrl.{BasePin}
import sifive.fpgashells.shell._
import sifive.fpgashells.ip.xilinx._
import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.clocks._
import chipyard._
import chipyard.harness._
import chipyard.iobinders._
import testchipip.serdes._

//============================================================================
/* CUSTOM IMPORT FOR BASE RISC-V SYSTEM ON CHIP ON KR260 */
//============================================================================
import sifive.blocks.devices.spi._
import freechips.rocketchip.subsystem.{HasTileLinkLocations, PBUS}
import freechips.rocketchip.prci._

// Note: DDR support removed - KR260's 4GB DDR4 is connected to PS, not PL
// To access DDR, integrate Zynq UltraScale+ MPSoC IP and use PS DDR controller

class WithKR260JTAG extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: JTAGPort, chipId: Int) => {
    val kth = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[KR260Harness]
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName("jtag")
    harnessIO <> port.io

    kth.sdc.addClock("JTCK", IOPin(harnessIO.TCK), 10)
    kth.sdc.addGroup(clocks = Seq("JTCK"))
    kth.xdc.clockDedicatedRouteFalse(IOPin(harnessIO.TCK))
    
    // KR260 JTAG pin assignments (matching KR260Shell.scala)
    // Note: These pins match the JTAG overlay in KR260Shell
    val packagePinsWithPackageIOs = Seq(
      ("AF10", IOPin(harnessIO.TCK)),  // pin PMOD4_IO4
      ("AC12", IOPin(harnessIO.TMS)),  // JTAG PMOD4_IO1
      ("AD12", IOPin(harnessIO.TDI)),  // JTAG PMOD4_IO2
      ("AE10", IOPin(harnessIO.TDO))   // JTAG PMOD4_IO3
      // Note: srst_n (F14) is handled separately in shell
    )
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      kth.xdc.addPackagePin(io, pin)
      kth.xdc.addIOStandard(io, "LVCMOS33")
      kth.xdc.addPullup(io)
    } }
  }
})

//============================================================================
/* CUSTOM CONFIGURATIONS FOR BASE RISC-V SYSTEM ON CHIP ON KR260 */
//============================================================================

/*** SPI ***/
object SPIBinderCounter {
  var idx = 0
}

class WithKR260SPIHarnessBinder extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: SPIPort, chipId: Int) => {
    val kth = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[KR260Harness]
    val idx = SPIBinderCounter.idx
    if(idx == 0) {
      println(f"SPI_0 (SD Card): ${port.io} with rAddress 0x${port.io.c.rAddress}%x")
      
      // Get the SPIPortIO wire from WithArty100TSDCardSPIIO lambda
      val spi_io = port.io

      // Create SD card interface signals with meaningful names at harness level
      val sd_clk  = IO(Output(Bool())).suggestName("sd_clk")   // SD CLK - clock output
      val sd_cmd  = IO(Output(Bool())).suggestName("sd_cmd")   // SD CMD - MOSI (command/data out)
      val sd_dat0 = IO(Input(Bool())).suggestName("sd_dat0")   // SD DAT0 - MISO (data in)
      val sd_dat1 = IO(Input(Bool())).suggestName("sd_dat1")   // SD DAT1 - not used here
      val sd_dat2 = IO(Input(Bool())).suggestName("sd_dat2")   // SD DAT2 - not used here
      val sd_cs   = IO(Output(Bool())).suggestName("sd_cs")    // SD CS/DAT3 - chip select (active low)

      // Connect SD card signals to ChipTop's simplified SPI port
      sd_clk  := spi_io.sck           // Clock output
      sd_cmd  := spi_io.dq(0).o       // MOSI output (CMD line)
      sd_cs   := spi_io.cs(0)         // Chip select output

      // Double-flop MISO for metastability protection
      val sd_dat0_sync1 = RegNext(sd_dat0, false.B)
      val sd_dat0_sync2 = RegNext(sd_dat0_sync1, false.B)
      spi_io.dq(1).i := sd_dat0_sync2
      
      // Pin assignments (PMOD JA on Arty100T) - Standard SPI pinout
      val packagePinsWithPackageIOs = Seq(
        ("C11", IOPin(sd_clk),  "CLK"),   // SD CLK - SPI clock
        ("E10", IOPin(sd_cmd),  "CMD"),   // SD CMD - SPI MOSI
        ("D10", IOPin(sd_dat0), "DAT0"),  // SD DAT0 - SPI MISO
        ("B10", IOPin(sd_dat1), "DAT1"),  // SD DAT1 - not used here
        ("B11", IOPin(sd_dat2), "DAT2"),  // SD DAT2 - not used here
        ("H12", IOPin(sd_cs),   "CS")     // SD DAT3/CS - SPI chip select
      )
      
      packagePinsWithPackageIOs.foreach { case (pin, io, name) =>
        kth.xdc.addPackagePin(io, pin)
        kth.xdc.addIOStandard(io, "LVCMOS33")
        kth.xdc.addIOB(io)
      }

      packagePinsWithPackageIOs.drop(1).foreach { case (pin, io, _) => {
        kth.xdc.addPullup(io)
      } }

      SPIBinderCounter.idx += 1
    }else if(idx == 1) {
      println(f"SPI_1: ${port.io} with rAddress 0x${port.io.c.rAddress}%x")
      val harnessIO = IO(new SPIPortIOSingle(port.io.c)).suggestName("spi1")
      // Manual connection since dq width differs
      harnessIO.sck := port.io.sck
      harnessIO.cs  := port.io.cs
      // Connect only dq(0)
      harnessIO.dq(0).o  := port.io.dq(0).o
      port.io.dq(0).i := harnessIO.dq(0).i
      harnessIO.dq(0).ie := port.io.dq(0).ie
      harnessIO.dq(0).oe := port.io.dq(0).oe

      // Tie off unused dq lines on the DUT side
      port.io.dq(1).i := false.B
      port.io.dq(2).i := false.B
      port.io.dq(3).i := false.B

      // KR260 SPI1 pin assignments (update with actual pinout)
      val packagePinsWithPackageIOs = Seq(
        ("C9",  IOPin(harnessIO.sck)),
        ("D9",  IOPin(harnessIO.cs(0))),
        ("C10", IOPin(harnessIO.dq(0).o)),
        ("D10", IOPin(harnessIO.dq(0).i)),
        ("E9",  IOPin(harnessIO.dq(0).ie)),
        ("E10", IOPin(harnessIO.dq(0).oe))
      )
      packagePinsWithPackageIOs.foreach { case (pin, io) =>
        kth.xdc.addPackagePin(io, pin)
        kth.xdc.addIOStandard(io, "LVCMOS33")
        kth.xdc.addIOB(io)
      }
    }
  }
})

/*** UART ***/
class WithKR260UARTHarnessBinder(uartPins: Seq[(String, String)] = Seq(("F12", "G10"))) extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: UARTPort, chipId: Int) => {
    val kth = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[KR260Harness]
    val uartId = port.uartNo
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName(s"uart$uartId")
    println(s"UART $uartId: ${harnessIO}")

    harnessIO <> port.io

    // Mapping UART to specified Pins (matching KR260Shell.scala)
    // Default UART0: J17 (RXD), K17 (TXD)
    val (rxdPin, txdPin) = uartPins.lift(uartId).getOrElse(
      throw new Exception(s"No UART Pin mapping for uartId $uartId")
    )
    println(s"UART $uartId assignment: RXD-$rxdPin   TXD-$txdPin")
    
    // Constraint in XDC
    val packagePinsWithPackageIOs = Seq(
      (rxdPin, IOPin(harnessIO.rxd)),
      (txdPin, IOPin(harnessIO.txd)))
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      kth.xdc.addPackagePin(io, pin)
      kth.xdc.addIOStandard(io, "LVCMOS33")
    //   kth.xdc.addIOB(io)
      // Note: IOB constraint removed to avoid DRC PDRC-248 error
      // UART RX has sampling logic that creates fanout, incompatible with IOB
    } }
  }
})

// NOTE: Pin assignments above now match KR260Shell.scala definitions:
// - JTAG: C14 (TCK), C15 (TMS), E14 (TDI), D15 (TDO) - LVCMOS18
// - UART0: J17 (RXD), K17 (TXD) - LVCMOS33
// - SPI0/SDIO: D11 (clk), D10 (cs), B10/E10/C11/H12 (dat 0-3) - defined in shell overlay
// 
// If you need to customize these, update both this file AND KR260Shell.scala
// For additional details, refer to:
// - KR260 schematics
// - Kria KR260 Robotics Starter Kit User Guide (UG1089)
// - PMOD connector pinouts if using PMODs

//============================================================================
/* CUSTOM OverrideIOBinders */
//============================================================================

// Override the default SPI IOBinder to expose only necessary signals for SD card
// This ties off unused signals inside ChipTop and only exposes the 4 needed signals
class WithModifiedChipTopSDCardIO extends OverrideIOBinder({
  (system: HasPeripherySPI) => {
    val ports = system.spi.zipWithIndex.map { case (s, i) =>
      val where = PBUS
      val bus = system.asInstanceOf[HasTileLinkLocations].locateTLBusWrapper(where)
      val freqMHz = bus.dtsFrequency.get / 1000000
      val spi_clock = bus.module.clock  // Get the system/bus clock
      val spi_reset = bus.module.reset  // Get the system/bus reset
      // Only simplify SPI_0 (SD card at 0x64001000)
      if (s.c.rAddress == 0x64001000L) {
        println(f"Creating simplified SD card SPI IO for SPI_$i at 0x${s.c.rAddress}%x")
        
        // Create only the 4 signals needed for SD card read-only operation
        val spi_sck  = IO(Output(Bool())).suggestName(s"spi_${i}_sck")
        val spi_mosi = IO(Output(Bool())).suggestName(s"spi_${i}_mosi")
        val spi_miso = IO(Input(Bool())).suggestName(s"spi_${i}_miso")
        val spi_cs   = IO(Output(Bool())).suggestName(s"spi_${i}_cs")
        
        // Connect ChipTop IOs to/from SPI peripheral (must happen here, not in lambda)
        spi_sck  := s.sck        // ChipTop output from SPI clock
        spi_mosi := s.dq(0).o    // ChipTop output from SPI MOSI
        spi_cs   := s.cs(0)      // ChipTop output from SPI CS
        
        // MISO synchronization is handled in the HarnessBinder.
        s.dq(1).i := spi_miso

        // Tie off unused SPI peripheral inputs (input side of DQ0/2/3).
        Seq(0, 2, 3).foreach { dqIdx => s.dq(dqIdx).i := false.B }
        
        SPIPort(() => {
          // Return a wire for the HarnessBinder to use.
          val spi_wire = Wire(new SPIPortIO(s.c))
          spi_wire.sck := spi_sck
          spi_wire.cs.foreach(_ := false.B)
          spi_wire.cs(0) := spi_cs

          spi_wire.dq.foreach { dq =>
            dq.o := false.B
            dq.oe := false.B
            dq.ie := false.B
            dq.i := false.B
          }

          spi_wire.dq(0).o := spi_mosi
          spi_wire.dq(0).oe := true.B
          spi_wire.dq(1).ie := true.B
          
          // Wire input connects to ChipTop input (HarnessBinder drives this)
          spi_miso := spi_wire.dq(1).i
        
          spi_wire
        })
      } else {
        // For other SPI peripherals, use standard passthrough
        SPIPort(() => {
          val spi_wire = Wire(new SPIPortIO(s.c))
          spi_wire <> s
          spi_wire
        })
      }
    }
    (ports, Nil)
  }
})