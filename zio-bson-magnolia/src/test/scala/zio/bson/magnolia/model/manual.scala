package zio.bson.magnolia.model

import zio.bson.BsonCodec
import zio.bson.magnolia.DeriveBsonCodec

case class ManualA(af: String)

object ManualA {
  implicit lazy val codec: BsonCodec[ManualA] = DeriveBsonCodec.derive
}

case class ManualB(bf: String)

object ManualB {
  implicit lazy val codec: BsonCodec[ManualB] = BsonCodec[String].transform(ManualB(_))(_.bf)
}

case class ManualAWithB(a: ManualA, b: ManualB)

object ManualAWithB {
  implicit lazy val codec: BsonCodec[ManualAWithB] = DeriveBsonCodec.derive
}
