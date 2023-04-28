package zio.bson.magnolia

import org.bson._
import org.bson.conversions.Bson
import org.bson.io.BasicOutputBuffer
import zio.ZIO
import zio.bson.BsonBuilder._
import zio.bson.TestUtils._
import zio.bson.magnolia.BsonCodecConfiguration.SumTypeHandling.{DiscriminatorField, WrapperWithClassNameField}
import zio.bson.magnolia.BsonCodecConfiguration.{toKebabCase, toScreamingSnakeCase, toSnakeCase, toUpperKebabCase}
import zio.bson.{bsonDiscriminator, bsonExclude, bsonField, bsonHint, _}
import zio.test._
import zio.test.diff.Diff
import zio.test.magnolia.DeriveDiff

object ConfigurationSpec extends ZIOSpecDefault {

  // TODO: bsonNoExtraFields

  implicit lazy val diffStruct: Diff[Struct]                                  = DeriveDiff.gen
  implicit lazy val diffOtherSealedTrait: Diff[OtherSealedTrait]              = DeriveDiff.gen
  implicit lazy val diffOtherSealedTraitA: Diff[OtherSealedTrait.A]           = DeriveDiff.gen
  implicit lazy val diffMySealedTrait: Diff[MySealedTrait]                    = DeriveDiff.gen
  implicit lazy val diffMySealedTraitABcDEFgHi: Diff[MySealedTrait.ABcDEFgHi] = DeriveDiff.gen
  implicit lazy val diffMySealedTraitBranchB: Diff[MySealedTrait.BranchB]     = DeriveDiff.gen

  sealed trait MySealedTrait

  object MySealedTrait {
    case class ABcDEFgHi(
      someFieldXYZ: String,
      @bsonField("constFieldName") fieldAbc: Int,
      optNone: Option[Int],
      optSome: Option[Int]
    )                                                     extends MySealedTrait
    @bsonHint("ContClassName") case class BranchB(a: Int) extends MySealedTrait
  }

  @bsonDiscriminator("constTypeField")
  sealed trait OtherSealedTrait

  object OtherSealedTrait {
    case class A(i: Int, @bsonExclude b: Int = 42) extends OtherSealedTrait
    case class B(s: String)                        extends OtherSealedTrait
  }

  case class Struct(mapField: Map[String, Option[Int]], sealedField: Seq[MySealedTrait], otherField: OtherSealedTrait)

  private lazy val inStruct = Struct(
    mapField = Map(
      "someKey" -> Some(1),
      "noneKey" -> None
    ),
    sealedField = Seq(
      MySealedTrait.ABcDEFgHi(someFieldXYZ = "abc", fieldAbc = 2, optNone = None, optSome = Some(3)),
      MySealedTrait.BranchB(a = 4)
    ),
    otherField = OtherSealedTrait.A(i = 5, b = 6)
  )

  private lazy val expectedStruct = inStruct.copy(otherField = OtherSealedTrait.A(i = 5))

  private def testConfiguration(
    name: String,
    config: BsonCodecConfiguration,
    expected: BsonDocument,
    expectedStruct: Struct
  ) = {
    implicit val conf: BsonCodecConfiguration = config

    val _ = conf

    implicit val codecMySealedTrait: BsonCodec[MySealedTrait]       = DeriveBsonCodec.derive
    implicit val codecOtherSealedTrait: BsonCodec[OtherSealedTrait] = DeriveBsonCodec.derive
    implicit val codec: BsonCodec[Struct]                           = DeriveBsonCodec.derive

    suite(name)(
      test("toBsonValue/as") {
        assertTrue(inStruct.toBsonValue.as[Struct].toOption.get == expectedStruct)
      },
      test("write/read") {
        for {
          buffer <- ZIO.fromAutoCloseable(ZIO.succeed(new BasicOutputBuffer()))
          writer <- ZIO.fromAutoCloseable(ZIO.succeed(new BsonBinaryWriter(buffer)))
          codec  <- ZIO.succeed(zioBsonCodecProvider[Struct].get[Struct](classOf[Struct], emptyCodecRegistry))
          _      <- writeValue(inStruct, codec, writer, isDocument = true)
          reader <- ZIO.fromAutoCloseable(ZIO.succeed(new BsonBinaryReader(buffer.getByteBuffers.get(0).asNIO())))
          res    <- readValue(codec, reader, isDocument = true)
        } yield assertTrue(res == expectedStruct)
      },
      test("toBsonValue/expected") {
        val bsonValue = inStruct.toBsonValue
        assertTrue(bsonValue == expected)
      },
      test("write/expected") {
        implicit val codec: BsonCodec[Struct] = DeriveBsonCodec.derive
        for {
          buffer       <- ZIO.fromAutoCloseable(ZIO.succeed(new BasicOutputBuffer()))
          writer       <- ZIO.fromAutoCloseable(ZIO.succeed(new BsonBinaryWriter(buffer)))
          codec        <- ZIO.succeed(zioBsonCodecProvider[Struct].get[Struct](classOf[Struct], emptyCodecRegistry))
          documentCodec = Bson.DEFAULT_CODEC_REGISTRY.get(classOf[BsonDocument])
          _            <- writeValue(inStruct, codec, writer, isDocument = true)
          reader       <- ZIO.fromAutoCloseable(ZIO.succeed(new BsonBinaryReader(buffer.getByteBuffers.get(0).asNIO())))
          document     <- readValue(documentCodec, reader, isDocument = true)
        } yield assertTrue(document == expected)
      }
    )
  }

  def spec = suite("ConfigurationSpec")(
    testConfiguration(
      "Wrap sum, skipNullsInCaseClass, identity class names, identity field names",
      BsonCodecConfiguration(
        sumTypeHandling = WrapperWithClassNameField,
        skipNullsInCaseClass = true,
        classNameMapping = identity,
        fieldNameMapping = identity
      ),
      doc(
        "mapField"    -> doc("someKey" -> int(1), "noneKey" -> bNull),
        "sealedField" -> array(
          doc("ABcDEFgHi"     -> doc("someFieldXYZ" -> str("abc"), "constFieldName" -> int(2), "optSome" -> int(3))),
          doc("ContClassName" -> doc("a" -> int(4)))
        ),
        "otherField"  -> doc("constTypeField" -> str("A"), "i" -> int(5))
      ),
      expectedStruct
    ),
    testConfiguration(
      "Discriminator sum, !skipNullsInCaseClass, kebab class names, screaming snake field names",
      BsonCodecConfiguration(
        sumTypeHandling = DiscriminatorField("$type"),
        skipNullsInCaseClass = false,
        classNameMapping = toKebabCase,
        fieldNameMapping = toScreamingSnakeCase
      ),
      doc(
        "MAP_FIELD"    -> doc("someKey" -> int(1), "noneKey" -> bNull),
        "SEALED_FIELD" -> array(
          doc(
            "$type"          -> str("a-bc-de-fg-hi"),
            "SOME_FIELD_XYZ" -> str("abc"),
            "constFieldName" -> int(2),
            "OPT_NONE"       -> bNull,
            "OPT_SOME"       -> int(3)
          ),
          doc(
            "$type"          -> str("ContClassName"),
            "A"              -> int(4)
          )
        ),
        "OTHER_FIELD"  -> doc("constTypeField" -> str("a"), "I" -> int(5))
      ),
      expectedStruct
    ),
    testConfiguration(
      "Discriminator sum, skipNullsInCaseClass, snake class names, upper kebab  field names",
      BsonCodecConfiguration(
        sumTypeHandling = DiscriminatorField("$type"),
        skipNullsInCaseClass = true,
        classNameMapping = toSnakeCase,
        fieldNameMapping = toUpperKebabCase
      ),
      doc(
        "MAP-FIELD"    -> doc("someKey" -> int(1), "noneKey" -> bNull),
        "SEALED-FIELD" -> array(
          doc(
            "$type"          -> str("a_bc_de_fg_hi"),
            "SOME-FIELD-XYZ" -> str("abc"),
            "constFieldName" -> int(2),
            "OPT-SOME"       -> int(3)
          ),
          doc(
            "$type"          -> str("ContClassName"),
            "A"              -> int(4)
          )
        ),
        "OTHER-FIELD"  -> doc("constTypeField" -> str("a"), "I" -> int(5))
      ),
      expectedStruct
    )
  )
}
