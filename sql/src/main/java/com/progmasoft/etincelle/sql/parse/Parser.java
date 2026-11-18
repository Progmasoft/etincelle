/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.parse;

import com.progmasoft.etincelle.sql.SqlException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reads one SELECT of Etincelle SQL:
 *
 * <pre>
 * SELECT [DISTINCT] item [, item ...]
 * [FROM table [[AS] alias]]
 * [WHERE condition]
 * [GROUP BY expression [, ...]]
 * [HAVING condition]
 * [ORDER BY expression [ASC | DESC] [NULLS FIRST | NULLS LAST] [, ...]]
 * [LIMIT count] [;]
 * </pre>
 *
 * <p>From the loosest binding to the tightest: OR; AND; NOT; comparisons, IS, IN, BETWEEN, LIKE;
 * {@code + - ||}; {@code * / %}; unary {@code + -}.
 */
public final class Parser {
  /** Words that cannot be a name without backquotes, nor an alias without AS. */
  private static final Set<String> RESERVED =
      Set.of(
          "SELECT",
          "DISTINCT",
          "FROM",
          "WHERE",
          "GROUP",
          "BY",
          "HAVING",
          "ORDER",
          "LIMIT",
          "AS",
          "AND",
          "OR",
          "NOT",
          "NULL",
          "TRUE",
          "FALSE",
          "IS",
          "IN",
          "BETWEEN",
          "LIKE",
          "CASE",
          "WHEN",
          "THEN",
          "ELSE",
          "END",
          "CAST",
          "ASC",
          "DESC",
          "NULLS",
          "JOIN",
          "ON",
          "UNION",
          "ALL");

  private final String sql;
  private final List<Token> tokens;
  private int at;

  private Parser(String sql) {
    this.sql = sql;
    this.tokens = Lexer.tokens(sql);
  }

  /**
   * The syntax tree of {@code sql}.
   *
   * @throws SqlException at the first place that does not fit the grammar
   */
  public static Ast.Select parse(String sql) {
    Parser parser = new Parser(sql);
    Ast.Select select = parser.select();
    parser.acceptSymbol(";");
    if (parser.peek().kind() != Token.Kind.END) {
      throw parser.error("the query ends here; found " + parser.describe(parser.peek()));
    }
    return select;
  }

  private Token peek() {
    return tokens.get(at);
  }

  private Token peek(int ahead) {
    return tokens.get(Math.min(at + ahead, tokens.size() - 1));
  }

  private Token take() {
    return tokens.get(at++);
  }

  private boolean accept(String keyword) {
    if (peek().is(keyword)) {
      at++;
      return true;
    }
    return false;
  }

  private boolean acceptSymbol(String symbol) {
    if (peek().isSymbol(symbol)) {
      at++;
      return true;
    }
    return false;
  }

  private void expect(String keyword) {
    if (!accept(keyword)) {
      throw error(keyword + " is expected; found " + describe(peek()));
    }
  }

  private void expectSymbol(String symbol) {
    if (!acceptSymbol(symbol)) {
      throw error("'" + symbol + "' is expected; found " + describe(peek()));
    }
  }

  private String describe(Token token) {
    return switch (token.kind()) {
      case END -> "the end of the query";
      case STRING -> "the string '" + token.text() + "'";
      case QUOTED -> "`" + token.text() + "`";
      default -> "'" + token.text() + "'";
    };
  }

  private SqlException error(String message) {
    return new SqlException(sql, peek().offset(), message);
  }

  /** Items separated by commas, at least one. */
  private <T> List<T> commaList(Supplier<T> item) {
    List<T> items = new ArrayList<>();
    do {
      items.add(item.get());
    } while (acceptSymbol(","));
    return List.copyOf(items);
  }

  /** The items after {@code first BY}, or none when the clause is left out. */
  private <T> List<T> byClause(String first, Supplier<T> item) {
    if (!accept(first)) {
      return List.of();
    }
    expect("BY");
    return commaList(item);
  }

  private Ast.Select select() {
    expect("SELECT");
    boolean distinct = accept("DISTINCT");
    accept("ALL");
    List<Ast.SelectItem> items = commaList(this::selectItem);
    Ast.TableRef from = from();
    Ast.Expr where = accept("WHERE") ? expression() : null;
    List<Ast.Expr> groupBy = byClause("GROUP", this::expression);
    Ast.Expr having = accept("HAVING") ? expression() : null;
    List<Ast.OrderItem> orderBy = byClause("ORDER", this::orderItem);
    Long limit = limit();
    if (peek().is("UNION")) {
      throw error("UNION is not supported");
    }
    return new Ast.Select(distinct, items, from, where, groupBy, having, orderBy, limit);
  }

  private Ast.TableRef from() {
    if (!accept("FROM")) {
      return null;
    }
    Ast.TableRef from = tableRef();
    if (peek().is("JOIN") || peek().is("ON")) {
      throw error("joins are not supported");
    }
    if (peek().isSymbol(",")) {
      throw error("a query reads one table");
    }
    return from;
  }

  private Long limit() {
    if (!accept("LIMIT")) {
      return null;
    }
    Token count = peek();
    if (count.kind() != Token.Kind.INTEGER) {
      throw error("LIMIT takes a whole number; found " + describe(count));
    }
    take();
    try {
      return Long.parseLong(count.text());
    } catch (NumberFormatException e) {
      throw new SqlException(sql, count.offset(), "LIMIT " + count.text() + " is too large");
    }
  }

  private Ast.SelectItem selectItem() {
    if (peek().isSymbol("*")) {
      return new Ast.SelectItem(new Ast.Star(null, take().offset()), null);
    }
    if (isNameToken(peek()) && peek(1).isSymbol(".") && peek(2).isSymbol("*")) {
      Token qualifier = take();
      take();
      take();
      return new Ast.SelectItem(new Ast.Star(qualifier.text(), qualifier.offset()), null);
    }
    Ast.Expr expression = expression();
    return new Ast.SelectItem(expression, alias());
  }

  private String alias() {
    if (accept("AS")) {
      Token name = peek();
      if (!isNameToken(name)) {
        throw error("AS is followed by a name; found " + describe(name));
      }
      take();
      return name.text();
    }
    if (isNameToken(peek())) {
      return take().text();
    }
    return null;
  }

  private boolean isNameToken(Token token) {
    return token.kind() == Token.Kind.QUOTED
        || (token.kind() == Token.Kind.WORD
            && !RESERVED.contains(token.text().toUpperCase(Locale.ROOT)));
  }

  private Ast.TableRef tableRef() {
    Token first = peek();
    List<String> name = new ArrayList<>();
    do {
      Token part = peek();
      if (!isNameToken(part)) {
        throw error("a table name is expected; found " + describe(part));
      }
      name.add(take().text());
    } while (acceptSymbol("."));
    return new Ast.TableRef(List.copyOf(name), alias(), first.offset());
  }

  private Ast.OrderItem orderItem() {
    Ast.Expr expression = expression();
    boolean descending = false;
    if (accept("DESC")) {
      descending = true;
    } else {
      accept("ASC");
    }
    Boolean nullsFirst = null;
    if (accept("NULLS")) {
      if (accept("FIRST")) {
        nullsFirst = true;
      } else if (accept("LAST")) {
        nullsFirst = false;
      } else {
        throw error("NULLS is followed by FIRST or LAST; found " + describe(peek()));
      }
    }
    return new Ast.OrderItem(expression, descending, nullsFirst);
  }

  /** An expression, from OR down. */
  public Ast.Expr expression() {
    return or();
  }

  private Ast.Expr or() {
    Ast.Expr left = and();
    while (peek().is("OR")) {
      int offset = take().offset();
      left = new Ast.Binary("OR", left, and(), offset);
    }
    return left;
  }

  private Ast.Expr and() {
    Ast.Expr left = not();
    while (peek().is("AND")) {
      int offset = take().offset();
      left = new Ast.Binary("AND", left, not(), offset);
    }
    return left;
  }

  private Ast.Expr not() {
    if (peek().is("NOT")) {
      int offset = take().offset();
      return new Ast.Unary("NOT", not(), offset);
    }
    return predicate();
  }

  private static final Set<String> COMPARISONS = Set.of("=", "<>", "!=", "<", "<=", ">", ">=");

  private Ast.Expr predicate() {
    Ast.Expr left = additive();
    while (true) {
      Token token = peek();
      if (token.kind() == Token.Kind.SYMBOL && COMPARISONS.contains(token.text())) {
        take();
        String operator = token.text().equals("!=") ? "<>" : token.text();
        left = new Ast.Binary(operator, left, additive(), token.offset());
      } else if (token.is("IS")) {
        take();
        boolean negated = accept("NOT");
        expect("NULL");
        left = new Ast.IsNull(left, negated, token.offset());
      } else if (isSetPredicate(token) || (token.is("NOT") && isSetPredicate(peek(1)))) {
        left = setPredicate(left, token.offset());
      } else {
        return left;
      }
    }
  }

  private static boolean isSetPredicate(Token token) {
    return token.is("IN") || token.is("BETWEEN") || token.is("LIKE");
  }

  /** [NOT] IN (...), [NOT] BETWEEN ... AND ... or [NOT] LIKE ... after {@code left}. */
  private Ast.Expr setPredicate(Ast.Expr left, int offset) {
    boolean negated = accept("NOT");
    Token word = take();
    if (word.is("IN")) {
      expectSymbol("(");
      List<Ast.Expr> items = commaList(this::expression);
      expectSymbol(")");
      return new Ast.InList(left, items, negated, offset);
    }
    if (word.is("BETWEEN")) {
      Ast.Expr low = additive();
      expect("AND");
      return new Ast.Between(left, low, additive(), negated, offset);
    }
    return new Ast.Like(left, additive(), negated, offset);
  }

  private Ast.Expr additive() {
    Ast.Expr left = multiplicative();
    while (peek().isSymbol("+") || peek().isSymbol("-") || peek().isSymbol("||")) {
      Token operator = take();
      left = new Ast.Binary(operator.text(), left, multiplicative(), operator.offset());
    }
    return left;
  }

  private Ast.Expr multiplicative() {
    Ast.Expr left = unary();
    while (peek().isSymbol("*") || peek().isSymbol("/") || peek().isSymbol("%")) {
      Token operator = take();
      left = new Ast.Binary(operator.text(), left, unary(), operator.offset());
    }
    return left;
  }

  private Ast.Expr unary() {
    if (peek().isSymbol("-") || peek().isSymbol("+")) {
      Token operator = take();
      Ast.Expr operand = unary();
      if (operator.text().equals("-")
          && operand instanceof Ast.NumberLiteral number
          && !number.text().startsWith("-")) {
        return new Ast.NumberLiteral("-" + number.text(), number.decimal(), operator.offset());
      }
      return new Ast.Unary(operator.text(), operand, operator.offset());
    }
    return primary();
  }

  private Ast.Expr primary() {
    Token token = peek();
    switch (token.kind()) {
      case INTEGER, DECIMAL -> {
        take();
        return new Ast.NumberLiteral(
            token.text(), token.kind() == Token.Kind.DECIMAL, token.offset());
      }
      case STRING -> {
        take();
        return new Ast.StringLiteral(token.text(), token.offset());
      }
      case SYMBOL -> {
        if (token.isSymbol("(")) {
          take();
          if (peek().is("SELECT")) {
            throw error("subqueries are not supported");
          }
          Ast.Expr inner = expression();
          expectSymbol(")");
          return inner;
        }
        throw error("an expression is expected; found " + describe(token));
      }
      case END -> throw error("an expression is expected; found the end of the query");
      default -> {
        return wordOrName(token);
      }
    }
  }

  private Ast.Expr wordOrName(Token token) {
    if (token.kind() == Token.Kind.WORD) {
      Ast.Expr keyword = keywordExpression(token);
      if (keyword != null) {
        return keyword;
      }
      if (!isNameToken(token)) {
        throw error("an expression is expected; found " + describe(token));
      }
      if (peek(1).isSymbol("(")) {
        return call();
      }
    }
    return name(token);
  }

  /** TRUE, FALSE, NULL, CASE or CAST at {@code token}, or {@code null} for none of them. */
  private Ast.Expr keywordExpression(Token token) {
    if (accept("TRUE") || accept("FALSE")) {
      return new Ast.BooleanLiteral(token.is("TRUE"), token.offset());
    }
    if (accept("NULL")) {
      return new Ast.NullLiteral(token.offset());
    }
    if (accept("CASE")) {
      return caseExpression(token.offset());
    }
    if (token.is("CAST") && peek(1).isSymbol("(")) {
      return cast(token);
    }
    return null;
  }

  private Ast.Expr cast(Token token) {
    take();
    take();
    Ast.Expr operand = expression();
    expect("AS");
    Token type = peek();
    if (type.kind() != Token.Kind.WORD) {
      throw error("a type is expected; found " + describe(type));
    }
    take();
    expectSymbol(")");
    return new Ast.Cast(operand, type.text(), type.offset(), token.offset());
  }

  private Ast.Expr name(Token token) {
    List<String> parts = new ArrayList<>();
    parts.add(take().text());
    while (peek().isSymbol(".")) {
      take();
      Token part = peek();
      if (!isNameToken(part)) {
        throw error("a name is expected after '.'; found " + describe(part));
      }
      parts.add(take().text());
    }
    return new Ast.Name(List.copyOf(parts), token.offset());
  }

  private Ast.Expr call() {
    Token name = take();
    take();
    if (acceptSymbol("*")) {
      expectSymbol(")");
      return new Ast.Call(name.text(), false, true, List.of(), name.offset());
    }
    boolean distinct = accept("DISTINCT");
    List<Ast.Expr> arguments = new ArrayList<>();
    if (!peek().isSymbol(")")) {
      do {
        arguments.add(expression());
      } while (acceptSymbol(","));
    }
    expectSymbol(")");
    return new Ast.Call(name.text(), distinct, false, List.copyOf(arguments), name.offset());
  }

  private Ast.Expr caseExpression(int offset) {
    Ast.Expr operand = peek().is("WHEN") ? null : expression();
    List<Ast.When> whens = new ArrayList<>();
    while (accept("WHEN")) {
      Ast.Expr condition = expression();
      expect("THEN");
      whens.add(new Ast.When(condition, expression()));
    }
    if (whens.isEmpty()) {
      throw error("CASE has at least one WHEN");
    }
    Ast.Expr otherwise = accept("ELSE") ? expression() : null;
    expect("END");
    return new Ast.Case(operand, List.copyOf(whens), otherwise, offset);
  }
}
