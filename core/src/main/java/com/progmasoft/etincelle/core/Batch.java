/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Rows of a {@link Schema} held column by column: one {@link Column} for each field. */
public final class Batch {
  private final Schema schema;
  private final List<Column> columns;
  private final int rowCount;

  /**
   * A batch of {@code columns}, one for each field of {@code schema}, in order, all of the same
   * size and of their field's type; a column of a field that is not nullable holds no null.
   */
  public Batch(Schema schema, List<Column> columns) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.columns = List.copyOf(columns);
    if (this.columns.size() != schema.size()) {
      throw new IllegalArgumentException(
          "the schema has " + schema.size() + " fields and the batch " + this.columns.size());
    }
    int rows = this.columns.isEmpty() ? 0 : this.columns.get(0).size();
    for (int i = 0; i < this.columns.size(); i++) {
      Field field = schema.fields().get(i);
      Column column = this.columns.get(i);
      if (!column.type().equals(field.type())) {
        throw new IllegalArgumentException(
            field.name()
                + " is "
                + field.type().sqlName()
                + ", its column "
                + column.type().sqlName());
      }
      if (column.size() != rows) {
        throw new IllegalArgumentException(
            "the columns have " + rows + " and " + column.size() + " values");
      }
      if (!field.nullable() && column.nullCount() > 0) {
        throw new IllegalArgumentException(field.name() + " is NOT NULL and holds a null");
      }
    }
    this.rowCount = rows;
  }

  /** A batch of no columns and {@code rowCount} rows, such as the input of SELECT 1. */
  public static Batch ofRows(int rowCount) {
    return new Batch(rowCount);
  }

  private Batch(int rowCount) {
    if (rowCount < 0) {
      throw new IllegalArgumentException("a row count is not negative: " + rowCount);
    }
    this.schema = new Schema(List.of());
    this.columns = List.of();
    this.rowCount = rowCount;
  }

  /** The schema of the rows. */
  public Schema schema() {
    return schema;
  }

  /** The columns, in the order of the schema. */
  public List<Column> columns() {
    return columns;
  }

  /** The column at {@code index}. */
  public Column column(int index) {
    return columns.get(index);
  }

  /** How many rows there are. */
  public int rowCount() {
    return rowCount;
  }

  /** The values of {@code row}, in the order of the schema; a null value is {@code null}. */
  public List<Object> row(int row) {
    Objects.checkIndex(row, rowCount);
    List<Object> values = new ArrayList<>(columns.size());
    for (Column column : columns) {
      values.add(column.get(row));
    }
    return values;
  }
}
