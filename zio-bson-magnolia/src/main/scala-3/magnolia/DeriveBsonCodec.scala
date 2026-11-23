package zio.bson.magnolia

import zio.bson.BsonCodec
import scala.deriving.Mirror

/**
 * Derives a `zio.bson.BsonCodec` for a case class or a sealed trait, made of the encoder from [[DeriveBsonEncoder]] and
 * the decoder from [[DeriveBsonDecoder]].
 *
 * {{{
 * import zio.bson._
 * import org.bson.BsonValue
 * import zio.bson.magnolia._
 *
 * final case class User(name: String, age: Int)
 *
 * object User {
 *   implicit val codec: BsonCodec[User] = DeriveBsonCodec.derive[User]
 * }
 *
 * val bson: BsonValue = User("Ada", 36).toBsonValue
 * // {"name": "Ada", "age": 36}
 *
 * val user: Either[String, User] = bson.as[User]
 * // Right(User(Ada,36))
 * }}}
 *
 * @see
 *   [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
object DeriveBsonCodec {

  /**
   * Returns a `zio.bson.BsonCodec` for `T`, found at compile time. The result follows the implicit
   * [[BsonCodecConfiguration]] in scope.
   */
  inline def derive[T: Mirror.Of](using BsonCodecConfiguration): BsonCodec[T] =
    BsonCodec(
      DeriveBsonEncoder.derived[T],
      DeriveBsonDecoder.derived[T]
    )
}
