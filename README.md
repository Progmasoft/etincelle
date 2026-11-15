<!--
SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
SPDX-License-Identifier: MIT
-->

# Etincelle

Etincelle is an engine for processing large data on the JVM, playing the part Apache Spark plays, for Java 21 and
later and the JVM languages that call Java, such as Kotlin and Scala. Its columnar data will live in
[Archer](https://github.com/Progmasoft/archer). The name comes from French: *étincelle* means spark, a nod to
Apache Spark, written without its accent as Etincelle.

Etincelle is under development and has no release yet.

## Modules

| Artifact | What it holds |
|---|---|
| `com.progmasoft.etincelle:etincelle-core` | Column types (a sealed `DataType`), `Field` and `Schema`, whose names match without regard to case. |
| `com.progmasoft.etincelle:etincelle-sql` | Etincelle SQL, starting with its names: plain names, names between backquotes, qualified names. |

```kotlin
repositories {
    maven("https://maven.progmasoft.com/bigdata")
}

dependencies {
    implementation("com.progmasoft.etincelle:etincelle-sql:$etincelleVersion")
}
```

## Building

Etincelle builds with any JDK from 21 on and is compiled against the Java 21 API.

```bash
./gradlew check
```

## License

[MIT](LICENSE.txt).
