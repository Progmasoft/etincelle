/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.parse;

import com.progmasoft.etincelle.sql.SqlException;
import java.util.ArrayList;
import java.util.List;

/** Cuts a query into tokens. Comments are -- to the end of the line and /* ... *&#47;. */
public final class Lexer {
  private static final String[] SYMBOLS = {
    "<=", ">=", "<>", "!=", "||", "=", "<", ">", "+", "-", "*", "/", "%", "(", ")", ",", ".", ";"
  };

  private final String sql;
  private int at;

  private Lexer(String sql) {
    this.sql = sql;
  }

  /**
   * The tokens of {@code sql}, ending with an {@link Token.Kind#END} token.
   *
   * @throws SqlException at the first character that starts no token
   */
  public static List<Token> tokens(String sql) {
    return new Lexer(sql).run();
  }

  private List<Token> run() {
    List<Token> tokens = new ArrayList<>();
    while (true) {
      skipSpaceAndComments();
      if (at >= sql.length()) {
        tokens.add(new Token(Token.Kind.END, "", at));
        return tokens;
      }
      tokens.add(next());
    }
  }

  private void skipSpaceAndComments() {
    while (at < sql.length()) {
      char c = sql.charAt(at);
      if (Character.isWhitespace(c)) {
        at++;
      } else if (sql.startsWith("--", at)) {
        while (at < sql.length() && sql.charAt(at) != '\n') {
          at++;
        }
      } else if (sql.startsWith("/*", at)) {
        int end = sql.indexOf("*/", at + 2);
        if (end < 0) {
          throw new SqlException(sql, at, "the comment is not closed");
        }
        at = end + 2;
      } else {
        return;
      }
    }
  }

  private Token next() {
    int start = at;
    char c = sql.charAt(at);
    if (c == '_' || Character.isLetter(c)) {
      while (at < sql.length()
          && (sql.charAt(at) == '_' || Character.isLetterOrDigit(sql.charAt(at)))) {
        at++;
      }
      return new Token(Token.Kind.WORD, sql.substring(start, at), start);
    }
    if (c >= '0' && c <= '9'
        || (c == '.' && at + 1 < sql.length() && isDigit(sql.charAt(at + 1)))) {
      return number(start);
    }
    if (c == '\'') {
      return quoted(start, '\'', Token.Kind.STRING, "the string is not closed");
    }
    if (c == '`') {
      Token name = quoted(start, '`', Token.Kind.QUOTED, "the backquote is not closed");
      if (name.text().isEmpty()) {
        throw new SqlException(sql, start, "a name is not empty");
      }
      return name;
    }
    for (String symbol : SYMBOLS) {
      if (sql.startsWith(symbol, at)) {
        at += symbol.length();
        return new Token(Token.Kind.SYMBOL, symbol, start);
      }
    }
    throw new SqlException(sql, start, "unexpected character '" + c + "'");
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private Token number(int start) {
    boolean decimal = false;
    while (at < sql.length() && isDigit(sql.charAt(at))) {
      at++;
    }
    if (at < sql.length() && sql.charAt(at) == '.') {
      decimal = true;
      at++;
      while (at < sql.length() && isDigit(sql.charAt(at))) {
        at++;
      }
    }
    if (at < sql.length() && (sql.charAt(at) == 'e' || sql.charAt(at) == 'E')) {
      int mark = at;
      at++;
      if (at < sql.length() && (sql.charAt(at) == '+' || sql.charAt(at) == '-')) {
        at++;
      }
      if (at < sql.length() && isDigit(sql.charAt(at))) {
        decimal = true;
        while (at < sql.length() && isDigit(sql.charAt(at))) {
          at++;
        }
      } else {
        at = mark;
      }
    }
    if (at < sql.length() && (sql.charAt(at) == '_' || Character.isLetter(sql.charAt(at)))) {
      throw new SqlException(sql, at, "a number is followed by a letter");
    }
    return new Token(
        decimal ? Token.Kind.DECIMAL : Token.Kind.INTEGER, sql.substring(start, at), start);
  }

  private Token quoted(int start, char quote, Token.Kind kind, String unclosed) {
    StringBuilder text = new StringBuilder();
    at++;
    while (true) {
      if (at >= sql.length()) {
        throw new SqlException(sql, start, unclosed);
      }
      char c = sql.charAt(at);
      if (c == quote) {
        if (at + 1 < sql.length() && sql.charAt(at + 1) == quote) {
          text.append(quote);
          at += 2;
          continue;
        }
        at++;
        return new Token(kind, text.toString(), start);
      }
      text.append(c);
      at++;
    }
  }
}
