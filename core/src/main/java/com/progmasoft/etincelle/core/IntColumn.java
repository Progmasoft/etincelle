/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

/** A column of {@link DataType#INT} values. */
public final class IntColumn extends Column {
  private final int[] values;

  IntColumn(int[] values, int size, long[] validity) {
    super(size, validity);
    this.values = values;
  }

  @Override
  public DataType type() {
    return DataType.INT;
  }

  /**
   * The value of {@code row}.
   *
   * @throws IllegalStateException when it is null
   */
  public int getInt(int row) {
    requireValue(row);
    return values[row];
  }

  @Override
  Object value(int row) {
    return values[row];
  }
}
