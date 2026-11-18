/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

/**
 * A value an expression cannot compute, such as an overflow or a division by zero, at the offset of
 * the expression in the query; the session turns it into an SqlException.
 */
public final class EvaluationException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** The offset of the expression in the query. */
  private final int offset;

  /** An error about the expression at {@code offset}. */
  public EvaluationException(int offset, String message) {
    super(message);
    this.offset = offset;
  }

  /** The offset of the expression in the query. */
  public int offset() {
    return offset;
  }
}
