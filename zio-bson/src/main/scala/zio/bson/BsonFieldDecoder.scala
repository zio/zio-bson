package zio.bson

/**
 * Turns a document field name into a map key of type `A`. The map decoder of [[BsonDecoder]] uses it for the keys.
 *
 * Instances exist for `String`, `Int` and `Long`. Use [[BsonFieldDecoder#map]] or [[BsonFieldDecoder#mapOrFail]] to
 * support another key type.
 *
 * {{{
 * final case class UserId(value: Long)
 *
 * implicit val userIdField: BsonFieldDecoder[UserId] = BsonFieldDecoder[Long].map(UserId(_))
 *
 * val id: UserId = userIdField.unsafeDecodeField(Nil, "42")
 * // UserId(42)
 * }}}
 */
trait BsonFieldDecoder[+A] {
  self =>

  /** Returns a field decoder for `B` that decodes with this decoder and applies `f` to the result. */
  final def map[B](f: A => B): BsonFieldDecoder[B] =
    new BsonFieldDecoder[B] {

      def unsafeDecodeField(trace: List[BsonTrace], in: String): B =
        f(self.unsafeDecodeField(trace, in))
    }

  /**
   * Returns a field decoder for `B` that decodes with this decoder and applies `f` to the result. When `f` returns a `Left`, the decoding throws a [[BsonDecoder.Error]] with that message at `trace`.
   */
  final def mapOrFail[B](f: A => Either[String, B]): BsonFieldDecoder[B] =
    new BsonFieldDecoder[B] {

      def unsafeDecodeField(trace: List[BsonTrace], in: String): B =
        f(self.unsafeDecodeField(trace, in)) match {
          case Left(err) => throw BsonDecoder.Error(trace, err)
          case Right(b)  => b
        }
    }

  /**
   * Returns the key for the field name `in`, or throws a [[BsonDecoder.Error]] at `trace` when the name is not valid
   * for `A`.
   */
  def unsafeDecodeField(trace: List[BsonTrace], in: String): A
}

/** The implicit field decoders for `String`, `Int` and `Long`, and the `apply` summoner. */
object BsonFieldDecoder {
  /** Returns the implicit `BsonFieldDecoder[A]` in scope. */
  def apply[A](implicit a: BsonFieldDecoder[A]): BsonFieldDecoder[A] = a

  /** Uses the field name as a `String` key. */
  implicit val string: BsonFieldDecoder[String] = new BsonFieldDecoder[String] {
    /** Returns the field name unchanged. */
    def unsafeDecodeField(trace: List[BsonTrace], in: String): String = in
  }

  /**
   * Parses the field name as an `Int`. A name that is not an `Int` fails with a message such as `Invalid Int: 'x': java.lang.NumberFormatException: For input string: "x"`.
   */
  implicit val int: BsonFieldDecoder[Int] =
    string.mapOrFail { str =>
      try Right(str.toInt)
      catch {
        case n: NumberFormatException => Left(s"Invalid Int: '$str': $n")
      }
    }

  /**
   * Parses the field name as a `Long`. A name that is not a `Long` fails with a message such as `Invalid Long: 'x': java.lang.NumberFormatException: For input string: "x"`.
   */
  implicit val long: BsonFieldDecoder[Long] =
    string.mapOrFail { str =>
      try Right(str.toLong)
      catch {
        case n: NumberFormatException => Left(s"Invalid Long: '$str': $n")
      }
    }
}
