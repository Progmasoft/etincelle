/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import com.progmasoft.etincelle.core.Schema;
import com.progmasoft.etincelle.sql.SqlException;
import com.progmasoft.etincelle.sql.parse.Ast;
import java.util.Locale;
import java.util.Set;

/** The text of the query being resolved, for errors at a place in it, and what the names mean. */
record Source(String sql) {
  /** The aggregates. */
  static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max");

  /** The functions of a row. */
  static final Set<String> FUNCTIONS = Set.of("lower", "upper", "length", "abs", "coalesce");

  /** An error at {@code offset} of the query. */
  SqlException error(int offset, String message) {
    return new SqlException(sql, offset, message);
  }

  /** Whether {@code name} names an aggregate, case aside. */
  static boolean isAggregate(String name) {
    return AGGREGATES.contains(name.toLowerCase(Locale.ROOT));
  }

  /** Whether {@code expression} holds an aggregate call anywhere. */
  static boolean hasAggregate(Ast.Expr expression) {
    if (expression instanceof Ast.Call call && isAggregate(call.name())) {
      return true;
    }
    return expression.children().stream().anyMatch(Source::hasAggregate);
  }

  /** The columns a query reads, and the name or alias that qualifies them. */
  record Scope(Schema schema, String qualifier) {}
}
