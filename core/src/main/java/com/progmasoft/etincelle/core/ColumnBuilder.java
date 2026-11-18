/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

import java.util.Arrays;
import java.util.Objects;

/** Appends the values of one column, then builds it; a builder builds one column. */
public final class ColumnBuilder {
  private final DataType type;
  private int size;
  private long[] validity;
  private int[] ints;
  private long[] longs;
  private double[] doubles;
  private long[] booleans;
  private String[] strings;
  private boolean built;

  ColumnBuilder(DataType type, int capacity) {
    this.type = Objects.requireNonNull(type, "type");
    if (capacity < 0) {
      throw new IllegalArgumentException("a capacity is not negative: " + capacity);
    }
    int room = Math.max(capacity, 8);
    validity = new long[words(room)];
    switch (type) {
      case DataType.IntType t -> ints = new int[room];
      case DataType.LongType t -> longs = new long[room];
      case DataType.DoubleType t -> doubles = new double[room];
      case DataType.BooleanType t -> booleans = new long[words(room)];
      case DataType.StringType t -> strings = new String[room];
    }
  }

  private static int words(int bits) {
    return (bits + 63) >>> 6;
  }

  /** The type of the column. */
  public DataType type() {
    return type;
  }

  /** How many values have been appended. */
  public int size() {
    return size;
  }

  /** Whether a column of {@code type} takes {@code value}, which is not null. */
  static boolean fits(DataType type, Object value) {
    return switch (type) {
      case DataType.IntType t -> value instanceof Integer;
      case DataType.LongType t -> value instanceof Long || value instanceof Integer;
      case DataType.DoubleType t -> value instanceof Double;
      case DataType.BooleanType t -> value instanceof Boolean;
      case DataType.StringType t -> value instanceof String;
    };
  }

  /** Appends a null. */
  public ColumnBuilder appendNull() {
    grow();
    size++;
    return this;
  }

  /**
   * Appends {@code value}: {@code null}, or an {@link Integer}, {@link Long}, {@link Double},
   * {@link Boolean} or {@link String} as the type of the column takes.
   *
   * @throws IllegalArgumentException when the value does not fit the type
   */
  public ColumnBuilder append(Object value) {
    if (value == null) {
      return appendNull();
    }
    switch (type) {
      case DataType.IntType t when value instanceof Integer v -> appendInt(v);
      case DataType.LongType t when value instanceof Long v -> appendLong(v);
      case DataType.LongType t when value instanceof Integer v -> appendLong(v);
      case DataType.DoubleType t when value instanceof Double v -> appendDouble(v);
      case DataType.BooleanType t when value instanceof Boolean v -> appendBoolean(v);
      case DataType.StringType t when value instanceof String v -> appendString(v);
      default ->
          throw new IllegalArgumentException(
              "a "
                  + type.sqlName()
                  + " column cannot hold the "
                  + value.getClass().getSimpleName()
                  + " "
                  + value);
    }
    return this;
  }

  /** Appends an INT. */
  public ColumnBuilder appendInt(int value) {
    require(DataType.INT);
    grow();
    ints[size] = value;
    return set();
  }

  /** Appends a BIGINT. */
  public ColumnBuilder appendLong(long value) {
    require(DataType.LONG);
    grow();
    longs[size] = value;
    return set();
  }

  /** Appends a DOUBLE. */
  public ColumnBuilder appendDouble(double value) {
    require(DataType.DOUBLE);
    grow();
    doubles[size] = value;
    return set();
  }

  /** Appends a BOOLEAN. */
  public ColumnBuilder appendBoolean(boolean value) {
    require(DataType.BOOLEAN);
    grow();
    if (value) {
      booleans[size >>> 6] |= 1L << size;
    }
    return set();
  }

  /** Appends a STRING, which is not null; {@link #appendNull()} appends a null. */
  public ColumnBuilder appendString(String value) {
    Objects.requireNonNull(value, "value");
    require(DataType.STRING);
    grow();
    strings[size] = value;
    return set();
  }

  private void require(DataType expected) {
    if (!type.equals(expected)) {
      throw new IllegalArgumentException(
          "a " + type.sqlName() + " column cannot hold a " + expected.sqlName());
    }
  }

  private ColumnBuilder set() {
    validity[size >>> 6] |= 1L << size;
    size++;
    return this;
  }

  private void grow() {
    if (built) {
      throw new IllegalStateException("the column is already built");
    }
    int room = validity.length * 64;
    if (size < room
        && (ints == null || size < ints.length)
        && (longs == null || size < longs.length)
        && (doubles == null || size < doubles.length)
        && (strings == null || size < strings.length)) {
      return;
    }
    if (size == Integer.MAX_VALUE - 8) {
      throw new IllegalStateException("a column holds fewer than 2^31 - 8 values");
    }
    int next = (int) Math.min((long) size * 2, Integer.MAX_VALUE - 8);
    validity = Arrays.copyOf(validity, words(next));
    if (ints != null) {
      ints = Arrays.copyOf(ints, next);
    } else if (longs != null) {
      longs = Arrays.copyOf(longs, next);
    } else if (doubles != null) {
      doubles = Arrays.copyOf(doubles, next);
    } else if (booleans != null) {
      booleans = Arrays.copyOf(booleans, words(next));
    } else {
      strings = Arrays.copyOf(strings, next);
    }
  }

  /** The column of the values appended; the builder takes no more. */
  public Column build() {
    if (built) {
      throw new IllegalStateException("the column is already built");
    }
    built = true;
    long[] bits = Arrays.copyOf(validity, words(size));
    return switch (type) {
      case DataType.IntType t -> new IntColumn(Arrays.copyOf(ints, size), size, bits);
      case DataType.LongType t -> new LongColumn(Arrays.copyOf(longs, size), size, bits);
      case DataType.DoubleType t -> new DoubleColumn(Arrays.copyOf(doubles, size), size, bits);
      case DataType.BooleanType t ->
          new BooleanColumn(Arrays.copyOf(booleans, words(size)), size, bits);
      case DataType.StringType t -> new StringColumn(Arrays.copyOf(strings, size), size, bits);
    };
  }
}
