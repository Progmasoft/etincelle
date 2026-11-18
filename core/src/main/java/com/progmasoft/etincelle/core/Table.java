/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Rows of a {@link Schema} in batches. A table does not change once built. */
public final class Table {
  /** How many rows a batch of {@link Builder} holds. */
  public static final int BATCH_ROWS = 4096;

  private final Schema schema;
  private final List<Batch> batches;

  /** A table of {@code batches}, each of {@code schema}. */
  public Table(Schema schema, List<Batch> batches) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.batches = List.copyOf(batches);
    for (Batch batch : this.batches) {
      if (!batch.schema().equals(schema)) {
        throw new IllegalArgumentException(
            "a batch of " + batch.schema() + " in a table of " + schema);
      }
    }
  }

  /** A builder of a table of {@code schema}, row by row. */
  public static Builder builder(Schema schema) {
    return new Builder(schema);
  }

  /** The schema of the rows. */
  public Schema schema() {
    return schema;
  }

  /** The batches, in order. */
  public List<Batch> batches() {
    return batches;
  }

  /** How many rows there are. */
  public long rowCount() {
    long rows = 0;
    for (Batch batch : batches) {
      rows += batch.rowCount();
    }
    return rows;
  }

  /** Every row, in order; a null value is {@code null}. */
  public List<List<Object>> rows() {
    List<List<Object>> rows = new ArrayList<>();
    for (Batch batch : batches) {
      for (int row = 0; row < batch.rowCount(); row++) {
        rows.add(batch.row(row));
      }
    }
    return rows;
  }

  /**
   * The rows as a text table, a header and one line a row, at most {@code maxRows} of them; NULL
   * stands for a null value.
   */
  public String format(int maxRows) {
    if (maxRows < 0) {
      throw new IllegalArgumentException("a row count is not negative: " + maxRows);
    }
    List<List<String>> cells = cells(maxRows);
    int[] widths = widths(cells);
    String rule = rule(widths);
    StringBuilder text = new StringBuilder(rule);
    for (int i = 0; i < cells.size(); i++) {
      line(text, cells.get(i), widths);
      if (i == 0) {
        text.append(rule);
      }
    }
    text.append(rule);
    long shown = cells.size() - 1;
    if (rowCount() > shown) {
      text.append("only the first ")
          .append(shown)
          .append(" of ")
          .append(rowCount())
          .append(" rows\n");
    }
    return text.toString();
  }

  /** The header, then the text of at most {@code maxRows} rows. */
  private List<List<String>> cells(int maxRows) {
    List<List<String>> cells = new ArrayList<>();
    List<String> header = new ArrayList<>();
    for (Field field : schema.fields()) {
      header.add(field.name());
    }
    cells.add(header);
    for (Batch batch : batches) {
      for (int row = 0; row < batch.rowCount() && cells.size() <= maxRows; row++) {
        List<String> line = new ArrayList<>();
        for (Object value : batch.row(row)) {
          line.add(value == null ? "NULL" : String.valueOf(value));
        }
        cells.add(line);
      }
    }
    return cells;
  }

  private static int width(String cell) {
    return cell.codePointCount(0, cell.length());
  }

  private int[] widths(List<List<String>> cells) {
    int[] widths = new int[schema.size()];
    for (List<String> line : cells) {
      for (int i = 0; i < line.size(); i++) {
        widths[i] = Math.max(widths[i], width(line.get(i)));
      }
    }
    return widths;
  }

  private static String rule(int[] widths) {
    StringBuilder rule = new StringBuilder("+");
    for (int width : widths) {
      rule.append("-".repeat(width + 2)).append('+');
    }
    return rule.append('\n').toString();
  }

  private static void line(StringBuilder text, List<String> cells, int[] widths) {
    text.append('|');
    for (int column = 0; column < cells.size(); column++) {
      String cell = cells.get(column);
      text.append(' ').append(cell).append(" ".repeat(widths[column] - width(cell))).append(" |");
    }
    text.append('\n');
  }

  @Override
  public String toString() {
    return format(20);
  }

  /** Appends rows and cuts them into batches of {@link #BATCH_ROWS}. */
  public static final class Builder {
    private final Schema schema;
    private final List<Batch> batches = new ArrayList<>();
    private List<ColumnBuilder> columns;

    private Builder(Schema schema) {
      this.schema = Objects.requireNonNull(schema, "schema");
      if (schema.size() == 0) {
        throw new IllegalArgumentException("a table has at least one column");
      }
      columns = newColumns();
    }

    private List<ColumnBuilder> newColumns() {
      List<ColumnBuilder> builders = new ArrayList<>(schema.size());
      for (Field field : schema.fields()) {
        builders.add(Column.builder(field.type(), BATCH_ROWS));
      }
      return builders;
    }

    /**
     * Appends a row of {@code values}, one for each field in order: {@code null}, or an {@link
     * Integer}, {@link Long}, {@link Double}, {@link Boolean} or {@link String} as its type takes.
     *
     * @throws IllegalArgumentException when the row does not fit the schema
     */
    public Builder row(Object... values) {
      if (values.length != schema.size()) {
        throw new IllegalArgumentException(
            "a row of " + schema + " has " + schema.size() + " values, not " + values.length);
      }
      for (int i = 0; i < values.length; i++) {
        Field field = schema.fields().get(i);
        if (values[i] == null && !field.nullable()) {
          throw new IllegalArgumentException(field.name() + " is NOT NULL");
        }
        if (values[i] != null && !ColumnBuilder.fits(field.type(), values[i])) {
          throw new IllegalArgumentException(
              field.name()
                  + " is "
                  + field.type().sqlName()
                  + " and cannot hold the "
                  + values[i].getClass().getSimpleName()
                  + " "
                  + values[i]);
        }
      }
      for (int i = 0; i < values.length; i++) {
        columns.get(i).append(values[i]);
      }
      if (columns.get(0).size() == BATCH_ROWS) {
        flush();
      }
      return this;
    }

    private void flush() {
      List<Column> built = new ArrayList<>(columns.size());
      for (ColumnBuilder builder : columns) {
        built.add(builder.build());
      }
      batches.add(new Batch(schema, built));
      columns = newColumns();
    }

    /** The table of the rows appended. */
    public Table build() {
      if (columns.get(0).size() > 0) {
        flush();
      }
      return new Table(schema, batches);
    }
  }
}
