package zio.bson.magnolia

import zio.Scope
import zio.bson.BsonBuilder._
import zio.bson.TestUtils._
import zio.bson.magnolia.model.Generators.genManualAWithB
import zio.bson.magnolia.model._
import zio.test.TestAspect.timed
import zio.test._

object RoundTripSpec extends ZIOSpecDefault {

  def spec: Spec[Environment with TestEnvironment with Scope, Any] = suite("RoundTripSpec")(
    roundTripTest("ManualAWithB")(
      genManualAWithB,
      ManualAWithB(ManualA("av"), ManualB("bv")),
      doc(
        "a" -> doc("af" -> str("av")),
        "b" -> str("bv")
      ),
      isDocument = true
    )
  ) @@ timed
}
