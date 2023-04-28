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
import scala.collection.compat._
import scala.jdk.CollectionConverters._

trait BsonEncoder[A] { self =>

  final def contramap[B](f: B => A): BsonEncoder[B] = new BsonEncoder[B] {
    override def isAbsent(value: B): Boolean = self.isAbsent(f(value))

    def encode(writer: BsonWriter, value: B, ctx: EncoderContext): Unit =
      self.encode(writer, f(value), ctx)

    def toBsonValue(value: B): BsonValue = self.toBsonValue(f(value))
  }

  /**
   * @return true if encoder can skip this value.
   */
  def isAbsent(value: A): Boolean = {
    val _ = value
    false
  }

  def encode(writer: BsonWriter, value: A, ctx: EncoderContext): Unit

  def toBsonValue(value: A): BsonValue
}

object BsonEncoder extends NumberEncoders with CollectionEncoders with BsonValueEncoders with LowPriorityBsonEncoder1 {

  case class EncoderContext(inlineNextObject: Boolean = false)

  object EncoderContext {
    val default: EncoderContext = EncoderContext()
  }

  def apply[A](implicit encoder: BsonEncoder[A]): BsonEncoder[A] = encoder

  implicit val string: BsonEncoder[String] = new BsonEncoder[String] {

    def encode(writer: BsonWriter, value: String, ctx: EncoderContext): Unit =
      writer.writeString(value)

    def toBsonValue(value: String): BsonValue = new BsonString(value)
  }

  implicit val objectId: BsonEncoder[ObjectId] = new BsonEncoder[ObjectId] {

    def encode(writer: BsonWriter, value: ObjectId, ctx: EncoderContext): Unit =
      writer.writeObjectId(value)

    def toBsonValue(value: ObjectId): BsonValue = new BsonObjectId(value)
  }

  implicit val boolean: BsonEncoder[Boolean] = new BsonEncoder[Boolean] {

    def encode(writer: BsonWriter, value: Boolean, ctx: EncoderContext): Unit =
      writer.writeBoolean(value)

    def toBsonValue(value: Boolean): BsonValue = new BsonBoolean(value)
  }

  implicit val dayOfWeek: BsonEncoder[DayOfWeek]   = string.contramap(_.toString)
  implicit val month: BsonEncoder[Month]           = string.contramap(_.toString)
  implicit val monthDay: BsonEncoder[MonthDay]     = string.contramap(_.toString)
  implicit val year: BsonEncoder[Year]             = string.contramap(_.toString)
  implicit val yearMonth: BsonEncoder[YearMonth]   = string.contramap(_.toString)
  implicit val zoneId: BsonEncoder[ZoneId]         = string.contramap(_.getId)
  implicit val zoneOffset: BsonEncoder[ZoneOffset] = string.contramap(_.toString)

  implicit val duration: BsonEncoder[Duration] = string.contramap(_.toString)
  implicit val period: BsonEncoder[Period]     = string.contramap(_.toString)

  implicit val instant: BsonEncoder[Instant] = new BsonEncoder[Instant] {

    def encode(writer: BsonWriter, value: Instant, ctx: EncoderContext): Unit =
      writer.writeDateTime(value.toEpochMilli)

    def toBsonValue(value: Instant): BsonValue = new BsonDateTime(value.toEpochMilli)
  }

  implicit val localDate: BsonEncoder[LocalDate]         = instant.contramap(_.atStartOfDay(ZoneOffset.UTC).toInstant)
  implicit val localDateTime: BsonEncoder[LocalDateTime] = instant.contramap(_.toInstant(ZoneOffset.UTC))
  implicit val localTime: BsonEncoder[LocalTime]         =
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

  implicit val offsetDateTime: BsonEncoder[OffsetDateTime] =
    locationDateTime[OffsetDateTime](_.toInstant, _.getOffset.toString, "offset")
  implicit val offsetTime: BsonEncoder[OffsetTime]         =
    offsetDateTime.contramap(_.atDate(LocalDate.ofEpochDay(0L)))
  implicit val zonedDateTime: BsonEncoder[ZonedDateTime]   =
    locationDateTime[ZonedDateTime](_.toInstant, _.getZone.getId, "zone_id")

  implicit val char: BsonEncoder[Char] = string.contramap(_.toString)

  implicit val symbol: BsonEncoder[Symbol] = string.contramap(_.name)

  implicit val uuid: BsonEncoder[UUID] = string.contramap(_.toString)

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

trait LowPriorityBsonEncoder1 {
  implicit def fromCodec[A](implicit codec: BsonCodec[A]): BsonEncoder[A] = codec.encoder
}

trait NumberEncoders {
  implicit val int: BsonEncoder[Int] = new BsonEncoder[Int] {
    def encode(writer: BsonWriter, value: Int, ctx: EncoderContext): Unit = writer.writeInt32(value)

    def toBsonValue(value: Int): BsonValue = new BsonInt32(value)
  }

  implicit val byte: BsonEncoder[Byte]   = int.contramap(_.toInt)
  implicit val short: BsonEncoder[Short] = int.contramap(_.toInt)

  implicit val long: BsonEncoder[Long] = new BsonEncoder[Long] {
    def encode(writer: BsonWriter, value: Long, ctx: EncoderContext): Unit = writer.writeInt64(value)

    def toBsonValue(value: Long): BsonValue = new BsonInt64(value)
  }

  implicit val float: BsonEncoder[Float] = new BsonEncoder[Float] {
    def encode(writer: BsonWriter, value: Float, ctx: EncoderContext): Unit = writer.writeDouble(value.toDouble)

    def toBsonValue(value: Float): BsonValue = new BsonDouble(value.toDouble)
  }

  implicit val double: BsonEncoder[Double] = new BsonEncoder[Double] {
    def encode(writer: BsonWriter, value: Double, ctx: EncoderContext): Unit = writer.writeDouble(value)

    def toBsonValue(value: Double): BsonValue = new BsonDouble(value)
  }

  implicit val javaBigDecimal: BsonEncoder[JBigDecimal] = new BsonEncoder[JBigDecimal] {

    def encode(writer: BsonWriter, value: JBigDecimal, ctx: EncoderContext): Unit =
      writer.writeDecimal128(new Decimal128(value))

    def toBsonValue(value: JBigDecimal): BsonValue = new BsonDecimal128(
      new Decimal128(value)
    )
  }

  implicit val bigDecimal: BsonEncoder[BigDecimal] = javaBigDecimal.contramap(_.underlying())

  implicit val bigInt: BsonEncoder[BigInt] = new BsonEncoder[BigInt] {

    def encode(writer: BsonWriter, value: BigInt, ctx: EncoderContext): Unit =
      writer.writeString(value.toString)

    def toBsonValue(value: BigInt): BsonValue = new BsonString(value.toString)
  }

  implicit val bigInteger: BsonEncoder[BigInteger] = bigInt.contramap(x => x)
}

trait CollectionEncoders extends LowPriorityCollectionEncoders1 {
  implicit val byteArray: BsonEncoder[Array[Byte]] = new BsonEncoder[Array[Byte]] {

    def encode(writer: BsonWriter, value: Array[Byte], ctx: EncoderContext): Unit =
      writer.writeBinaryData(new BsonBinary(value))

    def toBsonValue(value: Array[Byte]): BsonValue = new BsonBinary(value)
  }

  implicit def byteIterable[C <: Iterable[Byte]]: BsonEncoder[C] = byteArray.contramap[C](_.toArray)

  implicit val byteNonEmptyChunk: BsonEncoder[NonEmptyChunk[Byte]] = byteArray.contramap[NonEmptyChunk[Byte]](_.toArray)

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

trait LowPriorityCollectionEncoders1 {
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

  implicit def array[A: BsonEncoder]: BsonEncoder[Array[A]] =
    iterable[A, IndexedSeq].contramap(scala.collection.compat.immutable.ArraySeq.unsafeWrapArray)

  implicit def nonEmptyChunk[A: BsonEncoder]: BsonEncoder[NonEmptyChunk[A]] = iterable[A, Chunk].contramap(_.toChunk)
}

trait BsonValueEncoders {
  private lazy val registry       = Bson.DEFAULT_CODEC_REGISTRY
  private lazy val defaultContext = JEncoderContext.builder().build()

  implicit def bsonValueEncoder[T <: BsonValue]: BsonEncoder[T] = new BsonEncoder[T] {

    def encode(writer: BsonWriter, value: T, ctx: EncoderContext): Unit = {
      val codec = registry.get(value.getClass.asInstanceOf[Class[T]])

      if (codec == null) throw new RuntimeException(s"Can't find codec for BsonValue subtype ${value.getClass.getName}")

      codec.encode(writer, value, defaultContext)
    }

    def toBsonValue(value: T): BsonValue = value
  }
}
