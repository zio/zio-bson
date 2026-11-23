package zio.bson.magnolia

import magnolia1.{CaseClass, Magnolia, SealedTrait, Subtype}
import org.bson.{BsonReader, BsonType, BsonValue}
import zio.bson.BsonDecoder.BsonDecoderContext
import zio.bson.DecoderUtils.{assumeType, unsafeCall}
import zio.bson.magnolia.BsonCodecConfiguration.SumTypeHandling
import zio.bson.{BsonDecoder, _}

import scala.collection.immutable.{ArraySeq, HashMap}
import scala.jdk.CollectionConverters._

// scalafix:off
import scala.language.experimental.macros
// scalafix:on

/** The Magnolia derivation of a [[zio.bson.BsonDecoder]] for case classes and sealed traits. */
trait DeriveBsonDecoder {

  /** The type class that the derivation creates instances of. */
  type Typeclass[T] = BsonDecoder[T]

  /**
   * Returns the decoder of a case class. It reads a document and builds the class from the fields.
   *
   * The field names are the ones that [[DeriveBsonEncoder]] writes: the name in a `bsonField` annotation, or else the
   * parameter name changed by `fieldNameMapping`. A field that the document lacks gets the parameter's default value,
   * or else the result of `decodeMissingUnsafe` of its decoder, which is `None` for an `Option` and a `missing` error
   * for most types. When the document holds BSON null for a parameter that has a default value, the default value is
   * used. A field that appears twice fails with the message `duplicate`.
   *
   * Fields that the class doesn't have are ignored, unless the class has the `bsonNoExtraFields` annotation or
   * `allowExtraFields` is `false` in the configuration, in which case decoding fails with `Invalid extra field.`. A
   * parameter marked `bsonExclude` is still read when the document holds it.
   */
  def join[T](caseClass: CaseClass[BsonDecoder, T])(implicit configuration: BsonCodecConfiguration): BsonDecoder[T] =
    new BsonDecoder[T] {
      private val names = caseClass.parameters.map { p =>
        p.annotations.collectFirst { case bsonField(name) => name }
          .getOrElse(configuration.fieldNameMapping(p.label))
      }.toArray

      private val len     = names.length
      private val indexes = names.zipWithIndex.to(HashMap)
      private val spans   = names.map(BsonTrace.Field)

      private val noExtra =
        caseClass.annotations.collectFirst { case _: bsonNoExtraFields => }.isDefined || !configuration.allowExtraFields

      private lazy val tcs: Array[BsonDecoder[Any]] =
        caseClass.parameters.map(_.typeclass.asInstanceOf[BsonDecoder[Any]]).toArray
      private lazy val defaults: Array[Option[Any]] = caseClass.parameters.map(_.default).toArray

      def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): T = unsafeCall(trace) {
        reader.readStartDocument()

        val nextCtx        = ctx.copy(ignoreExtraField = None)
        val ps: Array[Any] = Array.ofDim(len)

        while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
          val name = reader.readName()
          val idx  = indexes.getOrElse(name, -1)

          if (idx >= 0) {
            val nextTrace = spans(idx) :: trace
            val tc        = tcs(idx)
            if (ps(idx) != null) throw BsonDecoder.Error(nextTrace, "duplicate")
            ps(idx) = defaults(idx) match {
              case Some(default) =>
                val opt = BsonDecoder.option(tc).decodeUnsafe(reader, nextTrace, nextCtx)
                opt.getOrElse(default)
              case None          =>
                tc.decodeUnsafe(reader, nextTrace, nextCtx)
            }
          } else if (noExtra && !ctx.ignoreExtraField.contains(name)) {
            throw BsonDecoder.Error(BsonTrace.Field(name) :: trace, "Invalid extra field.")
          } else reader.skipValue()
        }

        var i = 0
        while (i < len) {
          if (ps(i) == null) {
            ps(i) = defaults(i) match {
              case Some(default) => default
              case None          => tcs(i).decodeMissingUnsafe(spans(i) :: trace)
            }
          }
          i += 1
        }

        reader.readEndDocument()

        caseClass.rawConstruct(ArraySeq.unsafeWrapArray(ps))
      }

      def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): T =
        DecoderUtils.assumeType(trace)(BsonType.DOCUMENT, value) { value =>
          val nextCtx        = ctx.copy(ignoreExtraField = None)
          val ps: Array[Any] = Array.ofDim(len)

          value.asDocument().asScala.foreachEntry { (name, value) =>
            val idx = indexes.getOrElse(name, -1)

            if (idx >= 0) {
              val nextTrace = spans(idx) :: trace
              val tc        = tcs(idx)
              if (ps(idx) != null) throw BsonDecoder.Error(nextTrace, "duplicate")
              ps(idx) = defaults(idx) match {
                case Some(default) =>
                  val opt = BsonDecoder.option(tc).fromBsonValueUnsafe(value, nextTrace, nextCtx)
                  opt.getOrElse(default)
                case None          =>
                  tc.fromBsonValueUnsafe(value, nextTrace, nextCtx)
              }
            } else if (noExtra && !ctx.ignoreExtraField.contains(name))
              throw BsonDecoder.Error(BsonTrace.Field(name) :: trace, "Invalid extra field.")
          }

          var i = 0
          while (i < len) {
            if (ps(i) == null) {
              ps(i) = defaults(i) match {
                case Some(default) => default
                case None          => tcs(i).decodeMissingUnsafe(spans(i) :: trace)
              }
            }
            i += 1
          }

          caseClass.rawConstruct(ArraySeq.unsafeWrapArray(ps))
        }
    }

  /**
   * Returns the decoder of a sealed trait. It reads the subtype name from the document and decodes the rest as that
   * subtype, with the layout that [[DeriveBsonEncoder]] writes.
   *
   * Without a discriminator, the document must have exactly one field, named after the subtype. With a discriminator,
   * the document must have a string field with the discriminator's name. An unknown subtype name fails with
   * `Invalid disambiguator <name>.`, and a missing discriminator field fails with `Missing disambiguator <name>.`.
   */
  def split[T](
    sealedTrait: SealedTrait[BsonDecoder, T]
  )(implicit configuration: BsonCodecConfiguration): BsonDecoder[T] = {
    val configuredDiscriminator = configuration.sumTypeHandling match {
      case SumTypeHandling.WrapperWithClassNameField => None
      case SumTypeHandling.DiscriminatorField(name)  => Some(name)
    }

    val discriminator =
      sealedTrait.annotations.collectFirst { case bsonDiscriminator(name) => name }.orElse(configuredDiscriminator)

    def getSubName(sub: Subtype[BsonDecoder, ?]) =
      sub.annotations.collectFirst { case bsonHint(name) => name }
        .getOrElse(configuration.classNameMapping(sub.typeName.short))

    val subtypes = sealedTrait.subtypes.map(sub => getSubName(sub) -> sub).toMap

    discriminator match {
      case None                =>
        new BsonDecoder[T] {
          def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): T = unsafeCall(trace) {
            reader.readStartDocument()

            val name      = reader.readName()
            val nextTrace = BsonTrace.Field(name) :: trace
            val nextCtx   = ctx.copy(ignoreExtraField = None)

            val result =
              subtypes.get(name) match {
                case None      => throw BsonDecoder.Error(nextTrace, s"Invalid disambiguator $name.")
                case Some(sub) => sub.typeclass.decodeUnsafe(reader, nextTrace, nextCtx)
              }

            reader.readEndDocument()

            result
          }

          def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): T =
            assumeType(trace)(BsonType.DOCUMENT, value) { value =>
              val fields = value.asDocument().asScala

              if (fields.size != 1) throw BsonDecoder.Error(trace, "Expected exactly 1 disambiguator.")

              val (name, element) = fields.head
              val nextTrace       = BsonTrace.Field(name) :: trace
              val nextCtx         = ctx.copy(ignoreExtraField = None)

              subtypes.get(name) match {
                case None      => throw BsonDecoder.Error(nextTrace, s"Invalid disambiguator $name.")
                case Some(sub) => sub.typeclass.fromBsonValueUnsafe(element, nextTrace, nextCtx)
              }
            }
        }
      case Some(discriminator) =>
        new BsonDecoder[T] {
          def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): T = unsafeCall(trace) {
            val mark = reader.getMark

            var hint: String = null

            reader.readStartDocument()
            while (hint == null && reader.readBsonType() != BsonType.END_OF_DOCUMENT)
              if (reader.readName() == discriminator)
                hint = unsafeCall(BsonTrace.Field(discriminator) :: trace)(reader.readString())
              else reader.skipValue()

            if (hint == null) throw BsonDecoder.Error(trace, s"Missing disambiguator $discriminator.")

            subtypes.get(hint) match {
              case None      => throw BsonDecoder.Error(trace, s"Invalid disambiguator $hint.")
              case Some(sub) =>
                mark.reset()
                val nextCtx = ctx.copy(ignoreExtraField = Some(discriminator))
                sub.typeclass.decodeUnsafe(reader, trace, nextCtx)
            }
          }

          def fromBsonValueUnsafe(value: BsonValue, trace: List[BsonTrace], ctx: BsonDecoderContext): T =
            assumeType(trace)(BsonType.DOCUMENT, value) { value =>
              val fields = value.asDocument().asScala

              fields.get(discriminator) match {
                case None       => throw BsonDecoder.Error(trace, s"Missing disambiguator $discriminator.")
                case Some(hint) =>
                  assumeType(BsonTrace.Field(discriminator) :: trace)(BsonType.STRING, hint) { hint =>
                    subtypes.get(hint.asString().getValue) match {
                      case None      => throw BsonDecoder.Error(trace, s"Invalid disambiguator ${hint.asString().getValue}.")
                      case Some(sub) =>
                        val nextCtx = ctx.copy(ignoreExtraField = Some(discriminator))
                        sub.typeclass.fromBsonValueUnsafe(value, trace, nextCtx)
                    }
                  }
              }
            }
        }
    }
  }

}

/**
 * Derives a [[zio.bson.BsonDecoder]] for a case class or a sealed trait.
 *
 * {{{
 * import zio.bson._
 * import org.bson.{BsonDocument, BsonInt32}
 * import zio.bson.magnolia._
 *
 * final case class Point(x: Int, y: Int)
 *
 * object Point {
 *   implicit val decoder: BsonDecoder[Point] = DeriveBsonDecoder.derive[Point]
 * }
 *
 * val document = new BsonDocument("x", new BsonInt32(1))
 * document.put("y", new BsonInt32(2))
 *
 * val point: Either[BsonDecoder.Error, Point] = BsonDecoder[Point].fromBsonValue(document)
 * // Right(Point(1,2))
 * }}}
 *
 * @see
 *   [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
object DeriveBsonDecoder extends DeriveBsonDecoder {

  /**
   * Returns a [[zio.bson.BsonDecoder]] for `T`, found at compile time. The result follows the implicit
   * [[BsonCodecConfiguration]] in scope. The fields of a case class and the subtypes of a sealed trait need decoders in
   * scope.
   */
  def derive[T]: BsonDecoder[T] = macro Magnolia.gen[T]
}
