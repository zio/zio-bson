package zio.bson

trait BsonFieldDecoder[+A] {
  self =>

  final def map[B](f: A => B): BsonFieldDecoder[B] =
    new BsonFieldDecoder[B] {

      def unsafeDecodeField(trace: List[BsonTrace], in: String): B =
        f(self.unsafeDecodeField(trace, in))
    }

  final def mapOrFail[B](f: A => Either[String, B]): BsonFieldDecoder[B] =
    new BsonFieldDecoder[B] {

      def unsafeDecodeField(trace: List[BsonTrace], in: String): B =
        f(self.unsafeDecodeField(trace, in)) match {
          case Left(err) => throw BsonDecoder.Error(trace, err)
          case Right(b)  => b
        }
    }

  def unsafeDecodeField(trace: List[BsonTrace], in: String): A
}

object BsonFieldDecoder {
  def apply[A](implicit a: BsonFieldDecoder[A]): BsonFieldDecoder[A] = a

  implicit val string: BsonFieldDecoder[String] = new BsonFieldDecoder[String] {
    def unsafeDecodeField(trace: List[BsonTrace], in: String): String = in
  }

  implicit val int: BsonFieldDecoder[Int] =
    string.mapOrFail { str =>
      try Right(str.toInt)
      catch {
        case n: NumberFormatException => Left(s"Invalid Int: '$str': $n")
      }
    }

  implicit val long: BsonFieldDecoder[Long] =
    string.mapOrFail { str =>
      try Right(str.toLong)
      catch {
        case n: NumberFormatException => Left(s"Invalid Long: '$str': $n")
      }
    }
}
