package zio.bson.magnolia

import zio.bson.BsonCodec
import scala.deriving.Mirror

object DeriveBsonCodec {
  inline def derive[T: Mirror.Of](using BsonCodecConfiguration): BsonCodec[T] =
    BsonCodec(
      DeriveBsonEncoder.derived[T],
      DeriveBsonDecoder.derived[T]
    )
}
