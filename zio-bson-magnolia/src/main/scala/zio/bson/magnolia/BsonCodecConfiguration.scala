package zio.bson.magnolia

import zio.bson.magnolia.BsonCodecConfiguration.SumTypeHandling.WrapperWithClassNameField
import zio.bson.magnolia.BsonCodecConfiguration._

import java.util.regex.Pattern

case class BsonCodecConfiguration(
  sumTypeHandling: SumTypeHandling = WrapperWithClassNameField,
  skipNullsInCaseClass: Boolean = true,
  classNameMapping: TermMapping = identity,
  fieldNameMapping: TermMapping = identity,
  allowExtraFields: Boolean = true
)

object BsonCodecConfiguration {
  implicit val default: BsonCodecConfiguration = BsonCodecConfiguration()

  type TermMapping = String => String

  sealed trait SumTypeHandling

  object SumTypeHandling {

    /**
     * Sum type hierarchy:
     * {{{
     *   sealed trait MySum
     *   case class SomeBranch(a: Int) extends MySum
     *   case class OtherBranch(b: String) extends MySum
     *
     *   case class Outer(mySum: MySum)
     * }}}
     *
     * Result BSON for [[WrapperWithClassNameField]]:
     * {{{
     *   {
     *     mySum: {
     *       SomeBranch: {
     *         a: 123
     *       }
     *     }
     *   }
     * }}}
     */
    case object WrapperWithClassNameField extends SumTypeHandling

    /**
     * Sum type hierarchy:
     * {{{
     *   sealed trait MySum
     *   case class SomeBranch(a: Int) extends MySum
     *   case class OtherBranch(b: String) extends MySum
     *
     *   case class Outer(mySum: MySum)
     * }}}
     *
     * Result BSON for `DiscriminatorField("$type")`:
     * {{{
     *   {
     *     mySum: {
     *       $type: "SomeBranch"
     *       a: 123
     *     }
     *   }
     * }}}
     */
    final case class DiscriminatorField(name: String) extends SumTypeHandling
  }

  private val basePattern: Pattern = Pattern.compile("([A-Z]+)([A-Z][a-z])")
  private val swapPattern: Pattern = Pattern.compile("([a-z\\d])([A-Z])")

  /**
   * Convert a camelCase key to kebab-case
   */
  val toKebabCase: TermMapping = s => {
    val partial = basePattern.matcher(s).replaceAll("$1-$2")
    swapPattern.matcher(partial).replaceAll("$1-$2").toLowerCase
  }

  /**
   * Convert a camelCase key to snake_case
   */
  val toSnakeCase: TermMapping = s => {
    val partial = basePattern.matcher(s).replaceAll("$1_$2")
    swapPattern.matcher(partial).replaceAll("$1_$2").toLowerCase
  }

  /**
   * Convert a camelCase key to UPPER-KEBAB-CASE
   */
  val toUpperKebabCase: TermMapping = s => {
    val partial = basePattern.matcher(s).replaceAll("$1-$2")
    swapPattern.matcher(partial).replaceAll("$1-$2").toUpperCase
  }

  /**
   * Convert a camelCase key to SCREAMING_SNAKE_CASE
   */
  val toScreamingSnakeCase: TermMapping = s => {
    val partial = basePattern.matcher(s).replaceAll("$1_$2")
    swapPattern.matcher(partial).replaceAll("$1_$2").toUpperCase
  }

  val toUpperSnakeCase: String => String = toScreamingSnakeCase

}
