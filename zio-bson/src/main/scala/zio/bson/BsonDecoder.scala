package zio.bson

import org.bson.codecs.{DecoderContext => JDecoderContext}
import org.bson.conversions.Bson
import org.bson.types.{Decimal128, ObjectId}
import org.bson.{BsonInvalidOperationException, BsonReader, BsonType, BsonValue}
import zio.bson.BsonDecoder.{BsonDecoderContext, Error}
import zio.bson.DecoderUtils.{assumeType, throwInvalidType, unsafeCall}
import zio.{Chunk, NonEmptyChunk}

import java.math.{BigDecimal => JBigDecimal, BigInteger}
import java.time.format.DateTimeParseException
import java.time.zone.ZoneRulesException
import java.time.{
  DateTimeException,
  DayOfWeek,
  Duration,
  Instant,
  LocalDate,
  LocalDateTime,
  LocalTime,
  Month,
  MonthDay,
  OffsetDateTime,
  OffsetTime,
  Period,
  Year,
  YearMonth,
  ZoneId,
  ZoneOffset,
  ZonedDateTime
}
import java.util.UUID
import scala.collection.Factory
import scala.collection.compat._
import scala.jdk.CollectionConverters._
import scala.reflect.{ClassTag, classTag}
import scala.util.Try
import scala.util.control.NoStackTrace

trait BsonDecoder[A] { self =>

  def decode(reader: BsonReader): Either[BsonDecoder.Error, A] =
    try Right(decodeUnsafe(reader, Nil, BsonDecoderContext.default))
    catch {
      case e: BsonDecoder.Error => Left(e)
    }

  def fromBsonValue(value: BsonValue): Either[BsonDecoder.Error, A] =
    try Right(fromBsonValueUnsafe(value, Nil, BsonDecoderContext.default))
    catch {
      case e: BsonDecoder.Error => Left(e)
    }

  def decodeMissingUnsafe(trace: List[BsonTrace]): A =
    throw BsonDecoder.Error(trace, "missing")

  def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): A

  def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): A

  def map[B](f: A => B): BsonDecoder[B] = new BsonDecoder[B] {
    override def decodeMissingUnsafe(trace: List[BsonTrace]): B = f(self.decodeMissingUnsafe(trace))

    def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): B = f(
      self.decodeUnsafe(reader, trace, ctx)
    )

    def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): B = f(
      self.fromBsonValueUnsafe(value, trace, ctx)
    )
  }

  def mapOrFail[B](f: A => Either[String, B]): BsonDecoder[B] = new BsonDecoder[B] {
    override def decodeMissingUnsafe(trace: List[BsonTrace]): B = f(self.decodeMissingUnsafe(trace)) match {
      case Left(err)    => throw BsonDecoder.Error(trace, err)
      case Right(value) => value
    }

    def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): B =
      f(
        self.decodeUnsafe(reader, trace, ctx)
      ) match {
        case Left(msg)    => throw BsonDecoder.Error(trace, msg)
        case Right(value) => value
      }

    def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): B =
      f(
        self.fromBsonValueUnsafe(value, trace, ctx)
      ) match {
        case Left(msg)    => throw BsonDecoder.Error(trace, msg)
        case Right(value) => value
      }
  }
}

object BsonDecoder extends NumberDecoders with CollectionDecoders with BsonValueDecoders with LowPriorityBsonDecoder1 {

  def apply[A](implicit decoder: BsonDecoder[A]): BsonDecoder[A] = decoder

  case class BsonDecoderContext(ignoreExtraField: Option[String] = None)

  object BsonDecoderContext {
    val default: BsonDecoderContext = BsonDecoderContext()
  }

  case class Error(trace: List[BsonTrace], message: String)
      extends RuntimeException(
        s"Path: ${BsonTrace.render(trace)}, error: $message. Don't use `decodeUnsafe` and `fromBsonValueUnsafe` methods."
      )
      with NoStackTrace {
    def render: String = s"${BsonTrace.render(trace)}: $message"
  }

  private def primitiveDecoder[A](
    tpe: BsonType,
    fromReader: BsonReader => A,
    fromValue: BsonValue => A
  ): BsonDecoder[A] =
    new BsonDecoder[A] {

      def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): A =
        unsafeCall(trace)(fromReader(reader))

      def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): A =
        assumeType(trace)(tpe, value)(fromValue)
    }

  implicit val string: BsonDecoder[String] = new BsonDecoder[String] {

    def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): String =
      unsafeCall(trace) {
        val bsonType = reader.getCurrentBsonType
        if (bsonType == BsonType.SYMBOL) reader.readSymbol()
        else if (bsonType == BsonType.STRING) reader.readString()
        else throwInvalidType(trace, BsonType.STRING, bsonType)
      }

    def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): String =
      if (value.isSymbol) value.asSymbol().getSymbol
      else if (value.isString) value.asString().getValue
      else throwInvalidType(trace, BsonType.STRING, value.getBsonType)
  }

  implicit val objectId: BsonDecoder[ObjectId] =
    primitiveDecoder(BsonType.OBJECT_ID, _.readObjectId(), _.asObjectId().getValue)

  implicit val boolean: BsonDecoder[Boolean] =
    primitiveDecoder(BsonType.BOOLEAN, _.readBoolean(), _.asBoolean().getValue)

  // Commonized handling for decoding from string to java.time Class
  private[bson] def parseJavaTime[A](f: String => A, s: String): Either[String, A] =
    try Right(f(s))
    catch {
      case zre: ZoneRulesException      => Left(s"$s is not a valid ISO-8601 format, ${zre.getMessage}")
      case dtpe: DateTimeParseException => Left(s"$s is not a valid ISO-8601 format, ${dtpe.getMessage}")
      case dte: DateTimeException       => Left(s"$s is not a valid ISO-8601 format, ${dte.getMessage}")
      case ex: Exception                => Left(ex.getMessage)
    }

  implicit val dayOfWeek: BsonDecoder[DayOfWeek]   =
    string.mapOrFail(s => parseJavaTime(DayOfWeek.valueOf, s.toUpperCase))
  implicit val month: BsonDecoder[Month]           =
    string.mapOrFail(s => parseJavaTime(Month.valueOf, s.toUpperCase))
  implicit val monthDay: BsonDecoder[MonthDay]     = string.mapOrFail(s => parseJavaTime(MonthDay.parse, s))
  implicit val year: BsonDecoder[Year]             = string.mapOrFail(s => parseJavaTime(Year.parse, s))
  implicit val yearMonth: BsonDecoder[YearMonth]   = string.mapOrFail(s => parseJavaTime(YearMonth.parse, s))
  implicit val zoneId: BsonDecoder[ZoneId]         = string.mapOrFail(s => parseJavaTime(ZoneId.of, s))
  implicit val zoneOffset: BsonDecoder[ZoneOffset] = string.mapOrFail(s => parseJavaTime(ZoneOffset.of, s))
  implicit val duration: BsonDecoder[Duration]     = string.mapOrFail(s => parseJavaTime(Duration.parse, s))
  implicit val period: BsonDecoder[Period]         = string.mapOrFail(s => parseJavaTime(Period.parse, s))

  implicit val instant: BsonDecoder[Instant] =
    primitiveDecoder(
      BsonType.DATE_TIME,
      reader => Instant.ofEpochMilli(reader.readDateTime()),
      value => Instant.ofEpochMilli(value.asDateTime().getValue)
    )

  implicit val localDate: BsonDecoder[LocalDate]         = instant.map(_.atZone(ZoneOffset.UTC).toLocalDate)
  implicit val localDateTime: BsonDecoder[LocalDateTime] = instant.map(_.atZone(ZoneOffset.UTC).toLocalDateTime)
  implicit val localTime: BsonDecoder[LocalTime]         = instant.map(_.atZone(ZoneOffset.UTC).toLocalTime)

  private def locationDateTime[T](locationField: String)(from: (Instant, String) => Either[String, T]) =
    new BsonDecoder[T] {
      private val DATE_TIME = "date_time"

      private val allowExtraFields = false // TODO: configuration

      def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): T = unsafeCall(trace) {
        reader.readStartDocument()

        val dateTimeTrace = BsonTrace.Field(DATE_TIME) :: trace
        val locationTrace = BsonTrace.Field(locationField) :: trace

        var instantValue: Instant = null
        var locationValue: String = null

        while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
          val name = reader.readName()
          if (name == DATE_TIME) instantValue = instant.decodeUnsafe(reader, dateTimeTrace, ctx)
          else if (name == locationField) locationValue = string.decodeUnsafe(reader, locationTrace, ctx)
          else if (!allowExtraFields && !ctx.ignoreExtraField.contains(name))
            throw Error(BsonTrace.Field(name) :: trace, "Invalid extra field.")
          else reader.skipValue()
        }

        if (instantValue == null) throw Error(dateTimeTrace, "Missed field.")
        if (locationValue == null) throw Error(locationTrace, "Missed field.")

        reader.readEndDocument()

        from(instantValue, locationValue) match {
          case Left(err)     => throw Error(trace, err)
          case Right(result) => result
        }
      }

      def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): T =
        assumeType(trace)(BsonType.DOCUMENT, value) { value =>
          val fields        = value.asDocument().asScala
          if (!allowExtraFields && fields.size > 2) throw Error(trace, "Invalid extra fields.")
          val dateTimeTrace = BsonTrace.Field(DATE_TIME) :: trace
          val dateTime      = fields.getOrElse(DATE_TIME, throw Error(dateTimeTrace, "Missed field."))
          val instantValue  = instant.fromBsonValueUnsafe(dateTime, dateTimeTrace, ctx)
          val locationTrace = BsonTrace.Field(locationField) :: trace
          val locationBson  = fields.getOrElse(locationField, throw Error(locationTrace, "Missed field."))
          val locationValue = string.fromBsonValueUnsafe(locationBson, locationTrace, ctx)

          from(instantValue, locationValue) match {
            case Left(err)     => throw Error(trace, err)
            case Right(result) => result
          }
        }
    }

  implicit lazy val offsetDateTime: BsonDecoder[OffsetDateTime] =
    locationDateTime[OffsetDateTime]("offset") { (instant, offset) =>
      Try {
        OffsetDateTime.ofInstant(instant, ZoneOffset.of(offset))
      }.toEither.swap.map(t => s"Failed to parse OffsetDateTime: ${t.getMessage}").swap
    }

  implicit lazy val offsetTime: BsonDecoder[OffsetTime] =
    offsetDateTime.map(_.toOffsetTime)

  implicit lazy val zonedDateTime: BsonDecoder[ZonedDateTime] =
    locationDateTime[ZonedDateTime]("zone_id") { (instant, zoneId) =>
      Try {
        ZonedDateTime.ofInstant(instant, ZoneId.of(zoneId))
      }.toEither.swap.map(t => s"Failed to parse ZonedDateTime: ${t.getMessage}").swap
    }

  implicit val char: BsonDecoder[Char] = string.mapOrFail { str =>
    if (str.length == 1) Right(str(0))
    else Left(s"Expected one character, but got a string of length ${str.length}.")
  }

  implicit val symbol: BsonDecoder[Symbol] = string.map(Symbol(_))

  implicit val uuid: BsonDecoder[UUID] = string.mapOrFail { s =>
    Try(UUID.fromString(s)).toEither.swap.map(t => s"Invalid UUID: ${t.getMessage}").swap
  }

  implicit def option[A](implicit A: BsonDecoder[A]): BsonDecoder[Option[A]] = new BsonDecoder[Option[A]] {
    override def decodeMissingUnsafe(trace: List[BsonTrace]): Option[A] = None

    def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): Option[A] =
      if (reader.getCurrentBsonType == BsonType.NULL) {
        reader.skipValue()
        None
      } else Some(A.decodeUnsafe(reader, trace, ctx))

    def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): Option[A] =
      if (value.isNull) None
      else Some(A.fromBsonValueUnsafe(value, trace, ctx))
  }

}

trait LowPriorityBsonDecoder1 {
  implicit def fromCodec[A](implicit codec: BsonCodec[A]): BsonDecoder[A] = codec.decoder
}

trait NumberDecoders { self =>
  private def validateIntRange(value: Int, min: Int, max: Int): Either[String, Int] =
    if (value <= max && value >= min) Right(value)
    else Left(s"Int value $value is not within range [$min, $max].")

  private def validateLongRangeUnsafe(trace: List[BsonTrace], value: Long, min: Long, max: Long): Long =
    if (value <= max && value >= min) value
    else throw Error(trace, s"Long value $value is not within range [$min, $max].")

  private def throwInvalidType(trace: List[BsonTrace], expected: String, actual: BsonType): Nothing =
    throw Error(trace, s"Expected BSON type $expected, but got $actual.")

  private def throwInvalidConversion(
    trace: List[BsonTrace],
    expected: String,
    actual: BsonType,
    value: String
  ): Nothing =
    throw Error(trace, s"Could not convert $actual($value) to a $expected without losing precision.")

  implicit val int: BsonDecoder[Int] = new BsonDecoder[Int] {
    private def fromLong(trace: List[BsonTrace])(l: Long) =
      validateLongRangeUnsafe(trace, l, Int.MinValue, Int.MaxValue).toInt

    private def fromString(trace: List[BsonTrace])(s: String) =
      try s.toInt
      catch {
        case e: NumberFormatException => throw Error(trace, s"NumberFormatException(${e.getMessage})")
      }

    private def fromDouble(trace: List[BsonTrace])(d: Double) = {
      val i = d.toInt
      // noinspection DfaConstantConditions
      if (d != i.toDouble) throwInvalidConversion(trace, "Int", BsonType.DOUBLE, d.toString)
      else i
    }

    private def fromDecimal(trace: List[BsonTrace])(d: Decimal128) = {
      val i = d.intValue()

      if (d != new Decimal128(i.toLong)) throwInvalidConversion(trace, "Int", BsonType.DECIMAL128, d.toString)
      else i
    }

    def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): Int =
      unsafeCall(trace) {
        reader.getCurrentBsonType match {
          case BsonType.INT32      => reader.readInt32()
          case BsonType.INT64      => fromLong(trace)(reader.readInt64())
          case BsonType.DOUBLE     => fromDouble(trace)(reader.readDouble())
          case BsonType.DECIMAL128 => fromDecimal(trace)(reader.readDecimal128())
          case BsonType.STRING     => fromString(trace)(reader.readString())
          case tpe                 => throwInvalidType(trace, "Int", tpe)
        }
      }

    def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): Int =
      unsafeCall(trace) {
        value.getBsonType match {
          case BsonType.INT32      => value.asInt32().getValue
          case BsonType.INT64      => fromLong(trace)(value.asInt64().getValue)
          case BsonType.DOUBLE     => fromDouble(trace)(value.asDouble().getValue)
          case BsonType.DECIMAL128 => fromDecimal(trace)(value.asDecimal128().getValue)
          case BsonType.STRING     => fromString(trace)(value.asString().getValue)
          case tpe                 => throwInvalidType(trace, "Int", tpe)
        }
      }
  }

  implicit val byte: BsonDecoder[Byte]   =
    int.mapOrFail(validateIntRange(_, Byte.MinValue, Byte.MaxValue).map(_.toByte))
  implicit val short: BsonDecoder[Short] =
    int.mapOrFail(validateIntRange(_, Short.MinValue, Short.MaxValue).map(_.toShort))

  implicit val long: BsonDecoder[Long] = new BsonDecoder[Long] {
    private def fromString(trace: List[BsonTrace])(s: String) =
      try s.toLong
      catch {
        case e: NumberFormatException => throw Error(trace, s"NumberFormatException(${e.getMessage})")
      }

    private def fromDouble(trace: List[BsonTrace])(d: Double) = {
      val l = d.toLong
      if (d != l.toDouble) throwInvalidConversion(trace, "Long", BsonType.DOUBLE, d.toString)
      else l
    }

    private def fromDecimal(trace: List[BsonTrace])(d: Decimal128) = {
      val l = d.longValue()

      if (d != new Decimal128(l)) throwInvalidConversion(trace, "Long", BsonType.DECIMAL128, d.toString)
      else l
    }

    def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): Long =
      unsafeCall(trace) {
        reader.getCurrentBsonType match {
          case BsonType.INT32      => reader.readInt32().toLong
          case BsonType.INT64      => reader.readInt64()
          case BsonType.DOUBLE     => fromDouble(trace)(reader.readDouble())
          case BsonType.DECIMAL128 => fromDecimal(trace)(reader.readDecimal128())
          case BsonType.STRING     => fromString(trace)(reader.readString())
          case tpe                 => throwInvalidType(trace, "Long", tpe)
        }
      }

    def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): Long =
      unsafeCall(trace) {
        value.getBsonType match {
          case BsonType.INT32      => value.asInt32().getValue.toLong
          case BsonType.INT64      => value.asInt64().getValue
          case BsonType.DOUBLE     => fromDouble(trace)(value.asDouble().getValue)
          case BsonType.DECIMAL128 => fromDecimal(trace)(value.asDecimal128().getValue)
          case BsonType.STRING     => fromString(trace)(value.asString().getValue)
          case tpe                 => throwInvalidType(trace, "Long", tpe)
        }
      }
  }

  implicit val float: BsonDecoder[Float] = new BsonDecoder[Float] {
    private def fromString(trace: List[BsonTrace])(s: String) =
      try s.toFloat
      catch {
        case e: NumberFormatException => throw Error(trace, s"NumberFormatException(${e.getMessage})")
      }

    private def fromDecimal(trace: List[BsonTrace])(decimal128: Decimal128) = {
      val float = decimal128.floatValue()

      if (decimal128 != new Decimal128(BigDecimal(float.toDouble).underlying()))
        throwInvalidConversion(trace, "Float", BsonType.DECIMAL128, decimal128.toString)
      else float
    }

    private def fromDouble(trace: List[BsonTrace])(d: Double) = {
      val f = d.toFloat
      if (d != f.toDouble) throwInvalidConversion(trace, "Float", BsonType.DOUBLE, d.toString)
      else f
    }

    def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): Float =
      unsafeCall(trace) {
        reader.getCurrentBsonType match {
          case BsonType.INT32      => reader.readInt32().toFloat
          case BsonType.INT64      => reader.readInt64().toFloat
          case BsonType.DOUBLE     => fromDouble(trace)(reader.readDouble())
          case BsonType.DECIMAL128 => fromDecimal(trace)(reader.readDecimal128())
          case BsonType.STRING     => fromString(trace)(reader.readString())
          case tpe                 => throwInvalidType(trace, "Float", tpe)
        }
      }

    def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): Float =
      unsafeCall(trace) {
        value.getBsonType match {
          case BsonType.INT32      => value.asInt32().getValue.toFloat
          case BsonType.INT64      => value.asInt64().getValue.toFloat
          case BsonType.DOUBLE     => fromDouble(trace)(value.asDouble().getValue)
          case BsonType.DECIMAL128 => fromDecimal(trace)(value.asDecimal128().getValue)
          case BsonType.STRING     => fromString(trace)(value.asString().getValue)
          case tpe                 => throwInvalidType(trace, "Float", tpe)
        }
      }
  }

  implicit val double: BsonDecoder[Double] = new BsonDecoder[Double] {
    private def fromString(trace: List[BsonTrace])(s: String) =
      try s.toDouble
      catch {
        case e: NumberFormatException => throw Error(trace, s"NumberFormatException(${e.getMessage})")
      }

    private def fromDecimal(trace: List[BsonTrace])(decimal128: Decimal128) = {
      val double = decimal128.doubleValue()

      if (decimal128 != new Decimal128(BigDecimal(double).underlying()))
        throwInvalidConversion(trace, "Double", BsonType.DECIMAL128, decimal128.toString)
      else double
    }

    def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): Double =
      unsafeCall(trace) {
        reader.getCurrentBsonType match {
          case BsonType.INT32      => reader.readInt32().toDouble
          case BsonType.INT64      => reader.readInt64().toDouble
          case BsonType.DOUBLE     => reader.readDouble()
          case BsonType.DECIMAL128 => fromDecimal(trace)(reader.readDecimal128())
          case BsonType.STRING     => fromString(trace)(reader.readString())
          case tpe                 => throwInvalidType(trace, "Double", tpe)
        }
      }

    def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): Double =
      unsafeCall(trace) {
        value.getBsonType match {
          case BsonType.INT32      => value.asInt32().getValue.toDouble
          case BsonType.INT64      => value.asInt64().getValue.toDouble
          case BsonType.DOUBLE     => value.asDouble().getValue
          case BsonType.DECIMAL128 => fromDecimal(trace)(value.asDecimal128().getValue)
          case BsonType.STRING     => fromString(trace)(value.asString().getValue)
          case tpe                 => throwInvalidType(trace, "Double", tpe)
        }
      }
  }

  implicit val javaBigDecimal: BsonDecoder[JBigDecimal] = new BsonDecoder[JBigDecimal] {
    private def fromString(trace: List[BsonTrace])(s: String) =
      try new JBigDecimal(s)
      catch {
        case e: NumberFormatException => throw Error(trace, s"NumberFormatException(${e.getMessage})")
      }

    def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): JBigDecimal =
      unsafeCall(trace) {
        reader.getCurrentBsonType match {
          case BsonType.INT32      => JBigDecimal.valueOf(reader.readInt32().toLong)
          case BsonType.INT64      => JBigDecimal.valueOf(reader.readInt64())
          case BsonType.DOUBLE     => JBigDecimal.valueOf(reader.readDouble())
          case BsonType.DECIMAL128 => reader.readDecimal128().bigDecimalValue()
          case BsonType.STRING     => fromString(trace)(reader.readString())
          case tpe                 => throwInvalidType(trace, "BigDecimal", tpe)
        }
      }

    def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): JBigDecimal =
      unsafeCall(trace) {
        value.getBsonType match {
          case BsonType.INT32      => JBigDecimal.valueOf(value.asInt32().getValue.toLong)
          case BsonType.INT64      => JBigDecimal.valueOf(value.asInt64().getValue)
          case BsonType.DOUBLE     => JBigDecimal.valueOf(value.asDouble().getValue)
          case BsonType.DECIMAL128 => value.asDecimal128().getValue.bigDecimalValue()
          case BsonType.STRING     => fromString(trace)(value.asString().getValue)
          case tpe                 => throwInvalidType(trace, "BigDecimal", tpe)
        }
      }
  }

  implicit val bigDecimal: BsonDecoder[BigDecimal] = javaBigDecimal.map(x => x)

  implicit val bigInteger: BsonDecoder[BigInteger] = new BsonDecoder[BigInteger] {
    private def fromString(trace: List[BsonTrace])(s: String) =
      try new BigInteger(s)
      catch {
        case e: NumberFormatException => throw Error(trace, s"NumberFormatException(${e.getMessage})")
      }

    private def fromDouble(trace: List[BsonTrace])(d: Double) = {
      val bi = JBigDecimal.valueOf(d).toBigInteger
      // noinspection DfaConstantConditions
      if (d != bi.doubleValue()) throwInvalidConversion(trace, "BigInteger", BsonType.DOUBLE, d.toString)
      else bi
    }

    private def fromDecimal(trace: List[BsonTrace])(d: Decimal128) = {
      val bi = d.bigDecimalValue().toBigInteger

      if (d != new Decimal128(new JBigDecimal(bi)))
        throwInvalidConversion(trace, "BigInteger", BsonType.DECIMAL128, d.toString)
      else bi
    }

    def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): BigInteger =
      unsafeCall(trace) {
        reader.getCurrentBsonType match {
          case BsonType.INT32      => BigInteger.valueOf(reader.readInt32().toLong)
          case BsonType.INT64      => BigInteger.valueOf(reader.readInt64())
          case BsonType.DOUBLE     => fromDouble(trace)(reader.readDouble())
          case BsonType.DECIMAL128 => fromDecimal(trace)(reader.readDecimal128())
          case BsonType.STRING     => fromString(trace)(reader.readString())
          case tpe                 => throwInvalidType(trace, "BigInteger", tpe)
        }
      }

    def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): BigInteger =
      unsafeCall(trace) {
        value.getBsonType match {
          case BsonType.INT32      => BigInteger.valueOf(value.asInt32().getValue.toLong)
          case BsonType.INT64      => BigInteger.valueOf(value.asInt64().getValue)
          case BsonType.DOUBLE     => fromDouble(trace)(value.asDouble().getValue)
          case BsonType.DECIMAL128 => fromDecimal(trace)(value.asDecimal128().getValue)
          case BsonType.STRING     => fromString(trace)(value.asString().getValue)
          case tpe                 => throwInvalidType(trace, "BigInteger", tpe)
        }
      }
  }

  implicit val bigInt: BsonDecoder[BigInt] = bigInteger.map(x => x)

}

trait CollectionDecoders extends LowPriorityCollectionDecoders {
  implicit def byteIterableFactory[CC[_]](implicit factory: Factory[Byte, CC[Byte]]): BsonDecoder[CC[Byte]] =
    new BsonDecoder[CC[Byte]] {

      def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): CC[Byte] = {
        val bytes = unsafeCall(trace)(reader.readBinaryData().getData)
        factory.fromSpecific(bytes)
      }

      def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): CC[Byte] =
        assumeType(trace)(BsonType.BINARY, value) { value =>
          factory.fromSpecific(value.asBinary().getData)
        }
    }

  implicit val byteNonEmptyChunk: BsonDecoder[NonEmptyChunk[Byte]] =
    byteIterableFactory[Chunk].mapOrFail(NonEmptyChunk.fromChunk(_).toRight("Chunk was empty"))
}

trait LowPriorityCollectionDecoders {
  implicit def nonEmptyChunk[A: BsonDecoder]: BsonDecoder[NonEmptyChunk[A]] =
    implicitly[BsonDecoder[Chunk[A]]].mapOrFail(NonEmptyChunk.fromChunk(_).toRight("Chunk was empty"))

  implicit def iterableFactory[A, CC[_]](implicit A: BsonDecoder[A], factory: Factory[A, CC[A]]): BsonDecoder[CC[A]] =
    new BsonDecoder[CC[A]] {

      def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): CC[A] = {
        unsafeCall(trace)(reader.readStartArray())

        val builder = factory.newBuilder
        var idx     = 0
        while (unsafeCall(trace)(reader.readBsonType()) != BsonType.END_OF_DOCUMENT) {
          val arrayTrace = BsonTrace.Array(idx) :: trace
          builder += A.decodeUnsafe(reader, arrayTrace, ctx)
          idx += 1
        }

        unsafeCall(trace)(reader.readEndArray())

        builder.result()
      }

      def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): CC[A] =
        assumeType(trace)(BsonType.ARRAY, value) { value =>
          val builder = factory.newBuilder
          var idx     = 0
          value.asArray().forEach { (element: BsonValue) =>
            val arrayTrace = BsonTrace.Array(idx) :: trace
            builder += A.fromBsonValueUnsafe(element, arrayTrace, ctx)
            idx += 1
          }

          builder.result()
        }
    }

  implicit def mapFactory[K, V, CC[_, _]](implicit
    K: BsonFieldDecoder[K],
    V: BsonDecoder[V],
    factory: Factory[(K, V), CC[K, V]]
  ): BsonDecoder[CC[K, V]] =
    new BsonDecoder[CC[K, V]] {

      def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): CC[K, V] =
        unsafeCall(trace) {
          reader.readStartDocument()

          val builder = factory.newBuilder
          while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
            val fieldName = reader.readName()
            val docTrace  = BsonTrace.Field(fieldName) :: trace
            val value     = V.decodeUnsafe(reader, docTrace, ctx)
            builder += (K.unsafeDecodeField(docTrace, fieldName) -> value)
          }

          reader.readEndDocument()
          builder.result()
        }

      def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): CC[K, V] =
        assumeType(trace)(BsonType.DOCUMENT, value) { value =>
          val builder = factory.newBuilder
          value.asDocument().forEach { (fieldName: String, element: BsonValue) =>
            val docTrace = BsonTrace.Field(fieldName) :: trace
            val value    = V.fromBsonValueUnsafe(element, docTrace, ctx)
            builder += (K.unsafeDecodeField(docTrace, fieldName) -> value)
          }

          builder.result()
        }
    }
}

trait BsonValueDecoders {
  private lazy val registry       = Bson.DEFAULT_CODEC_REGISTRY
  private lazy val defaultContext = JDecoderContext.builder().build()

  implicit def bsonValueDecoder[T <: BsonValue: ClassTag]: BsonDecoder[T] = new BsonDecoder[T] {

    def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): T = unsafeCall(trace) {
      val codec = registry.get(classTag[T].runtimeClass.asInstanceOf[Class[T]])

      if (codec == null)
        throw BsonDecoder.Error(trace, s"Can't find codec for BsonValue subtype ${classTag[T].runtimeClass.getName}")

      codec.decode(reader, defaultContext)
    }

    def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): T = value match {
      case t: T => t
      case x    =>
        throw BsonDecoder.Error(
          trace,
          s"Expected BsonValue type ${classTag[T].runtimeClass.getName}, but got ${x.getBsonType}."
        )
    }
  }
}

object DecoderUtils {

  def unsafeCall[R](trace: List[BsonTrace])(call: => R): R =
    try call
    catch {
      case e: BsonInvalidOperationException => throw BsonDecoder.Error(trace, e.getMessage)
      case e: IllegalStateException         => throw BsonDecoder.Error(trace, e.getMessage)
    }

  def throwInvalidType(trace: List[BsonTrace], expected: BsonType, actual: BsonType): Nothing =
    throw BsonDecoder.Error(trace, s"Expected BSON type $expected, but got $actual.")

  def assumeType[T](trace: List[BsonTrace])(tpe: BsonType, value: BsonValue)(f: BsonValue => T): T =
    if (value.getBsonType == tpe) unsafeCall(trace)(f(value))
    else throwInvalidType(trace, tpe, value.getBsonType)
}
