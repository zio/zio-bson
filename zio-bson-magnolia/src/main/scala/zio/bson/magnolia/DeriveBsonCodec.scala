package zio.bson.magnolia

import zio.bson.BsonCodec

// scalafix:off
import scala.language.experimental.macros
// scalafix:on

object DeriveBsonCodec {
  def derive[T]: BsonCodec[T] = macro genBoth[T]

  import scala.reflect.macros.whitebox

  def genBoth[T: c.WeakTypeTag](c: whitebox.Context): c.Tree = {
    import c.universe._

    val encoder = q"_root_.zio.bson.magnolia.DeriveBsonEncoder.derive[${c.weakTypeOf[T]}]"
    val decoder = q"_root_.zio.bson.magnolia.DeriveBsonDecoder.derive[${c.weakTypeOf[T]}]"

    q"_root_.zio.bson.BsonCodec($encoder, $decoder)"
  }
}
