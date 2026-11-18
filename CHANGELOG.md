<!-- SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com> -->
<!-- SPDX-License-Identifier: MIT -->

# Changelog

Versions follow [Semantic Versioning](https://semver.org).

## Unreleased

- The license is MIT instead of MPL-2.0 with the Progmasoft Exception 2.0.
- `etincelle-core`: the sealed `DataType` (BOOLEAN, INT, BIGINT, DOUBLE, STRING), `Field` and `Schema`, whose names
  match without regard to case and may not repeat in another case.
- `etincelle-core`: columns of each type, a typed array with a validity bitmap, built by `ColumnBuilder`; `Batch`, the
  columns of a schema; `Table`, batches of 4096 rows, built row by row, and `Table.format` for a text table.
- `etincelle-sql`: `Session` registers tables and runs Etincelle SQL: one SELECT over one table with WHERE, GROUP BY,
  HAVING, ORDER BY, LIMIT and DISTINCT; arithmetic, comparisons, three-valued logic, IN, BETWEEN, LIKE, CASE, CAST,
  five functions and five aggregates; ANSI errors for overflow, division by zero and failed casts, each with its line
  and column; `explain` gives the plan.
- `etincelle-sql`: `Identifiers`, which quotes names with backquotes as needed and parses qualified names,
  reporting the offset of the first character that does not fit.
