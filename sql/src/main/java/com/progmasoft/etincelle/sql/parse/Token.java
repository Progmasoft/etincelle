/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.parse;

/** One token of a query, with the offset of its first character. */
public record Token(Kind kind, String text, int offset) {
  /** The kinds of token. */
  public enum Kind {
    /** A name without backquotes; it may be a keyword. */
    WORD,
    /** A name between backquotes, never a keyword; the text is the name without them. */
    QUOTED,
    /** Digits. */
    INTEGER,
    /** A number with a point or an exponent. */
    DECIMAL,
    /** A string between single quotes; the text is the string without them. */
    STRING,
    /** An operator or punctuation. */
    SYMBOL,
    /** The end of the query. */
    END,
  }

  /** Whether the token is the keyword {@code keyword}, case aside. */
  public boolean is(String keyword) {
    return kind == Kind.WORD && text.equalsIgnoreCase(keyword);
  }

  /** Whether the token is the symbol {@code symbol}. */
  public boolean isSymbol(String symbol) {
    return kind == Kind.SYMBOL && text.equals(symbol);
  }
}
