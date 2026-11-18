/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.parse;

import java.util.List;

/**
 * The syntax tree of a query, as the parser reads it. Each node keeps the offset of its first
 * character for errors.
 */
public final class Ast {
  private Ast() {}

  /** An expression. */
  public sealed interface Expr
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
  }

  /** A name, such as {@code price} or {@code t.price}, its parts as written. */
  public record Name(List<String> parts, int offset) implements Expr {}

  /** {@code *} or {@code t.*}; the qualifier is {@code null} for {@code *}. */
  public record Star(String qualifier, int offset) implements Expr {}

  /** A number as written; {@code decimal} when it has a point or an exponent. */
  public record NumberLiteral(String text, boolean decimal, int offset) implements Expr {}

  /** A string. */
  public record StringLiteral(String value, int offset) implements Expr {}

  /** TRUE or FALSE. */
  public record BooleanLiteral(boolean value, int offset) implements Expr {}

  /** NULL. */
  public record NullLiteral(int offset) implements Expr {}

  /** {@code -x}, {@code +x} or {@code NOT x}. */
  public record Unary(String operator, Expr operand, int offset) implements Expr {}

  /** An operator between two expressions: arithmetic, ||, a comparison, AND or OR. */
  public record Binary(String operator, Expr left, Expr right, int offset) implements Expr {}

  /** {@code x IS NULL} or {@code x IS NOT NULL}. */
  public record IsNull(Expr operand, boolean negated, int offset) implements Expr {}

  /** {@code x [NOT] IN (a, b, ...)}. */
  public record InList(Expr operand, List<Expr> items, boolean negated, int offset)
      implements Expr {}

  /** {@code x [NOT] BETWEEN low AND high}. */
  public record Between(Expr operand, Expr low, Expr high, boolean negated, int offset)
      implements Expr {}

  /** {@code x [NOT] LIKE pattern}. */
  public record Like(Expr operand, Expr pattern, boolean negated, int offset) implements Expr {}

  /**
   * {@code CASE [operand] WHEN ... THEN ... [ELSE ...] END}; the operand is {@code null} for the
   * searched form and the else {@code null} when left out.
   */
  public record Case(Expr operand, List<When> whens, Expr otherwise, int offset) implements Expr {}

  /** One WHEN ... THEN ... of a CASE. */
  public record When(Expr condition, Expr result) {}

  /** {@code CAST(x AS type)}, the type as written. */
  public record Cast(Expr operand, String type, int typeOffset, int offset) implements Expr {}

  /**
   * A call, such as {@code lower(name)}, {@code count(*)} or {@code count(DISTINCT x)}; {@code
   * star} for {@code (*)}.
   */
  public record Call(String name, boolean distinct, boolean star, List<Expr> arguments, int offset)
      implements Expr {}

  /** An item of the select list, with its alias or {@code null}. */
  public record SelectItem(Expr expression, String alias) {}

  /** An item of ORDER BY; {@code nullsFirst} is {@code null} when left to the default. */
  public record OrderItem(Expr expression, boolean descending, Boolean nullsFirst) {}

  /** The table of FROM, with its alias or {@code null}. */
  public record TableRef(List<String> name, String alias, int offset) {}

  /**
   * A SELECT. {@code from}, {@code where}, {@code having} and {@code limit} are {@code null} when
   * left out.
   */
  public record Select(
      boolean distinct,
      List<SelectItem> items,
      TableRef from,
      Expr where,
      List<Expr> groupBy,
      Expr having,
      List<OrderItem> orderBy,
      Long limit) {}
}
