/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql;

/**
 * A query Etincelle SQL cannot read, resolve or run, with the line and column of the place it is
 * about when there is one.
 */
public final class SqlException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** The line, 1-based, or 0 when the error has no place. */
  private final int line;

  /** The column, 1-based, or 0 when the error has no place. */
  private final int column;

  /** An error at {@code offset} of {@code sql}; a negative offset has no place. */
  public SqlException(String sql, int offset, String message) {
    super(place(sql, offset) + message);
    if (offset < 0) {
      this.line = 0;
      this.column = 0;
    } else {
      int[] at = lineAndColumn(sql, offset);
      this.line = at[0];
      this.column = at[1];
    }
  }

  private static String place(String sql, int offset) {
    if (offset < 0) {
      return "";
    }
    int[] at = lineAndColumn(sql, offset);
    return "line " + at[0] + ", column " + at[1] + ": ";
  }

  private static int[] lineAndColumn(String sql, int offset) {
    int line = 1;
    int column = 1;
    for (int i = 0; i < Math.min(offset, sql.length()); i++) {
      if (sql.charAt(i) == '\n') {
        line++;
        column = 1;
      } else {
        column++;
      }
    }
    return new int[] {line, column};
  }

  /** The line of the place, 1-based, or 0 when the error has none. */
  public int line() {
    return line;
  }

  /** The column of the place, 1-based, or 0 when the error has none. */
  public int column() {
    return column;
  }
}
