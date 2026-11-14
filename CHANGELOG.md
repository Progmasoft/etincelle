<!-- SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com> -->
<!-- SPDX-License-Identifier: MPL-2.0 WITH AdditionRef-Progmasoft-Exception-2.0 -->

# Changelog

Versions follow [Semantic Versioning](https://semver.org).

## Unreleased

- `etincelle-core`: the sealed `DataType` (BOOLEAN, INT, BIGINT, DOUBLE, STRING), `Field` and `Schema`, whose names
  match without regard to case and may not repeat in another case.
- `etincelle-sql`: `Identifiers`, which quotes names with backquotes as needed and parses qualified names,
  reporting the offset of the first character that does not fit.
