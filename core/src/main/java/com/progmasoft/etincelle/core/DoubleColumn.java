/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

/** A column of {@link DataType#DOUBLE} values. */
public final class DoubleColumn extends Column {
  private final double[] values;

  DoubleColumn(double[] values, int size, long[] validity) {
    super(size, validity);
    this.values = values;
  }

  @Override
  public DataType type() {
    return DataType.DOUBLE;
  }

  /**
   * The value of {@code row}.
   *
   * @throws IllegalStateException when it is null
   */
  public double getDouble(int row) {
    requireValue(row);
    return values[row];
  }

  @Override
  Object value(int row) {
    return values[row];
  }
}
