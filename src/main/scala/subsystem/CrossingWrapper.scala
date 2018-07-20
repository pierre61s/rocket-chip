// See LICENSE.SiFive for license details.

package freechips.rocketchip.subsystem

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.interrupts._

/** Given a constant crossing type, define a bunch of helper methods for
  * crossing to all the procotols.
  * Note: only use this if you don't care that all signals of a given protocol
  *       type will have the same name!
  */
trait HasCrossing extends LazyScope { this: LazyModule =>

  def crossing: ClockCrossingType

  val tlXing = new TLCrossingHelper(this)
  val axi4Xing = new AXI4CrossingHelper(this)
  val intXing = new IntCrossingHelper(this)

  def crossTLIn   (implicit p: Parameters): TLNode   = tlXing.crossIn(crossing)
  def crossTLOut  (implicit p: Parameters): TLNode   = tlXing.crossOut(crossing)
  def crossAXI4In (implicit p: Parameters): AXI4Node = axi4Xing.crossIn(crossing)
  def crossAXI4Out(implicit p: Parameters): AXI4Node = axi4Xing.crossOut(crossing)
  def crossIntIn  (implicit p: Parameters): IntNode  = intXing.crossIn(crossing)
  def crossIntOut (implicit p: Parameters): IntNode  = intXing.crossOut(crossing)
  def crossIntIn (alreadyRegistered: Boolean)(implicit p: Parameters): IntNode = intXing.crossIn (alreadyRegistered, crossing)
  def crossIntOut(alreadyRegistered: Boolean)(implicit p: Parameters): IntNode = intXing.crossOut(alreadyRegistered, crossing)
}

/** A convenient way of creating a LazyScope with a particular uniform clock relationship */
class CrossingWrapper(val crossing: ClockCrossingType)(implicit p: Parameters) extends SimpleLazyModule with HasCrossing
