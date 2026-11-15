<!-- SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com> -->
<!-- SPDX-License-Identifier: MIT -->

# Changelog

Versions follow [Semantic Versioning](https://semver.org).

## Unreleased

- The license is MIT instead of MPL-2.0 with the Progmasoft Exception 2.0.
- `etincelle-core`: the sealed `DataType` (BOOLEAN, INT, BIGINT, DOUBLE, STRING), `Field` and `Schema`, whose names
  match without regard to case and may not repeat in another case.
- `etincelle-sql`: `Identifiers`, which quotes names with backquotes as needed and parses qualified names,
  reporting the offset of the first character that does not fit.
