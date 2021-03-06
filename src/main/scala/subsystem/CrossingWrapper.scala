// See LICENSE.SiFive for license details.

package freechips.rocketchip.subsystem

import Chisel._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._

/** Enumerates the three types of clock crossing between tiles and system bus */
sealed trait SubsystemClockCrossing
{
  def sameClock = this match {
    case _: SynchronousCrossing => true
    case _ => false
  }
}
case class SynchronousCrossing(params: BufferParams = BufferParams.default) extends SubsystemClockCrossing
case class RationalCrossing(direction: RationalDirection = FastToSlow) extends SubsystemClockCrossing
case class AsynchronousCrossing(depth: Int, sync: Int = 3) extends SubsystemClockCrossing

class CrossingHelper(parent: LazyModule with LazyScope, arg: SubsystemClockCrossing, name: String) {
  def this(parent: LazyModule with LazyScope, arg: SubsystemClockCrossing)(implicit valName: ValName) = this(parent, arg, valName.name)

  // Detect incorrect crossing connectivity
  private def crossingCheck(out: Boolean, source: BaseNode, sink: BaseNode) {
    InModuleBody {
      def inside(node: BaseNode) = node.parents.exists(_ eq parent)
      source.inputs.foreach { case (syncSource, _) =>
        require (inside(syncSource) == out, s"${syncSource.name} source must ${if(out)""else"not "}be inside ${parent.name} (wrong .cross direction?)")
      }
      sink.outputs.foreach { case (syncSink, _) =>
        require (inside(syncSink) != out, s"${syncSink.name} sink must ${if(out)"not "else""}be inside ${parent.name} (wrong .cross direction?)")
      }
    }
  }

  // TileLink

  def crossTLSyncInOut(out: Boolean)(params: BufferParams = BufferParams.default)(implicit p: Parameters): TLNode = {
    lazy val sync_xing = LazyModule(new TLBuffer(params))
    crossingCheck(out, sync_xing.node, sync_xing.node)
    if (!out) parent { TLNameNode(name) :*=* sync_xing.node }
    else      parent { sync_xing.node :*=* TLNameNode(name) }
  }

  def crossTLAsyncInOut(out: Boolean)(depth: Int = 8, sync: Int = 3)(implicit p: Parameters): TLNode = {
    lazy val async_xing_source = LazyModule(new TLAsyncCrossingSource(sync))
    lazy val async_xing_sink = LazyModule(new TLAsyncCrossingSink(depth, sync))
    val source = if (out) parent { TLAsyncNameNode(name) :*=* async_xing_source.node } else async_xing_source.node
    val sink = if (out) async_xing_sink.node else parent { async_xing_sink.node :*=* TLAsyncNameNode(name) }
    crossingCheck(out, async_xing_source.node, async_xing_sink.node)
    sink :*=* source
    NodeHandle(source, sink)
  }

  def crossTLRationalInOut(out: Boolean)(direction: RationalDirection)(implicit p: Parameters): TLNode = {
    lazy val rational_xing_source = LazyModule(new TLRationalCrossingSource)
    lazy val rational_xing_sink = LazyModule(new TLRationalCrossingSink(if (out) direction else direction.flip))
    val source = if (out) parent { TLRationalNameNode(name) :*=* rational_xing_source.node } else rational_xing_source.node
    val sink = if (out) rational_xing_sink.node else parent { rational_xing_sink.node :*=* TLRationalNameNode(name) }
    crossingCheck(out, rational_xing_source.node, rational_xing_sink.node)
    sink :*=* source
    NodeHandle(source, sink)
  }

  def crossTLSyncIn (params: BufferParams = BufferParams.default)(implicit p: Parameters): TLNode = crossTLSyncInOut(false)(params)
  def crossTLSyncOut(params: BufferParams = BufferParams.default)(implicit p: Parameters): TLNode = crossTLSyncInOut(true )(params)
  def crossTLAsyncIn (depth: Int = 8, sync: Int = 3)(implicit p: Parameters): TLNode = crossTLAsyncInOut(false)(depth, sync)
  def crossTLAsyncOut(depth: Int = 8, sync: Int = 3)(implicit p: Parameters): TLNode = crossTLAsyncInOut(true )(depth, sync)
  def crossTLRationalIn (direction: RationalDirection)(implicit p: Parameters): TLNode = crossTLRationalInOut(false)(direction)
  def crossTLRationalOut(direction: RationalDirection)(implicit p: Parameters): TLNode = crossTLRationalInOut(true )(direction)

  def crossTLIn(implicit p: Parameters): TLNode = arg match {
    case x: SynchronousCrossing  => crossTLSyncIn(x.params)
    case x: AsynchronousCrossing => crossTLAsyncIn(x.depth, x.sync)
    case x: RationalCrossing     => crossTLRationalIn(x.direction)
  }

  def crossTLOut(implicit p: Parameters): TLNode = arg match {
    case x: SynchronousCrossing  => crossTLSyncOut(x.params)
    case x: AsynchronousCrossing => crossTLAsyncOut(x.depth, x.sync)
    case x: RationalCrossing     => crossTLRationalOut(x.direction)
  }

  // AXI4

  def crossAXI4SyncInOut(out: Boolean)(params: BufferParams = BufferParams.default)(implicit p: Parameters): AXI4Node = {
    val axi4_sync_xing = LazyModule(new AXI4Buffer(params))
    crossingCheck(out, axi4_sync_xing.node, axi4_sync_xing.node)
    if (!out) parent { AXI4NameNode(name) :*=* axi4_sync_xing.node }
    else      parent { axi4_sync_xing.node :*=* AXI4NameNode(name) }
  }

  def crossAXI4AsyncInOut(out: Boolean)(depth: Int = 8, sync: Int = 3)(implicit p: Parameters): AXI4Node = {
    lazy val axi4_async_xing_source = LazyModule(new AXI4AsyncCrossingSource(sync))
    lazy val axi4_async_xing_sink = LazyModule(new AXI4AsyncCrossingSink(depth, sync))
    val source = if (out) parent { AXI4AsyncNameNode(name) :*=* axi4_async_xing_source.node } else axi4_async_xing_source.node
    val sink = if (out) axi4_async_xing_sink.node else parent { axi4_async_xing_sink.node :*=* AXI4AsyncNameNode(name) }
    crossingCheck(out, axi4_async_xing_source.node, axi4_async_xing_sink.node)
    sink :*=* source
    NodeHandle(source, sink)
  }

  def crossAXI4SyncIn (params: BufferParams = BufferParams.default)(implicit p: Parameters): AXI4Node = crossAXI4SyncInOut(false)(params)
  def crossAXI4SyncOut(params: BufferParams = BufferParams.default)(implicit p: Parameters): AXI4Node = crossAXI4SyncInOut(true )(params)
  def crossAXI4AsyncIn (depth: Int = 8, sync: Int = 3)(implicit p: Parameters): AXI4Node = crossAXI4AsyncInOut(false)(depth, sync)
  def crossAXI4AsyncOut(depth: Int = 8, sync: Int = 3)(implicit p: Parameters): AXI4Node = crossAXI4AsyncInOut(true )(depth, sync)

  def crossAXI4In(implicit p: Parameters): AXI4Node = arg match {
    case x: SynchronousCrossing  => crossAXI4SyncIn(x.params)
    case x: AsynchronousCrossing => crossAXI4AsyncIn(x.depth, x.sync)
    case x: RationalCrossing     => throw new IllegalArgumentException("AXI4 Rational crossing unimplemented")
  }

  def crossAXI4Out(implicit p: Parameters): AXI4Node = arg match {
    case x: SynchronousCrossing  => crossAXI4SyncOut(x.params)
    case x: AsynchronousCrossing => crossAXI4AsyncOut(x.depth, x.sync)
    case x: RationalCrossing     => throw new IllegalArgumentException("AXI4 Rational crossing unimplemented")
  }

  // Interrupts

  def crossIntSyncInOut(out: Boolean)(alreadyRegistered: Boolean = false)(implicit p: Parameters): IntNode = {
    lazy val int_sync_xing_source = LazyModule(new IntSyncCrossingSource(alreadyRegistered))
    lazy val int_sync_xing_sink = LazyModule(new IntSyncCrossingSink(0))
    val source = if (out) parent { IntSyncNameNode(name) :*=* int_sync_xing_source.node } else int_sync_xing_source.node
    val sink = if (out) int_sync_xing_sink.node else parent { int_sync_xing_sink.node :*=* IntSyncNameNode(name) }
    crossingCheck(out, int_sync_xing_source.node, int_sync_xing_sink.node)
    sink :*=* source
    NodeHandle(source, sink)
  }

  def crossIntAsyncInOut(out: Boolean)(sync: Int = 3, alreadyRegistered: Boolean = false)(implicit p: Parameters): IntNode = {
    lazy val int_async_xing_source = LazyModule(new IntSyncCrossingSource(alreadyRegistered))
    lazy val int_async_xing_sink = LazyModule(new IntSyncCrossingSink(sync))
    val source = if (out) parent {  IntSyncNameNode(name) :*=* int_async_xing_source.node } else int_async_xing_source.node
    val sink = if (out) int_async_xing_sink.node else parent { int_async_xing_sink.node :*=* IntSyncNameNode(name) }
    crossingCheck(out, int_async_xing_source.node, int_async_xing_sink.node)
    sink :*=* source
    NodeHandle(source, sink)
  }

  def crossIntRationalInOut(out: Boolean)(alreadyRegistered: Boolean = false)(implicit p: Parameters): IntNode = {
    lazy val int_rational_xing_source = LazyModule(new IntSyncCrossingSource(alreadyRegistered))
    lazy val int_rational_xing_sink = LazyModule(new IntSyncCrossingSink(1))
    val source = if (out) parent { IntSyncNameNode(name) :*=* int_rational_xing_source.node } else int_rational_xing_source.node
    val sink = if (out) int_rational_xing_sink.node else parent {  int_rational_xing_sink.node :*=* IntSyncNameNode(name) }
    crossingCheck(out, int_rational_xing_source.node, int_rational_xing_sink.node)
    sink :*=* source
    NodeHandle(source, sink)
  }

  def crossIntSyncIn (alreadyRegistered: Boolean = false)(implicit p: Parameters): IntNode = crossIntSyncInOut(false)(alreadyRegistered)
  def crossIntSyncOut(alreadyRegistered: Boolean = false)(implicit p: Parameters): IntNode = crossIntSyncInOut(true )(alreadyRegistered)
  def crossIntAsyncIn (sync: Int = 3, alreadyRegistered: Boolean = false)(implicit p: Parameters): IntNode = crossIntAsyncInOut(false)(sync, alreadyRegistered)
  def crossIntAsyncOut(sync: Int = 3, alreadyRegistered: Boolean = false)(implicit p: Parameters): IntNode = crossIntAsyncInOut(true )(sync, alreadyRegistered)
  def crossIntRationalIn (alreadyRegistered: Boolean = false)(implicit p: Parameters): IntNode = crossIntRationalInOut(false)(alreadyRegistered)
  def crossIntRationalOut(alreadyRegistered: Boolean = false)(implicit p: Parameters): IntNode = crossIntRationalInOut(true )(alreadyRegistered)

  def crossIntIn(alreadyRegistered: Boolean)(implicit p: Parameters): IntNode = arg match {
    case x: SynchronousCrossing  => crossIntSyncIn(alreadyRegistered)
    case x: AsynchronousCrossing => crossIntAsyncIn(x.sync, alreadyRegistered)
    case x: RationalCrossing     => crossIntRationalIn(alreadyRegistered)
  }

  def crossIntOut(alreadyRegistered: Boolean)(implicit p: Parameters): IntNode = arg match {
    case x: SynchronousCrossing  => crossIntSyncOut(alreadyRegistered)
    case x: AsynchronousCrossing => crossIntAsyncOut(x.sync, alreadyRegistered)
    case x: RationalCrossing     => crossIntRationalOut(alreadyRegistered)
  }

  def crossIntIn (implicit p: Parameters): IntNode = crossIntIn (false)
  def crossIntOut(implicit p: Parameters): IntNode = crossIntOut(false)
}


trait HasCrossing extends LazyScope
{
  this: LazyModule =>

  def crossing: SubsystemClockCrossing
  protected lazy val xing = new CrossingHelper(this, crossing, "xing")

  def crossTLIn   (implicit p: Parameters): TLNode  = xing.crossTLIn
  def crossTLOut  (implicit p: Parameters): TLNode  = xing.crossTLOut
  def crossAXI4In (implicit p: Parameters): AXI4Node= xing.crossAXI4In
  def crossAXI4Out(implicit p: Parameters): AXI4Node= xing.crossAXI4Out
  def crossIntIn  (implicit p: Parameters): IntNode = xing.crossIntIn
  def crossIntOut (implicit p: Parameters): IntNode = xing.crossIntOut

  def crossIntIn (alreadyRegistered: Boolean)(implicit p: Parameters): IntNode = xing.crossIntIn (alreadyRegistered)
  def crossIntOut(alreadyRegistered: Boolean)(implicit p: Parameters): IntNode = xing.crossIntOut(alreadyRegistered)
}

class CrossingWrapper(val crossing: SubsystemClockCrossing)(implicit p: Parameters) extends SimpleLazyModule with HasCrossing
