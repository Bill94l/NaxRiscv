package vooxriscv.sandbox.cam2

import spinal.core._
import spinal.lib._
import spinal.lib.eda.bench.{Bench, Rtl, XilinxStdTargets}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


object WaitTableCalc extends App{
  val wayCount = 2
  val commitCount = 2
  val depCount = 2
  val eventCount = 32
  val eventBits = log2Up(eventCount)

  def equalLuts(bitCount : Int) = (bitCount+2)/3
  def zeroLuts(bitCount : Int) = (bitCount+5)/6

  val idLuts, maskLuts = ArrayBuffer[Int]()
  for(slotCount <- List(8, 16, 32, 48, 64)){
    idLuts += commitCount*depCount*equalLuts(eventBits)
    maskLuts += /*eventCount/2 +*/ zeroLuts(eventCount)
  }


  println(idLuts)
  println(maskLuts)
}

case class WaitTableParameter( slotCount : Int,
                               wayCount : Int,
                               triggerCount : Int,
                               eventPorts : Int,
                               eventCount : Int){
  assert(slotCount % wayCount == 0)
  val lineCount = slotCount/wayCount
  val eventBits = log2Up(eventCount)
  val eventType = HardType(UInt(eventBits bits))
}

case class WaitTablePush[T <: Data](p : WaitTableParameter, contextType : HardType[T]) extends Bundle{
  val line = Bits(p.lineCount bits)
  val slots = Vec.fill(p.wayCount)(WaitTablePushSlot(p, contextType))
}

case class WaitTablePushSlot[T <: Data](p : WaitTableParameter, contextType : HardType[T]) extends Bundle{
  val valid = Bool()
  val triggers = Vec.fill(p.triggerCount)(new Bundle {
    val done = Bool()
    val event = p.eventType()
  })
  val context = contextType()
}



case class WaitTableIo[T <: Data](p : WaitTableParameter, slotContextType : HardType[T]) extends Bundle{
  val events = Vec.fill(p.eventPorts)(in(p.eventType()))
  val push = slave Stream(WaitTablePush(p, slotContextType))
  val pop = Vec.fill(p.slotCount)(master Stream(slotContextType()))
}


class WaitTable[T <: Data](p : WaitTableParameter, slotContextType : HardType[T]) extends Component{
  val io = WaitTableIo(p, slotContextType)

  io.push.ready := False
  val lines = for(line <- 0 until p.lineCount) yield new Area{
    val ways = for(way <- 0 until p.wayCount) yield new Area{
      val priority = line*p.wayCount+way
      val pop = io.pop(priority)
      val valid = RegInit(False)
      val context = Reg(slotContextType())
      val triggers =  for(way <- 0 until p.triggerCount) yield new Area{
        val event = Reg(p.eventType())
        val hit = io.events.map(_ === event).orR
        val done = Reg(Bool()) clearWhen(hit)
      }
      val done = triggers.map(_.done).andR

      pop.valid := valid && done
      pop.payload := context
    }
    val free = !ways.map(_.valid).orR

    val refill = KeepAttribute(io.push.valid && free && io.push.line(line))

    //Push in the table
    when(refill) {
      io.push.ready := True
      for (wayId <- 0 until p.wayCount) {
        val wSrc = io.push.slots(wayId)
        val wDst = ways(wayId)
        wDst.valid := wSrc.valid
        wDst.context := wSrc.context
        for(triggerId <- 0 until p.triggerCount){
          val tSrc = wSrc.triggers(triggerId)
          val tDst = wDst.triggers(triggerId)
          tDst.done := tSrc.done
          tDst.event := tSrc.event
        }
      }
    }

    ways.foreach(w => w.valid clearWhen(w.pop.ready))
  }
}

object WaitTableSynthBench extends App{
  LutInputs.set(6)

  val rtls = ArrayBuffer[Rtl]()
  val p = WaitTableParameter(
    slotCount    = 32,
    wayCount     = 2,
    triggerCount = 2,
    eventPorts   = 2,
    eventCount   = 32
  )
  rtls += Rtl(SpinalVerilog(Rtl.ffIo(new WaitTable(p, Bits(log2Up(p.slotCount) bits)))))
  val targets = XilinxStdTargets().take(2)

  Bench(rtls, targets)
}

/*
Artix 7 -> 211 Mhz 433 LUT 431 FF
Artix 7 -> 353 Mhz 447 LUT 431 FF


Artix 7 -> 182 Mhz 433 LUT 731 FF
Artix 7 -> 344 Mhz 446 LUT 731 FF

Artix 7 -> 171 Mhz 816 LUT 923 FF
Artix 7 -> 311 Mhz 831 LUT 923 FF
*/