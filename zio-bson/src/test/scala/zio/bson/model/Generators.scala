package zio.bson.model

import org.bson.types.{Decimal128, ObjectId}
import zio.test.{Gen, Sized}

import java.math.{BigDecimal => JBigDecimal, MathContext}
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime, ZoneOffset, ZonedDateTime}

object Generators {
  lazy val genNonZeroChar: Gen[Any, Char] = Gen.oneOf(Gen.char('\u0001', '\uD7FF'), Gen.char('\uE000', '\uFFFD'))

  lazy val genLimitedString: Gen[Sized, String] = Gen.string(genNonZeroChar)

  lazy val genObjectId: Gen[Any, ObjectId] = Gen.vectorOfN(12)(Gen.byte).map(bs => new ObjectId(bs.toArray))

  lazy val genLimitedInstant: Gen[Any, Instant]               = Gen.long.map(Instant.ofEpochMilli)
  lazy val genLimitedLocalDate: Gen[Any, LocalDate]           = genLimitedInstant.map(_.atZone(ZoneOffset.UTC).toLocalDate)
  lazy val genLimitedLocalDateTime: Gen[Any, LocalDateTime]   =
    genLimitedInstant.map(_.atZone(ZoneOffset.UTC).toLocalDateTime)
  lazy val genLimitedLocalTime: Gen[Any, LocalTime]           = Gen.localTime.map(_.truncatedTo(ChronoUnit.MILLIS))
  lazy val genLimitedOffsetDateTime: Gen[Any, OffsetDateTime] = for {
    instant <- genLimitedInstant
    offset  <- Gen.zoneOffset
  } yield OffsetDateTime.ofInstant(instant, offset)
  lazy val genLimitedOffsetTime: Gen[Any, OffsetTime]         = Gen.offsetTime.map(_.truncatedTo(ChronoUnit.MILLIS))

  lazy val genLimitedZonedDateTime: Gen[Any, ZonedDateTime] = for {
    instant <- genLimitedInstant
    offset  <- Gen.zoneId
  } yield ZonedDateTime.ofInstant(instant, offset)

  lazy val genDecimal128: Gen[Any, Decimal128] = for {
    high <- Gen.long
    low  <- Gen.long
  } yield Decimal128.fromIEEE754BIDEncoding(high, low)

  lazy val genJavaBigDecimal128: Gen[Any, JBigDecimal] =
    genDecimal128
      .filter(v => !v.isNaN && !v.isInfinite && !(v.isNegative && (v.getHigh & 3L << 61) == 3L << 61))
      .map(_.bigDecimalValue().round(MathContext.DECIMAL128))

  lazy val genBigDecimal128: Gen[Any, BigDecimal] = genJavaBigDecimal128.map(x => x)

}
