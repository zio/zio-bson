---
id: bson-decoder
title: "BsonDecoder"
sidebar_label: "BsonDecoder"
---

## Overview

`BsonDecoder[A]` is the typeclass that reads BSON and produces a Scala value of type `A`. Every decoding call either returns the value or a `BsonDecoder.Error` that says where in the document the problem is.

As with encoders, you usually get a decoder from a `BsonCodec`. This page covers the public methods, the error type, and the operations that build new decoders from existing ones.

## Definition

The trait has these members that users call:

```scala
trait BsonDecoder[A] {
  def decode(reader: BsonReader): Either[BsonDecoder.Error, A]
  def fromBsonValue(value: BsonValue): Either[BsonDecoder.Error, A]
  def map[B](f: A => B): BsonDecoder[B]
  def mapOrFail[B](f: A => Either[String, B]): BsonDecoder[B]
}
```

`decode` reads from a `BsonReader`, the streaming API used by the MongoDB driver. `fromBsonValue` reads from an in-memory `BsonValue`. Both return `Left` with a `BsonDecoder.Error` when the input does not match.

The members with `Unsafe` in their names, such as `decodeUnsafe` and `fromBsonValueUnsafe`, throw instead of returning `Left`. They exist for the derived decoders and for code that needs to avoid allocation. Use the safe methods in application code.

## Decoding a BsonValue

The syntax method `as` from the `zio.bson` package calls `fromBsonValue` and converts the error to a `String`:

```scala mdoc:silent
import zio.bson._
import zio.bson.BsonBuilder._
```

To read an `Int` from a `BsonValue`:

```scala mdoc
println(int(42).as[Int])
```

A `BsonDecoder` also accepts other numeric BSON types for the same Scala type. For example, a `DOUBLE` with no fractional part decodes to an `Int`:

```scala mdoc
println(double(7.0).as[Int])
println(double(7.5).as[Int])
```

The second line fails because `7.5` cannot be stored in an `Int` without losing precision. The full list of accepted BSON types for each Scala type is in [Type Mappings](./types.md).

## Decoding from a BsonReader

To decode directly from the driver's stream, call `decode` with a `BsonReader`. The example below reads the value of the `count` field from a `BsonDocumentReader`, which is a reader over an in-memory document. The reader must be positioned on the value, so the code first moves past the start of the document and the field name:

```scala mdoc:silent
import org.bson.BsonDocumentReader

val reader = new BsonDocumentReader(doc("count" -> int(3)))
reader.readStartDocument()
reader.readName()
```

```scala mdoc
println(BsonDecoder[Int].decode(reader))
```

For real code, the derived codec for a case class already calls `decode` for you when the MongoDB driver is configured. See [Use zio-bson with the MongoDB Driver](./how-to-use-with-mongodb-driver.md).

## Transforming Decoders

`map` changes the result type of a decoder. The function you pass runs after the original decoder and cannot fail:

```scala mdoc:silent
final case class Celsius(degrees: Double)

val celsiusDecoder: BsonDecoder[Celsius] = BsonDecoder[Double].map(Celsius(_))
```

```scala mdoc
println(double(21.5).as(celsiusDecoder))
```

`mapOrFail` works the same way, but the function returns `Either[String, B]`. Use it when a value must pass a check, such as a range:

```scala mdoc:silent
final case class Percent(value: Int)

val percentDecoder: BsonDecoder[Percent] =
  BsonDecoder[Int].mapOrFail { n =>
    if (n >= 0 && n <= 100) Right(Percent(n))
    else Left(s"Expected a percentage between 0 and 100, but got $n.")
  }
```

```scala mdoc
println(int(150).as(percentDecoder))
```

When `mapOrFail` returns `Left`, the message becomes a `BsonDecoder.Error` at the current path, so the caller sees the field name as well.

For a full encoder and decoder pair, use `BsonCodec.transformOrFail`, described in [BsonCodec](./bson-codec.md).

## Missing Fields

A decoder can say what happens when a field is absent from a document. The default is an error. `Option` overrides this and returns `None`. The derived case-class decoder calls this method for each field that is missing from the input. You can override `decodeMissingUnsafe` only when you write a decoder by hand.

## Errors

Every failure is a `BsonDecoder.Error`, a case class with two fields:

- `trace`: a list of `BsonTrace` steps that points from the root of the value to the failing part.
- `message`: a human-readable reason.

The `render` method combines both into one string. See [Errors and BsonTrace](./bson-errors.md) for the full format.

## Predefined Instances

The `BsonDecoder` companion object has a decoder for every type listed in [BsonEncoder](./bson-encoder.md), with the same set of accepted BSON types per type. Each one is documented in [Type Mappings](./types.md).

## Related Pages

- [BsonEncoder](./bson-encoder.md) writes values.
- [BsonCodec](./bson-codec.md) bundles an encoder and a decoder.
- [Errors and BsonTrace](./bson-errors.md) explains the error format.
