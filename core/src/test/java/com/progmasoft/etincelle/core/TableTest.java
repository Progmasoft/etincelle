/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableTest {
  private static final Schema PEOPLE =
      Schema.of(
          new Field("id", DataType.LONG, false),
          new Field("name", DataType.STRING, true),
          new Field("age", DataType.INT, true),
          new Field("score", DataType.DOUBLE, true),
          new Field("active", DataType.BOOLEAN, true));

  @Test
  void eachTypeKeepsItsValuesAndNulls() {
    for (DataType type :
        List.of(DataType.INT, DataType.LONG, DataType.DOUBLE, DataType.BOOLEAN, DataType.STRING)) {
      Object value =
          switch (type) {
            case DataType.IntType t -> 7;
            case DataType.LongType t -> 7L;
            case DataType.DoubleType t -> 7.5;
            case DataType.BooleanType t -> true;
            case DataType.StringType t -> "seven";
          };
      ColumnBuilder builder = Column.builder(type, 0);
      for (int i = 0; i < 200; i++) {
        builder.append(i % 3 == 0 ? null : value);
      }
      Column column = builder.build();
      assertEquals(type, column.type());
      assertEquals(200, column.size());
      assertEquals(67, column.nullCount());
      assertNull(column.get(0));
      assertEquals(value, column.get(1));
      assertTrue(column.isNull(198));
      assertFalse(column.isNull(199));
    }
  }

  @Test
  void typedGettersRefuseNulls() {
    IntColumn column =
        (IntColumn) Column.builder(DataType.INT, 2).appendInt(3).appendNull().build();
    assertEquals(3, column.getInt(0));
    assertThrows(IllegalStateException.class, () -> column.getInt(1));
    assertThrows(IndexOutOfBoundsException.class, () -> column.isNull(2));
  }

  @Test
  void booleansAreBits() {
    ColumnBuilder builder = Column.builder(DataType.BOOLEAN, 1);
    for (int i = 0; i < 130; i++) {
      builder.appendBoolean(i % 5 == 0);
    }
    BooleanColumn column = assertInstanceOf(BooleanColumn.class, builder.build());
    assertTrue(column.getBoolean(0));
    assertFalse(column.getBoolean(64));
    assertTrue(column.getBoolean(65));
    assertTrue(column.getBoolean(125));
  }

  @Test
  void aBuilderTakesItsTypeOnly() {
    ColumnBuilder ints = Column.builder(DataType.INT, 1);
    assertThrows(IllegalArgumentException.class, () -> ints.append("1"));
    assertThrows(IllegalArgumentException.class, () -> ints.appendLong(1));
    assertEquals(5L, Column.builder(DataType.LONG, 1).append(5).build().get(0));
    ColumnBuilder done = Column.builder(DataType.STRING, 1);
    done.build();
    assertThrows(IllegalStateException.class, () -> done.appendString("late"));
  }

  @Test
  void aTableCutsItsRowsIntoBatches() {
    Table.Builder builder = Table.builder(PEOPLE);
    for (long i = 0; i < Table.BATCH_ROWS + 10; i++) {
      builder.row(i, "n" + i, (int) i, i / 2.0, i % 2 == 0);
    }
    Table table = builder.build();
    assertEquals(2, table.batches().size());
    assertEquals(Table.BATCH_ROWS + 10, table.rowCount());
    assertEquals(List.of(4100L, "n4100", 4100, 2050.0, true), table.rows().get(4100));
  }

  @Test
  void aRowMustFitTheSchema() {
    Table.Builder builder = Table.builder(PEOPLE);
    assertThrows(IllegalArgumentException.class, () -> builder.row(1L, "a", 1, 1.0));
    assertThrows(IllegalArgumentException.class, () -> builder.row(null, "a", 1, 1.0, true));
    assertThrows(IllegalArgumentException.class, () -> builder.row(1L, "a", "one", 1.0, true));
    builder.row(1L, null, null, null, null);
    assertEquals(Arrays.asList(1L, null, null, null, null), builder.build().rows().get(0));
  }

  @Test
  void aBatchChecksItsColumns() {
    Schema schema = Schema.of(new Field("a", DataType.INT, false));
    Column withNull = Column.builder(DataType.INT, 1).appendNull().build();
    Column text = Column.builder(DataType.STRING, 1).appendString("x").build();
    assertThrows(IllegalArgumentException.class, () -> new Batch(schema, List.of(withNull)));
    assertThrows(IllegalArgumentException.class, () -> new Batch(schema, List.of(text)));
    assertThrows(IllegalArgumentException.class, () -> new Batch(schema, List.of()));
    assertEquals(3, Batch.ofRows(3).rowCount());
  }

  @Test
  void formatWritesATextTable() {
    Table table =
        Table.builder(
                Schema.of(
                    new Field("name", DataType.STRING, true), new Field("n", DataType.INT, true)))
            .row("Ada", 1)
            .row(null, 20)
            .row("Grace", 3)
            .build();
    assertEquals(
        """
        +------+----+
        | name | n  |
        +------+----+
        | Ada  | 1  |
        | NULL | 20 |
        +------+----+
        only the first 2 of 3 rows
        """,
        table.format(2));
  }
}
