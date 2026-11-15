/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

rootProject.name = "etincelle"

// Types, schemas and rows: what every other module shares.
include("etincelle-core")
project(":etincelle-core").projectDir = file("core")

// SQL.
include("etincelle-sql")
project(":etincelle-sql").projectDir = file("sql")
