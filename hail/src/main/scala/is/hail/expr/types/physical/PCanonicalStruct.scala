package is.hail.expr.types.physical

import is.hail.annotations._
import is.hail.asm4s.Code
import is.hail.expr.types.BaseStruct
import is.hail.expr.types.virtual.{TStruct, Type}
import is.hail.utils._
import org.apache.spark.sql.Row

import collection.JavaConverters._

object PCanonicalStruct {
  private val requiredEmpty = PCanonicalStruct(Array.empty[PField], true)
  private val optionalEmpty = PCanonicalStruct(Array.empty[PField], false)

  def empty(required: Boolean = false): PStruct = if (required) requiredEmpty else optionalEmpty

  def apply(required: Boolean, args: (String, PType)*): PStruct =
    PCanonicalStruct(args
      .iterator
      .zipWithIndex
      .map { case ((n, t), i) => PField(n, t, i) }
      .toFastIndexedSeq,
      required)

  def apply(names: java.util.List[String], types: java.util.List[PType], required: Boolean): PStruct = {
    val sNames = names.asScala.toArray
    val sTypes = types.asScala.toArray
    if (sNames.length != sTypes.length)
      fatal(s"number of names does not match number of types: found ${ sNames.length } names and ${ sTypes.length } types")

    PCanonicalStruct(required, sNames.zip(sTypes): _*)
  }

  def apply(args: (String, PType)*): PStruct =
    PCanonicalStruct(false, args:_*)

  def canonical(t: Type): PStruct = PType.canonical(t).asInstanceOf[PStruct]
  def canonical(t: PType): PStruct = PType.canonical(t).asInstanceOf[PStruct]
}

final case class PCanonicalStruct(fields: IndexedSeq[PField], required: Boolean = false) extends PStruct {
  assert(fields.zipWithIndex.forall  { case (f, i) => f.index == i })

  val types: Array[PType] = fields.map(_.typ).toArray

  if (!fieldNames.areDistinct()) {
    val duplicates = fieldNames.duplicates()
    fatal(s"cannot create struct with duplicate ${plural(duplicates.size, "field")}: " +
      s"${fieldNames.map(prettyIdentifier).mkString(", ")}", fieldNames.duplicates())
  }

  val (missingIdx: Array[Int], nMissing: Int) = BaseStruct.getMissingIndexAndCount(types.map(_.required))
  val nMissingBytes = (nMissing + 7) >>> 3
  val byteOffsets = new Array[Long](size)
  override val byteSize: Long = PBaseStruct.getByteSizeAndOffsets(types, nMissingBytes, byteOffsets)
  override val alignment: Long = PBaseStruct.alignment(types)

  def copy(fields: IndexedSeq[PField] = this.fields, required: Boolean = this.required): PStruct = PCanonicalStruct(fields, required)

  override def truncate(newSize: Int): PStruct =
    PCanonicalStruct(fields.take(newSize), required)

  def unsafeStructInsert(typeToInsert: PType, path: List[String]): (PStruct, UnsafeInserter) = {
    assert(typeToInsert.isInstanceOf[PStruct] || path.nonEmpty)
    val (t, i) = unsafeInsert(typeToInsert, path)
    (t.asInstanceOf[PStruct], i)
  }

  override def unsafeInsert(typeToInsert: PType, path: List[String]): (PType, UnsafeInserter) = {
    if (path.isEmpty) {
      (typeToInsert, (region, offset, rvb, inserter) => inserter())
    } else {
      val localSize = size
      val key = path.head
      selfField(key) match {
        case Some(f) =>
          val j = f.index
          val (insertedFieldType, fieldInserter) = f.typ.unsafeInsert(typeToInsert, path.tail)

          (updateKey(key, j, insertedFieldType), { (region, offset, rvb, inserter) =>
            rvb.startStruct()
            var i = 0
            while (i < j) {
              if (region != null)
                rvb.addField(this, region, offset, i)
              else
                rvb.setMissing()
              i += 1
            }
            if (region != null && isFieldDefined(offset, j))
              fieldInserter(region, loadField(offset, j), rvb, inserter)
            else
              fieldInserter(null, 0, rvb, inserter)
            i += 1
            while (i < localSize) {
              if (region != null)
                rvb.addField(this, region, offset, i)
              else
                rvb.setMissing()
              i += 1
            }
            rvb.endStruct()
          })

        case None =>
          val (insertedFieldType, fieldInserter) = PStruct.empty().unsafeInsert(typeToInsert, path.tail)

          (appendKey(key, insertedFieldType), { (region, offset, rvb, inserter) =>
            rvb.startStruct()
            var i = 0
            while (i < localSize) {
              if (region != null)
                rvb.addField(this, region, offset, i)
              else
                rvb.setMissing()
              i += 1
            }
            fieldInserter(null, 0, rvb, inserter)
            rvb.endStruct()
          })
      }
    }
  }

  def updateKey(key: String, i: Int, sig: PType): PStruct = {
    assert(fieldIdx.contains(key))

    val newFields = Array.fill[PField](fields.length)(null)
    for (i <- fields.indices)
      newFields(i) = fields(i)
    newFields(i) = PField(key, sig, i)
    PCanonicalStruct(newFields, required)
  }

  def deleteField(key: String): PStruct = {
    assert(fieldIdx.contains(key))
    val index = fieldIdx(key)
    if (fields.length == 1)
      PCanonicalStruct.empty()
    else {
      val newFields = Array.fill[PField](fields.length - 1)(null)
      for (i <- 0 until index)
        newFields(i) = fields(i)
      for (i <- index + 1 until fields.length)
        newFields(i - 1) = fields(i).copy(index = i - 1)
      PCanonicalStruct(newFields, required)
    }
  }

  def appendKey(key: String, sig: PType): PStruct = {
    assert(!fieldIdx.contains(key))
    val newFields = Array.fill[PField](fields.length + 1)(null)
    for (i <- fields.indices)
      newFields(i) = fields(i)
    newFields(fields.length) = PField(key, sig, fields.length)
    PCanonicalStruct(newFields, required)
  }


  def rename(m: Map[String, String]): PStruct = {
    val newFieldsBuilder = new ArrayBuilder[(String, PType)]()
    fields.foreach { fd =>
      val n = fd.name
      newFieldsBuilder += (m.getOrElse(n, n) -> fd.typ)
    }
    PCanonicalStruct(newFieldsBuilder.result(): _*)
  }

  def ++(that: PStruct): PStruct = {
    val overlapping = fields.map(_.name).toSet.intersect(
      that.fields.map(_.name).toSet)
    if (overlapping.nonEmpty)
      fatal(s"overlapping fields in struct concatenation: ${ overlapping.mkString(", ") }")

    PCanonicalStruct(fields.map(f => (f.name, f.typ)) ++ that.fields.map(f => (f.name, f.typ)): _*)
  }

  override def _pretty(sb: StringBuilder, indent: Int, compact: Boolean) {
    if (compact) {
      sb.append("PCStruct{")
      fields.foreachBetween(_.pretty(sb, indent, compact))(sb += ',')
      sb += '}'
    } else {
      if (size == 0)
        sb.append("Struct { }")
      else {
        sb.append("Struct {")
        sb += '\n'
        fields.foreachBetween(_.pretty(sb, indent + 4, compact))(sb.append(",\n"))
        sb += '\n'
        sb.append(" " * indent)
        sb += '}'
      }
    }
  }

  def selectFields(names: Seq[String]): PStruct = {
    PCanonicalStruct(required,
      names.map(f => f -> field(f).typ): _*)
  }

  def select(keep: IndexedSeq[String]): (PStruct, (Row) => Row) = {
    val t = PCanonicalStruct(keep.map { n =>
      n -> field(n).typ
    }: _*)

    val keepIdx = keep.map(fieldIdx)
    val selectF: Row => Row = { r =>
      Row.fromSeq(keepIdx.map(r.get))
    }
    (t, selectF)
  }

  def dropFields(names: Set[String]): PStruct =
    selectFields(fieldNames.filter(!names.contains(_)))

  def typeAfterSelect(keep: IndexedSeq[Int]): PStruct =
    PCanonicalStruct(keep.map(i => fieldNames(i) -> types(i)): _*)

  lazy val structFundamentalType: PStruct = {
    val fundamentalFieldTypes = fields.map(f => f.typ.fundamentalType)
    if ((fields, fundamentalFieldTypes).zipped
      .forall { case (f, ft) => f.typ == ft })
      this
    else {
      PCanonicalStruct(required, (fields, fundamentalFieldTypes).zipped.map { case (f, ft) => (f.name, ft) }: _*)
    }
  }

  def loadField(offset: Code[Long], fieldName: String): Code[Long] =
    loadField(offset, fieldIdx(fieldName))

  def isFieldMissing(offset: Code[Long], field: String): Code[Boolean] =
    isFieldMissing(offset, fieldIdx(field))

  def fieldOffset(offset: Code[Long], fieldName: String): Code[Long] =
    fieldOffset(offset, fieldIdx(fieldName))

  def setFieldPresent(offset: Code[Long], field: String): Code[Unit] =
    setFieldPresent(offset, fieldIdx(field))

  def setFieldMissing(offset: Code[Long], field: String): Code[Unit] =
    setFieldMissing(offset, fieldIdx(field))

  def insertFields(fieldsToInsert: TraversableOnce[(String, PType)]): PStruct = {
    val ab = new ArrayBuilder[PField](fields.length)
    var i = 0
    while (i < fields.length) {
      ab += fields(i)
      i += 1
    }
    val it = fieldsToInsert.toIterator
    while (it.hasNext) {
      val (name, typ) = it.next
      if (fieldIdx.contains(name)) {
        val j = fieldIdx(name)
        ab(j) = PField(name, typ, j)
      } else
        ab += PField(name, typ, ab.length)
    }
    PCanonicalStruct(ab.result(), required)
  }

  override def deepRename(t: Type): PType = deepRenameStruct(t.asInstanceOf[TStruct])

  private def deepRenameStruct(t: TStruct): PStruct = {
    PCanonicalStruct((t.fields, this.fields).zipped.map( (tfield, pfield) => {
      assert(tfield.index == pfield.index)
      PField(tfield.name, pfield.typ.deepRename(tfield.typ), pfield.index)
    }), this.required)
  }
}
