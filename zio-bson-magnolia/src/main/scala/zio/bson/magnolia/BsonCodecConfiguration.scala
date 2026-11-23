package zio.bson.magnolia

import zio.bson.magnolia.BsonCodecConfiguration.SumTypeHandling.WrapperWithClassNameField
import zio.bson.magnolia.BsonCodecConfiguration._

import java.util.regex.Pattern

/**
 * Settings for the codecs that the derivation in this package creates. The derivation takes the configuration as an
 * implicit parameter and finds [[BsonCodecConfiguration.default]] when none is in scope.
 *
 * {{{
 * import zio.bson._
 * import zio.bson.magnolia._
 *
 * final case class User(firstName: String, lastName: Option[String])
 *
 * implicit val configuration: BsonCodecConfiguration =
 *   BsonCodecConfiguration(fieldNameMapping = BsonCodecConfiguration.toSnakeCase)
 *
 * object User {
 *   implicit val codec: BsonCodec[User] = DeriveBsonCodec.derive[User]
 * }
 *
 * val bson = BsonEncoder[User].toBsonValue(User("Ada", None))
 * // {"first_name": "Ada"}
 * }}}
 *
 * @param sumTypeHandling
 *   how a sealed trait is written: as a document with one field, named after the subtype, that holds the subtype's
 *   document, which is the default, or with a discriminator field. See [[BsonCodecConfiguration.SumTypeHandling]].
 * @param skipNullsInCaseClass
 *   when `true`, the default, an encoder leaves out a case class field whose encoder reports the value as absent, which
 *   is `None` for an `Option` field. When `false`, such a field is written with its encoder, which writes BSON null for
 *   `None`.
 * @param classNameMapping
 *   changes the class name of a subtype of a sealed trait into the name that the document uses. The default keeps the
 *   class name. A `bsonHint` annotation on the subtype takes priority.
 * @param fieldNameMapping
 *   changes the name of a case class field into the name that the document uses. The default keeps the name. A
 *   `bsonField` annotation on the field takes priority.
 * @param allowExtraFields
 *   when `true`, the default, a decoder ignores document fields that the case class doesn't have. When `false`, it
 *   fails with the message `Invalid extra field.`, except for the discriminator field of a sum type. The
 *   `bsonNoExtraFields` annotation makes a single class reject extra fields even when this is `true`.
 *
 * @see
 *   [[https://zio.dev/zio-bson/ ZIO BSON]]
 */
case class BsonCodecConfiguration(
  sumTypeHandling: SumTypeHandling = WrapperWithClassNameField,
  skipNullsInCaseClass: Boolean = true,
  classNameMapping: TermMapping = identity,
  fieldNameMapping: TermMapping = identity,
  allowExtraFields: Boolean = true
)

/** The companion of [[BsonCodecConfiguration]]: the default configuration and the name mappings that it can use. */
object BsonCodecConfiguration {

  /** The configuration with the default value of every setting. */
  implicit val default: BsonCodecConfiguration = BsonCodecConfiguration()

  /** A function that turns a class or field name into the name that the BSON document uses. */
  type TermMapping = String => String

  /**
   * How the derivation writes and reads a sealed trait. Use
   * [[BsonCodecConfiguration.SumTypeHandling.WrapperWithClassNameField]] or
   * [[BsonCodecConfiguration.SumTypeHandling.DiscriminatorField]].
   */
  sealed trait SumTypeHandling

  /** The ways to write a sum type. */
  object SumTypeHandling {

    /**
     * Encodes a sum type as a document with one field, named after the chosen subtype, that holds the subtype's own
     * document. This is the default. The subtype name is the class name, changed by `classNameMapping` or replaced by
     * `bsonHint`.
     *
     * {{{
     * import zio.bson._
     * import zio.bson.magnolia._
     *
     * sealed trait MySum
     * final case class SomeBranch(a: Int)     extends MySum
     * final case class OtherBranch(b: String) extends MySum
     *
     * object MySum {
     *   implicit val codec: BsonCodec[MySum] = DeriveBsonCodec.derive[MySum]
     * }
     *
     * final case class Outer(mySum: MySum)
     *
     * object Outer {
     *   implicit val codec: BsonCodec[Outer] = DeriveBsonCodec.derive[Outer]
     * }
     *
     * val bson = BsonEncoder[Outer].toBsonValue(Outer(SomeBranch(123)))
     * // a document whose mySum field is {"SomeBranch": {"a": 123}}
     * }}}
     */
    case object WrapperWithClassNameField extends SumTypeHandling

    /**
     * Encodes a sum type as the document of the chosen subtype with one more field, named `name`, that holds the
     * subtype's name. The subtype name is the class name, changed by `classNameMapping` or replaced by `bsonHint`. A
     * `bsonDiscriminator` annotation on the sealed trait takes priority over this setting.
     *
     * The subtypes must encode as documents: encoding a value through `toBsonValue` throws a `RuntimeException` with
     * the message `Subtype is not encoded as an object` when a subtype encodes as another BSON type, and `encode` fails
     * with a `BsonInvalidOperationException` from the writer. When decoding, the discriminator field is not treated as
     * an extra field of the subtype.
     *
     * {{{
     * import zio.bson._
     * import zio.bson.magnolia._
     *
     * sealed trait MySum
     * final case class SomeBranch(a: Int)     extends MySum
     * final case class OtherBranch(b: String) extends MySum
     *
     * implicit val configuration: BsonCodecConfiguration =
     *   BsonCodecConfiguration(sumTypeHandling = BsonCodecConfiguration.SumTypeHandling.DiscriminatorField("type"))
     *
     * object MySum {
     *   implicit val codec: BsonCodec[MySum] = DeriveBsonCodec.derive[MySum]
     * }
     *
     * val bson = BsonEncoder[MySum].toBsonValue(SomeBranch(123))
     * // {"a": 123, "type": "SomeBranch"}
     * }}}
     */
    final case class DiscriminatorField(name: String) extends SumTypeHandling
  }

  private val basePattern: Pattern = Pattern.compile("([A-Z]+)([A-Z][a-z])")
  private val swapPattern: Pattern = Pattern.compile("([a-z\\d])([A-Z])")

  /**
   * Converts a camelCase name to kebab-case, for example `fooBar` to `foo-bar`.
   */
  val toKebabCase: TermMapping = s => {
    val partial = basePattern.matcher(s).replaceAll("$1-$2")
    swapPattern.matcher(partial).replaceAll("$1-$2").toLowerCase
  }

  /**
   * Converts a camelCase name to snake_case, for example `fooBar` to `foo_bar`.
   */
  val toSnakeCase: TermMapping = s => {
    val partial = basePattern.matcher(s).replaceAll("$1_$2")
    swapPattern.matcher(partial).replaceAll("$1_$2").toLowerCase
  }

  /**
   * Converts a camelCase name to UPPER-KEBAB-CASE, for example `fooBar` to `FOO-BAR`.
   */
  val toUpperKebabCase: TermMapping = s => {
    val partial = basePattern.matcher(s).replaceAll("$1-$2")
    swapPattern.matcher(partial).replaceAll("$1-$2").toUpperCase
  }

  /**
   * Converts a camelCase name to SCREAMING_SNAKE_CASE, for example `fooBar` to `FOO_BAR`.
   */
  val toScreamingSnakeCase: TermMapping = s => {
    val partial = basePattern.matcher(s).replaceAll("$1_$2")
    swapPattern.matcher(partial).replaceAll("$1_$2").toUpperCase
  }

  /** The same mapping as [[BsonCodecConfiguration.toScreamingSnakeCase]]. */
  val toUpperSnakeCase: String => String = toScreamingSnakeCase

}
