package zio.bson

import scala.annotation.Annotation

final case class bsonField(name: String) extends Annotation

final case class bsonDiscriminator(name: String) extends Annotation

final case class bsonHint(name: String) extends Annotation

final class bsonNoExtraFields extends Annotation

final class bsonExclude extends Annotation
