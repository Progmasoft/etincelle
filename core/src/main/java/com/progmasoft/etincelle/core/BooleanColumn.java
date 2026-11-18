/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

/** A column of {@link DataType#BOOLEAN} values, one bit each. */
public final class BooleanColumn extends Column {
  private final long[] values;

  BooleanColumn(long[] values, int size, long[] validity) {
    super(size, validity);
    this.values = values;
  }

  @Override
  public DataType type() {
    return DataType.BOOLEAN;
  }

  /**
   * The value of {@code row}.
   *
   * @throws IllegalStateException when it is null
   */
  public boolean getBoolean(int row) {
    requireValue(row);
    return (values[row >>> 6] & (1L << row)) != 0;
  }

  @Override
  Object value(int row) {
    return (values[row >>> 6] & (1L << row)) != 0;
  }
}
