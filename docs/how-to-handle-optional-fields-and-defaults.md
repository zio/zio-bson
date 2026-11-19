---
id: how-to-handle-optional-fields-and-defaults
title: "Handle Optional Fields and Default Values"
sidebar_label: "Handle Optional Fields and Defaults"
---

## Problem

Documents in a collection are rarely uniform: older documents lack newer fields, and some fields are only set sometimes. This guide shows how to read and write such documents with `Option` fields, default values and the `skipNullsInCaseClass` setting.

## Use Option for Fields That May Be Missing

A field of type `Option[A]` decodes to `None` when the key is missing or the value is `NULL`. When encoding, `None` is left out of the document, because `skipNullsInCaseClass` is `true` by default:

```scala mdoc:silent
import zio.bson._
import zio.bson.BsonBuilder._
import zio.bson.magnolia._

final case class User(name: String, nickname: Option[String])

object User {
  implicit val codec: BsonCodec[User] = DeriveBsonCodec.derive
}
```

To see what `User` writes and reads, encode a user without a nickname, then decode documents with the key missing and with `NULL`:

```scala mdoc
println(User("Ada", None).toBsonValue)
println(doc("name" -> str("Ada")).as[User])
println(doc("name" -> str("Ada"), "nickname" -> bNull).as[User])
```

## Use a Default Value for Documents Written Before a Field Existed

When a case class field has a default value and the key is missing, the decoder uses the default instead of failing. This lets us add a field without migrating old documents:

```scala mdoc:silent
final case class Settings(theme: String, pageSize: Int = 20)

object Settings {
  implicit val codec: BsonCodec[Settings] = DeriveBsonCodec.derive
}
```

To decode a document written before `pageSize` existed:

```scala mdoc
doc("theme" -> str("dark")).as[Settings]
```

A field without a default and without an `Option` type is required. Its absence is an error with the path of the field:

```scala mdoc
doc("pageSize" -> int(50)).as[Settings]
```

## Write Explicit Nulls

Some consumers expect every key to be present. To write `None` as `NULL` instead of leaving the key out, set `skipNullsInCaseClass` to `false` in the [configuration](./bson-codec-configuration.md):

```scala mdoc:silent
final case class Profile(name: String, bio: Option[String])

object Profile {
  implicit val config: BsonCodecConfiguration =
    BsonCodecConfiguration(skipNullsInCaseClass = false)

  implicit val codec: BsonCodec[Profile] = DeriveBsonCodec.derive
}
```

With the setting turned off, `None` is written as `NULL`:

```scala mdoc
println(Profile("Ada", None).toBsonValue)
```

## Related Pages

- [Configuration and Annotations](./bson-codec-configuration.md) lists every setting of the derivation.
- [Type Mappings](./types.md) describes how `Option` maps to BSON.
