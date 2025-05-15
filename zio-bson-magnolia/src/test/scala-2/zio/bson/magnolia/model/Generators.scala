package zio.bson.magnolia.model

import zio.bson.model.Generators._
import zio.test.{Gen, Sized}

object Generators {

  lazy val genManualAWithB: Gen[Sized, ManualAWithB] = for {
    a <- genLimitedString
    b <- genLimitedString
  } yield ManualAWithB(ManualA(a), ManualB(b))
}
