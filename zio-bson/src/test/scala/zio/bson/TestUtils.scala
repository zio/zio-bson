package zio.bson

import org.bson._
import org.bson.codecs.configuration.CodecRegistry
import org.bson.codecs.{Codec, DecoderContext, EncoderContext}
import org.bson.conversions.Bson
import org.bson.io.BasicOutputBuffer
import zio.test._
import zio.test.diff.Diff
import zio.{UIO, ZIO}

import scala.reflect.{ClassTag, classTag}

object TestUtils {
  val emptyCodecRegistry: CodecRegistry = new CodecRegistry {
    def get[T](clazz: Class[T]): Codec[T] = null

    def get[T](clazz: Class[T], registry: CodecRegistry): Codec[T] = null
  }

  def writeValue[T](value: T, codec: Codec[T], writer: BsonWriter, isDocument: Boolean): UIO[Unit] =
    ZIO.succeed {
      if (isDocument) codec.encode(writer, value, EncoderContext.builder().build())
      else {
        writer.writeStartDocument()
        writer.writeName("v")
        codec.encode(writer, value, EncoderContext.builder().build())
        writer.writeEndDocument()
      }
    }

  def readValue[T](codec: Codec[T], reader: BsonReader, isDocument: Boolean): UIO[T] =
    ZIO.succeed {
      if (isDocument) codec.decode(reader, DecoderContext.builder().build())
      else {
        reader.readStartDocument()
        reader.readBsonType()
        reader.skipName()
        val res = codec.decode(reader, DecoderContext.builder().build())
        reader.readEndDocument()
        res
      }
    }

  private def roundTripTests[T](
    gen: Gen[Sized, T],
    example: T,
    bsonExample: BsonValue,
    isDocument: Boolean
  )(implicit encoder: BsonEncoder[T], decoder: BsonDecoder[T], ct: ClassTag[T], diff: Diff[T]) = {
    val _ = diff
    test("example") {
      assertTrue(example.toBsonValue == bsonExample)
    } +
      test("toBsonValue/as") {
        check(gen) { t =>
          assertTrue(t.toBsonValue.as[T].toOption.get == t)
        }
      } +
      test("writer/reader") {
        check(gen) { t =>
          for {
            buffer <- ZIO.fromAutoCloseable(ZIO.succeed(new BasicOutputBuffer()))
            writer <- ZIO.fromAutoCloseable(ZIO.succeed(new BsonBinaryWriter(buffer)))
            codec  <-
              ZIO
                .succeed(
                  zioBsonCodecProvider[T].get[T](classTag[T].runtimeClass.asInstanceOf[Class[T]], emptyCodecRegistry)
                )
            _      <- writeValue(t, codec, writer, isDocument)
            reader <- ZIO.fromAutoCloseable(ZIO.succeed(new BsonBinaryReader(buffer.getByteBuffers.get(0).asNIO())))
            res    <- readValue(codec, reader, isDocument)
          } yield assertTrue(res == t)
        }
      } +
      test("toBsonValue/reader") {
        check(gen) { t =>
          for {
            buffer    <- ZIO.fromAutoCloseable(ZIO.succeed(new BasicOutputBuffer()))
            writer    <- ZIO.fromAutoCloseable(ZIO.succeed(new BsonBinaryWriter(buffer)))
            bsonValue  = t.toBsonValue
            valueCodec = Bson.DEFAULT_CODEC_REGISTRY.get(bsonValue.getClass).asInstanceOf[Codec[BsonValue]]
            codec     <-
              ZIO
                .succeed(
                  zioBsonCodecProvider[T].get[T](classTag[T].runtimeClass.asInstanceOf[Class[T]], emptyCodecRegistry)
                )
            _         <- writeValue(bsonValue, valueCodec, writer, isDocument)
            reader    <- ZIO.fromAutoCloseable(ZIO.succeed(new BsonBinaryReader(buffer.getByteBuffers.get(0).asNIO())))
            res       <- readValue(codec, reader, isDocument)
          } yield assertTrue(res == t)
        }
      } +
      test("writer/as") {
        check(gen) { t =>
          for {
            buffer       <- ZIO.fromAutoCloseable(ZIO.succeed(new BasicOutputBuffer()))
            writer       <- ZIO.fromAutoCloseable(ZIO.succeed(new BsonBinaryWriter(buffer)))
            documentCodec = Bson.DEFAULT_CODEC_REGISTRY.get(classOf[BsonDocument])
            codec        <-
              ZIO
                .succeed(
                  zioBsonCodecProvider[T].get[T](classTag[T].runtimeClass.asInstanceOf[Class[T]], emptyCodecRegistry)
                )
            _            <- writeValue(t, codec, writer, isDocument)
            reader       <- ZIO.fromAutoCloseable(ZIO.succeed(new BsonBinaryReader(buffer.getByteBuffers.get(0).asNIO())))
            document     <- readValue(documentCodec, reader, isDocument = true)
            bsonValue     = if (isDocument) document
                            else document.get("v")
            res           = bsonValue.as[T]
          } yield assertTrue(res.toOption.get == t)
        }
      }
  }

  def roundTripTest[T: ClassTag](name: String)(
    gen: Gen[Sized, T],
    example: T,
    bsonExample: BsonValue,
    isDocument: Boolean
  )(implicit encoder: BsonEncoder[T], decoder: BsonDecoder[T], codec: BsonCodec[T], diff: Diff[T] = Diff.anyDiff[T]) =
    suite(name)(
      suite("encoder/decoder") {
        roundTripTests(gen, example, bsonExample, isDocument)(encoder, decoder, implicitly, implicitly)
      },
      suite("codec") {
        roundTripTests(gen, example, bsonExample, isDocument)(codec.encoder, codec.decoder, implicitly, implicitly)
      }
    )
}
