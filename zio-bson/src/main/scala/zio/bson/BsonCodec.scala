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
import scala.collection.Factory
import scala.collection.compat._
import scala.reflect.ClassTag

final case class BsonCodec[A](encoder: BsonEncoder[A], decoder: BsonDecoder[A]) {
  def transform[B](to: A => B)(from: B => A): BsonCodec[B] = BsonCodec(encoder.contramap(from), decoder.map(to))

  def transformOrFail[B](to: A => Either[String, B])(from: B => A): BsonCodec[B] =
    BsonCodec(encoder.contramap(from), decoder.mapOrFail(to))
}

object BsonCodec extends CollectionCodecs with BsonValueCodecs {
  def apply[A](implicit codec: BsonCodec[A]): BsonCodec[A] = codec

  def apply[A](encoder: BsonEncoder[A], decoder: BsonDecoder[A]): BsonCodec[A] = new BsonCodec[A](encoder, decoder)

  implicit val string: BsonCodec[String]               = BsonCodec(BsonEncoder.string, BsonDecoder.string)
  implicit val objectId: BsonCodec[ObjectId]           = BsonCodec(BsonEncoder.objectId, BsonDecoder.objectId)
  implicit val boolean: BsonCodec[Boolean]             = BsonCodec(BsonEncoder.boolean, BsonDecoder.boolean)
  implicit val dayOfWeek: BsonCodec[DayOfWeek]         = BsonCodec(BsonEncoder.dayOfWeek, BsonDecoder.dayOfWeek)
  implicit val month: BsonCodec[Month]                 = BsonCodec(BsonEncoder.month, BsonDecoder.month)
  implicit val monthDay: BsonCodec[MonthDay]           = BsonCodec(BsonEncoder.monthDay, BsonDecoder.monthDay)
  implicit val year: BsonCodec[Year]                   = BsonCodec(BsonEncoder.year, BsonDecoder.year)
  implicit val yearMonth: BsonCodec[YearMonth]         = BsonCodec(BsonEncoder.yearMonth, BsonDecoder.yearMonth)
  implicit val zoneId: BsonCodec[ZoneId]               = BsonCodec(BsonEncoder.zoneId, BsonDecoder.zoneId)
  implicit val zoneOffset: BsonCodec[ZoneOffset]       = BsonCodec(BsonEncoder.zoneOffset, BsonDecoder.zoneOffset)
  implicit val duration: BsonCodec[Duration]           = BsonCodec(BsonEncoder.duration, BsonDecoder.duration)
  implicit val period: BsonCodec[Period]               = BsonCodec(BsonEncoder.period, BsonDecoder.period)
  implicit val instant: BsonCodec[Instant]             = BsonCodec(BsonEncoder.instant, BsonDecoder.instant)
  implicit val localDate: BsonCodec[LocalDate]         = BsonCodec(BsonEncoder.localDate, BsonDecoder.localDate)
  implicit val localDateTime: BsonCodec[LocalDateTime] = BsonCodec(BsonEncoder.localDateTime, BsonDecoder.localDateTime)
  implicit val localTime: BsonCodec[LocalTime]         = BsonCodec(BsonEncoder.localTime, BsonDecoder.localTime)

  implicit lazy val offsetDateTime: BsonCodec[OffsetDateTime] =
    BsonCodec(BsonEncoder.offsetDateTime, BsonDecoder.offsetDateTime)
  implicit lazy val offsetTime: BsonCodec[OffsetTime]         =
    BsonCodec(BsonEncoder.offsetTime, BsonDecoder.offsetTime)
  implicit lazy val zonedDateTime: BsonCodec[ZonedDateTime]   =
    BsonCodec(BsonEncoder.zonedDateTime, BsonDecoder.zonedDateTime)

  implicit val char: BsonCodec[Char]     = BsonCodec(BsonEncoder.char, BsonDecoder.char)
  implicit val symbol: BsonCodec[Symbol] = BsonCodec(BsonEncoder.symbol, BsonDecoder.symbol)
  implicit val uuid: BsonCodec[UUID]     = BsonCodec(BsonEncoder.uuid, BsonDecoder.uuid)

  implicit def option[A](implicit encoder: BsonEncoder[A], decoder: BsonDecoder[A]): BsonCodec[Option[A]] =
    BsonCodec(BsonEncoder.option(encoder), BsonDecoder.option(decoder))

  implicit val int: BsonCodec[Int]                    = BsonCodec(BsonEncoder.int, BsonDecoder.int)
  implicit val byte: BsonCodec[Byte]                  = BsonCodec(BsonEncoder.byte, BsonDecoder.byte)
  implicit val short: BsonCodec[Short]                = BsonCodec(BsonEncoder.short, BsonDecoder.short)
  implicit val long: BsonCodec[Long]                  = BsonCodec(BsonEncoder.long, BsonDecoder.long)
  implicit val float: BsonCodec[Float]                = BsonCodec(BsonEncoder.float, BsonDecoder.float)
  implicit val double: BsonCodec[Double]              = BsonCodec(BsonEncoder.double, BsonDecoder.double)
  implicit val javaBigDecimal: BsonCodec[JBigDecimal] =
    BsonCodec(BsonEncoder.javaBigDecimal, BsonDecoder.javaBigDecimal)
  implicit val bigDecimal: BsonCodec[BigDecimal]      = BsonCodec(BsonEncoder.bigDecimal, BsonDecoder.bigDecimal)
  implicit val bigInt: BsonCodec[BigInt]              = BsonCodec(BsonEncoder.bigInt, BsonDecoder.bigInt)
  implicit val bigInteger: BsonCodec[BigInteger]      = BsonCodec(BsonEncoder.bigInteger, BsonDecoder.bigInteger)

}

trait CollectionCodecs extends LowPriorityCollectionCodecs {
  implicit val byteArray: BsonCodec[Array[Byte]] =
    BsonCodec(BsonEncoder.byteArray, BsonDecoder.byteIterableFactory[Array])

  implicit def byteIterable[CC[T] <: Iterable[T]](implicit factory: Factory[Byte, CC[Byte]]): BsonCodec[CC[Byte]] =
    BsonCodec(BsonEncoder.byteIterable[CC[Byte]], BsonDecoder.byteIterableFactory[CC])

  implicit val byteNonEmptyChunk: BsonCodec[NonEmptyChunk[Byte]] =
    BsonCodec(BsonEncoder.byteNonEmptyChunk, BsonDecoder.byteNonEmptyChunk)

  implicit def map[
    K: BsonFieldEncoder: BsonFieldDecoder,
    V: BsonEncoder: BsonDecoder,
    CC[A, B] <: scala.collection.Map[A, B]
  ](implicit
    factory: Factory[(K, V), CC[K, V]]
  ): BsonCodec[CC[K, V]] =
    BsonCodec(BsonEncoder.map[K, V, CC], BsonDecoder.mapFactory[K, V, CC])
}

trait LowPriorityCollectionCodecs {
  implicit def nonEmptyChunk[A: BsonEncoder: BsonDecoder]: BsonCodec[NonEmptyChunk[A]] =
    BsonCodec(BsonEncoder.iterable[A, Chunk].contramap[NonEmptyChunk[A]](_.toChunk), BsonDecoder.nonEmptyChunk[A])

  implicit def array[A: BsonEncoder: BsonDecoder: ClassTag]: BsonCodec[Array[A]] =
    BsonCodec(BsonEncoder.array[A], BsonDecoder.iterableFactory[A, Array])

  implicit def iterable[A: BsonEncoder: BsonDecoder, CC[T] <: Iterable[T]](implicit
    factory: Factory[A, CC[A]]
  ): BsonCodec[CC[A]] =
    BsonCodec(BsonEncoder.iterable[A, CC], BsonDecoder.iterableFactory[A, CC])
}

trait BsonValueCodecs {
  implicit def bsonValueDecoder[T <: BsonValue: ClassTag]: BsonCodec[T] =
    BsonCodec(BsonEncoder.bsonValueEncoder[T], BsonDecoder.bsonValueDecoder[T])
}
