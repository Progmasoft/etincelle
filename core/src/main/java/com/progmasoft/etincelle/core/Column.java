/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

import java.util.Objects;

/**
 * The values of one column of a {@link Batch}: a typed array and a validity bitmap, whose bit
 * {@code i} is set when value {@code i} is not null. A column does not change once built.
 */
public abstract sealed class Column
    permits IntColumn, LongColumn, DoubleColumn, BooleanColumn, StringColumn {
  private final int size;
  private final long[] validity;

  Column(int size, long[] validity) {
    this.size = size;
    this.validity = validity;
  }

  /** The type of the values. */
  public abstract DataType type();

  /** How many values the column holds. */
  public final int size() {
    return size;
  }

  /** Whether the value of {@code row} is null. */
  public final boolean isNull(int row) {
    Objects.checkIndex(row, size);
    return (validity[row >>> 6] & (1L << row)) == 0;
  }

  /** How many values are null. */
  public final int nullCount() {
    int set = 0;
    for (long word : validity) {
      set += Long.bitCount(word);
    }
    return size - set;
  }

  /** The value of {@code row} as a Java object, or {@code null}. */
  public final Object get(int row) {
    return isNull(row) ? null : value(row);
  }

  /** The value of {@code row}, which is not null, as a Java object. */
  abstract Object value(int row);

  /**
   * Checks that the value of {@code row} is not null before a typed getter reads it.
   *
   * @throws IllegalStateException when it is null
   */
  final void requireValue(int row) {
    if (isNull(row)) {
      throw new IllegalStateException("the value of row " + row + " is null");
    }
  }

  /** A builder of a column of {@code type}, for about {@code capacity} values. */
  public static ColumnBuilder builder(DataType type, int capacity) {
    return new ColumnBuilder(type, capacity);
  }
}
