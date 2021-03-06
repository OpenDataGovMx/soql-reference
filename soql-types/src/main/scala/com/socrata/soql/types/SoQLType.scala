package com.socrata.soql.types

import com.ibm.icu.util.CaseInsensitiveString

import com.socrata.soql.environment.TypeName
import org.joda.time.{LocalTime, LocalDate, LocalDateTime, DateTime}
import org.joda.time.format.ISODateTimeFormat
import com.rojoma.json.ast.{JValue, JArray, JObject}
import com.google.protobuf.{CodedInputStream, CodedOutputStream}

sealed abstract class SoQLAnalysisType(val name: TypeName) {
  def this(name: String) = this(TypeName(name))

  override final def toString = name.toString
  def isPassableTo(that: SoQLAnalysisType): Boolean = (this == that)

  def real: Boolean
  def canonical: SoQLType
}

object SoQLAnalysisType {
  object Serialization {
    def serialize(out: CodedOutputStream, t: SoQLAnalysisType) =
      out.writeStringNoTag(t.name.name)
    def deserialize(in: CodedInputStream): SoQLType = {
      val name = TypeName(in.readString())
      SoQLType.typesByName(name) // post-analysis, the fake types are gone
    }
  }
}

sealed abstract class SoQLType(n: String) extends SoQLAnalysisType(n) {
  def real = true
  def canonical: SoQLType = this
}

object SoQLType {
  // FIXME: Figure out a way to DRY this.  It's pretty easy in Scala 2.10, but
  // I still want to retain pre-2.10 compat.
  val typesByName = Seq(
    SoQLID, SoQLVersion, SoQLText, SoQLBoolean, SoQLNumber, SoQLMoney, SoQLDouble, SoQLFixedTimestamp, SoQLFloatingTimestamp,
    SoQLDate, SoQLTime, SoQLObject, SoQLArray, SoQLLocation, SoQLJson
  ).foldLeft(Map.empty[TypeName, SoQLType]) { (acc, typ) =>
    acc + (typ.name -> typ)
  }
}

sealed trait SoQLValue {
  def typ: SoQLType
}

case class SoQLID(value: Long) extends SoQLValue {
  def typ = SoQLID
}
case object SoQLID extends SoQLType("row_identifier")

case class SoQLVersion(value: Long) extends SoQLValue {
  def typ = SoQLVersion
}
case object SoQLVersion extends SoQLType("row_version")

case class SoQLText(value: String) extends SoQLValue {
  def typ = SoQLText
}
case object SoQLText extends SoQLType("text")

case class SoQLBoolean(value: Boolean) extends SoQLValue {
  def typ = SoQLBoolean
}
case object SoQLBoolean extends SoQLType("boolean") {
  val canonicalTrue = SoQLBoolean(true)
  val canonicalFalse = SoQLBoolean(false)
  def canonicalValue(b: Boolean) = if(b) canonicalTrue else canonicalFalse
}

case class SoQLNumber(value: java.math.BigDecimal) extends SoQLValue {
  def typ = SoQLNumber
}
case object SoQLNumber extends SoQLType("number")

case class SoQLMoney(value: java.math.BigDecimal) extends SoQLValue {
  def typ = SoQLMoney
}
case object SoQLMoney extends SoQLType("money")

case class SoQLDouble(value: Double) extends SoQLValue {
  def typ = SoQLDouble
}
case object SoQLDouble extends SoQLType("double")

case class SoQLFixedTimestamp(value: DateTime) extends SoQLValue {
  def typ = SoQLFixedTimestamp
}
case object SoQLFixedTimestamp extends SoQLType("fixed_timestamp") {
  object StringRep {
    private val fixedParser = ISODateTimeFormat.dateTimeParser.withZoneUTC
    private val fixedRenderer = ISODateTimeFormat.dateTime.withZoneUTC

    def unapply(text: String): Option[DateTime] =
      try { Some(fixedParser.parseDateTime(text)) }
      catch { case _: IllegalArgumentException => None }

    def unapply(text: CaseInsensitiveString): Option[DateTime] = unapply(text.getString)

    def apply(dateTime: DateTime) =
      fixedRenderer.print(dateTime)

    def printTo(appendable: Appendable, dateTime: DateTime) =
      fixedRenderer.printTo(appendable, dateTime)
  }
}

case class SoQLFloatingTimestamp(value: LocalDateTime) extends SoQLValue {
  def typ = SoQLFloatingTimestamp
}
case object SoQLFloatingTimestamp extends SoQLType("floating_timestamp") {
  object StringRep {
    def unapply(text: String): Option[LocalDateTime] =
      try { Some(ISODateTimeFormat.localDateOptionalTimeParser.parseLocalDateTime(text)) }
      catch { case _: IllegalArgumentException => None }

    def unapply(text: CaseInsensitiveString): Option[LocalDateTime] = unapply(text.getString)

    def apply(dateTime: LocalDateTime) =
      ISODateTimeFormat.dateTime.print(dateTime)

    def printTo(appendable: Appendable, dateTime: LocalDateTime) =
      ISODateTimeFormat.dateTime.printTo(appendable, dateTime)
  }
}

case class SoQLDate(value: LocalDate) extends SoQLValue {
  def typ = SoQLDate
}
case object SoQLDate extends SoQLType("date") {
  object StringRep {
    def unapply(text: String): Option[LocalDate] =
      try { Some(ISODateTimeFormat.localDateParser.parseLocalDate(text)) }
      catch { case _: IllegalArgumentException => None }

    def unapply(text: CaseInsensitiveString): Option[LocalDate] = unapply(text.getString)

    def apply(date: LocalDate) =
      ISODateTimeFormat.date.print(date)

    def printTo(appendable: Appendable, date: LocalDate) =
      ISODateTimeFormat.dateTime.printTo(appendable, date)
  }
}

case class SoQLTime(value: LocalTime) extends SoQLValue {
  def typ = SoQLTime
}
case object SoQLTime extends SoQLType("time") {
  object StringRep {
    def unapply(text: String): Option[LocalTime] =
      try { Some(ISODateTimeFormat.localTimeParser.parseLocalTime(text)) }
      catch { case _: IllegalArgumentException => None }

    def unapply(text: CaseInsensitiveString): Option[LocalTime] = unapply(text.getString)

    def apply(time: LocalTime) =
      ISODateTimeFormat.time.print(time)

    def printTo(appendable: Appendable, time: LocalTime) =
      ISODateTimeFormat.dateTime.printTo(appendable, time)
  }
}

case class SoQLObject(value: JObject) extends SoQLValue {
  def typ = SoQLObject
}
case object SoQLObject extends SoQLType("object")

case class SoQLArray(value: JArray) extends SoQLValue {
  def typ = SoQLArray
}
case object SoQLArray extends SoQLType("array")

case class SoQLLocation(latitude: Double, longitude: Double) extends SoQLValue {
  def typ = SoQLLocation
}
case object SoQLLocation extends SoQLType("location")

case class SoQLJson(value: JValue) extends SoQLValue {
  def typ = SoQLJson
}
case object SoQLJson extends SoQLType("json")

case object SoQLNull extends SoQLType("null") with SoQLValue {
  override def isPassableTo(that: SoQLAnalysisType) = true
  def typ = this
}

sealed abstract class FakeSoQLType(name: TypeName) extends SoQLAnalysisType(name) {
  def real = false
  override def isPassableTo(that: SoQLAnalysisType) =
    super.isPassableTo(that) || canonical.isPassableTo(that)
}

case class SoQLTextLiteral(text: CaseInsensitiveString) extends FakeSoQLType(SoQLTextLiteral.typeName) {
  def canonical = SoQLText
}
object SoQLTextLiteral {
  val typeName = TypeName("*text")
  def apply(s: String): SoQLTextLiteral = apply(new CaseInsensitiveString(s))
}

case class SoQLNumberLiteral(number: BigDecimal) extends FakeSoQLType(SoQLNumberLiteral.typeName) {
  def canonical = SoQLNumber
}
object SoQLNumberLiteral {
  val typeName = TypeName("*number")
}
