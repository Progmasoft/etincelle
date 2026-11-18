<!--
SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
SPDX-License-Identifier: MIT
-->

# Etincelle

Etincelle is an engine for processing large data on the JVM, playing the part Apache Spark plays, for Java 21 and
later and the JVM languages that call Java, such as Kotlin and Scala. It keeps its data in columns of its own. The
name comes from French: *étincelle* means spark, a nod to Apache Spark, written without its accent as Etincelle.

Etincelle is under development and has no release yet.

## Modules

| Artifact | What it holds |
|---|---|
| `com.progmasoft.etincelle:etincelle-core` | Column types (a sealed `DataType`), `Field` and `Schema`, whose names match without regard to case; columns, each a typed array with a validity bitmap (`IntColumn`, `LongColumn`, `DoubleColumn`, `BooleanColumn`, `StringColumn`); `Batch` and `Table`. |
| `com.progmasoft.etincelle:etincelle-sql` | Etincelle SQL: a `Session` runs SELECT queries over the tables registered in it. |

```kotlin
repositories {
    maven("https://maven.progmasoft.com/bigdata")
}

dependencies {
    implementation("com.progmasoft.etincelle:etincelle-sql:$etincelleVersion")
}
```

## Etincelle SQL

```java
Schema schema = Schema.of(
    new Field("name", DataType.STRING, false),
    new Field("dept", DataType.STRING, true),
    new Field("salary", DataType.DOUBLE, true));
Table people = Table.builder(schema)
    .row("Ada", "eng", 120.0)
    .row("Grace", "eng", 150.0)
    .row("Alan", "research", 110.0)
    .build();

Session session = new Session().register("people", people);
String query = """
    SELECT dept, count(*) AS n, sum(salary) AS total
    FROM people
    GROUP BY dept
    ORDER BY total DESC""";
System.out.print(session.sql(query).format(10));
System.out.print(session.explain(query));
```

```text
+----------+---+-------+
| dept     | n | total |
+----------+---+-------+
| eng      | 2 | 270.0 |
| research | 1 | 110.0 |
+----------+---+-------+
Sort [total DESC NULLS LAST]
  Project [dept, `count(*)`, `sum(salary)`] (dept STRING, n BIGINT NOT NULL, total DOUBLE)
    Aggregate keys=[dept] aggregates=[count(*), sum(salary)]
      Scan people (name STRING NOT NULL, dept STRING, salary DOUBLE)
```

A test runs this example, so the output above is what Etincelle gives. A query is one SELECT over one table:

```sql
SELECT [DISTINCT] item [, ...]
[FROM table [[AS] alias]]
[WHERE condition]
[GROUP BY expression [, ...]]
[HAVING condition]
[ORDER BY expression [ASC | DESC] [NULLS FIRST | NULLS LAST] [, ...]]
[LIMIT count]
```

- **Expressions:** `+ - * / %`, `||`, `= <> != < <= > >=`, `AND OR NOT` with three-valued logic, `IS [NOT] NULL`,
  `[NOT] IN (...)`, `[NOT] BETWEEN ... AND ...`, `[NOT] LIKE` (`%`, `_`, and `\` before either to match it),
  `CASE`, `CAST(x AS type)`.
- **Functions:** `lower`, `upper`, `length`, `abs`, `coalesce`. **Aggregates:** `count(*)`, `count`, `sum`, `avg`,
  `min`, `max`, each also with `DISTINCT`.
- **Types:** `INT`, `BIGINT` and `DOUBLE` widen to each other in that order; `STRING` and `BOOLEAN` change only
  through `CAST`. `/` gives a `DOUBLE`. A whole number literal is an `INT`, or a `BIGINT` when it does not fit; a
  number with a point or an exponent is a `DOUBLE`.
- **Errors, as in ANSI SQL:** an overflow, a division by zero and a `CAST` that does not fit stop the query. Every
  error names its line and column: `line 1, column 8: there is no column age; the columns are name, dept, salary`.
- **Order:** `ASC` puts NULLs first and `DESC` last unless `NULLS` says otherwise; strings order by code point.
- **Names** match without regard to case. `GROUP BY` takes expressions, select list aliases and positions; `ORDER BY`
  takes positions, result column names and expressions, which need not be in the result unless the query is
  `DISTINCT`.
- **One table a query:** joins, subqueries and `UNION` are refused with an error at the place they start.

## Building

Etincelle builds with any JDK from 21 on and is compiled against the Java 21 API.

```bash
./gradlew check
```

## License

[MIT](LICENSE.txt).
Progmasoft's own code is also covered by the [Progmasoft Patent Grant 1.1](PATENTS.md).
