package zio.bson

import org.bson._

import java.time.Instant
import scala.jdk.CollectionConverters._

/**
 * Short constructors for `org.bson` values, for building a `BsonDocument` by hand in tests or in code that works with
 * the MongoDB driver.
 *
 * {{{
 * import org.bson.BsonDocument
 *
 * val document: BsonDocument = BsonBuilder.doc(
 *   "name"  -> BsonBuilder.str("Ada"),
 *   "age"   -> BsonBuilder.int(36),
 *   "tags"  -> BsonBuilder.array(BsonBuilder.str("math"), BsonBuilder.str("code"))
 * )
 * }}}
 *
 * @see
 *   [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
object BsonBuilder {

  /** Returns a `BsonDocument` with the given fields, in the order given. */
  def doc(kv: (String, BsonValue)*): BsonDocument = new BsonDocument(
    kv.map { case (k, v) => element(k, v) }.toVector.asJava
  )

  /** Returns a `BsonElement`, a field name with its value. */
  def element(k: String, v: BsonValue): BsonElement = new BsonElement(k, v)

  /** Returns a `BsonArray` with the given elements, in the order given. */
  def array(elements: BsonValue*): BsonArray = new BsonArray(elements.toVector.asJava)

  /** Returns a `BsonString`. */
  def str(s: String): BsonString = new BsonString(s)

  /** Returns a `BsonInt32`. */
  def int(i: Int): BsonInt32 = new BsonInt32(i)

  /** Returns a `BsonInt64`. */
  def long(l: Long): BsonInt64 = new BsonInt64(l)

  /** Returns a `BsonBoolean`. */
  def bool(b: Boolean): BsonBoolean = BsonBoolean.valueOf(b)

  /** Returns a `BsonDouble`. */
  def double(d: Double): BsonDouble = new BsonDouble(d)

  /** Returns a BSON date-time that holds `instant` in milliseconds since the epoch, with the `Instant` encoder. */
  def date(instant: Instant): BsonValue = instant.toBsonValue

  /** The BSON null value. */
  val bNull: BsonNull = BsonNull.VALUE
}
