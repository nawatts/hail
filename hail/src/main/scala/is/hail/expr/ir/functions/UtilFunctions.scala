package is.hail.expr.ir.functions

import is.hail.asm4s
import is.hail.asm4s._
import is.hail.expr.ir._
import is.hail.expr.types.physical.{PString, PTuple}
import is.hail.utils._
import is.hail.expr.types.virtual._
import org.apache.spark.sql.Row

import scala.reflect.ClassTag

object UtilFunctions extends RegistryFunctions {

  def parseBoolean(s: String): Boolean = s.toBoolean

  def parseInt32(s: String): Int = s.toInt

  def parseInt64(s: String): Long = s.toLong

  private val NAN = 1
  private val POS_INF = 2
  private val NEG_INF = 3

  def parseSpecialNum(s: String): Int = s.length match {
    case 3 if s equalsCI "nan" => NAN
    case 4 if (s equalsCI "+nan") || (s equalsCI "-nan") => NAN
    case 3 if s equalsCI "inf" => POS_INF
    case 4 if s equalsCI "+inf" => POS_INF
    case 4 if s equalsCI "-inf" => NEG_INF
    case 8 if s equalsCI "infinity" => POS_INF
    case 9 if s equalsCI "+infinity" => POS_INF
    case 9 if s equalsCI "-infinity" => NEG_INF
    case _ => 0
  }

  def parseFloat32(s: String): Float = parseSpecialNum(s) match {
    case NAN => Float.NaN
    case POS_INF => Float.PositiveInfinity
    case NEG_INF => Float.NegativeInfinity
    case _ => s.toFloat
  }

  def parseFloat64(s: String): Double = parseSpecialNum(s) match {
    case NAN => Double.NaN
    case POS_INF => Double.PositiveInfinity
    case NEG_INF => Double.NegativeInfinity
    case _ => s.toDouble
  }

  def isValidBoolean(s: String): Boolean =
    (s equalsCI "true") || (s equalsCI "false")

  def isValidInt32(s: String): Boolean =
    try { s.toInt; true } catch { case _: NumberFormatException => false }

  def isValidInt64(s: String): Boolean =
    try { s.toLong; true } catch { case _: NumberFormatException => false }

  def isValidFloat32(s: String): Boolean = parseSpecialNum(s) match {
    case 0 => try { s.toFloat; true } catch { case _: NumberFormatException => false }
    case _ => true
  }

  def isValidFloat64(s: String): Boolean = parseSpecialNum(s) match {
    case 0 => try { s.toDouble; true } catch { case _: NumberFormatException => false }
    case _ => true
  }

  def min_ignore_missing(l: Int, lMissing: Boolean, r: Int, rMissing: Boolean): Int =
    if (lMissing) r else if (rMissing) l else Math.min(l, r)

  def min_ignore_missing(l: Long, lMissing: Boolean, r: Long, rMissing: Boolean): Long =
    if (lMissing) r else if (rMissing) l else Math.min(l, r)

  def min_ignore_missing(l: Float, lMissing: Boolean, r: Float, rMissing: Boolean): Float =
    if (lMissing) r else if (rMissing) l else Math.min(l, r)

  def min_ignore_missing(l: Double, lMissing: Boolean, r: Double, rMissing: Boolean): Double =
    if (lMissing) r else if (rMissing) l else Math.min(l, r)

  def max_ignore_missing(l: Int, lMissing: Boolean, r: Int, rMissing: Boolean): Int =
    if (lMissing) r else if (rMissing) l else Math.max(l, r)

  def max_ignore_missing(l: Long, lMissing: Boolean, r: Long, rMissing: Boolean): Long =
    if (lMissing) r else if (rMissing) l else Math.max(l, r)

  def max_ignore_missing(l: Float, lMissing: Boolean, r: Float, rMissing: Boolean): Float =
    if (lMissing) r else if (rMissing) l else Math.max(l, r)

  def max_ignore_missing(l: Double, lMissing: Boolean, r: Double, rMissing: Boolean): Double =
    if (lMissing) r else if (rMissing) l else Math.max(l, r)

  def nanmax(l: Double, r: Double): Double =
    if (java.lang.Double.isNaN(l)) r else if (java.lang.Double.isNaN(r)) l else Math.max(l, r)

  def nanmax(l: Float, r: Float): Float =
    if (java.lang.Float.isNaN(l)) r else if (java.lang.Float.isNaN(r)) l else Math.max(l, r)

  def nanmin(l: Double, r: Double): Double =
    if (java.lang.Double.isNaN(l)) r else if (java.lang.Double.isNaN(r)) l else Math.min(l, r)

  def nanmin(l: Float, r: Float): Float =
    if (java.lang.Float.isNaN(l)) r else if (java.lang.Float.isNaN(r)) l else Math.min(l, r)

  def nanmin_ignore_missing(l: Float, lMissing: Boolean, r: Float, rMissing: Boolean): Float =
    if (lMissing) r else if (rMissing) l else nanmin(l, r)

  def nanmin_ignore_missing(l: Double, lMissing: Boolean, r: Double, rMissing: Boolean): Double =
    if (lMissing) r else if (rMissing) l else nanmin(l, r)

  def nanmax_ignore_missing(l: Float, lMissing: Boolean, r: Float, rMissing: Boolean): Float =
    if (lMissing) r else if (rMissing) l else nanmax(l, r)

  def nanmax_ignore_missing(l: Double, lMissing: Boolean, r: Double, rMissing: Boolean): Double =
    if (lMissing) r else if (rMissing) l else nanmax(l, r)

  def intMin(a: IR, b: IR): IR = If(ApplyComparisonOp(LT(a.typ), a, b), a, b)

  def intMax(a: IR, b: IR): IR = If(ApplyComparisonOp(GT(a.typ), a, b), a, b)

  def format(f: String, args: Row): String =
    String.format(f, args.toSeq.map(_.asInstanceOf[java.lang.Object]): _*)

  def registerAll() {
    val thisClass = getClass

    registerCode("valuesSimilar", tv("T"), tv("U"), TFloat64(), TBoolean(), TBoolean(), null) {
      case (er, rt, (lT, l), (rT, r), (tolT, tolerance), (absT, absolute)) =>
        assert(lT.virtualType.isOfType(rT.virtualType))
        val lb = boxArg(er, lT)(l)
        val rb = boxArg(er, rT)(r)
        er.mb.getType(lT.virtualType).invoke[Any, Any, Double, Boolean, Boolean]("valuesSimilar", lb, rb, tolerance, absolute)
    }

    registerCode[Int]("triangle", TInt32(), TInt32(), null) { case (_, rt, (nT, n: Code[Int])) => (n * (n + 1)) / 2 }

    registerCode[Boolean]("toInt32", TBoolean(), TInt32(), null) { case (_, rt, (xT, x: Code[Boolean])) => x.toI }
    registerCode[Boolean]("toInt64", TBoolean(), TInt64(), null) { case (_, rt, (xT, x: Code[Boolean])) => x.toI.toL }
    registerCode[Boolean]("toFloat32", TBoolean(), TFloat32(), null) { case (_, rt, (xT, x: Code[Boolean])) => x.toI.toF }
    registerCode[Boolean]("toFloat64", TBoolean(), TFloat64(), null) { case (_, rt, (xT, x: Code[Boolean])) => x.toI.toD }

    for ((name, t, ct) <- Seq[(String, Type, ClassTag[_])](
      ("Boolean", TBoolean(), implicitly[ClassTag[Boolean]]),
      ("Int32", TInt32(), implicitly[ClassTag[Int]]),
      ("Int64", TInt64(), implicitly[ClassTag[Long]]),
      ("Float64", TFloat64(), implicitly[ClassTag[Double]]),
      ("Float32", TFloat32(), implicitly[ClassTag[Float]])
    )) {
      val ctString: ClassTag[String] = implicitly
      registerCode(s"to$name", TString(), t, null) {
        case (r, rt, (xT: PString, x: Code[Long])) =>
          val s = asm4s.coerce[String](wrapArg(r, xT)(x))
          Code.invokeScalaObject(thisClass, s"parse$name", s)(ctString, ct)
      }
      registerCodeWithMissingness(s"to${name}OrMissing", TString(), t, null) {
        case (r, rt, (xT: PString, x: EmitTriplet)) =>
          val s = r.mb.newLocal[String]
          val m = r.mb.newLocal[Boolean]
          EmitTriplet(
            Code(x.setup, m := x.m, s := m.mux(Code._null[String], asm4s.coerce[String](wrapArg(r, xT)(x.v)))),
            (m || !Code.invokeScalaObject[String, Boolean](thisClass, s"isValid$name", s)),
            Code.invokeScalaObject(thisClass, s"parse$name", s)(ctString, ct))
      }
    }

    Array(TInt32(), TInt64()).foreach { t =>
      registerIR("min", t, t, t)(intMin)
      registerIR("max", t, t, t)(intMax)
    }

    Array("min", "max").foreach { name =>
      registerCode(name, TFloat32(), TFloat32(), TFloat32(), null) {
        case (r, rt, (t1, v1: Code[Float]), (t2, v2: Code[Float])) =>
          Code.invokeStatic[Math, Float, Float, Float](name, v1, v2)
      }

      registerCode(name, TFloat64(), TFloat64(), TFloat64(), null) {
        case (r, rt, (t1, v1: Code[Double]), (t2, v2: Code[Double])) =>
          Code.invokeStatic[Math, Double, Double, Double](name, v1, v2)
      }

      val ignoreMissingName = name + "_ignore_missing"
      val ignoreNanName = "nan" + name
      val ignoreBothName = ignoreNanName + "_ignore_missing"

      registerCode(ignoreNanName, TFloat32(), TFloat32(), TFloat32(), null) {
        case (r, rt, (t1, v1: Code[Float]), (t2, v2: Code[Float])) =>
          Code.invokeScalaObject[Float, Float, Float](thisClass, ignoreNanName, v1, v2)
      }

      registerCode(ignoreNanName, TFloat64(), TFloat64(), TFloat64(), null) {
        case (r, rt, (t1, v1: Code[Double]), (t2, v2: Code[Double])) =>
          Code.invokeScalaObject[Double, Double, Double](thisClass, ignoreNanName, v1, v2)
      }

      def ignoreMissingTriplet[T](v1: EmitTriplet, v2: EmitTriplet, name: String)(implicit ct: ClassTag[T]): EmitTriplet =
        EmitTriplet(
          Code(v1.setup, v2.setup),
          v1.m && v2.m,
          Code.invokeScalaObject[T, Boolean, T, Boolean, T](thisClass, name, v1.v.asInstanceOf[Code[T]], v1.m, v2.v.asInstanceOf[Code[T]], v2.m)
        )

      registerCodeWithMissingness(ignoreMissingName, TInt32(), TInt32(), TInt32(), null) {
        case (r, rt, (t1, v1), (t2, v2)) => ignoreMissingTriplet[Int](v1, v2, ignoreMissingName)
      }

      registerCodeWithMissingness(ignoreMissingName, TInt64(), TInt64(), TInt64(), null) {
        case (r, rt, (t1, v1), (t2, v2)) => ignoreMissingTriplet[Long](v1, v2, ignoreMissingName)
      }

      registerCodeWithMissingness(ignoreMissingName, TFloat32(), TFloat32(), TFloat32(), null) {
        case (r, rt, (t1, v1), (t2, v2)) => ignoreMissingTriplet[Float](v1, v2, ignoreMissingName)
      }

      registerCodeWithMissingness(ignoreMissingName, TFloat64(), TFloat64(), TFloat64(), null) {
        case (r, rt, (t1, v1), (t2, v2)) => ignoreMissingTriplet[Double](v1, v2, ignoreMissingName)
      }

      registerCodeWithMissingness(ignoreBothName, TFloat32(), TFloat32(), TFloat32(), null) {
        case (r, rt, (t1, v1), (t2, v2)) => ignoreMissingTriplet[Float](v1, v2, ignoreBothName)
      }

      registerCodeWithMissingness(ignoreBothName, TFloat64(), TFloat64(), TFloat64(), null) {
        case (r, rt, (t1, v1), (t2, v2)) => ignoreMissingTriplet[Double](v1, v2, ignoreBothName)
      }
    }

    registerCode("format", TString(), tv("T", "tuple"), TString(), null) {
      case (r, rt, (fmtT: PString, format: Code[Long]), (argsT: PTuple, args: Code[Long])) =>
        unwrapReturn(r, rt)(Code.invokeScalaObject[String, Row, String](thisClass, "format",
          asm4s.coerce[String](wrapArg(r, fmtT)(format)),
          Code.checkcast[Row](asm4s.coerce[java.lang.Object](wrapArg(r, argsT)(args)))))
    }

    registerCodeWithMissingness("&&", TBoolean(), TBoolean(), TBoolean(), null) {
      case (er, rt, (lT, l), (rT, r)) =>
        val lm = Code(l.setup, l.m)
        val rm = Code(r.setup, r.m)

        val lv = l.value[Boolean]
        val rv = r.value[Boolean]

        val m = er.mb.newLocal[Boolean]
        val v = er.mb.newLocal[Boolean]
        val setup = Code(m := lm, v := !m && lv)
        val missing = m.mux(rm || rv, v && (rm || Code(v := rv, false)))
        val value = v

        EmitTriplet(setup, missing, value)
    }

    registerCodeWithMissingness("||", TBoolean(), TBoolean(), TBoolean(), null) {
      case (er, rt, (lT, l), (rT, r)) =>
        val lm = Code(l.setup, l.m)
        val rm = Code(r.setup, r.m)

        val lv = l.value[Boolean]
        val rv = r.value[Boolean]

        val m = er.mb.newLocal[Boolean]
        val v = er.mb.newLocal[Boolean]
        val setup = Code(m := lm, v := m || lv)
        val missing = m.mux(rm || !rv, !v && (rm || Code(v := rv, false)))
        val value = v

        EmitTriplet(setup, missing, value)
    }
  }
}
