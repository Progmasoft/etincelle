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

  private Ast.Select select() {
    expect("SELECT");
    boolean distinct = accept("DISTINCT");
    accept("ALL");
    List<Ast.SelectItem> items = new ArrayList<>();
    do {
      items.add(selectItem());
    } while (acceptSymbol(","));
    Ast.TableRef from = accept("FROM") ? tableRef() : null;
    if (peek().is("JOIN") || (peek().is("ON"))) {
      throw error("joins are not supported");
    }
    if (peek().isSymbol(",") && from != null) {
      throw error("a query reads one table");
    }
    Ast.Expr where = accept("WHERE") ? expression() : null;
    List<Ast.Expr> groupBy = new ArrayList<>();
    if (accept("GROUP")) {
      expect("BY");
      do {
        groupBy.add(expression());
      } while (acceptSymbol(","));
    }
    Ast.Expr having = accept("HAVING") ? expression() : null;
    List<Ast.OrderItem> orderBy = new ArrayList<>();
    if (accept("ORDER")) {
      expect("BY");
      do {
        orderBy.add(orderItem());
      } while (acceptSymbol(","));
    }
    Long limit = null;
    if (accept("LIMIT")) {
      Token count = peek();
      if (count.kind() != Token.Kind.INTEGER) {
        throw error("LIMIT takes a whole number; found " + describe(count));
      }
      take();
      try {
        limit = Long.parseLong(count.text());
      } catch (NumberFormatException e) {
        throw new SqlException(sql, count.offset(), "LIMIT " + count.text() + " is too large");
      }
    }
    if (peek().is("UNION")) {
      throw error("UNION is not supported");
    }
    return new Ast.Select(
        distinct,
        List.copyOf(items),
        from,
        where,
        List.copyOf(groupBy),
        having,
        List.copyOf(orderBy),
        limit);
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

  private Ast.Expr predicate() {
    Ast.Expr left = additive();
    while (true) {
      Token token = peek();
      if (token.kind() == Token.Kind.SYMBOL
          && Set.of("=", "<>", "!=", "<", "<=", ">", ">=").contains(token.text())) {
        take();
        String operator = token.text().equals("!=") ? "<>" : token.text();
        left = new Ast.Binary(operator, left, additive(), token.offset());
      } else if (token.is("IS")) {
        take();
        boolean negated = accept("NOT");
        expect("NULL");
        left = new Ast.IsNull(left, negated, token.offset());
      } else if (token.is("IN")
          || token.is("BETWEEN")
          || token.is("LIKE")
          || (token.is("NOT")
              && (peek(1).is("IN") || peek(1).is("BETWEEN") || peek(1).is("LIKE")))) {
        boolean negated = accept("NOT");
        Token word = take();
        if (word.is("IN")) {
          expectSymbol("(");
          List<Ast.Expr> items = new ArrayList<>();
          do {
            items.add(expression());
          } while (acceptSymbol(","));
          expectSymbol(")");
          left = new Ast.InList(left, List.copyOf(items), negated, token.offset());
        } else if (word.is("BETWEEN")) {
          Ast.Expr low = additive();
          expect("AND");
          left = new Ast.Between(left, low, additive(), negated, token.offset());
        } else {
          left = new Ast.Like(left, additive(), negated, token.offset());
        }
      } else {
        return left;
      }
    }
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
      if (accept("TRUE")) {
        return new Ast.BooleanLiteral(true, token.offset());
      }
      if (accept("FALSE")) {
        return new Ast.BooleanLiteral(false, token.offset());
      }
      if (accept("NULL")) {
        return new Ast.NullLiteral(token.offset());
      }
      if (accept("CASE")) {
        return caseExpression(token.offset());
      }
      if (token.is("CAST") && peek(1).isSymbol("(")) {
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
      if (!isNameToken(token)) {
        throw error("an expression is expected; found " + describe(token));
      }
      if (peek(1).isSymbol("(")) {
        return call();
      }
    }
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
