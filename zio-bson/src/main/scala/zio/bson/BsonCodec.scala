package zio.bson

import org.bson.BsonValue
import org.bson.types.ObjectId
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
import scala.collection.compat._
import scala.reflect.ClassTag

/**
 * A `BsonCodec[A]` holds a [[BsonEncoder]] and a [[BsonDecoder]] for `A`, so that one implicit value serves both
 * directions. The `BsonEncoder` and `BsonDecoder` companions find the encoder and decoder of an implicit codec.
 *
 * Codecs for your own types come from [[BsonCodec#transform]] and [[BsonCodec#transformOrFail]], from
 * `BsonCodec.apply`, or from the `zio.bson.magnolia.DeriveBsonCodec` derivation in the `zio-bson-magnolia` module.
 *
 * {{{
 * final case class UserId(value: Long)
 *
 * implicit val userIdCodec: BsonCodec[UserId] =
 *   BsonCodec[Long].transform(UserId(_))(_.value)
 *
 * val bson = BsonEncoder[UserId].toBsonValue(UserId(42L))
 * // BsonInt64{value=42}
 * }}}
 *
 * @param encoder
 *   the encoder for `A`
 * @param decoder
 *   the decoder for `A`
 *
 * @see
 *   [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
final case class BsonCodec[A](encoder: BsonEncoder[A], decoder: BsonDecoder[A]) {

  /**
   * Returns a codec for `B` that converts with `to` when decoding and with `from` when encoding. The conversion can't
   * fail; use [[BsonCodec#transformOrFail]] when decoding can.
   *
   * {{{
   * final case class Meters(value: Double)
   *
   * val meters: BsonCodec[Meters] = BsonCodec[Double].transform(Meters(_))(_.value)
   * }}}
   */
  def transform[B](to: A => B)(from: B => A): BsonCodec[B] = BsonCodec(encoder.contramap(from), decoder.map(to))

  /**
   * Returns a codec for `B` that converts with `to` when decoding and with `from` when encoding. When `to` returns a
   * `Left`, the decoding fails with a [[BsonDecoder.Error]] that carries the message.
   *
   * {{{
   * final case class Percent(value: Int)
   *
   * val percent: BsonCodec[Percent] =
   *   BsonCodec[Int].transformOrFail { n =>
   *     if (n >= 0 && n <= 100) Right(Percent(n)) else Left(s"Out of range: \$n")
   *   }(_.value)
   * }}}
   */
  def transformOrFail[B](to: A => Either[String, B])(from: B => A): BsonCodec[B] =
    BsonCodec(encoder.contramap(from), decoder.mapOrFail(to))
}

/**
 * The implicit codecs for the types that `zio-bson` supports, the `BsonCodec.apply` summoner, and the `BsonCodec.apply`
 * overload that builds a codec from an encoder and a decoder.
 *
 * Each codec pairs the encoder and the decoder of the same type from the [[BsonEncoder]] and [[BsonDecoder]]
 * companions, so the mapping of each type is described there.
 */
object BsonCodec extends CollectionCodecs with BsonValueCodecs {

  /** Returns the implicit `BsonCodec[A]` in scope. */
  def apply[A](implicit codec: BsonCodec[A]): BsonCodec[A] = codec

  /** Returns a codec made of `encoder` and `decoder`. */
  def apply[A](encoder: BsonEncoder[A], decoder: BsonDecoder[A]): BsonCodec[A] = new BsonCodec[A](encoder, decoder)

  /** A codec for `String`, using a BSON string. */
  implicit val string: BsonCodec[String] = BsonCodec(BsonEncoder.string, BsonDecoder.string)

  /** A codec for `org.bson.types.ObjectId`, using a BSON ObjectId. */
  implicit val objectId: BsonCodec[ObjectId] = BsonCodec(BsonEncoder.objectId, BsonDecoder.objectId)

  /** A codec for `Boolean`, using a BSON boolean. */
  implicit val boolean: BsonCodec[Boolean] = BsonCodec(BsonEncoder.boolean, BsonDecoder.boolean)

  /** A codec for `java.time.DayOfWeek`, using the name as a BSON string. */
  implicit val dayOfWeek: BsonCodec[DayOfWeek] = BsonCodec(BsonEncoder.dayOfWeek, BsonDecoder.dayOfWeek)

  /** A codec for `java.time.Month`, using the name as a BSON string. */
  implicit val month: BsonCodec[Month] = BsonCodec(BsonEncoder.month, BsonDecoder.month)

  /** A codec for `java.time.MonthDay`, using an ISO-8601 BSON string. */
  implicit val monthDay: BsonCodec[MonthDay] = BsonCodec(BsonEncoder.monthDay, BsonDecoder.monthDay)

  /** A codec for `java.time.Year`, using a BSON 32-bit integer. */
  implicit val year: BsonCodec[Year] = BsonCodec(BsonEncoder.year, BsonDecoder.year)

  /** A codec for `java.time.YearMonth`, using an ISO-8601 BSON string. */
  implicit val yearMonth: BsonCodec[YearMonth] = BsonCodec(BsonEncoder.yearMonth, BsonDecoder.yearMonth)

  /** A codec for `java.time.ZoneId`, using the id as a BSON string. */
  implicit val zoneId: BsonCodec[ZoneId] = BsonCodec(BsonEncoder.zoneId, BsonDecoder.zoneId)

  /** A codec for `java.time.ZoneOffset`, using a BSON string such as `"+02:00"`. */
  implicit val zoneOffset: BsonCodec[ZoneOffset] = BsonCodec(BsonEncoder.zoneOffset, BsonDecoder.zoneOffset)

  /** A codec for `java.time.Duration`, using an ISO-8601 BSON string. */
  implicit val duration: BsonCodec[Duration] = BsonCodec(BsonEncoder.duration, BsonDecoder.duration)

  /** A codec for `java.time.Period`, using an ISO-8601 BSON string. */
  implicit val period: BsonCodec[Period] = BsonCodec(BsonEncoder.period, BsonDecoder.period)

  /** A codec for `java.time.Instant`, using a BSON date-time with millisecond precision. */
  implicit val instant: BsonCodec[Instant] = BsonCodec(BsonEncoder.instant, BsonDecoder.instant)

  /** A codec for `java.time.LocalDate`, using a BSON date-time at the start of the day in UTC. */
  implicit val localDate: BsonCodec[LocalDate] = BsonCodec(BsonEncoder.localDate, BsonDecoder.localDate)

  /** A codec for `java.time.LocalDateTime`, using a BSON date-time read as UTC. */
  implicit val localDateTime: BsonCodec[LocalDateTime] = BsonCodec(BsonEncoder.localDateTime, BsonDecoder.localDateTime)

  /** A codec for `java.time.LocalTime`, using a BSON date-time on 1970-01-01 in UTC. */
  implicit val localTime: BsonCodec[LocalTime] = BsonCodec(BsonEncoder.localTime, BsonDecoder.localTime)

  /** A codec for `java.time.OffsetDateTime`, using a BSON document with `date_time` and `offset` fields. */
  implicit lazy val offsetDateTime: BsonCodec[OffsetDateTime] =
    BsonCodec(BsonEncoder.offsetDateTime, BsonDecoder.offsetDateTime)

  /** A codec for `java.time.OffsetTime`, using a BSON document with `date_time` and `offset` fields. */
  implicit lazy val offsetTime: BsonCodec[OffsetTime] =
    BsonCodec(BsonEncoder.offsetTime, BsonDecoder.offsetTime)

  /** A codec for `java.time.ZonedDateTime`, using a BSON document with `date_time` and `zone_id` fields. */
  implicit lazy val zonedDateTime: BsonCodec[ZonedDateTime] =
    BsonCodec(BsonEncoder.zonedDateTime, BsonDecoder.zonedDateTime)

  /** A codec for `Char`, using a BSON string of length one. */
  implicit val char: BsonCodec[Char] = BsonCodec(BsonEncoder.char, BsonDecoder.char)

  /** A codec for `scala.Symbol`, using a BSON string. */
  implicit val symbol: BsonCodec[Symbol] = BsonCodec(BsonEncoder.symbol, BsonDecoder.symbol)

  /** A codec for `java.util.UUID`, using a BSON string. */
  implicit val uuid: BsonCodec[UUID] = BsonCodec(BsonEncoder.uuid, BsonDecoder.uuid)

  /** A codec for `java.util.Currency`, using the ISO 4217 code as a BSON string. */
  implicit val currency: BsonCodec[java.util.Currency] = BsonCodec(BsonEncoder.currency, BsonDecoder.currency)

  /** A codec for `Option[A]`: `None` is BSON null, and a missing field in a document decodes as `None`. */
  implicit def option[A](implicit encoder: BsonEncoder[A], decoder: BsonDecoder[A]): BsonCodec[Option[A]] =
    BsonCodec(BsonEncoder.option(encoder), BsonDecoder.option(decoder))

  /** A codec for `Int`, using a BSON 32-bit integer. */
  implicit val int: BsonCodec[Int] = BsonCodec(BsonEncoder.int, BsonDecoder.int)

  /** A codec for `Byte`, using a BSON 32-bit integer. */
  implicit val byte: BsonCodec[Byte] = BsonCodec(BsonEncoder.byte, BsonDecoder.byte)

  /** A codec for `Short`, using a BSON 32-bit integer. */
  implicit val short: BsonCodec[Short] = BsonCodec(BsonEncoder.short, BsonDecoder.short)

  /** A codec for `Long`, using a BSON 64-bit integer. */
  implicit val long: BsonCodec[Long] = BsonCodec(BsonEncoder.long, BsonDecoder.long)

  /** A codec for `Float`, using a BSON double. */
  implicit val float: BsonCodec[Float] = BsonCodec(BsonEncoder.float, BsonDecoder.float)

  /** A codec for `Double`, using a BSON double. */
  implicit val double: BsonCodec[Double] = BsonCodec(BsonEncoder.double, BsonDecoder.double)

  /** A codec for `java.math.BigDecimal`, using a BSON Decimal128. */
  implicit val javaBigDecimal: BsonCodec[JBigDecimal] =
    BsonCodec(BsonEncoder.javaBigDecimal, BsonDecoder.javaBigDecimal)

  /** A codec for `scala.BigDecimal`, using a BSON Decimal128. */
  implicit val bigDecimal: BsonCodec[BigDecimal] = BsonCodec(BsonEncoder.bigDecimal, BsonDecoder.bigDecimal)

  /** A codec for `scala.BigInt`, using a BSON string of decimal digits. */
  implicit val bigInt: BsonCodec[BigInt] = BsonCodec(BsonEncoder.bigInt, BsonDecoder.bigInt)

  /** A codec for `java.math.BigInteger`, using a BSON string of decimal digits. */
  implicit val bigInteger: BsonCodec[BigInteger] = BsonCodec(BsonEncoder.bigInteger, BsonDecoder.bigInteger)

}

/** Implicit codecs for byte collections and maps, mixed into the [[BsonCodec]] companion. */
trait CollectionCodecs extends LowPriorityCollectionCodecs {

  /** A codec for `Array[Byte]`, using BSON binary data. */
  implicit val byteArray: BsonCodec[Array[Byte]] =
    BsonCodec(BsonEncoder.byteArray, BsonDecoder.byteIterableFactory[Array])

  /** A codec for a collection of bytes, such as `Vector[Byte]`, using BSON binary data. */
  implicit def byteIterable[CC[T] <: Iterable[T]](implicit factory: Factory[Byte, CC[Byte]]): BsonCodec[CC[Byte]] =
    BsonCodec(BsonEncoder.byteIterable[CC[Byte]], BsonDecoder.byteIterableFactory[CC])

  /** A codec for `zio.NonEmptyChunk[Byte]`, using BSON binary data. */
  implicit val byteNonEmptyChunk: BsonCodec[NonEmptyChunk[Byte]] =
    BsonCodec(BsonEncoder.byteNonEmptyChunk, BsonDecoder.byteNonEmptyChunk)

  /**
   * A codec for a map, using a BSON document. The keys go through the implicit [[BsonFieldEncoder]] and
   * [[BsonFieldDecoder]] for `K`.
   */
  implicit def map[
    K: BsonFieldEncoder: BsonFieldDecoder,
    V: BsonEncoder: BsonDecoder,
    CC[A, B] <: scala.collection.Map[A, B]
  ](implicit
    factory: Factory[(K, V), CC[K, V]]
  ): BsonCodec[CC[K, V]] =
    BsonCodec(BsonEncoder.map[K, V, CC], BsonDecoder.mapFactory[K, V, CC])
}

/** Implicit codecs for collections, arrays and `zio.NonEmptyChunk`, tried after the codecs in [[CollectionCodecs]]. */
trait LowPriorityCollectionCodecs {

  /** A codec for `zio.NonEmptyChunk[A]`, using a BSON array. */
  implicit def nonEmptyChunk[A: BsonEncoder: BsonDecoder]: BsonCodec[NonEmptyChunk[A]] =
    BsonCodec(BsonEncoder.iterable[A, Chunk].contramap[NonEmptyChunk[A]](_.toChunk), BsonDecoder.nonEmptyChunk[A])

  /** A codec for `Array[A]`, using a BSON array. */
  implicit def array[A: BsonEncoder: BsonDecoder: ClassTag]: BsonCodec[Array[A]] =
    BsonCodec(BsonEncoder.array[A], BsonDecoder.iterableFactory[A, Array])

  /**
   * A codec for a collection of `A` that `Factory` can build, such as `List`, `Vector` or `Set`, using a BSON array.
   */
  implicit def iterable[A: BsonEncoder: BsonDecoder, CC[T] <: Iterable[T]](implicit
    factory: Factory[A, CC[A]]
  ): BsonCodec[CC[A]] =
    BsonCodec(BsonEncoder.iterable[A, CC], BsonDecoder.iterableFactory[A, CC])
}

/** Implicit codecs for the `org.bson.BsonValue` types, mixed into the [[BsonCodec]] companion. */
trait BsonValueCodecs {

  /** A codec for the `org.bson.BsonValue` subtype `T`, which is encoded and decoded as itself. */
  implicit def bsonValueDecoder[T <: BsonValue: ClassTag]: BsonCodec[T] =
    BsonCodec(BsonEncoder.bsonValueEncoder[T], BsonDecoder.bsonValueDecoder[T])
}
