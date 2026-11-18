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

  /** The character at {@code index}, or 0 past the end, so a look ahead needs no bounds check. */
  private char charAt(int index) {
    return index < sql.length() ? sql.charAt(index) : 0;
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isWordStart(char c) {
    return c == '_' || Character.isLetter(c);
  }

  private Token next() {
    char c = sql.charAt(at);
    if (isWordStart(c)) {
      return word();
    }
    if (isDigit(c) || (c == '.' && isDigit(charAt(at + 1)))) {
      return number();
    }
    if (c == '\'') {
      return quoted(at, '\'', Token.Kind.STRING, "the string is not closed");
    }
    if (c == '`') {
      return backquoted();
    }
    return symbol();
  }

  private Token word() {
    int start = at;
    while (isWordStart(charAt(at)) || Character.isDigit(charAt(at))) {
      at++;
    }
    return new Token(Token.Kind.WORD, sql.substring(start, at), start);
  }

  private Token backquoted() {
    int start = at;
    Token name = quoted(start, '`', Token.Kind.QUOTED, "the backquote is not closed");
    if (name.text().isEmpty()) {
      throw new SqlException(sql, start, "a name is not empty");
    }
    return name;
  }

  private Token symbol() {
    for (String symbol : SYMBOLS) {
      if (sql.startsWith(symbol, at)) {
        int start = at;
        at += symbol.length();
        return new Token(Token.Kind.SYMBOL, symbol, start);
      }
    }
    throw new SqlException(sql, at, "unexpected character '" + sql.charAt(at) + "'");
  }

  private void skipDigits() {
    while (isDigit(charAt(at))) {
      at++;
    }
  }

  private Token number() {
    int start = at;
    skipDigits();
    boolean decimal = false;
    if (charAt(at) == '.') {
      decimal = true;
      at++;
      skipDigits();
    }
    decimal |= exponent();
    if (isWordStart(charAt(at))) {
      throw new SqlException(sql, at, "a number is followed by a letter");
    }
    return new Token(
        decimal ? Token.Kind.DECIMAL : Token.Kind.INTEGER, sql.substring(start, at), start);
  }

  /** Reads an exponent, such as e10 or E-3, if one follows; an e without digits is left alone. */
  private boolean exponent() {
    if (charAt(at) != 'e' && charAt(at) != 'E') {
      return false;
    }
    int digits = charAt(at + 1) == '+' || charAt(at + 1) == '-' ? at + 2 : at + 1;
    if (!isDigit(charAt(digits))) {
      return false;
    }
    at = digits;
    skipDigits();
    return true;
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
