package zio

import org.bson.codecs.configuration.{CodecProvider, CodecRegistry}
import org.bson.codecs.{Codec, DecoderContext, EncoderContext}
import org.bson.{BsonReader, BsonValue, BsonWriter}

import scala.reflect.{ClassTag, classTag}

package object bson {
  /** Adds [[BsonEncoderOps#toBsonValue]] to every value. */
  implicit class BsonEncoderOps[A](private val a: A) extends AnyVal {
    /**
     * Returns this value as an `org.bson.BsonValue`, using the implicit [[BsonEncoder]] for `A`.
     *
     * {{{
     * import zio.bson._
     * import org.bson.BsonValue
     *
     * val bson: BsonValue = List(1, 2, 3).toBsonValue
     * // BsonArray{values=[BsonInt32{value=1}, BsonInt32{value=2}, BsonInt32{value=3}]}
     * }}}
     *
     * @see [[https://zio.dev/zio-bson/ ZIO BSON]]
     */
    def toBsonValue(implicit encoder: BsonEncoder[A]): BsonValue = encoder.toBsonValue(a)
  }

  /** Adds [[BsonDecoderOps#as]] to every `org.bson.BsonValue`. */
  implicit class BsonDecoderOps(private val value: BsonValue) extends AnyVal {

    /**
     * Decodes this value as an `A`, using the implicit [[BsonDecoder]] for `A`.
     *
     * A failure is a `Left` with the message of the [[BsonDecoder.Error]] rendered by [[BsonDecoder.Error#render]], which
     * starts with the path to the offending value.
     *
     * {{{
     * import zio.bson._
     * import org.bson.{BsonInt32, BsonString, BsonValue}
     *
     * val ok: Either[String, Int] = (new BsonInt32(7): BsonValue).as[Int]
     * // Right(7)
     *
     * val failed: Either[String, Int] = (new BsonString("x"): BsonValue).as[Int]
     * // Left(: NumberFormatException(For input string: "x"))
     * }}}
     *
     * @see [[https://zio.dev/zio-bson/ ZIO BSON]]
     */
    def as[A](implicit decoder: BsonDecoder[A]): Either[String, A] =
      decoder.fromBsonValue(value).swap.map(_.render).swap
  }

  /**
   * Returns a MongoDB driver `CodecProvider` for `A`, built from the implicit [[BsonEncoder]] and [[BsonDecoder]] for `A`.
   *
   * The provider returns a codec when the requested class is `A` or a subclass of `A`, and `null` for any other class, so
   * that the driver moves on to the next provider. The codec throws the [[BsonDecoder.Error]] when decoding fails.
   *
   * {{{
   * // no-check: needs zio-bson-magnolia, which is not published for Scala 2.12
   * import zio.bson._
   * import org.bson.codecs.configuration.{CodecProvider, CodecRegistries, CodecRegistry}
   * import zio.bson.magnolia._
   *
   * final case class User(name: String)
   *
   * object User {
   *   implicit val codec: BsonCodec[User] = DeriveBsonCodec.derive[User]
   * }
   *
   * val provider: CodecProvider = zioBsonCodecProvider[User]
   *
   * val registry: CodecRegistry = CodecRegistries.fromProviders(provider)
   * }}}
   *
   * @see [[https://zio.dev/zio-bson/ ZIO BSON]]
   */
  def zioBsonCodecProvider[A: BsonEncoder: BsonDecoder: ClassTag]: CodecProvider = new CodecProvider {
    private val clazz: Class[A] = classTag[A].runtimeClass.asInstanceOf[Class[A]]

    override def get[T](targetClazz: Class[T], registry: CodecRegistry): Codec[T] =
      if (targetClazz == clazz || clazz.isAssignableFrom(targetClazz))
        new Codec[T] {

          def decode(reader: BsonReader, decoderContext: DecoderContext): T =
            BsonDecoder[A].decode(reader).fold(e => throw e, _.asInstanceOf[T])

          def encode(writer: BsonWriter, value: T, encoderContext: EncoderContext): Unit =
            BsonEncoder[A].encode(writer, value.asInstanceOf[A], BsonEncoder.EncoderContext.default)

          def getEncoderClass: Class[T] = targetClazz
        }
      else null
  }
}
