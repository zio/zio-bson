package zio.bson.magnolia

import magnolia1.{CaseClass, SealedTrait}
import org.bson._
import zio.bson.BsonEncoder.EncoderContext
import zio.bson.magnolia.BsonCodecConfiguration.SumTypeHandling
import zio.bson.{BsonEncoder, _}

import scala.jdk.CollectionConverters._

// scalafix:off
import scala.language.experimental.macros
import org.bson.conversions.Bson
import magnolia1.AutoDerivation
import scala.deriving.Mirror
// scalafix:on

/**
 * Derives a [[zio.bson.BsonEncoder]] for a case class or a sealed trait.
 *
 * {{{
 * import zio.bson.magnolia._
 *
 * final case class Point(x: Int, y: Int)
 *
 * object Point {
 *   implicit val encoder: BsonEncoder[Point] = DeriveBsonEncoder.derived[Point]
 * }
 *
 * val bson = BsonEncoder[Point].toBsonValue(Point(1, 2))
 * // {"x": 1, "y": 2}
 * }}}
 *
 * @see [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
object DeriveBsonEncoder {
  /** The type class that the derivation creates instances of. */
  type Typeclass[T] = BsonEncoder[T]

  /** The encoders for the two kinds of type that the derivation handles. */
  object Codec {
    /**
     * Returns the encoder of a case class. It writes a document with one field per constructor parameter, in the order of
     * the parameters.
     *
     * A parameter marked `bsonExclude` is left out. The field name is the one in a `bsonField` annotation, or else the
     * parameter name changed by the `fieldNameMapping` of the configuration. A field whose encoder reports the value as
     * absent, such as an `Option` that is `None`, is left out when `skipNullsInCaseClass` is enabled and is written as
     * BSON null otherwise. Each parameter needs an encoder in scope.
     */
    def join[T](caseClass: CaseClass[BsonEncoder, T], configuration: BsonCodecConfiguration): BsonEncoder[T] =
      new BsonEncoder[T] {
        private val keepNulls = !configuration.skipNullsInCaseClass

        private val params =
          caseClass.parameters.filter(_.annotations.collectFirst { case _: bsonExclude => }.isEmpty).toArray

        private val names: Array[String] =
          params.map { p =>
            p.annotations.collectFirst { case bsonField(name) => name }
              .getOrElse(configuration.fieldNameMapping(p.label))
          }

        private lazy val tcs: Array[BsonEncoder[Any]] = params.map(_.typeclass.asInstanceOf[BsonEncoder[Any]])

        private val len = params.length

        def encode(writer: BsonWriter, value: T, ctx: EncoderContext): Unit = {
          val nextCtx = ctx.copy(inlineNextObject = false)

          if (!ctx.inlineNextObject) writer.writeStartDocument()

          var i = 0

          while (i < len) {
            val tc         = tcs(i)
            val fieldValue = params(i).deref(value)

            if (keepNulls || !tc.isAbsent(fieldValue)) {
              writer.writeName(names(i))
              tc.encode(writer, fieldValue, nextCtx)
            }

            i += 1
          }

          if (!ctx.inlineNextObject) writer.writeEndDocument()
        }

        def toBsonValue(value: T): BsonValue = {
          val elements = params.indices.view.flatMap { idx =>
            val fieldValue = params(idx).deref(value)
            val tc         = tcs(idx)

            if (keepNulls || !tc.isAbsent(fieldValue)) Some(new BsonElement(names(idx), tc.toBsonValue(fieldValue)))
            else None
          }.toVector

          new BsonDocument(elements.asJava)
        }
      }

    /**
     * Returns the encoder of a sealed trait. It encodes a value as its subtype, and the configuration and the
     * annotations decide the layout.
     *
     * By default the document has one field named after the subtype that holds the subtype's document. When a discriminator
     * is selected, by a `bsonDiscriminator` annotation on the sealed trait or by `DiscriminatorField` in the configuration,
     * the subtype's fields are written together with a field that holds the subtype name. The subtype name is the one in a
     * `bsonHint` annotation, or else the class name changed by `classNameMapping`.
     *
     * With a discriminator, `toBsonValue` throws a `RuntimeException` with the message `Subtype is not encoded as an object`
     * when a subtype is not encoded as a document.
     */
    def split[T](
      sealedTrait: SealedTrait[BsonEncoder, T],
      configuration: BsonCodecConfiguration
    ): BsonEncoder[T] = {
      val configuredDiscriminator = configuration.sumTypeHandling match {
        case SumTypeHandling.WrapperWithClassNameField => None
        case SumTypeHandling.DiscriminatorField(name)  => Some(name)
      }

      val discriminator =
        sealedTrait.annotations.collectFirst { case bsonDiscriminator(name) => name }.orElse(configuredDiscriminator)

      def getSubName(sub: magnolia1.SealedTrait.SubtypeValue[BsonEncoder, ?, ?]) =
        sub.annotations.collectFirst { case bsonHint(name) => name }
          .getOrElse(configuration.classNameMapping(sub.typeInfo.short))

      discriminator match {
        case None                =>
          new BsonEncoder[T] {
            def encode(writer: BsonWriter, value: T, ctx: EncoderContext): Unit = {
              val nextCtx = ctx.copy(inlineNextObject = false)

              writer.writeStartDocument()

              sealedTrait.choose(value) { sub =>
                val name = getSubName(sub)
                writer.writeName(name)

                sub.typeclass.encode(writer, sub.cast(value), nextCtx)
              }

              writer.writeEndDocument()
            }

            def toBsonValue(value: T): BsonValue =
              sealedTrait.choose(value) { sub =>
                val name = getSubName(sub)
                new BsonDocument(name, sub.typeclass.toBsonValue(sub.cast(value)))
              }
          }
        case Some(discriminator) =>
          new BsonEncoder[T] {
            def encode(writer: BsonWriter, value: T, ctx: EncoderContext): Unit = {
              val nextCtx = ctx.copy(inlineNextObject = true)

              writer.writeStartDocument()

              sealedTrait.choose(value) { sub =>
                val name = getSubName(sub)
                writer.writeName(discriminator)
                writer.writeString(name)
                sub.typeclass.encode(writer, sub.cast(value), nextCtx)
              }

              writer.writeEndDocument()
            }

            def toBsonValue(value: T): BsonValue =
              sealedTrait.choose(value) { sub =>
                val name = getSubName(sub)

                val subBson = sub.typeclass.toBsonValue(sub.cast(value))
                if (!subBson.isDocument) throw new RuntimeException("Subtype is not encoded as an object")
                else {
                  val doc = subBson.asDocument()
                  doc.put(discriminator, new BsonString(name))
                  doc
                }
              }
          }
      }
    }
  }

  /** The Magnolia derivation that uses the configuration `settings`. */
  class Derive(settings: BsonCodecConfiguration) extends AutoDerivation[BsonEncoder] {
    /**
     * Returns the encoder of a case class. It writes a document with one field per constructor parameter, in the order of
     * the parameters.
     *
     * A parameter marked `bsonExclude` is left out. The field name is the one in a `bsonField` annotation, or else the
     * parameter name changed by the `fieldNameMapping` of the configuration. A field whose encoder reports the value as
     * absent, such as an `Option` that is `None`, is left out when `skipNullsInCaseClass` is enabled and is written as
     * BSON null otherwise. Each parameter needs an encoder in scope.
     */
    def join[T](caseClass: CaseClass[BsonEncoder, T]): BsonEncoder[T] =
      Codec.join(caseClass, settings)

    /**
     * Returns the encoder of a sealed trait. It encodes a value as its subtype, and the configuration and the
     * annotations decide the layout.
     *
     * By default the document has one field named after the subtype that holds the subtype's document. When a discriminator
     * is selected, by a `bsonDiscriminator` annotation on the sealed trait or by `DiscriminatorField` in the configuration,
     * the subtype's fields are written together with a field that holds the subtype name. The subtype name is the one in a
     * `bsonHint` annotation, or else the class name changed by `classNameMapping`.
     *
     * With a discriminator, `toBsonValue` throws a `RuntimeException` with the message `Subtype is not encoded as an object`
     * when a subtype is not encoded as a document.
     */
    def split[T](sealedTrait: SealedTrait[BsonEncoder, T]): BsonEncoder[T] =
      Codec.split(sealedTrait, settings)
  }

  /**
   * Returns a [[zio.bson.BsonEncoder]] for `T`, found at compile time. The result follows the given
   * [[BsonCodecConfiguration]]. The fields of a case class and the subtypes of a sealed trait need encoders in scope.
   */
  inline def derived[T: Mirror.Of](using settings: BsonCodecConfiguration): BsonEncoder[T] =
    new Derive(settings).derived[T]
}
