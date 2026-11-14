/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MPL-2.0 WITH AdditionRef-Progmasoft-Exception-2.0
 */

rootProject.name = "etincelle"

// Types, schemas and rows: what every other module shares.
include("etincelle-core")
project(":etincelle-core").projectDir = file("core")

// SQL. The _2.13 suffix is the Scala binary version of the Scala API this
// artifact carries next to its Java one, as Scala libraries name theirs.
include("etincelle-sql_2.13")
project(":etincelle-sql_2.13").projectDir = file("sql")
