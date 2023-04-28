package zio.bson

trait BsonFieldEncoder[-A] {
  self =>

  final def contramap[B](f: B => A): BsonFieldEncoder[B] = new BsonFieldEncoder[B] {
    override def unsafeEncodeField(in: B): String = self.unsafeEncodeField(f(in))
  }

  def unsafeEncodeField(in: A): String
}

object BsonFieldEncoder {
  def apply[A](implicit a: BsonFieldEncoder[A]): BsonFieldEncoder[A] = a

  implicit val string: BsonFieldEncoder[String] = new BsonFieldEncoder[String] {
    def unsafeEncodeField(in: String): String = in
  }

  implicit val int: BsonFieldEncoder[Int] =
    string.contramap(_.toString)

  implicit val long: BsonFieldEncoder[Long] =
    string.contramap(_.toString)
}
