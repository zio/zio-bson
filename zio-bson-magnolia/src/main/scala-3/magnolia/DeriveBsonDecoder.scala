package zio.bson.magnolia

import magnolia1.{CaseClass, SealedTrait}
import org.bson.{BsonReader, BsonType, BsonValue}
import zio.bson.BsonDecoder.BsonDecoderContext
import zio.bson.DecoderUtils.{assumeType, unsafeCall}
import zio.bson.magnolia.BsonCodecConfiguration.SumTypeHandling
import zio.bson.{BsonDecoder, _}

import scala.collection.immutable.{ArraySeq, HashMap}
import scala.jdk.CollectionConverters._

// scalafix:off
import scala.language.experimental.macros
import scala.deriving.Mirror
import magnolia1.AutoDerivation
// scalafix:on

object DeriveBsonDecoder {
  type Typeclass[T] = BsonDecoder[T]

  object Codec {
    def join[T](caseClass: CaseClass[BsonDecoder, T], configuration: BsonCodecConfiguration): BsonDecoder[T] =
      new BsonDecoder[T] {
        private val names = IArray
          .genericWrapArray(caseClass.parameters.map { p =>
            p.annotations.collectFirst { case bsonField(name) => name }
              .getOrElse(configuration.fieldNameMapping(p.label))
          })
          .toArray

        private val len     = names.length
        private val indexes = names.zipWithIndex.to(HashMap)
        private val spans   = names.map(BsonTrace.Field.apply)

        private val noExtra =
          caseClass.annotations.collectFirst { case _: bsonNoExtraFields =>
          }.isDefined || !configuration.allowExtraFields

        private lazy val tcs: Array[BsonDecoder[Any]] =
          IArray.genericWrapArray(caseClass.parameters.map(_.typeclass.asInstanceOf[BsonDecoder[Any]])).toArray
        private lazy val defaults: Array[Option[Any]] =
          IArray.genericWrapArray(caseClass.parameters.map(_.default)).toArray

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

    def split[T](
      sealedTrait: SealedTrait[BsonDecoder, T],
      configuration: BsonCodecConfiguration
    ): BsonDecoder[T] = {
      val configuredDiscriminator = configuration.sumTypeHandling match {
        case SumTypeHandling.WrapperWithClassNameField => None
        case SumTypeHandling.DiscriminatorField(name)  => Some(name)
      }

      val discriminator =
        sealedTrait.annotations.collectFirst { case bsonDiscriminator(name) => name }.orElse(configuredDiscriminator)

      def getSubName(sub: SealedTrait.Subtype[BsonDecoder, ?, ?]) =
        sub.annotations.collectFirst { case bsonHint(name) => name }
          .getOrElse(configuration.classNameMapping(sub.typeInfo.short))

      val subtypes = sealedTrait.subtypes.map(sub => getSubName(sub) -> sub).toMap

      discriminator match {
        case None                =>
          new BsonDecoder[T] {
            def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): T =
              unsafeCall(trace) {
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
            def decodeUnsafe(reader: BsonReader, trace: List[BsonTrace], ctx: BsonDecoderContext): T =
              unsafeCall(trace) {
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
                        case None      =>
                          throw BsonDecoder.Error(trace, s"Invalid disambiguator ${hint.asString().getValue}.")
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

  class Derive(settings: BsonCodecConfiguration) extends AutoDerivation[BsonDecoder] {
    def join[T](caseClass: CaseClass[BsonDecoder, T]): BsonDecoder[T] =
      Codec.join(caseClass, settings)

    def split[T](sealedTrait: SealedTrait[BsonDecoder, T]): BsonDecoder[T] =
      Codec.split(sealedTrait, settings)
  }

  inline def derived[T: Mirror.Of](using settings: BsonCodecConfiguration): BsonDecoder[T] =
    new Derive(settings).derived[T]

}
