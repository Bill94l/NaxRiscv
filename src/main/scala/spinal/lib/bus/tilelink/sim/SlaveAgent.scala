package spinal.lib.bus.tilelink.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.tilelink._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}

class SlaveAgent(bus : Bus, cd : ClockDomain) {
  val driver = new Area{
    val aInst = StreamReadyRandomizer(bus.a, cd)
    val bTuple = bus.p.withBCE generate StreamDriver.queue(bus.b, cd)
    def bInst = bTuple._1
    def b = bTuple._2
    val cInst = bus.p.withBCE generate StreamReadyRandomizer(bus.c, cd)
    val (dInst, d) = StreamDriver.queue(bus.d, cd)
    val eInst = bus.p.withBCE generate StreamReadyRandomizer(bus.e, cd)
  }

  def onGet(source : Int,
            address : Long,
            bytes : Int): Unit ={
    ???
  }

  def onPutPartialData(source : Int,
                       address : Long,
                       size : Int,
                       mask : Array[Boolean],
                       data : Array[Byte]): Unit ={
    ???
  }

  def accessAckData(source : Int,
                    data : Seq[Byte],
                    denied : Boolean = false,
                    corrupt : Boolean = false): Unit ={
    val size = log2Up(data.size)
    for(offset <- 0 until data.size by bus.p.dataBytes){
      driver.d.enqueue { p =>
        val buf = new Array[Byte](bus.p.dataBytes)
        (0 until bus.p.dataBytes).foreach(i => buf(i) = data(offset + i))
        p.opcode  #= Opcode.D.ACCESS_ACK_DATA
        p.param   #= 0
        p.size    #= size
        p.source  #= source
        p.sink    #= 0
        p.denied  #= denied
        if(bus.p.withDataD) {
          p.data    #= buf
          p.corrupt #= corrupt
        }
      }
    }
  }
  def accessAck(source : Int,
                size : Int,
                denied : Boolean = false): Unit ={
    driver.d.enqueue { p =>
      val buf = new Array[Byte](bus.p.dataBytes)
      p.opcode  #= Opcode.D.ACCESS_ACK
      p.param   #= 0
      p.size    #= size
      p.source  #= source
      p.sink    #= 0
      p.denied  #= denied
      if(bus.p.withDataD) {
        p.data.randomize()
        p.corrupt #= false
      }
    }
  }

  val monitor = new Area{
    val a = StreamMonitor(bus.a, cd){ p =>
      val opcode = p.opcode.toEnum
      val source = p.source.toInt
      val address = p.address.toLong
      val size = p.size.toInt
      val offset = (address & (bus.p.dataBytes-1)).toInt
      opcode match {
        case Opcode.A.GET => onGet(source, address, 1 << size)
        case Opcode.A.PUT_PARTIAL_DATA => onPutPartialData(source, address, size, p.mask.toBytes.flatMap(v => (0 to 7).map(i => ((v>>i)&1).toBoolean)).drop(offset), p.data.toBytes.drop(offset))
      }
    }

    val c = bus.p.withBCE generate StreamMonitor(bus.c, cd){ p =>

    }
    val e = bus.p.withBCE generate StreamMonitor(bus.e, cd){ p =>

    }
  }
}
