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

object DeriveBsonEncoder {
  type Typeclass[T] = BsonEncoder[T]

  object Codec {
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

  class Derive(settings: BsonCodecConfiguration) extends AutoDerivation[BsonEncoder] {
    def join[T](caseClass: CaseClass[BsonEncoder, T]): BsonEncoder[T] =
      Codec.join(caseClass, settings)

    def split[T](sealedTrait: SealedTrait[BsonEncoder, T]): BsonEncoder[T] =
      Codec.split(sealedTrait, settings)
  }

  inline def derived[T: Mirror.Of](using settings: BsonCodecConfiguration): BsonEncoder[T] =
    new Derive(settings).derived[T]
}
