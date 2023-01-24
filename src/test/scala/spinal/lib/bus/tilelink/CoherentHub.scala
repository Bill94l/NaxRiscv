package spinal.lib.bus.tilelink

import naxriscv.utilities.AllocatorMultiPortMem
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink._
import spinal.lib.bus.tilelink.sim._
import spinal.lib.eda.bench.{Bench, Rtl, XilinxStdTargets}
import spinal.lib.sim.SparseMemory

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

object CoherentHubTesterUtils{
  class Testbench(val cd : ClockDomain, upBuses : Seq[Bus], downBuses : Seq[Bus], orderings : List[Flow[CoherentHubOrdering]]) extends Area{
    val mainMem = SparseMemory()
    val globalMem = SparseMemory(mainMem.seed)

    val rand = new Area{
      def address() : Long = Random.nextLong() & (1l << upBuses(0).p.addressWidth)-1
      def address(bytes : Int) : Long = address() & ~(bytes.toLong-1)
    }

    val blockSize = 64
    val blockSizes = (0 to log2Up(blockSize)).map(1 << _).toArray
    val ups = for(up <- upBuses) yield new Area{
      val agent = new MasterAgent(up, cd){

      }
    }

    val downs = for(down <- downBuses) yield new Area{
      val agent = new SlaveAgent(down, cd){
        override def onGet(source: Int, address: Long, bytes: Int) = {
          val alignedAddr = address & ~(down.p.dataBytes-1)
          val alignedBytes = bytes max down.p.dataBytes
          val data = mainMem.readBytes(alignedAddr, alignedBytes)
          if(alignedAddr != address){
            for(i <- 0 until down.p.dataBytes) if(alignedAddr+i < address || alignedAddr+i >= address + bytes){
              data(i) = Random.nextInt().toByte
            }
          }
          accessAckData(
            source = source,
            data = data
          )
        }

        var  onPutPartialDataCounter = 0
        override def onPutPartialData(source: Int, address: Long, size: Int, mask: Array[Boolean], data: Array[Byte]) = {
          val isLast = onPutPartialDataCounter == (((1 << size) >> down.p.dataBytesLog2Up)-1 max 0)
          mainMem.write(address, data, mask)
          onPutPartialDataCounter += 1
          if(isLast){
            onPutPartialDataCounter = 0
            accessAck(
              source = source,
              size = size
            )
          }
        }
      }
    }

    cd.onSamplings{
      for (ordering <- orderings) {
        if(ordering.valid.toBoolean){
          val busId = ordering.upId.toInt
          val source = ordering.upSource.toInt
          val map = ups(busId).agent.ordering.map
          map(source).apply()
          map(source) = null
        }
      }
    }

    def acquireBlock(agent : MasterAgent,
                     source : Int,
                     param : Int,
                     address : Long,
                     bytes : Int): Block ={
      var ref: Array[Byte] = null
      val agent = ups(0).agent
      val block = agent.acquireBlock(source, param, address, bytes){
        ref = globalMem.readBytes(address, bytes)
      }
      block.ordering(globalMem.write(address, block.data))
      if(block.retains == 0) block.retain()
//      println(f"* $address%x")
//      println(toHex(block.data))
//      println(toHex(ref))
      assert((block.data, ref).zipped.forall(_ == _))
      block
    }

    def toHex(that : Seq[Byte]) = that.map(v => (v.toInt & 0xFF).toHexString).mkString(" ")
    def get(agent : MasterAgent,
            source : Int,
            address : Long,
            bytes : Int): Unit ={
      var ref: Array[Byte] = null
      val data = ups(0).agent.get(source, address, bytes){
        ref = globalMem.readBytes(address, bytes)
      }
//      println(f"* $address%x")
//      println(toHex(data))
//      println(toHex(ref))
      assert((data, ref).zipped.forall(_ == _))
    }
    def putFullData(agent : MasterAgent,
                    source : Int,
                    address : Long,
                    data : Array[Byte]): Unit ={
      assert(!ups(0).agent.putFullData(source, address, data){
        globalMem.write(address, data)
      })
    }
    def putPartialData(agent : MasterAgent,
                       source : Int,
                       address : Long,
                       data : Array[Byte],
                       mask : Array[Boolean]): Unit ={
      assert(!ups(0).agent.putPartialData(source, address, data, mask){
        globalMem.write(address, data, mask)
      })
    }

    def releaseData(agent : MasterAgent,
                    source : Int,
                    param : Int,
                    block : Block): Unit ={
      ups(0).agent.releaseData(source, param, block.address, block.data){
        globalMem.write(block.address, block.data)
      }
    }



  }
}

//TODO randomized agent transaction order
class CoherentHubTester extends AnyFunSuite {

  class Gen(val gen : () => CoherentHub)
  def Gen(gen : => CoherentHub) : Gen = new Gen(() => gen)
  val basicGen = Gen(new CoherentHub(CoherentHubGen.basicConfig(2)))

  val gens = mutable.HashMap[Gen, SimCompiled[CoherentHub]]()
  def doSim(gen : Gen)(body : CoherentHubTesterUtils.Testbench => Unit) = {
    gens.getOrElseUpdate(gen, SimConfig.withFstWave.addSimulatorFlag("--trace-max-width 256").compile(gen.gen())).doSim(seed = 42){dut =>
      dut.clockDomain.forkStimulus(10)
//      SimTimeout(2000000*10)
      var timeout = 0
      dut.clockDomain.onSamplings{
        for(up <- dut.io.ups){
          timeout += 1
          if(up.a.valid.toBoolean && up.a.ready.toBoolean){
            timeout = 0
          }
          if(timeout == 500){
            simFailure("No activity :(")
          }

        }
      }
      val setup = new CoherentHubTesterUtils.Testbench(dut.clockDomain, dut.io.ups, List(dut.io.downGet, dut.io.downPut), dut.io.ordering.all)
      body(setup)
    }
  }

  test("get") {
    doSim(basicGen) { utils =>
      import utils._
      for(bytes <- (6 downto 0).map(1 << _)) {
        get(ups(0).agent, 0, 0x1000, bytes)
      }
      for(bytes <- (6 downto 0).map(1 << _)) {
        get(ups(0).agent, 0, 0x1000+bytes, bytes)
      }
    }
  }
  test("putFull") {
    doSim(basicGen) { utils =>
      import utils._
      for(bytes <- (6 downto 0).map(1 << _)) {
        val data = Array.fill[Byte](bytes)(Random.nextInt().toByte)
        putFullData(ups(0).agent, 0, 0x1000 , data)
        get(ups(0).agent, 0, 0x1000, data.size)
      }
      for(bytes <- (6 downto 0).map(1 << _)) {
        val data = Array.fill[Byte](bytes)(Random.nextInt().toByte)
        putFullData(ups(0).agent, 0, 0x1000+bytes , data)
        get(ups(0).agent, 0, 0x1000+bytes, data.size)
      }
    }
  }
  test("putPartial") {
    doSim(basicGen) { utils =>
      import utils._
      for(bytes <- (6 downto 0).map(1 << _)) {
        val data = Array.fill[Byte](bytes)(Random.nextInt().toByte)
        val mask = Array.fill[Boolean](bytes)(Random.nextInt(2).toBoolean)
        putPartialData(ups(0).agent, 0, 0x1000 , data,mask)
        get(ups(0).agent, 0, 0x1000, data.size)
      }
      for(bytes <- (6 downto 0).map(1 << _)) {
        val data = Array.fill[Byte](bytes)(Random.nextInt().toByte)
        val mask = Array.fill[Boolean](bytes)(Random.nextInt(2).toBoolean)
        putPartialData(ups(0).agent, 0, 0x1000+bytes , data,mask)
        get(ups(0).agent, 0, 0x1000+bytes, data.size)
      }
    }
  }

  test("NtoT->dirty->NtoT->clean") {
    doSim(basicGen) { utils =>
      import utils._
      for (i <- 0 until 10) {
        val block = acquireBlock(ups(0).agent, 0, Param.Grow.NtoT, 0x1000 + i * 0x40, 0x40)
        fork {
          cd.waitSamplingWhere(block.probe.nonEmpty)
          cd.waitSampling(Random.nextInt(20))
          block.dirty = true
          block.data(2) = (i + 0x30).toByte
          block.release()
        }
        val block2 = acquireBlock(ups(0).agent, 4, Param.Grow.NtoT, 0x1000 + i * 0x40, 0x40)
        block2.release()
      }
    }
  }

  test("NtoT->dirty->NtoS->BtoT") {
    doSim(basicGen) { utils =>
      import utils._
      val block = acquireBlock(ups(0).agent, 0, Param.Grow.NtoT, 0x1000, 0x40)
      for (i <- 0 until 10) {
        fork {
          cd.waitSamplingWhere(block.probe.nonEmpty)
          cd.waitSampling(Random.nextInt(20))
          block.dirty = true
          block.data(2) = (i + 0x30).toByte
          block.release()
        }
        val block2 = acquireBlock(ups(0).agent, 4, Param.Grow.NtoB, 0x1000, 0x40)
        block2.release()
        assert(block.cap == Param.Cap.toB)
        acquireBlock(ups(0).agent, 0, Param.Grow.BtoT, 0x1000, 0x40)
        assert(block.cap == Param.Cap.toT)
      }
    }
  }

  test("NtoT->clean->NtoS->BtoT") {
    doSim(basicGen) { utils =>
      import utils._
      val block = acquireBlock(ups(0).agent, 0, Param.Grow.NtoT, 0x1000, 0x40)
      for (i <- 0 until 10) {
        fork {
          cd.waitSamplingWhere(block.probe.nonEmpty)
          cd.waitSampling(Random.nextInt(20))
          block.release()
        }
        val block2 = acquireBlock(ups(0).agent, 4, Param.Grow.NtoB, 0x1000, 0x40)
        block2.release()
        assert(block.cap == Param.Cap.toB)
        acquireBlock(ups(0).agent, 0, Param.Grow.BtoT, 0x1000, 0x40)
        assert(block.cap == Param.Cap.toT)
      }
    }
  }
  test("NtoB->NtoT->BtoT") {
    doSim(basicGen) { utils =>
      import utils._
      var block = acquireBlock(ups(0).agent, 0, Param.Grow.NtoB, 0x1000, 0x40)
      block.release()
      for (i <- 0 until 10) {
        val block2 = acquireBlock(ups(0).agent, 4, Param.Grow.NtoT, 0x1000, 0x40)
        block2.release()
        assert(block.cap == Param.Cap.toN)
        block = acquireBlock(ups(0).agent, 0, Param.Grow.BtoT, 0x1000, 0x40)
        block.release()
        assert(block.cap == Param.Cap.toT)
      }
    }
  }
  test("NtoBx2->BtoTx2") {
    doSim(basicGen) { utils =>
      import utils._
      var block = acquireBlock(ups(0).agent, 0, Param.Grow.NtoB, 0x1000, 0x40)
      block.release()
      var block2 = acquireBlock(ups(0).agent, 4, Param.Grow.NtoB, 0x1000, 0x40)
      block2.release()
      assert(block.cap == Param.Cap.toB)
      assert(block.cap == Param.Cap.toB)
      acquireBlock(ups(0).agent, 0, Param.Grow.BtoT, 0x1000, 0x40)
      block.release()
      assert(block.cap == Param.Cap.toT)
      assert(block2.cap == Param.Cap.toN)
      block2 = acquireBlock(ups(0).agent, 4, Param.Grow.BtoT, 0x1000, 0x40)
      block2.release()
      assert(block.cap == Param.Cap.toN)
      assert(block2.cap == Param.Cap.toT)
    }
  }

  test("miaou"){
    doSim(basicGen){ utils =>
      import utils._
      for(r <- 0 until 8) {
        val busId = 0

        for (i <- 0 until 1) {
          val data = Array.fill[Byte](0x40)(Random.nextInt().toByte)
          val block = acquireBlock(ups(busId).agent, 0, Param.Grow.NtoT, 0x1000 + i * 0x40, 0x40)
          fork{
            cd.waitSamplingWhere(block.probe.nonEmpty)
            cd.waitSampling(Random.nextInt(100))
            block.dirty = true
            block.data(2) = (r+0x30).toByte
            block.release()
            //          ups(busId).agent.block.changeBlockCap(0, 0x1000 + i * 0x40, Param.Cap.toN)
          }
          val block2 = acquireBlock(ups(busId).agent, 4, Param.Grow.NtoT, 0x1000 + i * 0x40, 0x40)
          block2.release()
          //        releaseData(ups(busId).agent, 4, Param.Prune.TtoN, block)
          cd.waitSampling(10)
        }

        for (i <- 0 until 1) {
          get(ups(busId).agent, 0, 0x1000 + i * 0x40, 0x40)
          cd.waitSampling(10)
        }

        for (i <- 0 until 1) {
          val data = Array.fill[Byte](0x40)(Random.nextInt().toByte)
          putFullData(ups(busId).agent, 0, 0x1000 + i * 0x40, data)
          cd.waitSampling(10)
        }

        for (i <- 0 until 1) {
          val bytes = 0x40
          val data = Array.fill[Byte](bytes)(Random.nextInt().toByte)
          val mask = Array.fill[Boolean](bytes)(Random.nextInt(2).toBoolean)
          putPartialData(ups(busId).agent, 0, 0x1000 + i * 0x40, data, mask)
          cd.waitSampling(10)
        }
      }
    }
  }

  def randomized(utils : CoherentHubTesterUtils.Testbench) : Unit = {
    import utils._


    val threads = for(up <- ups; agent = up.agent; m <- agent.bus.p.node.m.masters) yield fork {
      class WeightedDistribution[T](){
        val storage = ArrayBuffer[(Int, () => T)]()
        var total = 0
        def apply(weight : Int)(that : => T) = {
          storage += weight -> (() => that)
          total += weight
        }

        def randomExecute() : T = {
          val rand = Random.nextInt(total)
          var stack = 0
          for((w,body) <- storage) {
            stack += w
            if(rand < stack) return body()
          }
          ???
        }
      }

      val locks = mutable.HashMap[Long, SimMutex]()
      def lock(address : Long) = {
        val l = locks.getOrElseUpdate(address, new SimMutex(randomized = true))
        l.lock()
      }
      def unlock(address : Long) = {
        val l = locks.getOrElseUpdate(address, new SimMutex(randomized = true))
        l.unlock()
      }

      val distribution = new WeightedDistribution[Unit]()
//      distribution(100){
//        val bytes = blockSizes.toList.randomPick()
//        val address = rand.address(bytes)
//        val source = m.mapping.randomPick().id.randomPick().toInt
//        get(agent, source, address, bytes)
//      }

//      distribution(100){
//        val bytes = blockSizes.toList.randomPick()
//        val address = rand.address(bytes)
//        val source = m.mapping.randomPick().id.randomPick().toInt
//        val data = Array.fill[Byte](bytes)(Random.nextInt().toByte)
//        val mask = Array.fill[Boolean](bytes)(Random.nextInt(2).toBoolean)
//        putPartialData(agent, source, address , data,mask)
//      }

      distribution(100){
        val bytes = blockSizes.toList.randomPick()
        val address = rand.address(bytes)
        val source = m.mapping.randomPick().id.randomPick().toInt
        val data = Array.fill[Byte](bytes)(Random.nextInt().toByte)
        putFullData(agent, source, address , data)
      }

      //Read block
      distribution(100){
        val address = rand.address(blockSize)
        val source = m.mapping.randomPick().id.randomPick().toInt
        val data = Array.fill[Byte](blockSize)(Random.nextInt().toByte)
        lock(address)
        var b : Block = null
        agent.block.get(source, address) match {
          case Some(x) => b = x
          case None => {
            b = acquireBlock(agent, source, Param.Grow.NtoB, address, blockSize)
            b.release()
          }
        }
        unlock(address)
      }

      //Write block
      distribution(100){
        val address = rand.address(blockSize)
        val source = m.mapping.randomPick().id.randomPick().toInt
        val data = Array.fill[Byte](blockSize)(Random.nextInt().toByte)
        lock(address)
        var b : Block = null
        agent.block.get(source, address) match {
          case Some(x) => b = x
          case None => {
            b = acquireBlock(agent, source, Param.Grow.NtoT, address, blockSize)
            b.release()
          }
        }
        if(b.cap > Param.Cap.toT){
          b = acquireBlock(agent, source, Param.Grow.BtoT, address, blockSize)
          b.release()
        }
        assert(b.cap == Param.Cap.toT, f"$source $address%x")
        b.dirty = true
        for(i <- 0 until blockSize){
          if(Random.nextBoolean()) b.data(i) = Random.nextInt().toByte
        }
        unlock(address)
      }


      disableSimWave()
      for(r <- 0 until 100000) {
        distribution.randomExecute()
      }
    }
    threads.foreach(_.join())
  }

  test("randomized"){
    doSim(basicGen)(randomized)
  }
}



object CoherencyHubSynt extends App{
  val rtls = ArrayBuffer[Rtl]()
  rtls += Rtl(SpinalVerilog(Rtl.ffIo(new CoherentHub(CoherentHubGen.basicConfig(8)))))
  val targets = XilinxStdTargets().take(2)

  Bench(rtls, targets)
}