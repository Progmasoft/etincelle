/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

/** A column of {@link DataType#STRING} values. */
public final class StringColumn extends Column {
  private final String[] values;

  StringColumn(String[] values, int size, long[] validity) {
    super(size, validity);
    this.values = values;
  }

  @Override
  public DataType type() {
    return DataType.STRING;
  }

  /**
   * The value of {@code row}.
   *
   * @throws IllegalStateException when it is null
   */
  public String getString(int row) {
    requireValue(row);
    return values[row];
  }

  @Override
  Object value(int row) {
    return values[row];
  }
}
