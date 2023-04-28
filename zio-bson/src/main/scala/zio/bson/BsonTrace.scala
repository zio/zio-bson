package zio.bson

sealed trait BsonTrace

object BsonTrace {

  def render(trace: List[BsonTrace]): String =
    trace.reverse.map {
      case Field(name) => s".$name"
      case Array(idx)  => s"[$idx]"
    }.mkString

  case class Field(name: String) extends BsonTrace
  case class Array(idx: Int)     extends BsonTrace
}
