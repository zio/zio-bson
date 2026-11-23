package zio.bson

/**
 * One step on the path from the root of a BSON value to a nested value: a document field ([[BsonTrace.Field]]) or an
 * array element ([[BsonTrace.Array]]). A decoding error carries a `List[BsonTrace]` with the innermost step first.
 *
 * @see [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
sealed trait BsonTrace

/** The steps of a path, and [[BsonTrace.render]] to write a path as text. */
object BsonTrace {

  /**
   * Renders a trace as a path, for example `.address[0].street`, writing document fields as `.name` and array elements
   * as `[index]`. The list is given innermost step first, and the path starts at the root.
   *
   * {{{
   * val path: String = BsonTrace.render(List(BsonTrace.Field("street"), BsonTrace.Array(0), BsonTrace.Field("address")))
   * // .address[0].street
   * }}}
   */
  def render(trace: List[BsonTrace]): String =
    trace.reverse.map {
      case Field(name) => s".$name"
      case Array(idx)  => s"[$idx]"
    }.mkString

  /** A step into the document field `name`. */
  case class Field(name: String) extends BsonTrace
  /** A step into the array element at index `idx`, counted from zero. */
  case class Array(idx: Int)     extends BsonTrace
}
