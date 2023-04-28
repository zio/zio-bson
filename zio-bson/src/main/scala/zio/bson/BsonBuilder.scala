package zio.bson

import org.bson._

import java.time.Instant
import scala.jdk.CollectionConverters._

object BsonBuilder {

  def doc(kv: (String, BsonValue)*): BsonDocument = new BsonDocument(
    kv.map { case (k, v) => element(k, v) }.toVector.asJava
  )

  def element(k: String, v: BsonValue): BsonElement = new BsonElement(k, v)

  def array(elements: BsonValue*): BsonArray = new BsonArray(elements.toVector.asJava)

  def str(s: String): BsonString = new BsonString(s)

  def int(i: Int): BsonInt32 = new BsonInt32(i)

  def long(l: Long): BsonInt64 = new BsonInt64(l)

  def bool(b: Boolean): BsonBoolean = BsonBoolean.valueOf(b)

  def double(d: Double): BsonDouble = new BsonDouble(d)

  def date(instant: Instant): BsonValue = instant.toBsonValue

  val bNull: BsonNull = BsonNull.VALUE
}
