package spinal.lib.bus.tilelink

import spinal.core._
import spinal.core.fiber._
import spinal.lib.bus.misc.{AddressMapping, DefaultMapping, SizeMapping}
import spinal.lib.{master, slave}

import scala.collection.mutable.ArrayBuffer

case class M2sSupport(transfers : M2sTransfers,
                      dataBytes : Int,
                      addressWidth : Int,
                      allowExecute : Boolean){
  def mincover(that : M2sSupport): M2sSupport ={
    M2sSupport(
      transfers = transfers.mincover(that.transfers),
      dataBytes = dataBytes max that.dataBytes,
      addressWidth = addressWidth max that.addressWidth,
      allowExecute = this.allowExecute && that.allowExecute
    )
  }
}

class InterconnectNodeMode extends Nameable
object InterconnectNodeMode extends AreaRoot {
  val BOTH, MASTER, SLAVE = new InterconnectNodeMode
}
class InterconnectNode(i : Interconnect) extends Area {
  val bus = Handle[Bus]()
  val ups = ArrayBuffer[InterconnectConnection]()
  val downs = ArrayBuffer[InterconnectConnection]()
  val lock = Lock()

  val dataBytes = Handle[Int]()
  def setDataBytes(bytes : Int) = this.dataBytes.load(bytes)

  val m2s = new Area{
    val proposed = Handle[M2sSupport]()
    val supported = Handle[M2sSupport]()
    val parameters = Handle[M2sParameters]()

    val proposedModifiers = ArrayBuffer[M2sSupport => M2sSupport]()
  }
  val s2m = new Area{
    val parameters = Handle[S2mParameters]()
  }

  var mode = InterconnectNodeMode.BOTH
  def setSlaveOnly() = {mode = InterconnectNodeMode.SLAVE; this}
  def setMasterOnly() = {mode = InterconnectNodeMode.MASTER; this}

  def <<(m : InterconnectNode): InterconnectConnection = {
    this at DefaultMapping of m
  }

  class At(body : InterconnectConnection => Unit){
    def of(m : InterconnectNode): InterconnectConnection = {
      val c = i.connect(m, InterconnectNode.this)
      body(c)
      c
    }
  }
  def at(address : BigInt) = new At(_.explicitAddress = Some(address))
  def at(address : BigInt, size : BigInt) = new At(_.explicitMapping = Some(SizeMapping(address, size)))
  def at(address : AddressMapping) = new At(_.explicitMapping = Some(address))

//  def forceSingleBeat(): Unit ={
//    m2s.proposedModifiers += { s =>
//      s.copy(transfers = s.transfers.)
//    }
//  }
}

class InterconnectConnection(val m : InterconnectNode, val s : InterconnectNode) extends Area {
  m.downs += this
  s.ups += this

  var explicitAddress = Option.empty[BigInt]
  var explicitMapping = Option.empty[AddressMapping]

  def getMapping() : AddressMapping = {
    explicitAddress match {
      case Some(v) => return SizeMapping(v, BigInt(1) << decoder.m2s.parameters.get.addressWidth)
      case None =>
    }
    explicitMapping match {
      case Some(x) => x
    }
  }

  val decoder, arbiter = new Area{
    val bus = Handle[Bus]()
    val m2s = new Area{
      val parameters = Handle[M2sParameters]()
    }
    val s2m = new Area{
      val parameters = Handle[S2mParameters]()
    }
  }

  val thread = hardFork on new Area{
    soon(arbiter.m2s.parameters)
    soon(decoder.s2m.parameters)

    arbiter.m2s.parameters.load(decoder.m2s.parameters)
    decoder.s2m.parameters.load(arbiter.s2m.parameters)

    if(decoder.bus.p.dataBytes != arbiter.bus.p.dataBytes) PendingError("Tilelink interconnect does not support resized yet")
    decoder.bus >> arbiter.bus
  }
}

class Interconnect extends Area{
  val lock = Lock() //Lock which can be used to hold the whole interconnect generation
  val nodes = ArrayBuffer[InterconnectNode]()
  val connections = ArrayBuffer[InterconnectConnection]()
  def createNode(): InterconnectNode ={
    val node = new InterconnectNode(this)
    nodes += node
    node
  }

  def createSlave() = createNode().setSlaveOnly()
  def createMaster() = createNode().setMasterOnly()

  def connect(m : InterconnectNode, s : InterconnectNode): InterconnectConnection ={
    val c = new InterconnectConnection(m,s)
    c.setLambdaName(m.isNamed && s.isNamed)(s"${m.getName()}_to_${s.getName()}")
    connections += c
    c
  }

  val gen = Elab build new Area{
    lock.await()
    var error = false
    for(n <- nodes){
      n.mode match {
        case InterconnectNodeMode.MASTER =>
          if(n.ups.nonEmpty) { println(s"${n.getName()} has masters"); error = true }
          if(n.downs.isEmpty) { println(s"${n.getName()} has no slave"); error = true }
        case InterconnectNodeMode.BOTH =>
          if(n.ups.isEmpty) { println(s"${n.getName()} has no master"); error = true }
          if(n.downs.isEmpty) { println(s"${n.getName()} has no slave"); error = true }
        case InterconnectNodeMode.SLAVE =>
          if(n.ups.isEmpty) { println(s"${n.getName()} has no master"); error = true }
          if(n.downs.nonEmpty) { println(s"${n.getName()} has slaves"); error = true }
      }
    }
    if(error) SpinalError("Failed")

    val threads = for(n <- nodes) hardFork on new Composite(n, "thread"){
      // Specify which Handle will be loaded by the current thread, as this help provide automated error messages
      soon(n.ups.map(_.arbiter.bus) :_*)
      soon(n.downs.map(_.decoder.bus) :_*)
      soon(n.downs.map(_.decoder.m2s.parameters) :_*)
      soon(n.ups.map(_.arbiter.s2m.parameters) :_*)
      soon(n.ups.map(_.arbiter.bus) :_*)
      soon(
        n.dataBytes,
        n.bus
      )
      if(n.mode != InterconnectNodeMode.MASTER) soon(
        n.m2s.proposed,
        n.m2s.parameters
      )
      if(n.mode != InterconnectNodeMode.SLAVE) soon(
        n.m2s.supported,
        n.s2m.parameters
      )

      // n.m2s.proposed <- ups.m2s.proposed
      if(n.mode != InterconnectNodeMode.MASTER) {
        n.m2s.proposed load n.ups.map(_.m.m2s.proposed).reduce(_ mincover _)
      }

      // n.m2s.supported <- downs.m2s.supported
      if(n.mode != InterconnectNodeMode.SLAVE) {
        n.m2s.supported load n.downs.map(_.s.m2s.supported.get).reduce(_ mincover _)
      }

      if(n.ups.nonEmpty) n.setDataBytes(n.m2s.supported.dataBytes)

      // n.m2s.parameters <- ups.arbiter.m2s.parameters
      if(n.mode != InterconnectNodeMode.MASTER) {
        n.m2s.parameters load Arbiter.outputMastersFrom(
          n.ups.map(_.arbiter.m2s.parameters.get)
        )
      }

      //down.decoder.m2s.parameters <- m2s.parameters + down.s.m2s.supported
      for (down <- n.downs) {
        down.decoder.m2s.parameters.load(Decoder.outputMastersFrom(n.m2s.parameters, down.s.m2s.supported))
      }

      // n.s2m.parameters <- downs.decoder.s2m.parameters
      if(n.mode != InterconnectNodeMode.SLAVE){
        n.s2m.parameters.load(Decoder.inputSlavesFrom(n.downs.map(_.decoder.s2m.parameters.get)))
      }

      //ups.arbiter.s2m.parameters <- s2m.parameters
      for(up <- n.ups){
        up.arbiter.s2m.parameters.load(n.s2m.parameters)
      }

      // Start hardware generation from that point
      // Generate the node bus
      val p = NodeParameters(n.m2s.parameters, n.s2m.parameters, n.dataBytes).toBusParameter()
      n.bus.load(Bus(p))

      val arbiter = (n.mode != InterconnectNodeMode.MASTER) generate new Area{
        val core = Arbiter(n.ups.map(up => NodeParameters(
          m = up.arbiter.m2s.parameters,
          s = up.arbiter.s2m.parameters,
          dataBytes = n.dataBytes
        )))
        core.setCompositeName(n.bus, "arbiter")
        (n.ups, core.io.inputs.map(_.fromCombStage())).zipped.foreach(_.arbiter.bus.load(_))
        n.bus << core.io.output
      }

      val decoder = (n.mode != InterconnectNodeMode.SLAVE) generate new Area {
        val core = Decoder(n.bus.p.node, n.downs.map(_.s.m2s.supported), n.downs.map(_.getMapping()))
        core.setCompositeName(n.bus, "decoder")
        (n.downs, core.io.outputs.map(_.combStage())).zipped.foreach(_.decoder.bus.load(_))
        core.io.input << n.bus
      }
    }
  }
}

class CPU()(implicit ic : Interconnect) extends Area{
  val node = ic.createMaster()
  node.setDataBytes(4)
  node.m2s.proposed load M2sSupport(  //TODO
    transfers = M2sTransfers(
      get = SizeRange.upTo(0x40),
      putFull = SizeRange.upTo(0x40)
    ),
    dataBytes    = 4,
    addressWidth = 32,
    allowExecute = false
  )
  node.m2s.parameters.load(
    M2sParameters(
      addressWidth = 32,
      masters = List(M2sAgent(
        name = this,
        mapping = List(M2sSource(
          id = SizeMapping(0, 4),
          emits = M2sTransfers(
            get = SizeRange.upTo(0x40),
            putFull = SizeRange.upTo(0x40)
          )
        ))
      ))
    )
  )
  hardFork(slave(node.bus))
}


class Adapter(canCrossClock : Boolean = false,
              canUpsize : Boolean = false,
              canDownSize : Boolean = false,
              canSplit : Boolean = false)(implicit ic : Interconnect) extends Area{
  val input  = ic.createSlave()
  val output = ic.createMaster()

  hardFork{
    output.m2s.proposed.load(input.m2s.proposed)
    input.m2s.supported.load(output.m2s.supported)
    output.m2s.parameters.load(input.m2s.parameters)
    input.s2m.parameters.load(output.s2m.parameters)
    output.dataBytes.load(input.dataBytes)
    output.bus << input.bus
  }
}


class VideoIn()(implicit ic : Interconnect) extends Area{
  val node = ic.createMaster()
  node.setDataBytes(4)
  node.m2s.parameters.load(
    M2sParameters(
      addressWidth = 32,
      masters = List(M2sAgent(
        name = this,
        mapping = List(M2sSource(
          id = SizeMapping(0, 4),
          emits = M2sTransfers(
            putFull = SizeRange.upTo(0x40)
          )
        ))
      ))
    )
  )
  hardFork(slave(node.bus))
}

class VideoOut()(implicit ic : Interconnect) extends Area{
  val node = ic.createMaster()
  node.setDataBytes(4)
  node.m2s.parameters.load(
    M2sParameters(
      addressWidth = 32,
      masters = List(M2sAgent(
        name = this,
        mapping = List(M2sSource(
          id = SizeMapping(0, 4),
          emits = M2sTransfers(
            get = SizeRange.upTo(0x40)
          )
        ))
      ))
    )
  )
  hardFork(slave(node.bus))
}



//class Bridge()(implicit ic : Interconnect) extends Area{
//  val node = ic.createNode()
//}

class UART()(implicit ic : Interconnect) extends Area{
  val node = ic.createSlave()
  node.s2m.parameters.load(
    S2mParameters.simple(this)
  )
  node.m2s.supported.loadAsync(
    M2sSupport(
      transfers = node.m2s.proposed.transfers.intersect(
        M2sTransfers(
          get = SizeRange.upTo( 0x1000),
          putFull = SizeRange.upTo(0x1000)
        )
      ),
      dataBytes = 4,
      addressWidth = 32, //TODO 8
      allowExecute = false
    )
  )
  hardFork(master(node.bus))
}

class ROM()(implicit ic : Interconnect) extends Area{
  val node = ic.createSlave()
  node.s2m.parameters.load(
    S2mParameters.simple(this)
  )
  node.m2s.supported.loadAsync(
    M2sSupport(
      transfers = node.m2s.proposed.transfers.intersect(
        M2sTransfers(
          get = SizeRange.upTo( 0x1000)
        )
      ),
      dataBytes = 4,
      addressWidth = 7,
      allowExecute = false
    )
  )
  hardFork(master(node.bus))
}

class StreamOut()(implicit ic : Interconnect) extends Area{
  val node = ic.createSlave()
  node.s2m.parameters.load(
    S2mParameters.simple(this)
  )
  node.m2s.supported.loadAsync(
    M2sSupport(
      transfers = node.m2s.proposed.transfers.intersect(
        M2sTransfers(
          putFull = SizeRange.upTo( 0x1000)
        )
      ),
      dataBytes = 4,
      addressWidth = 6,
      allowExecute = false
    )
  )
  hardFork(master(node.bus))
}

class CoherentCpu()(implicit ic : Interconnect) extends Area{
  val node = ic.createMaster()
  node.setDataBytes(4)
  node.m2s.parameters.load(
    M2sParameters(
      addressWidth = 32,
      masters = List(M2sAgent(
        name = this,
        mapping = List(M2sSource(
          id = SizeMapping(0, 4),
          emits = M2sTransfers(
            acquireT = SizeRange(0x40),
            acquireB = SizeRange(0x40),
            probeAckData = SizeRange(0x40)
          )
        ))
      ))
    )
  )
  hardFork(slave(node.bus))
}

class CoherencyHubIntegrator()(implicit ic : Interconnect) extends Area{
  val memPut = ic.createMaster()
  val memGet = ic.createMaster()
  val coherents = ArrayBuffer[InterconnectNode]()

  def createCoherent() ={
    val ret = ic.createSlave()
    coherents += ret
    ret
  }

  val logic = hardFork(new Area{
    val blockSize = 64
    val slotsCount = 4
    val cSourceCount = 4
    val dataBytes = coherents.map(_.m2s.proposed.dataBytes).max
    for(node <- coherents){
      node.m2s.supported.loadAsync(
        M2sSupport(
          transfers = node.m2s.proposed.transfers.intersect(
            M2sTransfers(
              acquireT = SizeRange(blockSize),
              acquireB = SizeRange(blockSize),
              get = SizeRange.upTo(blockSize),
              putFull = SizeRange.upTo(blockSize),
              putPartial = SizeRange.upTo(blockSize),
              probeAckData = SizeRange(blockSize)
            )
          ),
          dataBytes = dataBytes,
          addressWidth = node.m2s.proposed.addressWidth,
          allowExecute = true
        )
      )

      node.s2m.parameters.load(
        node.m2s.proposed.transfers.withBCE match {
          case false =>  S2mParameters.simple(this)
          case true => S2mParameters(List(S2mAgent(
            name = this,
            emits = S2mTransfers(
              probe = SizeRange(blockSize)
            ),
            sinkId = SizeMapping(0, slotsCount)
          )))
        }
      )
    }

    val addressWidth = ???
    memPut.setDataBytes(4)
    memPut.m2s.parameters.load(
      CoherentHub.downPutM2s(
        name           = this,
        addressWidth   = addressWidth,
        blockSize      = blockSize,
        slotCount      = slotsCount,
        cSourceCount   = cSourceCount
      )
    )
    memGet.setDataBytes(4)
    memGet.m2s.parameters.load(
      CoherentHub.downGetM2s(
        name           = this,
        addressWidth   = addressWidth,
        blockSize      = blockSize,
        slotCount      = slotsCount
      )
    )

    val hub = new CoherentHub(
      CoherentHubParameters(
        nodes     = coherents.map(_.bus.p.node),
        slotCount = slotsCount,
        cacheSize = 1024,
        wayCount  = 2,
        lineSize  = blockSize,
        cSourceCount = cSourceCount
      )
    )
    for((s, m) <- hub.io.ups zip coherents) s << m.bus
    hub.io.downGet >> memGet.bus
    hub.io.downPut >> memPut.bus
  })
}






object TopGen extends App{
  SpinalVerilog(new Component{
    implicit val interconnect = new Interconnect()

    val cpu0 = new CPU()

    val busA = interconnect.createNode()
//    busA.forceSingleBeat()
    busA << cpu0.node

    val adapter = new Adapter(canDownSize = true)
    adapter.input << busA

    val uart0 = new UART()
    uart0.node at 0x100 of adapter.output

    val uart1 = new UART()
    uart1.node at 0x200 of adapter.output

//    uart0.node.mapping.load(SizeMapping(0x100, 0x100))

//    val uart1 = new UART()
//    interconnect.connect(bridgeA.node, uart1.node)
//    uart1.node.mapping.load(SizeMapping(0x200, 0x100))


//    val cpu0 = new CPU()
//    val cpu1 = new CPU()
//
//    val bridgeA = new Bridge()
//    bridgeA.node.mapping.load(DefaultMapping)
//    interconnect.connect(cpu0.node, bridgeA.node)
//    interconnect.connect(cpu1.node, bridgeA.node)
//
//    val bridgeB = new Bridge()
//    bridgeB.node.mapping.load(SizeMapping(0x100, 0x300))
//    interconnect.connect(bridgeA.node, bridgeB.node)

//    val videoIn0 = new VideoIn()
//    interconnect.connect(videoIn0.node, bridgeA.node)
//
//    val videoOut0 = new VideoOut()
//    interconnect.connect(videoOut0.node, bridgeA.node)

//    val uart0 = new UART()
//    interconnect.connect(bridgeB.node, uart0.node)
//    uart0.node.mapping.load(SizeMapping(0x100, 0x100))
//
//    val uart1 = new UART()
//    interconnect.connect(bridgeB.node, uart1.node)
//    uart1.node.mapping.load(SizeMapping(0x200, 0x100))

//    val bridgeC = new Bridge()
//    bridgeC.node.mapping.load(DefaultMapping)
//    interconnect.connect(bridgeB.node, bridgeC.node)

//    val bridgeD = new Bridge()
//    bridgeD.node.mapping.load(DefaultMapping)
//    interconnect.connect(bridgeC.node, bridgeD.node)
//
//    val rom0 = new ROM()
//    rom0.node.mapping.load(SizeMapping(0x300, 0x100))
//    interconnect.connect(bridgeD.node, rom0.node)
//
//    val streamOut = new StreamOut()
//    streamOut.node.mapping.load(SizeMapping(0x400, 0x100))
//    interconnect.connect(bridgeD.node, streamOut.node)

//    val hub = new CoherencyHubIntegrator()
//    interconnect.connect(hub.memGet, bridgeA.node)
//    interconnect.connect(hub.memPut, bridgeA.node)
//
//    val ccpu0 = new CoherentCpu()
//    val c0 = hub.createCoherent()
//    interconnect.connect(ccpu0.node, c0)
//
//    val ccpu1 = new CoherentCpu()
//    val c1 = hub.createCoherent()
//    interconnect.connect(ccpu1.node, c1)

//    val rom0 = new ROM()
//    rom0.node.mapping.load(SizeMapping(0x300, 0x100))
//    interconnect.connect(cpu0.node, rom0.node)

//    val streamOut = new StreamOut()
//    streamOut.node.mapping.load(SizeMapping(0x400, 0x100))
//    interconnect.connect(cpu0.node, streamOut.node)

//    interconnect.connect(cpu0.node, uart0.node)
//    interconnect.connect(cpu1.node, uart0.node)
//    interconnect.connect(cpu0.node, uart1.node)
//    interconnect.connect(cpu1.node, uart1.node)
//    uart0.node.mapping.load(SizeMapping(0x100, 0x100))
//    uart1.node.mapping.load(SizeMapping(0x200, 0x100))
  })
}
