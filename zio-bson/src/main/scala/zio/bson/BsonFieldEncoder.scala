package zio.bson

/**
 * Turns a map key of type `A` into a document field name. The map encoder of [[BsonEncoder]] uses it for the keys.
 *
 * Instances exist for `String`, `Int` and `Long`. Use [[BsonFieldEncoder#contramap]] to support another key type.
 *
 * {{{
 * final case class UserId(value: Long)
 *
 * implicit val userIdField: BsonFieldEncoder[UserId] = BsonFieldEncoder[Long].contramap(_.value)
 *
 * val name: String = userIdField.unsafeEncodeField(UserId(42L))
 * // 42
 * }}}
 */
trait BsonFieldEncoder[-A] {
  self =>

  /** Returns a field encoder for `B` that applies `f` to each key and encodes the result with this encoder. */
  final def contramap[B](f: B => A): BsonFieldEncoder[B] = new BsonFieldEncoder[B] {
    override def unsafeEncodeField(in: B): String = self.unsafeEncodeField(f(in))
  }

  /** Returns the field name for `in`. */
  def unsafeEncodeField(in: A): String
}

object BsonFieldEncoder {
  /** Returns the implicit `BsonFieldEncoder[A]` in scope. */
  def apply[A](implicit a: BsonFieldEncoder[A]): BsonFieldEncoder[A] = a

  /** Uses a `String` key as the field name. */
  implicit val string: BsonFieldEncoder[String] = new BsonFieldEncoder[String] {
    def unsafeEncodeField(in: String): String = in
  }

  /** Uses the decimal text of an `Int` key as the field name. */
  implicit val int: BsonFieldEncoder[Int] =
    string.contramap(_.toString)

  /** Uses the decimal text of a `Long` key as the field name. */
  implicit val long: BsonFieldEncoder[Long] =
    string.contramap(_.toString)
}
