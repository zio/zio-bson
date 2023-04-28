package zio.bson

import org.bson._
import org.bson.types.{Decimal128, ObjectId}
import zio._
import zio.bson.BsonBuilder._
import zio.bson.TestUtils._
import zio.bson.model.Generators._
import zio.test.TestAspect.timed
import zio.test._

import java.time.{DayOfWeek, Instant, Month, MonthDay, Period, Year, YearMonth, ZoneId, ZoneOffset}
import java.util.UUID
import scala.jdk.CollectionConverters._

object RoundTripSpec extends ZIOSpecDefault {

  private val dummyObjectId     = new ObjectId(Array[Byte](1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))
  private val dummyInstant      = Instant.EPOCH
  private val dummyBsonDateTime = new BsonDateTime(dummyInstant.toEpochMilli)

  def spec = suite("RoundTripSpec")(
    roundTripTest("String")(genLimitedString, "abc", str("abc"), isDocument = false),
    roundTripTest("ObjectId")(genObjectId, dummyObjectId, new BsonObjectId(dummyObjectId), isDocument = false),
    roundTripTest("Boolean")(Gen.boolean, true, new BsonBoolean(true), isDocument = false),
    roundTripTest("DayOfWeek")(Gen.dayOfWeek, DayOfWeek.MONDAY, str("MONDAY"), isDocument = false),
    roundTripTest("Month")(Gen.month, Month.JANUARY, str("JANUARY"), isDocument = false),
    roundTripTest("MonthDay")(Gen.monthDay, MonthDay.of(Month.JANUARY, 1), str("--01-01"), isDocument = false),
    roundTripTest("Year")(Gen.year, Year.of(2023), str("2023"), isDocument = false),
    roundTripTest("YearMonth")(
      Gen.yearMonth(YearMonth.of(-9999, 1), YearMonth.of(9999, 12)),
      YearMonth.of(2023, Month.JANUARY),
      str("2023-01"),
      isDocument = false
    ),
    roundTripTest("ZoneId")(Gen.zoneId, ZoneId.of("UTC"), str("UTC"), isDocument = false),
    roundTripTest("ZoneOffset")(Gen.zoneOffset, ZoneOffset.UTC, str("Z"), isDocument = false),
    roundTripTest("Duration")(Gen.finiteDuration, Duration.Zero, str("PT0S"), isDocument = false),
    roundTripTest("Period")(Gen.period, Period.ZERO, str("P0D"), isDocument = false),
    roundTripTest("Instant")(genLimitedInstant, dummyInstant, dummyBsonDateTime, isDocument = false),
    roundTripTest("LocalDate")(
      genLimitedLocalDate,
      dummyInstant.atZone(ZoneOffset.UTC).toLocalDate,
      dummyBsonDateTime,
      isDocument = false
    ),
    roundTripTest("LocalDateTime")(
      genLimitedLocalDateTime,
      dummyInstant.atZone(ZoneOffset.UTC).toLocalDateTime,
      dummyBsonDateTime,
      isDocument = false
    ),
    roundTripTest("LocalTime")(
      genLimitedLocalTime,
      dummyInstant.atZone(ZoneOffset.UTC).toLocalTime,
      dummyBsonDateTime,
      isDocument = false
    ),
    roundTripTest("OffsetDateTime")(
      genLimitedOffsetDateTime,
      dummyInstant.atZone(ZoneOffset.UTC).toOffsetDateTime,
      doc("date_time" -> dummyBsonDateTime, "offset" -> str(ZoneOffset.UTC.toString)),
      isDocument = true
    ),
    roundTripTest("OffsetTime")(
      genLimitedOffsetTime,
      dummyInstant.atZone(ZoneOffset.UTC).toOffsetDateTime.toOffsetTime,
      doc("date_time" -> dummyBsonDateTime, "offset" -> str(ZoneOffset.UTC.toString)),
      isDocument = true
    ),
    roundTripTest("ZonedDateTime")(
      genLimitedZonedDateTime,
      dummyInstant.atZone(ZoneOffset.UTC),
      doc("date_time" -> dummyBsonDateTime, "zone_id" -> str(ZoneOffset.UTC.toString)),
      isDocument = true
    ),
    roundTripTest("Char")(genNonZeroChar, 'a', str("a"), isDocument = false),
    roundTripTest("Symbol")(genLimitedString.map(Symbol(_)), Symbol("abc"), str("abc"), isDocument = false),
    roundTripTest("UUID")(
      Gen.uuid,
      UUID.fromString("03190796-cc05-4811-bb79-f880917e7c07"),
      str("03190796-cc05-4811-bb79-f880917e7c07"),
      isDocument = false
    ),
    roundTripTest("Option[String]")(Gen.option(genLimitedString), None, bNull, isDocument = false),
    roundTripTest("Int")(Gen.int, 0, int(0), isDocument = false),
    roundTripTest("Byte")(Gen.byte, 0: Byte, int(0), isDocument = false),
    roundTripTest("Short")(Gen.short, 0: Short, int(0), isDocument = false),
    roundTripTest("Long")(Gen.long, 0L, new BsonInt64(0), isDocument = false),
    roundTripTest("Float")(Gen.float, 0f, new BsonDouble(0), isDocument = false),
    roundTripTest("Double")(Gen.double, 0d, new BsonDouble(0), isDocument = false),
    roundTripTest("JavaBigDecimal")(
      genJavaBigDecimal128,
      BigDecimal(0).underlying(),
      new BsonDecimal128(Decimal128.POSITIVE_ZERO),
      isDocument = false
    ),
    roundTripTest("BigDecimal")(
      genBigDecimal128,
      BigDecimal(0),
      new BsonDecimal128(Decimal128.POSITIVE_ZERO),
      isDocument = false
    ),
    roundTripTest("BigInt")(
      Gen.bigInt(Long.MinValue, Long.MaxValue),
      BigInt(0),
      str("0"),
      isDocument = false
    ),
    roundTripTest("BigInteger")(
      Gen.bigInt(Long.MinValue, Long.MaxValue).map(_.underlying()),
      BigInt(0).underlying(),
      str("0"),
      isDocument = false
    ),
    roundTripTest[Array[Byte]]("Array[Byte]")(
      Gen.vectorOf(Gen.byte).map(_.toArray),
      Array[Byte](1, 2, 3),
      new BsonBinary(Array[Byte](1, 2, 3)),
      isDocument = false
    ),
    roundTripTest[Vector[Byte]]("Vector[Byte]")(
      Gen.vectorOf(Gen.byte),
      Vector[Byte](1, 2, 3),
      new BsonBinary(Array[Byte](1, 2, 3)),
      isDocument = false
    ),
    roundTripTest[Chunk[Byte]]("Chunk[Byte]")(
      Gen.chunkOf(Gen.byte),
      Chunk[Byte](1, 2, 3),
      new BsonBinary(Array[Byte](1, 2, 3)),
      isDocument = false
    ),
    roundTripTest[NonEmptyChunk[Byte]]("NonEmptyChunk[Byte]")(
      Gen.chunkOf1(Gen.byte),
      NonEmptyChunk[Byte](1, 2, 3),
      new BsonBinary(Array[Byte](1, 2, 3)),
      isDocument = false
    ),
    roundTripTest[Array[String]]("Array[String]")(
      Gen.vectorOf(genLimitedString).map(_.toArray),
      Array[String]("a", "b", "cd"),
      new BsonArray(Vector("a", "b", "cd").map(str).asJava),
      isDocument = false
    ),
    roundTripTest[Vector[String]]("Vector[String]")(
      Gen.vectorOf(genLimitedString),
      Vector[String]("a", "b", "cd"),
      new BsonArray(Vector("a", "b", "cd").map(str).asJava),
      isDocument = false
    ),
    roundTripTest[Chunk[String]]("Chunk[String]")(
      Gen.chunkOf(genLimitedString),
      Chunk[String]("a", "b", "cd"),
      new BsonArray(Vector("a", "b", "cd").map(str).asJava),
      isDocument = false
    ),
    roundTripTest[NonEmptyChunk[String]]("NonEmptyChunk[String]")(
      Gen.chunkOf1(genLimitedString),
      NonEmptyChunk[String]("a", "b", "cd"),
      new BsonArray(Vector("a", "b", "cd").map(str).asJava),
      isDocument = false
    ),
    roundTripTest[Map[String, Int]]("Map[String, Int]")(
      Gen.mapOf(genLimitedString, Gen.int),
      Map("a" -> 1),
      doc("a" -> int(1)),
      isDocument = true
    ),
    // TODO: fix compilation on 2.12
//    roundTripTest[HashMap[Int, String]]("HashMap[Int, String]")(
//      Gen.mapOf(Gen.int, genLimitedString).map(_.to(HashMap)),
//      HashMap(1 -> "a"),
//      doc("1"   -> str("a")),
//      isDocument = true
//    ),
    // TODO: tests for all BsonValue subtypes
    roundTripTest[BsonString]("BsonString")(
      genLimitedString.map(str),
      str("abc"),
      str("abc"),
      isDocument = false
    ),
    roundTripTest[BsonDocument]("BsonDocument")(
      // TODO: proper document generator
      genLimitedString.map(s => doc("f" -> str(s))),
      doc(),
      doc(),
      isDocument = true
    ),
    // TODO: proper failures tests in a separate object
    suite("failures")(
      test("BsonString") {
        val res = int(1).as[BsonString]
        assertTrue(res.swap.toOption.get.contains("Expected BsonValue type org.bson.BsonString, but got INT32."))
      },
      test("BsonDocument") {
        val res = int(1).as[BsonDocument]
        assertTrue(res.swap.toOption.get.contains("Expected BsonValue type org.bson.BsonDocument, but got INT32."))
      }
    )
  ) @@ timed
}
