/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.parse;

import com.progmasoft.etincelle.sql.Identifiers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The syntax tree of a query, as the parser reads it. Each expression keeps the offset of its first
 * character for errors, knows its children, and writes itself back as SQL.
 */
public interface Ast {
  /** An expression. */
  sealed interface Expr
      permits Name,
          Star,
          NumberLiteral,
          StringLiteral,
          BooleanLiteral,
          NullLiteral,
          Unary,
          Binary,
          IsNull,
          InList,
          Between,
          Like,
          Case,
          Cast,
          Call {
    /** The offset of the first character. */
    int offset();

    /** The expressions directly inside this one, in order. */
    default List<Expr> children() {
      return List.of();
    }

    /**
     * The expression as SQL: names as written, to name a column of the result, or with {@code fold}
     * in lower case, so that two expressions that are the same give the same text.
     */
    String toSql(boolean fold);
  }

  private static String name(String part, boolean fold) {
    return Identifiers.quote(fold ? part.toLowerCase(Locale.ROOT) : part);
  }

  private static String join(List<Expr> expressions, boolean fold) {
    List<String> parts = new ArrayList<>(expressions.size());
    for (Expr expression : expressions) {
      parts.add(expression.toSql(fold));
    }
    return String.join(", ", parts);
  }

  /** A name, such as {@code price} or {@code t.price}, its parts as written. */
  record Name(List<String> parts, int offset) implements Expr {
    @Override
    public String toSql(boolean fold) {
      List<String> quoted = new ArrayList<>(parts.size());
      for (String part : parts) {
        quoted.add(name(part, fold));
      }
      return String.join(".", quoted);
    }
  }

  /** {@code *} or {@code t.*}; the qualifier is {@code null} for {@code *}. */
  record Star(String qualifier, int offset) implements Expr {
    @Override
    public String toSql(boolean fold) {
      return qualifier == null ? "*" : name(qualifier, fold) + ".*";
    }
  }

  /** A number as written; {@code decimal} when it has a point or an exponent. */
  record NumberLiteral(String text, boolean decimal, int offset) implements Expr {
    @Override
    public String toSql(boolean fold) {
      return text;
    }
  }

  /** A string. */
  record StringLiteral(String value, int offset) implements Expr {
    @Override
    public String toSql(boolean fold) {
      return "'" + value.replace("'", "''") + "'";
    }
  }

  /** TRUE or FALSE. */
  record BooleanLiteral(boolean value, int offset) implements Expr {
    @Override
    public String toSql(boolean fold) {
      return value ? "TRUE" : "FALSE";
    }
  }

  /** NULL. */
  record NullLiteral(int offset) implements Expr {
    @Override
    public String toSql(boolean fold) {
      return "NULL";
    }
  }

  /** {@code -x}, {@code +x} or {@code NOT x}. */
  record Unary(String operator, Expr operand, int offset) implements Expr {
    @Override
    public List<Expr> children() {
      return List.of(operand);
    }

    @Override
    public String toSql(boolean fold) {
      String separator = operator.equals("NOT") ? " " : "";
      return "(" + operator + separator + operand.toSql(fold) + ")";
    }
  }

  /** An operator between two expressions: arithmetic, ||, a comparison, AND or OR. */
  record Binary(String operator, Expr left, Expr right, int offset) implements Expr {
    @Override
    public List<Expr> children() {
      return List.of(left, right);
    }

    @Override
    public String toSql(boolean fold) {
      return "(" + left.toSql(fold) + " " + operator + " " + right.toSql(fold) + ")";
    }
  }

  /** {@code x IS NULL} or {@code x IS NOT NULL}. */
  record IsNull(Expr operand, boolean negated, int offset) implements Expr {
    @Override
    public List<Expr> children() {
      return List.of(operand);
    }

    @Override
    public String toSql(boolean fold) {
      return "(" + operand.toSql(fold) + (negated ? " IS NOT NULL)" : " IS NULL)");
    }
  }

  /** {@code x [NOT] IN (a, b, ...)}. */
  record InList(Expr operand, List<Expr> items, boolean negated, int offset) implements Expr {
    @Override
    public List<Expr> children() {
      List<Expr> children = new ArrayList<>(items.size() + 1);
      children.add(operand);
      children.addAll(items);
      return children;
    }

    @Override
    public String toSql(boolean fold) {
      return "("
          + operand.toSql(fold)
          + (negated ? " NOT IN (" : " IN (")
          + join(items, fold)
          + "))";
    }
  }

  /** {@code x [NOT] BETWEEN low AND high}. */
  record Between(Expr operand, Expr low, Expr high, boolean negated, int offset) implements Expr {
    @Override
    public List<Expr> children() {
      return List.of(operand, low, high);
    }

    @Override
    public String toSql(boolean fold) {
      return "("
          + operand.toSql(fold)
          + (negated ? " NOT BETWEEN " : " BETWEEN ")
          + low.toSql(fold)
          + " AND "
          + high.toSql(fold)
          + ")";
    }
  }

  /** {@code x [NOT] LIKE pattern}. */
  record Like(Expr operand, Expr pattern, boolean negated, int offset) implements Expr {
    @Override
    public List<Expr> children() {
      return List.of(operand, pattern);
    }

    @Override
    public String toSql(boolean fold) {
      return "("
          + operand.toSql(fold)
          + (negated ? " NOT LIKE " : " LIKE ")
          + pattern.toSql(fold)
          + ")";
    }
  }

  /**
   * {@code CASE [operand] WHEN ... THEN ... [ELSE ...] END}; the operand is {@code null} for the
   * searched form and the else {@code null} when left out.
   */
  record Case(Expr operand, List<When> whens, Expr otherwise, int offset) implements Expr {
    @Override
    public List<Expr> children() {
      List<Expr> children = new ArrayList<>();
      if (operand != null) {
        children.add(operand);
      }
      for (When when : whens) {
        children.add(when.condition());
        children.add(when.result());
      }
      if (otherwise != null) {
        children.add(otherwise);
      }
      return children;
    }

    @Override
    public String toSql(boolean fold) {
      StringBuilder text = new StringBuilder("CASE");
      if (operand != null) {
        text.append(' ').append(operand.toSql(fold));
      }
      for (When when : whens) {
        text.append(" WHEN ").append(when.condition().toSql(fold));
        text.append(" THEN ").append(when.result().toSql(fold));
      }
      if (otherwise != null) {
        text.append(" ELSE ").append(otherwise.toSql(fold));
      }
      return text.append(" END").toString();
    }
  }

  /** One WHEN ... THEN ... of a CASE. */
  record When(Expr condition, Expr result) {}

  /** {@code CAST(x AS type)}, the type as written. */
  record Cast(Expr operand, String type, int typeOffset, int offset) implements Expr {
    @Override
    public List<Expr> children() {
      return List.of(operand);
    }

    @Override
    public String toSql(boolean fold) {
      return "CAST(" + operand.toSql(fold) + " AS " + type.toUpperCase(Locale.ROOT) + ")";
    }
  }

  /**
   * A call, such as {@code lower(name)}, {@code count(*)} or {@code count(DISTINCT x)}; {@code
   * star} for {@code (*)}.
   */
  record Call(String name, boolean distinct, boolean star, List<Expr> arguments, int offset)
      implements Expr {
    @Override
    public List<Expr> children() {
      return arguments;
    }

    @Override
    public String toSql(boolean fold) {
      String function = name.toLowerCase(Locale.ROOT);
      if (star) {
        return function + "(*)";
      }
      return function + "(" + (distinct ? "DISTINCT " : "") + join(arguments, fold) + ")";
    }
  }

  /** An item of the select list, with its alias or {@code null}. */
  record SelectItem(Expr expression, String alias) {}

  /** An item of ORDER BY; {@code nullsFirst} is {@code null} when left to the default. */
  record OrderItem(Expr expression, boolean descending, Boolean nullsFirst) {}

  /** The table of FROM, with its alias or {@code null}. */
  record TableRef(List<String> name, String alias, int offset) {}

  /**
   * A SELECT. {@code from}, {@code where}, {@code having} and {@code limit} are {@code null} when
   * left out.
   */
  record Select(
      boolean distinct,
      List<SelectItem> items,
      TableRef from,
      Expr where,
      List<Expr> groupBy,
      Expr having,
      List<OrderItem> orderBy,
      Long limit) {}
}
