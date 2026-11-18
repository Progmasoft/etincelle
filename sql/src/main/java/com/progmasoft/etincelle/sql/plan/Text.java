/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import com.progmasoft.etincelle.sql.Identifiers;
import com.progmasoft.etincelle.sql.parse.Ast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes an expression of the syntax tree as text: as written, to name a column of the result, or
 * with every name in lower case, to tell whether two expressions are the same.
 */
final class Text {
  private Text() {}

  /** The text that names a column of the result, names as written. */
  static String display(Ast.Expr expression) {
    return write(expression, false);
  }

  /** The text two expressions share when they are the same, names in lower case. */
  static String canonical(Ast.Expr expression) {
    return write(expression, true);
  }

  private static String name(String part, boolean fold) {
    return Identifiers.quote(fold ? part.toLowerCase(Locale.ROOT) : part);
  }

  private static String write(Ast.Expr expression, boolean fold) {
    return switch (expression) {
      case Ast.Name n -> {
        List<String> parts = new ArrayList<>();
        for (String part : n.parts()) {
          parts.add(name(part, fold));
        }
        yield String.join(".", parts);
      }
      case Ast.Star s -> s.qualifier() == null ? "*" : name(s.qualifier(), fold) + ".*";
      case Ast.NumberLiteral n -> n.text();
      case Ast.StringLiteral s -> "'" + s.value().replace("'", "''") + "'";
      case Ast.BooleanLiteral b -> b.value() ? "TRUE" : "FALSE";
      case Ast.NullLiteral n -> "NULL";
      case Ast.Unary u ->
          u.operator().equals("NOT")
              ? "(NOT " + write(u.operand(), fold) + ")"
              : "(" + u.operator() + write(u.operand(), fold) + ")";
      case Ast.Binary b ->
          "(" + write(b.left(), fold) + " " + b.operator() + " " + write(b.right(), fold) + ")";
      case Ast.IsNull i ->
          "(" + write(i.operand(), fold) + (i.negated() ? " IS NOT NULL)" : " IS NULL)");
      case Ast.InList i -> {
        List<String> items = new ArrayList<>();
        for (Ast.Expr item : i.items()) {
          items.add(write(item, fold));
        }
        yield "("
            + write(i.operand(), fold)
            + (i.negated() ? " NOT IN (" : " IN (")
            + String.join(", ", items)
            + "))";
      }
      case Ast.Between b ->
          "("
              + write(b.operand(), fold)
              + (b.negated() ? " NOT BETWEEN " : " BETWEEN ")
              + write(b.low(), fold)
              + " AND "
              + write(b.high(), fold)
              + ")";
      case Ast.Like l ->
          "("
              + write(l.operand(), fold)
              + (l.negated() ? " NOT LIKE " : " LIKE ")
              + write(l.pattern(), fold)
              + ")";
      case Ast.Case c -> {
        StringBuilder text = new StringBuilder("CASE");
        if (c.operand() != null) {
          text.append(' ').append(write(c.operand(), fold));
        }
        for (Ast.When when : c.whens()) {
          text.append(" WHEN ").append(write(when.condition(), fold));
          text.append(" THEN ").append(write(when.result(), fold));
        }
        if (c.otherwise() != null) {
          text.append(" ELSE ").append(write(c.otherwise(), fold));
        }
        yield text.append(" END").toString();
      }
      case Ast.Cast c ->
          "CAST(" + write(c.operand(), fold) + " AS " + c.type().toUpperCase(Locale.ROOT) + ")";
      case Ast.Call c -> {
        if (c.star()) {
          yield c.name().toLowerCase(Locale.ROOT) + "(*)";
        }
        List<String> arguments = new ArrayList<>();
        for (Ast.Expr argument : c.arguments()) {
          arguments.add(write(argument, fold));
        }
        yield c.name().toLowerCase(Locale.ROOT)
            + "("
            + (c.distinct() ? "DISTINCT " : "")
            + String.join(", ", arguments)
            + ")";
      }
    };
  }
}
