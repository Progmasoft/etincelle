/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

/** A column of {@link DataType#LONG} values. */
public final class LongColumn extends Column {
  private final long[] values;

  LongColumn(long[] values, int size, long[] validity) {
    super(size, validity);
    this.values = values;
  }

  @Override
  public DataType type() {
    return DataType.LONG;
  }

  /**
   * The value of {@code row}.
   *
   * @throws IllegalStateException when it is null
   */
  public long getLong(int row) {
    requireValue(row);
    return values[row];
  }

  @Override
  Object value(int row) {
    return values[row];
  }
}
