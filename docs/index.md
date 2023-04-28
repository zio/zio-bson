---
id: index
title: "Getting Started with ZIO Bson"
sidebar_label: "Getting Started"
---

[ZIO Bson](https://github.com/zio/zio-bson) is BSON library with tight ZIO integration.

@PROJECT_BADGES@

## Introduction

The goal of this project is to create the best all-round JSON library for Scala:

- **Native BSON support** to avoid intermediate JSON conversions and support BSON types.
- **Future-Proof**, prepared for Scala 3 and next-generation Java.
- **Simple** small codebase, concise documentation that covers everything.
- **Helpful errors** are readable by humans and machines.
- **ZIO Integration** so nothing more is required.

## Installation

In order to use this library, we need to add the following lines in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-bson" % "<version>"
libraryDependencies += "dev.zio" %% "zio-bson-magnolia" % "<version>"
```

## zio-schema support

`zio-bson-magnolia` projects provides typeclass derivation only for `scala` `2.13`.
You can use [zio-schema-bson](https://github.com/zio/zio-schema/) instead to get typeclass derivation on `scala` `2.12`, `2.13` and `3.2`.

## Example

All the following code snippets assume that the following imports have been declared

```scala
import zio.bson._
import zio.bson.BsonBuilder._
```

### Declaring codecs

Use `DeriveBsonCodec.derive` to get a codec for your case class or sealed trait:

```scala
import zio.bson.magnolia._

case class Banana(curvature: Double)

object Banana {
  implicit val codec: BsonCodec[Banana] = DeriveBsonCodec.derive
}
```

### Converting to BsonValue

To use values in `Filter` of `Update` expressions you can convert them to `BsonValue` this way:

```scala
"str".toBsonValue

Banana(0.2).toBsonValue

import org.bson._

def method[T: BsonEncoder](value: T): BsonDocument = doc("key" -> value.toBsonValue)
```

### Creating CodecProvider

To get `CodecProvider` for your `BsonCodec` use `zioBsonCodecProvider`:

```scala
val codecProvider = zioBsonCodecProvider[Banana]
```

### Parsing BsonValue

In general `CodecProvider` should parse your case class without intermediate `BsonValue` representation.
But you can parse `BsonValue` any way:

```scala
import BsonBuilder._

val bsonVal: BsonValue = doc("curvature" -> double(0.2))

bsonVal.as[Banana]
```

## Errors

Bad BSON will produce an error with path and contextual information

```
scala> doc("curvature" -> Array[Byte](1, 2, 3).toBsonValue).as[Banana]
val res: Either[String,Banana] = Left(.curvature: Expected BSON type Double, but got BINARY.)
```

## Configuration

You can configure typeclass derivation with annotations.

```scala mdoc:compile-only
import zio.bson._
import zio.bson.BsonBuilder._
import zio.bson.magnolia._

sealed trait Fruit

object Fruit {
  case class Banana(curvature: Double) extends Fruit
  case class Apple(poison: Boolean) extends Fruit
  
  implicit val codec: BsonCodec[Fruit] = DeriveBsonCodec.derive
}

val jsonFruit = doc( "Banana" -> doc( "curvature" -> double(0.5) ))

jsonFruit.as[Fruit]
//Right(Banana(0.5))

@bsonDiscriminator("$type")
sealed trait FruitConfigured

object FruitConfigured {
  case class Banana(curvature: Double) extends FruitConfigured
  @bsonHint("custom_type_name")
  case class Apple(@bsonField("is_poisoned") poison: Boolean) extends FruitConfigured

  implicit val codec: BsonCodec[FruitConfigured] = DeriveBsonCodec.derive
}

val jsonFruitConfigured = doc(
  "$type" -> str("custom_type_name"),
  "is_poisoned" -> bool(true)
)

jsonFruitConfigured.as[FruitConfigured]
//Right(Apple(true))
```
