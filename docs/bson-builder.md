---
id: bson-builder
title: "BsonBuilder"
sidebar_label: "BsonBuilder"
---

`BsonBuilder` is a small object of helpers that build `BsonValue` instances from Scala values. It is useful for writing MongoDB filters and updates by hand and for building the expected documents in tests. Each helper wraps one constructor of the MongoDB Java driver, so there is no encoding or codec involved.

To bring the helpers into scope:

```scala mdoc:silent
import zio.bson._
import zio.bson.BsonBuilder._
```

## Definition

The object has one helper per common BSON type:

```scala
object BsonBuilder {
  def doc(kv: (String, BsonValue)*): BsonDocument
  def element(k: String, v: BsonValue): BsonElement
  def array(elements: BsonValue*): BsonArray
  def str(s: String): BsonString
  def int(i: Int): BsonInt32
  def long(l: Long): BsonInt64
  def bool(b: Boolean): BsonBoolean
  def double(d: Double): BsonDouble
  def date(instant: Instant): BsonValue
  val bNull: BsonNull
}
```

## Building Documents and Arrays

To build a nested document, pass pairs of a key and a `BsonValue` to `BsonBuilder.doc`, and use `BsonBuilder.array` for lists:

```scala mdoc
val order = doc(
  "id"    -> long(42L),
  "items" -> array(str("apple"), str("pear")),
  "paid"  -> bool(true),
  "note"  -> bNull
)
```

`BsonBuilder.element` builds one `BsonElement`, a key with its value, which some driver APIs take instead of a pair.

## Numbers

`BsonBuilder.int` writes `INT32`, `BsonBuilder.long` writes `INT64`, and `BsonBuilder.double` writes `DOUBLE`. The type matters when reading: the [type mappings](./types.md) list which numeric BSON types each Scala type accepts.

```scala mdoc
doc("a" -> int(1), "b" -> long(1L), "c" -> double(1.0)).as[Map[String, Int]]
```

## Dates

`BsonBuilder.date` converts an `Instant` with the same encoder as `Instant#toBsonValue`, so it writes a `DATE_TIME` with millisecond precision:

```scala mdoc
import java.time.Instant

date(Instant.ofEpochMilli(1700000000000L))
```

## Related Pages

- [BsonEncoder](./bson-encoder.md) converts any Scala value to a `BsonValue`.
- [BsonDecoder](./bson-decoder.md) reads a `BsonValue` back with `as`.
- [Type Mappings](./types.md) lists the BSON type of each Scala type.
