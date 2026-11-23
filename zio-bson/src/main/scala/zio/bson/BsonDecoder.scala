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
import scala.collection.compat._
import scala.jdk.CollectionConverters._
import scala.reflect.{ClassTag, classTag}
import scala.util.Try
import scala.util.control.NoStackTrace

/**
 * A `BsonDecoder[A]` reads values of type `A` from BSON, either from an `org.bson.BsonReader` ([[BsonDecoder#decode]])
 * or from an in-memory `org.bson.BsonValue` ([[BsonDecoder#fromBsonValue]]). A failed decoding is a
 * [[BsonDecoder.Error]] that holds the path to the offending value and a message.
 *
 * Instances for the common Scala and Java types are implicit members of the `BsonDecoder` companion object. Instances
 * for your own types come from [[BsonDecoder#map]] and [[BsonDecoder#mapOrFail]], from a [[BsonCodec]], or from the
 * `zio.bson.magnolia.DeriveBsonDecoder` derivation in the `zio-bson-magnolia` module.
 *
 * {{{
 * import org.bson.{BsonInt32, BsonString}
 *
 * val int: Either[BsonDecoder.Error, Int] = BsonDecoder[Int].fromBsonValue(new BsonInt32(7))
 * // Right(7)
 *
 * val failed: Either[String, Int] = BsonDecoder[Int].fromBsonValue(new BsonString("x")).left.map(_.render)
 * // Left(: NumberFormatException(For input string: "x"))
 * }}}
 *
 * @see
 *   [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
trait BsonDecoder[A] { self =>

  /**
   * Reads one value from `reader`, starting at the reader's current position.
   *
   * Returns a `Left` with a [[BsonDecoder.Error]] when the value can't be decoded.
   */
  def decode(reader: BsonReader): Either[BsonDecoder.Error, A] =
    try Right(decodeUnsafe(reader, Nil, BsonDecoderContext.default))
    catch {
      case e: BsonDecoder.Error => Left(e)
    }

  /**
   * Decodes `value`, returning a `Left` with a [[BsonDecoder.Error]] when the value is of a type that the decoder
   * doesn't accept or holds an invalid value.
   *
   * {{{
   * import org.bson.{BsonArray, BsonInt32}
   *
   * val array = new BsonArray()
   * array.add(new BsonInt32(1))
   * array.add(new BsonInt32(2))
   *
   * val ints: Either[BsonDecoder.Error, List[Int]] = BsonDecoder[List[Int]].fromBsonValue(array)
   * // Right(List(1, 2))
   * }}}
   */
  def fromBsonValue(value: BsonValue): Either[BsonDecoder.Error, A] =
    try Right(fromBsonValueUnsafe(value, Nil, BsonDecoderContext.default))
    catch {
      case e: BsonDecoder.Error => Left(e)
    }

  /**
   * Returns the value to use when a document has no field for this type. The default implementation throws a
   * [[BsonDecoder.Error]] with the message `missing`; the `Option` decoder returns `None`.
   *
   * The derived case class decoders call it for each field that a document lacks. It is not meant to be called from
   * user code.
   */
  def decodeMissingUnsafe(trace: List[BsonTrace]): A =
    throw BsonDecoder.Error(trace, "missing")

  /**
   * Reads one value from `reader`, throwing a [[BsonDecoder.Error]] when it can't be decoded.
   *
   * `trace` is the path to the value, the innermost step first. Decoders extend it with [[BsonTrace.Field]] and
   * [[BsonTrace.Array]] steps when they read nested values, so that an error reports where the problem is.
   *
   * This is the method that decoders implement, and that they call on the decoders of nested values. Other code should
   * call [[BsonDecoder#decode]].
   */
  def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): A

  /**
   * Decodes `value`, throwing a [[BsonDecoder.Error]] when it can't be decoded. `trace` is the path to `value`, the
   * innermost step first.
   *
   * This is the method that decoders implement, and that they call on the decoders of nested values. Other code should
   * call [[BsonDecoder#fromBsonValue]].
   */
  def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): A

  /**
   * Returns a decoder for `B` that decodes with this decoder and applies `f` to the result.
   *
   * {{{
   * import org.bson.BsonInt64
   *
   * final case class UserId(value: Long)
   *
   * val userId: BsonDecoder[UserId] = BsonDecoder[Long].map(UserId(_))
   *
   * val decoded: Either[BsonDecoder.Error, UserId] = userId.fromBsonValue(new BsonInt64(42L))
   * // Right(UserId(42))
   * }}}
   */
  def map[B](f: A => B): BsonDecoder[B] = new BsonDecoder[B] {
    override def decodeMissingUnsafe(trace: List[BsonTrace]): B = f(self.decodeMissingUnsafe(trace))

    def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): B = f(
      self.decodeUnsafe(reader, trace, ctx)
    )

    def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): B = f(
      self.fromBsonValueUnsafe(value, trace, ctx)
    )
  }

  /**
   * Returns a decoder for `B` that decodes with this decoder and applies `f` to the result. When `f` returns a `Left`,
   * the decoding fails with a [[BsonDecoder.Error]] that carries the message.
   *
   * {{{
   * import org.bson.BsonString
   *
   * final case class Email(address: String)
   *
   * val email: BsonDecoder[Email] =
   *   BsonDecoder[String].mapOrFail { s =>
   *     if (s.contains("@")) Right(Email(s)) else Left(s"Not an email: \$s")
   *   }
   *
   * val failed: Either[String, Email] = email.fromBsonValue(new BsonString("nope")).left.map(_.render)
   * // Left(: Not an email: nope)
   * }}}
   */
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

/**
 * The implicit decoders for the types that `zio-bson` supports, and the [[BsonDecoder.Error]] type.
 *
 * The `zio.bson` package object adds an `as` extension method on `org.bson.BsonValue` for types that have a decoder in
 * scope.
 */
object BsonDecoder extends NumberDecoders with CollectionDecoders with BsonValueDecoders with LowPriorityBsonDecoder1 {

  /**
   * Returns the implicit `BsonDecoder[A]` in scope.
   *
   * {{{
   * val decoder: BsonDecoder[Int] = BsonDecoder[Int]
   * }}}
   */
  def apply[A](implicit decoder: BsonDecoder[A]): BsonDecoder[A] = decoder

  /**
   * State that a decoder passes down to the decoders of nested values.
   *
   * @param ignoreExtraField
   *   a field name that the next case class decoder must skip even when extra fields are not allowed. The derived
   *   decoders for sum types set it to the discriminator field, because the subtype's decoder reads the document that
   *   still contains that field.
   */
  case class BsonDecoderContext(ignoreExtraField: Option[String] = None)

  /** The companion of [[BsonDecoder.BsonDecoderContext]]. */
  object BsonDecoderContext {

    /** The context with no field to ignore. */
    val default: BsonDecoderContext = BsonDecoderContext()
  }

  /**
   * The failure of a decoding: where it happened and what went wrong.
   *
   * `trace` is the path to the offending value, the innermost step first, and `message` describes the problem. The
   * exception has no stack trace, and its own message ends with a reminder not to call the `Unsafe` methods directly.
   *
   * {{{
   * val error = BsonDecoder.Error(List(BsonTrace.Field("street"), BsonTrace.Field("address")), "missing")
   *
   * val rendered: String = error.render
   * // .address.street: missing
   * }}}
   */
  case class Error(trace: List[BsonTrace], message: String)
      extends RuntimeException(
        s"Path: ${BsonTrace.render(trace)}, error: $message. Don't use `decodeUnsafe` and `fromBsonValueUnsafe` methods."
      )
      with NoStackTrace {

    /**
     * Returns the path followed by the message, as `<path>: <message>`. The path is empty when the error is at the top
     * level, and is written with `.name` for document fields and `[index]` for array elements.
     */
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

  /** Decodes a BSON string or a BSON symbol as a `String`. */
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

  /** Decodes a BSON ObjectId as an `org.bson.types.ObjectId`. */
  implicit val objectId: BsonDecoder[ObjectId] =
    primitiveDecoder(BsonType.OBJECT_ID, _.readObjectId(), _.asObjectId().getValue)

  /** Decodes a BSON boolean as a `Boolean`. */
  implicit val boolean: BsonDecoder[Boolean] =
    primitiveDecoder(BsonType.BOOLEAN, _.readBoolean(), _.asBoolean().getValue)

  // Commonized handling for decoding from string to java.time Class
  private[bson] def parseJavaTime[V, A](v: V)(f: V => A): Either[String, A] =
    try Right(f(v))
    catch {
      case zre: ZoneRulesException      => Left(s"$v is not a valid ISO-8601 format, ${zre.getMessage}")
      case dtpe: DateTimeParseException => Left(s"$v is not a valid ISO-8601 format, ${dtpe.getMessage}")
      case dte: DateTimeException       => Left(s"$v is not a valid ISO-8601 format, ${dte.getMessage}")
      case ex: Exception                => Left(ex.getMessage)
    }

  /**
   * Decodes a BSON string as a `java.time.DayOfWeek`. The name is matched without regard to case, so `"monday"` and
   * `"MONDAY"` both decode. An unknown name fails with the message of the underlying `IllegalArgumentException`, for
   * example `No enum constant java.time.DayOfWeek.FOO`.
   */
  implicit val dayOfWeek: BsonDecoder[DayOfWeek] =
    string.mapOrFail(s => parseJavaTime(s.toUpperCase)(DayOfWeek.valueOf))

  /** Decodes a BSON string as a `java.time.Month`. The name is matched without regard to case. */
  implicit val month: BsonDecoder[Month] =
    string.mapOrFail(s => parseJavaTime(s.toUpperCase)(Month.valueOf))

  /** Decodes a BSON string such as `"--12-03"` as a `java.time.MonthDay`. */
  implicit val monthDay: BsonDecoder[MonthDay] = string.mapOrFail(s => parseJavaTime(s)(MonthDay.parse))

  /** Decodes a BSON number or string as a `java.time.Year`, using the rules of the `Int` decoder. */
  implicit val year: BsonDecoder[Year] = int.mapOrFail(i => parseJavaTime(i)(Year.of))

  /** Decodes a BSON string such as `"2024-05"` as a `java.time.YearMonth`. */
  implicit val yearMonth: BsonDecoder[YearMonth] = string.mapOrFail(s => parseJavaTime(s)(YearMonth.parse))

  /** Decodes a BSON string such as `"Europe/Paris"` as a `java.time.ZoneId`. */
  implicit val zoneId: BsonDecoder[ZoneId] = string.mapOrFail(s => parseJavaTime(s)(ZoneId.of))

  /** Decodes a BSON string such as `"+02:00"` as a `java.time.ZoneOffset`. */
  implicit val zoneOffset: BsonDecoder[ZoneOffset] = string.mapOrFail(s => parseJavaTime(s)(ZoneOffset.of))

  /** Decodes a BSON string in the ISO-8601 form, such as `"PT1H30M"`, as a `java.time.Duration`. */
  implicit val duration: BsonDecoder[Duration] = string.mapOrFail(s => parseJavaTime(s)(Duration.parse))

  /** Decodes a BSON string in the ISO-8601 form, such as `"P1Y2M"`, as a `java.time.Period`. */
  implicit val period: BsonDecoder[Period] = string.mapOrFail(s => parseJavaTime(s)(Period.parse))

  /** Decodes a BSON date-time as a `java.time.Instant`. Other BSON types are rejected. */
  implicit val instant: BsonDecoder[Instant] =
    primitiveDecoder(
      BsonType.DATE_TIME,
      reader => Instant.ofEpochMilli(reader.readDateTime()),
      value => Instant.ofEpochMilli(value.asDateTime().getValue)
    )

  /** Decodes a BSON date-time as a `java.time.LocalDate`, reading the instant in UTC. */
  implicit val localDate: BsonDecoder[LocalDate] = instant.map(_.atZone(ZoneOffset.UTC).toLocalDate)

  /** Decodes a BSON date-time as a `java.time.LocalDateTime`, reading the instant in UTC. */
  implicit val localDateTime: BsonDecoder[LocalDateTime] = instant.map(_.atZone(ZoneOffset.UTC).toLocalDateTime)

  /** Decodes a BSON date-time as a `java.time.LocalTime`, reading the instant in UTC. */
  implicit val localTime: BsonDecoder[LocalTime] = instant.map(_.atZone(ZoneOffset.UTC).toLocalTime)

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

  /**
   * Decodes a BSON document with a `date_time` field, a BSON date-time, and an `offset` field, a string such as
   * `"+02:00"`, as a `java.time.OffsetDateTime` at that instant and offset.
   *
   * A missing field fails with `Missed field.` at the path of the field. When read from a `BsonReader`, any other field
   * fails with `Invalid extra field.` at the path of that field. When decoded from a `BsonValue`, a document with more
   * than two fields fails with `Invalid extra fields.`, and a document with two fields where one is unknown fails with
   * `Missed field.` for the field it lacks.
   */
  implicit lazy val offsetDateTime: BsonDecoder[OffsetDateTime] =
    locationDateTime[OffsetDateTime]("offset") { (instant, offset) =>
      Try {
        OffsetDateTime.ofInstant(instant, ZoneOffset.of(offset))
      }.toEither.swap.map(t => s"Failed to parse OffsetDateTime: ${t.getMessage}").swap
    }

  /**
   * Decodes the document of the [[BsonDecoder.offsetDateTime]] decoder as a `java.time.OffsetTime`, keeping only the
   * time and the offset.
   */
  implicit lazy val offsetTime: BsonDecoder[OffsetTime] =
    offsetDateTime.map(_.toOffsetTime)

  /**
   * Decodes a BSON document with a `date_time` field, a BSON date-time, and a `zone_id` field, a string such as
   * `"Europe/Paris"`, as a `java.time.ZonedDateTime` at that instant in that zone. The rules for missing and extra
   * fields are those of the [[BsonDecoder.offsetDateTime]] decoder.
   */
  implicit lazy val zonedDateTime: BsonDecoder[ZonedDateTime] =
    locationDateTime[ZonedDateTime]("zone_id") { (instant, zoneId) =>
      Try {
        ZonedDateTime.ofInstant(instant, ZoneId.of(zoneId))
      }.toEither.swap.map(t => s"Failed to parse ZonedDateTime: ${t.getMessage}").swap
    }

  /**
   * Decodes a BSON string of length one as a `Char`. A string of any other length fails with
   * `Expected one character, but got a string of length <n>.`
   */
  implicit val char: BsonDecoder[Char] = string.mapOrFail { str =>
    if (str.length == 1) Right(str(0))
    else Left(s"Expected one character, but got a string of length ${str.length}.")
  }

  /** Decodes a BSON string or symbol as a `scala.Symbol`. */
  implicit val symbol: BsonDecoder[Symbol] = string.map(Symbol(_))

  /** Decodes a BSON string in the canonical text form as a `java.util.UUID`. */
  implicit val uuid: BsonDecoder[UUID] = string.mapOrFail { s =>
    Try(UUID.fromString(s)).toEither.swap.map(t => s"Invalid UUID: ${t.getMessage}").swap
  }

  /** Decodes a BSON string holding an ISO 4217 code, such as `"EUR"`, as a `java.util.Currency`. */
  implicit val currency: BsonDecoder[java.util.Currency] = string.mapOrFail { s =>
    Try(java.util.Currency.getInstance(s)).toEither.swap.map(t => s"Invalid Currency: ${t.getMessage}").swap
  }

  /**
   * Decodes an `Option[A]`: BSON null is `None`, and any other value is decoded with the decoder for `A` and wrapped in
   * `Some`. A missing document field is also `None`, through [[BsonDecoder#decodeMissingUnsafe]].
   *
   * {{{
   * import org.bson.{BsonInt32, BsonNull}
   *
   * val none: Either[BsonDecoder.Error, Option[Int]] = BsonDecoder[Option[Int]].fromBsonValue(BsonNull.VALUE)
   * // Right(None)
   *
   * val some: Either[BsonDecoder.Error, Option[Int]] = BsonDecoder[Option[Int]].fromBsonValue(new BsonInt32(1))
   * // Right(Some(1))
   * }}}
   */
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

/** Implicit decoders that are tried after those in the [[BsonDecoder]] companion. */
trait LowPriorityBsonDecoder1 {

  /** Returns the decoder of the implicit [[BsonCodec]] for `A`. */
  implicit def fromCodec[A](implicit codec: BsonCodec[A]): BsonDecoder[A] = codec.decoder
}

/**
 * Implicit decoders for the numeric types, mixed into the `BsonDecoder` companion.
 *
 * The numeric decoders accept several BSON types. Integers convert to `Float` and `Double` with rounding. The other
 * conversions fail when the value does not convert exactly, and a BSON string is parsed as a number.
 */
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

  /**
   * Decodes a BSON number or string as an `Int`.
   *
   * A 32-bit integer is taken as is. A 64-bit integer must be within the range of an `Int`, and fails with a message
   * such as `Long value 3000000000 is not within range [-2147483648, 2147483647].` otherwise. A double must hold a
   * whole number in that range, and fails with `Could not convert DOUBLE(1.5) to a Int without losing precision.`
   * otherwise. A Decimal128 must also be written without a fractional part: `3` decodes and `3.0` fails. A string is
   * parsed, and fails with a `NumberFormatException` message when it isn't an `Int`.
   *
   * {{{
   * import org.bson.BsonDouble
   *
   * val whole: Either[BsonDecoder.Error, Int] = BsonDecoder[Int].fromBsonValue(new BsonDouble(3.0))
   * // Right(3)
   *
   * val fraction: Either[String, Int] = BsonDecoder[Int].fromBsonValue(new BsonDouble(1.5)).left.map(_.render)
   * // Left(: Could not convert DOUBLE(1.5) to a Int without losing precision.)
   * }}}
   */
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

  /**
   * Decodes a BSON number or string as a `Byte`. The value is decoded as an `Int` first and fails with
   * `Int value 300 is not within range [-128, 127].` when it is outside the range of a `Byte`.
   */
  implicit val byte: BsonDecoder[Byte] =
    int.mapOrFail(validateIntRange(_, Byte.MinValue, Byte.MaxValue).map(_.toByte))

  /**
   * Decodes a BSON number or string as a `Short`. The value is decoded as an `Int` first and fails when it is outside
   * the range of a `Short`.
   */
  implicit val short: BsonDecoder[Short] =
    int.mapOrFail(validateIntRange(_, Short.MinValue, Short.MaxValue).map(_.toShort))

  /**
   * Decodes a BSON number or string as a `Long`.
   *
   * A 32-bit or 64-bit integer is taken as is. A double must hold a whole number, a Decimal128 must be written without
   * a fractional part (`3` decodes, `3.0` fails), and a string is parsed. A double of exactly 9.223372036854775808E18
   * (2 to the power of 63) decodes as `Long.MaxValue`.
   */
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

  /**
   * Decodes a BSON number or string as a `Float`.
   *
   * A 32-bit or 64-bit integer is converted with rounding when it has more digits than a `Float` holds. A double must
   * convert to a `Float` exactly. A Decimal128 decodes only when it is written as `Double.toString` writes the `Float`
   * widened to a `Double`: `0.5` and `1.0` decode, while `1` and `0.1` fail with
   * `Could not convert DECIMAL128(0.1) to a Float without losing precision.` A string is parsed.
   */
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

  /**
   * Decodes a BSON number or string as a `Double`.
   *
   * A 32-bit integer, a 64-bit integer, and a double are converted directly; a 64-bit integer with more digits than a
   * `Double` holds is rounded. A Decimal128 decodes only when it is written as `Double.toString` writes the result:
   * `0.1` and `1.0` decode, while `0.10` and `1` fail. A string is parsed.
   */
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

  /**
   * Decodes a BSON number or string as a `java.math.BigDecimal`.
   *
   * Integers keep their value, and a Decimal128 is converted to a `BigDecimal`. A double is converted through the text
   * of `Double.toString`, so a BSON double `0.1` decodes as `0.1`. A string is parsed.
   */
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

  /**
   * Decodes a BSON number or string as a `scala.BigDecimal`, with the rules of the `java.math.BigDecimal` decoder.
   */
  implicit val bigDecimal: BsonDecoder[BigDecimal] = javaBigDecimal.map(x => x)

  /**
   * Decodes a BSON number or string as a `java.math.BigInteger`.
   *
   * Integers are taken as is. A double must hold a whole number, a Decimal128 must be written without a fractional part
   * (`3` decodes, `3.0` fails), and a string of digits is parsed, which is the form that the `BigInt` encoder writes.
   */
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

  /** Decodes a BSON number or string as a `scala.BigInt`, with the rules of the `java.math.BigInteger` decoder. */
  implicit val bigInt: BsonDecoder[BigInt] = bigInteger.map(x => x)

}

/** Implicit decoders for binary data, mixed into the `BsonDecoder` companion. */
trait CollectionDecoders extends LowPriorityCollectionDecoders {

  /**
   * Decodes BSON binary data into a collection of bytes that `factory` can build, such as `Array[Byte]`, `Chunk[Byte]`
   * or `Vector[Byte]`.
   */
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

  /** Decodes BSON binary data as a `zio.NonEmptyChunk[Byte]`. Empty data fails with `Chunk was empty`. */
  implicit val byteNonEmptyChunk: BsonDecoder[NonEmptyChunk[Byte]] =
    byteIterableFactory[Chunk].mapOrFail(NonEmptyChunk.fromChunk(_).toRight("Chunk was empty"))
}

/** Implicit decoders for arrays and maps, tried after the decoders in [[CollectionDecoders]]. */
trait LowPriorityCollectionDecoders {

  /** Decodes a BSON array as a `zio.NonEmptyChunk[A]`. An empty array fails with `Chunk was empty`. */
  implicit def nonEmptyChunk[A: BsonDecoder]: BsonDecoder[NonEmptyChunk[A]] =
    implicitly[BsonDecoder[Chunk[A]]].mapOrFail(NonEmptyChunk.fromChunk(_).toRight("Chunk was empty"))

  /**
   * Decodes a BSON array into a collection that `factory` can build, such as `List`, `Vector`, `Set` or `Array`,
   * decoding each element with the decoder for `A`. An error in an element reports the index of the element in its
   * path.
   *
   * {{{
   * import org.bson.{BsonArray, BsonInt32, BsonString}
   *
   * val array = new BsonArray()
   * array.add(new BsonInt32(1))
   * array.add(new BsonString("x"))
   *
   * val result: Either[String, List[Int]] = BsonDecoder[List[Int]].fromBsonValue(array).left.map(_.render)
   * // Left([1]: NumberFormatException(For input string: "x"))
   * }}}
   */
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

  /**
   * Decodes a BSON document into a map that `factory` can build. Each field name is decoded with the implicit
   * [[BsonFieldDecoder]] for `K` and each value with the decoder for `V`. An error in a value reports the field name in
   * its path.
   *
   * {{{
   * import org.bson.{BsonDocument, BsonInt32}
   *
   * val document = new BsonDocument("a", new BsonInt32(1))
   * document.put("b", new BsonInt32(2))
   *
   * val map: Either[BsonDecoder.Error, Map[String, Int]] = BsonDecoder[Map[String, Int]].fromBsonValue(document)
   * // Right(Map(a -> 1, b -> 2))
   * }}}
   */
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

/** Implicit decoders for the `org.bson.BsonValue` types, mixed into the `BsonDecoder` companion. */
trait BsonValueDecoders {
  private lazy val registry       = Bson.DEFAULT_CODEC_REGISTRY
  private lazy val defaultContext = JDecoderContext.builder().build()

  /**
   * Decodes a value as the `org.bson.BsonValue` subtype `T`, for example `BsonString` or `BsonDocument`, without
   * converting it.
   *
   * From a `BsonValue`, a value of another type fails with a message such as
   * `Expected BsonValue type org.bson.BsonString, but got INT32.` From a `BsonReader`, the value is read with the
   * default codec of the MongoDB driver for `T`.
   */
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

/**
 * Helpers for writing decoders by hand: they turn the exceptions of the BSON reader and type mismatches into
 * [[BsonDecoder.Error]].
 */
object DecoderUtils {

  /**
   * Runs `call` and turns a `BsonInvalidOperationException` or an `IllegalStateException` that it throws into a
   * [[BsonDecoder.Error]] at `trace`.
   */
  def unsafeCall[R](trace: List[BsonTrace])(call: => R): R =
    try call
    catch {
      case e: BsonInvalidOperationException => throw BsonDecoder.Error(trace, e.getMessage)
      case e: IllegalStateException         => throw BsonDecoder.Error(trace, e.getMessage)
    }

  /**
   * Throws a [[BsonDecoder.Error]] at `trace` with the message `Expected BSON type <expected>, but got <actual>.`
   */
  def throwInvalidType(trace: List[BsonTrace], expected: BsonType, actual: BsonType): Nothing =
    throw BsonDecoder.Error(trace, s"Expected BSON type $expected, but got $actual.")

  /**
   * Applies `f` to `value` when its BSON type is `tpe`, and otherwise throws a [[BsonDecoder.Error]] at `trace` for the
   * type mismatch. Exceptions from `f` are handled as in `unsafeCall`.
   */
  def assumeType[T](trace: List[BsonTrace])(tpe: BsonType, value: BsonValue)(f: BsonValue => T): T =
    if (value.getBsonType == tpe) unsafeCall(trace)(f(value))
    else throwInvalidType(trace, tpe, value.getBsonType)
}
