package chipyard.base.arty100t

import chisel3._
import chisel3.util.ShiftRegister

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
/* CUSTOM IMPORT FOR BASE RISC-V SYSTEM ON CHIP ON ARTY100T */
//============================================================================
import sifive.blocks.devices.spi._
import freechips.rocketchip.subsystem.{HasTileLinkLocations, PBUS}
import freechips.rocketchip.prci._

class WithArty100TUARTTSI extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: UARTTSIPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val harnessIO = IO(new UARTPortIO(port.io.uartParams)).suggestName("uart_tsi")
    harnessIO <> port.io.uart
    val packagePinsWithPackageIOs = Seq(
      ("A9" , IOPin(harnessIO.rxd)),
      ("D10", IOPin(harnessIO.txd)))
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      ath.xdc.addPackagePin(io, pin)
      ath.xdc.addIOStandard(io, "LVCMOS33")
      ath.xdc.addIOB(io)
    } }

    ath.other_leds(1) := port.io.dropped
    ath.other_leds(9) := port.io.tsi2tl_state(0)
    ath.other_leds(10) := port.io.tsi2tl_state(1)
    ath.other_leds(11) := port.io.tsi2tl_state(2)
    ath.other_leds(12) := port.io.tsi2tl_state(3)
  }
})

class WithArty100TDDRTL extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: TLMemPort, chipId: Int) => {
    val artyTh = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    if(artyTh.ddrClient == null) {
      println("DDR client is null. DDR HarnessBinder is no longer needed.")
    }else{
      println("DDR client is present. Connecting DDR client to TLMemPort.")
      val bundles = artyTh.ddrClient.out.map(_._1)
      val ddrClientBundle = Wire(new HeterogeneousBag(bundles.map(_.cloneType)))
      bundles.zip(ddrClientBundle).foreach { case (bundle, io) => bundle <> io }
      ddrClientBundle <> port.io
    }
  }
})


// Uses PMOD JA/JB
class WithArty100TSerialTLToGPIO extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: SerialTLPort, chipId: Int) => {
    val artyTh = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName("serial_tl")
    harnessIO <> port.io

    harnessIO match {
      case io: DecoupledPhitIO => {
        val clkIO = io match {
          case io: InternalSyncPhitIO => IOPin(io.clock_out)
          case io: ExternalSyncPhitIO => IOPin(io.clock_in)
        }
        val packagePinsWithPackageIOs = Seq(
          ("G13", clkIO),
          ("B11", IOPin(io.out.valid)),
          ("A11", IOPin(io.out.ready)),
          ("D12", IOPin(io.in.valid)),
          ("D13", IOPin(io.in.ready)),
          ("B18", IOPin(io.out.bits.phit, 0)),
          ("A18", IOPin(io.out.bits.phit, 1)),
          ("K16", IOPin(io.out.bits.phit, 2)),
          ("E15", IOPin(io.out.bits.phit, 3)),
          ("E16", IOPin(io.in.bits.phit, 0)),
          ("D15", IOPin(io.in.bits.phit, 1)),
          ("C15", IOPin(io.in.bits.phit, 2)),
          ("J17", IOPin(io.in.bits.phit, 3))
        )
        packagePinsWithPackageIOs foreach { case (pin, io) => {
          artyTh.xdc.addPackagePin(io, pin)
          artyTh.xdc.addIOStandard(io, "LVCMOS33")
        }}

        // Don't add IOB to the clock, if its an input
        io match {
          case io: InternalSyncPhitIO => packagePinsWithPackageIOs foreach { case (pin, io) => {
            artyTh.xdc.addIOB(io)
          }}
          case io: ExternalSyncPhitIO => packagePinsWithPackageIOs.drop(1).foreach { case (pin, io) => {
            artyTh.xdc.addIOB(io)
          }}
        }

        artyTh.sdc.addClock("ser_tl_clock", clkIO, 100)
        artyTh.sdc.addGroup(pins = Seq(clkIO))
        artyTh.xdc.clockDedicatedRouteFalse(clkIO)
      }
    }
  }
})

// Maps the UART device to the on-board USB-UART
class WithArty100TUART(rxdPin: String = "A9", txdPin: String = "D10") extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: UARTPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName("uart")
    harnessIO <> port.io
    val packagePinsWithPackageIOs = Seq(
      (rxdPin, IOPin(harnessIO.rxd)),
      (txdPin, IOPin(harnessIO.txd)))
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      ath.xdc.addPackagePin(io, pin)
      ath.xdc.addIOStandard(io, "LVCMOS33")
      ath.xdc.addIOB(io)
    } }
  }
})

// Maps the UART device to PMOD JD pins 3/7
class WithArty100TPMODUART extends WithArty100TUART("G2", "F3")

class WithArty100TJTAG extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: JTAGPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName("jtag")
    harnessIO <> port.io

    ath.sdc.addClock("JTCK", IOPin(harnessIO.TCK), 10)
    ath.sdc.addGroup(clocks = Seq("JTCK"))
    ath.xdc.clockDedicatedRouteFalse(IOPin(harnessIO.TCK))
    val packagePinsWithPackageIOs = Seq(
      ("F4", IOPin(harnessIO.TCK)),
      ("D2", IOPin(harnessIO.TMS)),
      ("E2", IOPin(harnessIO.TDI)),
      ("D4", IOPin(harnessIO.TDO))
    )
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      ath.xdc.addPackagePin(io, pin)
      ath.xdc.addIOStandard(io, "LVCMOS33")
      ath.xdc.addPullup(io)
    } }
  }
})

//============================================================================
/* CUSTOM CONFIGURATIONS FOR BASE RISC-V SYSTEM ON CHIP ON ARTY100T */
//============================================================================

/*** SPI to SD Card Interface ***/
// Maps SPI peripheral to SD card pins following standard SD/SDIO pinout
// SD Card SPI Mode Pin Mapping:
//   CLK  -> SPI SCK
//   CMD  -> SPI MOSI (DQ0)
//   DAT0 -> SPI MISO (DQ1) 
//   DAT3 -> SPI CS (chip select, active low)
//   DAT1 -> Can be tied high or used for quad SPI
//   DAT2 -> Can be tied high or used for quad SPI

object SPIBinderCounter {
  var idx = 0
}

class WithArty100TSPIHarnessBinder extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: SPIPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
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
        ("D12", IOPin(sd_clk),  "CLK"),   // SD CLK - SPI clock
        ("B11", IOPin(sd_cmd),  "CMD"),   // SD CMD - SPI MOSI
        ("A11", IOPin(sd_dat0), "DAT0"),  // SD DAT0 - SPI MISO
        ("D13", IOPin(sd_dat1), "DAT1"),  // SD DAT1 - not used here
        ("B18", IOPin(sd_dat2), "DAT2"),  // SD DAT2 - not used here
        ("G13", IOPin(sd_cs),   "CS")     // SD DAT3/CS - SPI chip select
      )
      
      packagePinsWithPackageIOs.foreach { case (pin, io, name) =>
        ath.xdc.addPackagePin(io, pin)
        ath.xdc.addIOStandard(io, "LVCMOS33")
        ath.xdc.addIOB(io)
      }

      packagePinsWithPackageIOs.drop(1).foreach { case (pin, io, _) => {
        ath.xdc.addPullup(io)
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

      // Assign pins as needed
      val packagePinsWithPackageIOs = Seq(
        ("U12", IOPin(harnessIO.sck)),
        ("V12", IOPin(harnessIO.cs(0))),
        ("V10", IOPin(harnessIO.dq(0).o)),
        ("V11", IOPin(harnessIO.dq(0).i)),
        ("U14", IOPin(harnessIO.dq(0).ie)),
        ("V14", IOPin(harnessIO.dq(0).oe))
      )
      packagePinsWithPackageIOs.foreach { case (pin, io) =>
        ath.xdc.addPackagePin(io, pin)
        ath.xdc.addIOStandard(io, "LVCMOS33")
        ath.xdc.addIOB(io)
      }

      SPIBinderCounter.idx += 1
    }
  }
})



class WithArty100TUARTHarnessBinder(uartPins: Seq[(String, String)] = Seq(("A9", "D10"))) extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: UARTPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val uartId = port.uartNo
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName(s"uart$uartId")
    //print harressIO name
    println(s"UART $uartId: ${harnessIO}")

    harnessIO <> port.io

    // Mapping UART to specified Pins is done here
    val (rxdPin, txdPin) = uartPins.lift(uartId).getOrElse(
      throw new Exception(s"No UART Pin mapping for uartId $uartId")
    )
    println(s"UART $uartId assignment: RXD-$rxdPin   TXD-$txdPin")
    
    // Constraint in XDC
    val packagePinsWithPackageIOs = Seq(
      (rxdPin, IOPin(harnessIO.rxd)),
      (txdPin, IOPin(harnessIO.txd)))
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      ath.xdc.addPackagePin(io, pin)
      ath.xdc.addIOStandard(io, "LVCMOS33")
      ath.xdc.addIOB(io)
    } }
  }
})

// Maps the UART device to PMOD JD pins 3/7
class WithArty100TPMODUARTHarnessBinder extends WithArty100TUARTHarnessBinder(Seq(("G2", "F3")))


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