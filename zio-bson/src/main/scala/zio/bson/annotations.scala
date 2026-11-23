package zio.bson

import scala.annotation.Annotation

/**
 * Gives a case class field the name `name` in the BSON document, in place of the name that the configuration's
 * `fieldNameMapping` produces. The derived encoder writes the field under that name and the derived decoder reads it.
 *
 * {{{
 * // no-check: needs zio-bson-magnolia, which is not published for Scala 2.12
 * import zio.bson.magnolia._
 *
 * final case class User(@bsonField("user_name") name: String)
 *
 * object User {
 *   implicit val codec: BsonCodec[User] = DeriveBsonCodec.derive[User]
 * }
 *
 * val bson = BsonEncoder[User].toBsonValue(User("Ada"))
 * // {"user_name": "Ada"}
 * }}}
 *
 * @see
 *   [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
final case class bsonField(name: String) extends Annotation

/**
 * Marks a sealed trait to be encoded with a discriminator field named `name` that holds the subtype's name, in place of
 * the wrapper document that is the default. It takes priority over the `sumTypeHandling` of the configuration.
 *
 * {{{
 * // no-check: needs zio-bson-magnolia, which is not published for Scala 2.12
 * import zio.bson.magnolia._
 *
 * @bsonDiscriminator("type")
 * sealed trait Shape
 * final case class Circle(radius: Double) extends Shape
 * final case class Square(side: Double)   extends Shape
 *
 * object Shape {
 *   implicit val codec: BsonCodec[Shape] = DeriveBsonCodec.derive[Shape]
 * }
 *
 * val bson = BsonEncoder[Shape].toBsonValue(Circle(1.5))
 * // {"radius": 1.5, "type": "Circle"}
 * }}}
 *
 * @see
 *   [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
final case class bsonDiscriminator(name: String) extends Annotation

/**
 * Gives a subtype of a sealed trait the name `name` in the BSON document, in place of the name that the configuration's
 * `classNameMapping` produces from its class name. The name is the key of the wrapper document, or the value of the
 * discriminator field.
 *
 * {{{
 * // no-check: needs zio-bson-magnolia, which is not published for Scala 2.12
 * import zio.bson.magnolia._
 *
 * sealed trait Shape
 * final case class Circle(radius: Double) extends Shape
 * @bsonHint("sq") final case class Square(side: Double) extends Shape
 *
 * object Shape {
 *   implicit val codec: BsonCodec[Shape] = DeriveBsonCodec.derive[Shape]
 * }
 *
 * val bson = BsonEncoder[Shape].toBsonValue(Square(2.0))
 * // {"sq": {"side": 2.0}}
 * }}}
 *
 * @see
 *   [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
final case class bsonHint(name: String) extends Annotation

/**
 * Marks a case class so that its derived decoder rejects a document with a field that the class doesn't have. It fails
 * with `Invalid extra field.` at the path of the extra field. Without it, extra fields are ignored unless
 * `allowExtraFields` is `false` in the configuration.
 *
 * {{{
 * // no-check: needs zio-bson-magnolia, which is not published for Scala 2.12
 * import org.bson.BsonDocument
 * import org.bson.BsonInt32
 * import zio.bson.magnolia._
 *
 * @bsonNoExtraFields
 * final case class Strict(a: Int)
 *
 * object Strict {
 *   implicit val codec: BsonCodec[Strict] = DeriveBsonCodec.derive[Strict]
 * }
 *
 * val document = new BsonDocument("a", new BsonInt32(1))
 * document.put("b", new BsonInt32(2))
 *
 * val result: Either[String, Strict] = document.as[Strict]
 * // Left(.b: Invalid extra field.)
 * }}}
 *
 * @see
 *   [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
final class bsonNoExtraFields extends Annotation

/**
 * Marks a case class field so that the derived encoder leaves it out of the document.
 *
 * The derived decoder still treats the field as part of the class. When the document has no value for it, the decoder
 * uses the field's default value; without a default value the decoding fails with the message `missing`, unless the
 * type of the field decodes a missing field, as `Option` does. When the document does hold a value for the field, the
 * decoder reads it.
 *
 * {{{
 * // no-check: needs zio-bson-magnolia, which is not published for Scala 2.12
 * import zio.bson.magnolia._
 *
 * final case class Session(user: String, @bsonExclude attempts: Int = 0)
 *
 * object Session {
 *   implicit val codec: BsonCodec[Session] = DeriveBsonCodec.derive[Session]
 * }
 *
 * val bson = BsonEncoder[Session].toBsonValue(Session("ada", 3))
 * // {"user": "ada"}
 * }}}
 *
 * @see
 *   [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
final class bsonExclude extends Annotation
