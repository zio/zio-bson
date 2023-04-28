package zio

import org.bson.codecs.configuration.{CodecProvider, CodecRegistry}
import org.bson.codecs.{Codec, DecoderContext, EncoderContext}
import org.bson.{BsonReader, BsonValue, BsonWriter}

import scala.reflect.{ClassTag, classTag}

package object bson {
  implicit class BsonEncoderOps[A](private val a: A) extends AnyVal {
    def toBsonValue(implicit encoder: BsonEncoder[A]): BsonValue = encoder.toBsonValue(a)
  }

  implicit class BsonDecoderOps(private val value: BsonValue) extends AnyVal {

    def as[A](implicit decoder: BsonDecoder[A]): Either[String, A] =
      decoder.fromBsonValue(value).swap.map(_.render).swap
  }

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
