package zio.bson.magnolia

import zio.bson.BsonCodec

// scalafix:off
import scala.language.experimental.macros
// scalafix:on

/**
 * Derives a [[zio.bson.BsonCodec]] for a case class or a sealed trait, made of the encoder from [[DeriveBsonEncoder]]
 * and the decoder from [[DeriveBsonDecoder]].
 *
 * {{{
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
 * @see [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
object DeriveBsonCodec {
  /**
   * Returns a [[zio.bson.BsonCodec]] for `T`, found at compile time. The result follows the implicit
   * [[BsonCodecConfiguration]] in scope.
   */
  def derive[T]: BsonCodec[T] = macro genBoth[T]

  import scala.reflect.macros.whitebox

  /** The macro implementation of `derive`. */
  def genBoth[T: c.WeakTypeTag](c: whitebox.Context): c.Tree = {
    import c.universe._

    val encoder = q"_root_.zio.bson.magnolia.DeriveBsonEncoder.derive[${c.weakTypeOf[T]}]"
    val decoder = q"_root_.zio.bson.magnolia.DeriveBsonDecoder.derive[${c.weakTypeOf[T]}]"

    q"_root_.zio.bson.BsonCodec($encoder, $decoder)"
  }
}
