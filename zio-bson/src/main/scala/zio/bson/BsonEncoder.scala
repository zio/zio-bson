package zio.bson

import org.bson._
import org.bson.codecs.{EncoderContext => JEncoderContext}
import org.bson.conversions.Bson
import org.bson.types.{Decimal128, ObjectId}
import zio.bson.BsonEncoder.EncoderContext
import zio.{Chunk, NonEmptyChunk}

import java.math.{BigDecimal => JBigDecimal, BigInteger}
import java.time.{
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
import scala.annotation.unused
import scala.collection.compat._
import scala.jdk.CollectionConverters._

/**
 * A `BsonEncoder[A]` converts values of type `A` to BSON. It can write a value to an `org.bson.BsonWriter`
 * ([[BsonEncoder#encode]]) or build an in-memory `org.bson.BsonValue` ([[BsonEncoder#toBsonValue]]).
 *
 * Instances for the common Scala and Java types are implicit members of the `BsonEncoder` companion object. Instances
 * for your own types come from [[BsonEncoder#contramap]], from a [[BsonCodec]], or from the
 * `zio.bson.magnolia.DeriveBsonEncoder` derivation in the `zio-bson-magnolia` module.
 *
 * {{{
 * import org.bson.BsonValue
 *
 * final case class UserId(value: Long)
 *
 * val userId: BsonEncoder[UserId] = BsonEncoder[Long].contramap(_.value)
 *
 * val bson: BsonValue = userId.toBsonValue(UserId(42L))
 * // BsonInt64{value=42}
 * }}}
 *
 * @see
 *   [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
trait BsonEncoder[A] { self =>

  locally {
    // TODO: better way to fix unused import warning.
    val _ = IterableOnce
  }

  /**
   * Returns an encoder for `B` that applies `f` to each value and encodes the result with this encoder.
   *
   * The new encoder reports a value as absent (see [[BsonEncoder#isAbsent]]) when this encoder does so for `f(value)`.
   *
   * {{{
   * import org.bson.BsonValue
   *
   * final case class Email(address: String)
   *
   * val email: BsonEncoder[Email] = BsonEncoder[String].contramap(_.address)
   *
   * val bson: BsonValue = email.toBsonValue(Email("ada@example.com"))
   * // BsonString{value='ada@example.com'}
   * }}}
   */
  final def contramap[B](f: B => A): BsonEncoder[B] = new BsonEncoder[B] {
    override def isAbsent(value: B): Boolean = self.isAbsent(f(value))

    def encode(writer: BsonWriter, value: B, ctx: EncoderContext): Unit =
      self.encode(writer, f(value), ctx)

    def toBsonValue(value: B): BsonValue = self.toBsonValue(f(value))
  }

  /**
   * Returns `true` when `value` counts as absent. The encoders that the derivation in `zio-bson-magnolia` creates for
   * case classes leave a field out of the document when its encoder reports the value as absent and
   * `skipNullsInCaseClass` is enabled, which is the default.
   *
   * Returns `false` for every value unless overridden. The `Option` encoder returns `true` for `None`.
   */
  def isAbsent(@unused value: A): Boolean =
    false

  /**
   * Writes `value` to `writer` as one BSON value. The caller has already written the field name when the value is a
   * document field.
   *
   * `ctx` carries the state that the derived case class encoders use to write into a document that is already open (see
   * [[BsonEncoder.EncoderContext]]). Pass [[BsonEncoder.EncoderContext.default]] when calling an encoder directly.
   */
  def encode(writer: BsonWriter, value: A, ctx: EncoderContext): Unit

  /**
   * Returns `value` as an in-memory `org.bson.BsonValue`.
   *
   * {{{
   * import org.bson.BsonValue
   *
   * val bson: BsonValue = BsonEncoder[List[Int]].toBsonValue(List(1, 2))
   * // BsonArray{values=[BsonInt32{value=1}, BsonInt32{value=2}]}
   * }}}
   */
  def toBsonValue(value: A): BsonValue
}

/**
 * The implicit encoders for the types that `zio-bson` supports, and the `BsonEncoder.apply` summoner.
 *
 * The `zio.bson` package object adds a `toBsonValue` extension method for values that have an encoder in scope.
 */
object BsonEncoder extends NumberEncoders with CollectionEncoders with BsonValueEncoders with LowPriorityBsonEncoder1 {

  /**
   * State that an encoder passes down to the encoders of nested values.
   *
   * @param inlineNextObject
   *   when `true`, the next case class encoder that runs, which is the one the derivation in `zio-bson-magnolia`
   *   creates, writes its fields into the document that is already open instead of opening a new one. The derived
   *   encoders for sum types use it to place a discriminator field next to the fields of the chosen subtype. Encoders
   *   written by hand don't read it.
   */
  case class EncoderContext(inlineNextObject: Boolean = false)

  /** The companion of [[BsonEncoder.EncoderContext]]. */
  object EncoderContext {

    /** The context with `inlineNextObject` set to `false`. */
    val default: EncoderContext = EncoderContext()
  }

  /**
   * Returns the implicit `BsonEncoder[A]` in scope.
   *
   * {{{
   * val encoder: BsonEncoder[Int] = BsonEncoder[Int]
   * }}}
   */
  def apply[A](implicit encoder: BsonEncoder[A]): BsonEncoder[A] = encoder

  /** Encodes a `String` as a BSON string. */
  implicit val string: BsonEncoder[String] = new BsonEncoder[String] {

    def encode(writer: BsonWriter, value: String, ctx: EncoderContext): Unit =
      writer.writeString(value)

    def toBsonValue(value: String): BsonValue = new BsonString(value)
  }

  /** Encodes an `org.bson.types.ObjectId` as a BSON ObjectId. */
  implicit val objectId: BsonEncoder[ObjectId] = new BsonEncoder[ObjectId] {

    def encode(writer: BsonWriter, value: ObjectId, ctx: EncoderContext): Unit =
      writer.writeObjectId(value)

    def toBsonValue(value: ObjectId): BsonValue = new BsonObjectId(value)
  }

  /** Encodes a `Boolean` as a BSON boolean. */
  implicit val boolean: BsonEncoder[Boolean] = new BsonEncoder[Boolean] {

    def encode(writer: BsonWriter, value: Boolean, ctx: EncoderContext): Unit =
      writer.writeBoolean(value)

    def toBsonValue(value: Boolean): BsonValue = new BsonBoolean(value)
  }

  /** Encodes a `java.time.DayOfWeek` as a BSON string holding its name, for example `"MONDAY"`. */
  implicit val dayOfWeek: BsonEncoder[DayOfWeek] = string.contramap(_.toString)

  /** Encodes a `java.time.Month` as a BSON string holding its name, for example `"MAY"`. */
  implicit val month: BsonEncoder[Month] = string.contramap(_.toString)

  /** Encodes a `java.time.MonthDay` as a BSON string in its ISO-8601 form, for example `"--12-03"`. */
  implicit val monthDay: BsonEncoder[MonthDay] = string.contramap(_.toString)

  /** Encodes a `java.time.Year` as a BSON 32-bit integer holding the year. */
  implicit val year: BsonEncoder[Year] = int.contramap(_.getValue)

  /** Encodes a `java.time.YearMonth` as a BSON string in its ISO-8601 form, for example `"2024-05"`. */
  implicit val yearMonth: BsonEncoder[YearMonth] = string.contramap(_.toString)

  /** Encodes a `java.time.ZoneId` as a BSON string holding its id, for example `"Europe/Paris"`. */
  implicit val zoneId: BsonEncoder[ZoneId] = string.contramap(_.getId)

  /** Encodes a `java.time.ZoneOffset` as a BSON string, for example `"+02:00"`. */
  implicit val zoneOffset: BsonEncoder[ZoneOffset] = string.contramap(_.toString)

  /** Encodes a `java.time.Duration` as a BSON string in its ISO-8601 form, for example `"PT1H30M"`. */
  implicit val duration: BsonEncoder[Duration] = string.contramap(_.toString)

  /** Encodes a `java.time.Period` as a BSON string in its ISO-8601 form, for example `"P1Y2M"`. */
  implicit val period: BsonEncoder[Period] = string.contramap(_.toString)

  /**
   * Encodes a `java.time.Instant` as a BSON date-time holding the milliseconds since the epoch. Encoding throws a
   * `java.lang.ArithmeticException` for an instant whose milliseconds don't fit in a `Long`.
   *
   * The precision is one millisecond, so the sub-millisecond part of the instant is not stored.
   */
  implicit val instant: BsonEncoder[Instant] = new BsonEncoder[Instant] {

    def encode(writer: BsonWriter, value: Instant, ctx: EncoderContext): Unit =
      writer.writeDateTime(value.toEpochMilli)

    def toBsonValue(value: Instant): BsonValue = new BsonDateTime(value.toEpochMilli)
  }

  /** Encodes a `java.time.LocalDate` as a BSON date-time at the start of that day in UTC. */
  implicit val localDate: BsonEncoder[LocalDate] = instant.contramap(_.atStartOfDay(ZoneOffset.UTC).toInstant)

  /** Encodes a `java.time.LocalDateTime` as a BSON date-time, reading the value as UTC. */
  implicit val localDateTime: BsonEncoder[LocalDateTime] = instant.contramap(_.toInstant(ZoneOffset.UTC))

  /** Encodes a `java.time.LocalTime` as a BSON date-time on 1970-01-01 in UTC. */
  implicit val localTime: BsonEncoder[LocalTime] =
    instant.contramap(_.atDate(LocalDate.ofEpochDay(0L)).toInstant(ZoneOffset.UTC))

  private def locationDateTime[T](toInstant: T => Instant, toLocation: T => String, locationField: String) =
    new BsonEncoder[T] {
      private val DATE_TIME = "date_time"

      def encode(writer: BsonWriter, value: T, ctx: EncoderContext): Unit = {
        writer.writeStartDocument()

        writer.writeName(DATE_TIME)
        instant.encode(writer, toInstant(value), ctx)
        writer.writeString(locationField, toLocation(value))

        writer.writeEndDocument()
      }

      def toBsonValue(value: T): BsonValue =
        new BsonDocument(
          Vector(
            new BsonElement(DATE_TIME, instant.toBsonValue(toInstant(value))),
            new BsonElement(locationField, new BsonString(toLocation(value)))
          ).asJava
        )
    }

  /**
   * Encodes a `java.time.OffsetDateTime` as a BSON document with two fields: `date_time`, a BSON date-time of the
   * instant, and `offset`, a string such as `"+02:00"`.
   *
   * {{{
   * import java.time.OffsetDateTime
   * import org.bson.BsonValue
   *
   * val bson: BsonValue = BsonEncoder[OffsetDateTime].toBsonValue(OffsetDateTime.parse("2024-05-01T10:00:00+02:00"))
   * // {"date_time": {"\$date": "2024-05-01T08:00:00Z"}, "offset": "+02:00"}
   * }}}
   */
  implicit val offsetDateTime: BsonEncoder[OffsetDateTime] =
    locationDateTime[OffsetDateTime](_.toInstant, _.getOffset.toString, "offset")

  /**
   * Encodes a `java.time.OffsetTime` like an `OffsetDateTime` on 1970-01-01: a document with `date_time` and `offset`
   * fields.
   */
  implicit val offsetTime: BsonEncoder[OffsetTime] =
    offsetDateTime.contramap(_.atDate(LocalDate.ofEpochDay(0L)))

  /**
   * Encodes a `java.time.ZonedDateTime` as a BSON document with two fields: `date_time`, a BSON date-time of the
   * instant, and `zone_id`, the zone id as a string such as `"Europe/Paris"`.
   */
  implicit val zonedDateTime: BsonEncoder[ZonedDateTime] =
    locationDateTime[ZonedDateTime](_.toInstant, _.getZone.getId, "zone_id")

  /** Encodes a `Char` as a BSON string of length one. */
  implicit val char: BsonEncoder[Char] = string.contramap(_.toString)

  /** Encodes a `scala.Symbol` as a BSON string holding its name. */
  implicit val symbol: BsonEncoder[Symbol] = string.contramap(_.name)

  /** Encodes a `java.util.UUID` as a BSON string in its canonical text form. */
  implicit val uuid: BsonEncoder[UUID] = string.contramap(_.toString)

  /** Encodes a `java.util.Currency` as a BSON string holding its ISO 4217 code, for example `"EUR"`. */
  implicit val currency: BsonEncoder[java.util.Currency] = string.contramap(_.getCurrencyCode)

  /**
   * Encodes an `Option[A]`: `Some(a)` is encoded with the encoder for `A`, and `None` as BSON null.
   *
   * [[BsonEncoder#isAbsent]] is `true` for `None`, so a derived case class encoder leaves the field out unless
   * `skipNullsInCaseClass` is set to `false` in the configuration.
   *
   * {{{
   * import org.bson.BsonValue
   *
   * val some: BsonValue = BsonEncoder[Option[Int]].toBsonValue(Some(1))
   * // BsonInt32{value=1}
   *
   * val none: BsonValue = BsonEncoder[Option[Int]].toBsonValue(None)
   * // BsonNull
   * }}}
   */
  implicit def option[A](implicit encoder: BsonEncoder[A]): BsonEncoder[Option[A]] = new BsonEncoder[Option[A]] {
    override def isAbsent(value: Option[A]): Boolean = value.isEmpty

    def encode(writer: BsonWriter, value: Option[A], ctx: EncoderContext): Unit =
      value match {
        case Some(value) => encoder.encode(writer, value, ctx)
        case None        => writer.writeNull()
      }

    def toBsonValue(value: Option[A]): BsonValue = value match {
      case Some(value) => encoder.toBsonValue(value)
      case None        => BsonNull.VALUE
    }
  }

}

/** Implicit encoders that are tried after those in the [[BsonEncoder]] companion. */
trait LowPriorityBsonEncoder1 {

  /** Returns the encoder of the implicit [[BsonCodec]] for `A`. */
  implicit def fromCodec[A](implicit codec: BsonCodec[A]): BsonEncoder[A] = codec.encoder
}

/** Implicit encoders for the numeric types, mixed into the `BsonEncoder` companion. */
trait NumberEncoders {

  /** Encodes an `Int` as a BSON 32-bit integer. */
  implicit val int: BsonEncoder[Int] = new BsonEncoder[Int] {
    def encode(writer: BsonWriter, value: Int, ctx: EncoderContext): Unit = writer.writeInt32(value)

    def toBsonValue(value: Int): BsonValue = new BsonInt32(value)
  }

  /** Encodes a `Byte` as a BSON 32-bit integer. */
  implicit val byte: BsonEncoder[Byte] = int.contramap(_.toInt)

  /** Encodes a `Short` as a BSON 32-bit integer. */
  implicit val short: BsonEncoder[Short] = int.contramap(_.toInt)

  /** Encodes a `Long` as a BSON 64-bit integer. */
  implicit val long: BsonEncoder[Long] = new BsonEncoder[Long] {
    def encode(writer: BsonWriter, value: Long, ctx: EncoderContext): Unit = writer.writeInt64(value)

    def toBsonValue(value: Long): BsonValue = new BsonInt64(value)
  }

  /** Encodes a `Float` as a BSON double, widening the value to a `Double`. */
  implicit val float: BsonEncoder[Float] = new BsonEncoder[Float] {
    def encode(writer: BsonWriter, value: Float, ctx: EncoderContext): Unit = writer.writeDouble(value.toDouble)

    def toBsonValue(value: Float): BsonValue = new BsonDouble(value.toDouble)
  }

  /** Encodes a `Double` as a BSON double. */
  implicit val double: BsonEncoder[Double] = new BsonEncoder[Double] {
    def encode(writer: BsonWriter, value: Double, ctx: EncoderContext): Unit = writer.writeDouble(value)

    def toBsonValue(value: Double): BsonValue = new BsonDouble(value)
  }

  /**
   * Encodes a `java.math.BigDecimal` as a BSON Decimal128. Encoding throws a `java.lang.NumberFormatException` for a
   * value that Decimal128 can't hold exactly, such as one with more than 34 significant digits.
   */
  implicit val javaBigDecimal: BsonEncoder[JBigDecimal] = new BsonEncoder[JBigDecimal] {

    def encode(writer: BsonWriter, value: JBigDecimal, ctx: EncoderContext): Unit =
      writer.writeDecimal128(new Decimal128(value))

    def toBsonValue(value: JBigDecimal): BsonValue = new BsonDecimal128(
      new Decimal128(value)
    )
  }

  /**
   * Encodes a `scala.BigDecimal` as a BSON Decimal128. Encoding throws a `java.lang.NumberFormatException` for a value
   * that Decimal128 can't hold exactly, such as one with more than 34 significant digits.
   */
  implicit val bigDecimal: BsonEncoder[BigDecimal] = javaBigDecimal.contramap(_.underlying())

  /**
   * Encodes a `scala.BigInt` as a BSON string of its decimal digits, so that values beyond the range of a `Long` are
   * kept.
   */
  implicit val bigInt: BsonEncoder[BigInt] = new BsonEncoder[BigInt] {

    def encode(writer: BsonWriter, value: BigInt, ctx: EncoderContext): Unit =
      writer.writeString(value.toString)

    def toBsonValue(value: BigInt): BsonValue = new BsonString(value.toString)
  }

  /** Encodes a `java.math.BigInteger` as a BSON string of its decimal digits. */
  implicit val bigInteger: BsonEncoder[BigInteger] = bigInt.contramap(x => x)
}

/** Implicit encoders for byte collections and maps, mixed into the `BsonEncoder` companion. */
trait CollectionEncoders extends LowPriorityCollectionEncoders1 {

  /** Encodes an `Array[Byte]` as BSON binary data. */
  implicit val byteArray: BsonEncoder[Array[Byte]] = new BsonEncoder[Array[Byte]] {

    def encode(writer: BsonWriter, value: Array[Byte], ctx: EncoderContext): Unit =
      writer.writeBinaryData(new BsonBinary(value))

    def toBsonValue(value: Array[Byte]): BsonValue = new BsonBinary(value)
  }

  /** Encodes any `Iterable[Byte]` as BSON binary data. */
  implicit def byteIterable[C <: Iterable[Byte]]: BsonEncoder[C] = byteArray.contramap[C](_.toArray)

  /** Encodes a `zio.NonEmptyChunk[Byte]` as BSON binary data. */
  implicit val byteNonEmptyChunk: BsonEncoder[NonEmptyChunk[Byte]] = byteArray.contramap[NonEmptyChunk[Byte]](_.toArray)

  /**
   * Encodes a map as a BSON document. Each key is turned into a field name by the implicit [[BsonFieldEncoder]] for
   * `K`, and each value is encoded with the encoder for `V`.
   *
   * Every entry is written, including those whose value is absent (see [[BsonEncoder#isAbsent]]). A `None` value is
   * written as BSON null.
   *
   * {{{
   * import org.bson.BsonValue
   *
   * val bson: BsonValue = BsonEncoder[Map[String, Int]].toBsonValue(Map("a" -> 1, "b" -> 2))
   * // {"a": 1, "b": 2}
   * }}}
   */
  implicit def map[K, V, CC[A, B] <: scala.collection.Map[A, B]](implicit
    K: BsonFieldEncoder[K],
    V: BsonEncoder[V]
  ): BsonEncoder[CC[K, V]] = new BsonEncoder[CC[K, V]] {

    def encode(writer: BsonWriter, value: CC[K, V], ctx: EncoderContext): Unit = {
      val keepNulls = true // TODO: configuration

      writer.writeStartDocument()

      value.foreachEntry { (k, v) =>
        if (keepNulls || !V.isAbsent(v)) {
          writer.writeName(K.unsafeEncodeField(k))
          V.encode(writer, v, ctx)
        }
      }

      writer.writeEndDocument()
    }

    def toBsonValue(value: CC[K, V]): BsonValue = {
      val keepNulls = true // TODO: configuration

      new BsonDocument(
        value.view.filter { case (_, v) => keepNulls || !V.isAbsent(v) }.map { case (k, v) =>
          new BsonElement(K.unsafeEncodeField(k), V.toBsonValue(v))
        }.toVector.asJava
      )
    }
  }
}

/**
 * Implicit encoders for collections, arrays and `zio.NonEmptyChunk`, tried after the encoders in
 * [[CollectionEncoders]].
 */
trait LowPriorityCollectionEncoders1 {

  /**
   * Encodes any `Iterable` of `A` as a BSON array, in the order of iteration.
   *
   * {{{
   * import org.bson.BsonValue
   *
   * val bson: BsonValue = BsonEncoder[Vector[String]].toBsonValue(Vector("x", "y"))
   * // BsonArray{values=[BsonString{value='x'}, BsonString{value='y'}]}
   * }}}
   */
  implicit def iterable[A, CC[T] <: Iterable[T]](implicit A: BsonEncoder[A]): BsonEncoder[CC[A]] =
    new BsonEncoder[CC[A]] {
      override def encode(writer: BsonWriter, value: CC[A], ctx: EncoderContext): Unit = {
        writer.writeStartArray()

        value.foreach(A.encode(writer, _, ctx))

        writer.writeEndArray()
      }

      def toBsonValue(value: CC[A]): BsonValue =
        new BsonArray(value.view.map(A.toBsonValue).toVector.asJava)
    }

  /** Encodes an `Array[A]` as a BSON array. */
  implicit def array[A: BsonEncoder]: BsonEncoder[Array[A]] =
    iterable[A, IndexedSeq].contramap(scala.collection.compat.immutable.ArraySeq.unsafeWrapArray)

  /** Encodes a `zio.NonEmptyChunk[A]` as a BSON array. */
  implicit def nonEmptyChunk[A: BsonEncoder]: BsonEncoder[NonEmptyChunk[A]] = iterable[A, Chunk].contramap(_.toChunk)
}

/** Implicit encoders for the `org.bson.BsonValue` types, mixed into the `BsonEncoder` companion. */
trait BsonValueEncoders {
  private lazy val registry       = Bson.DEFAULT_CODEC_REGISTRY
  private lazy val defaultContext = JEncoderContext.builder().build()

  /**
   * Encodes any `org.bson.BsonValue` as itself. [[BsonEncoder#toBsonValue]] returns the value unchanged.
   *
   * When writing to a `BsonWriter`, the value is written with the default codec of the MongoDB driver for its class.
   * When the driver has no default codec for that class, the call throws an
   * `org.bson.codecs.configuration.CodecConfigurationException`, with a message such as
   * `Can't find a codec for CodecCacheKey{clazz=class <class name>, types=null}.`
   */
  implicit def bsonValueEncoder[T <: BsonValue]: BsonEncoder[T] = new BsonEncoder[T] {

    def encode(writer: BsonWriter, value: T, ctx: EncoderContext): Unit = {
      val codec = registry.get(value.getClass.asInstanceOf[Class[T]])

      if (codec == null) throw new RuntimeException(s"Can't find codec for BsonValue subtype ${value.getClass.getName}")

      codec.encode(writer, value, defaultContext)
    }

    def toBsonValue(value: T): BsonValue = value
  }
}
