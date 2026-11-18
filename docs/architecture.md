<!-- SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com> -->
<!-- SPDX-License-Identifier: MIT -->

# Architecture

Etincelle keeps its data in columns of its own; it needs no other library for them.

## `core`, `com.progmasoft.etincelle.core`

`Table`, `Batch`, `Schema` and `Field`; `Column` and its typed columns, `IntColumn`, `LongColumn`, `DoubleColumn`,
`BooleanColumn` and `StringColumn`, each a typed array with a validity bitmap, built through `ColumnBuilder`;
`DataType`.

## `sql`, `com.progmasoft.etincelle.sql`

A query goes through four stages:

| Stage | Package | What it does |
| --- | --- | --- |
| Lexing | `parse` | `Lexer` turns the text into `Token`s with their line and column |
| Parsing | `parse` | `Parser`, a recursive-descent parser, builds the `Ast` |
| Planning | `plan` | `Analyzer` and `Binder` resolve names against the `Catalog` and types; `Aggregation` and `AggregateCall` plan grouping; the result is a `Plan` of `Expression`s |
| Running | `exec` | `Executor` runs the plan over the columns |

`Session` is the entry point; errors are `SqlException`s with ANSI SQL states and their positions.
