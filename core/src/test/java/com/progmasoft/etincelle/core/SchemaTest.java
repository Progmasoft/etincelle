/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemaTest {
  private final Schema people =
      Schema.of(
          new Field("id", DataType.LONG, false),
          new Field("Name", DataType.STRING, true),
          new Field("score", DataType.DOUBLE, true));

  @Test
  void fieldsKeepTheirOrderAndAreFoundCaseAside() {
    assertEquals(3, people.size());
    assertEquals(Optional.of(1), people.indexOf("name"));
    assertEquals(Optional.of(1), people.indexOf("NAME"));
    assertEquals(DataType.STRING, people.field("Name").orElseThrow().type());
    assertTrue(people.indexOf("age").isEmpty());
    assertEquals("(id BIGINT NOT NULL, Name STRING, score DOUBLE)", people.toString());
  }

  @Test
  void namesThatDifferOnlyInCaseAreRefused() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Schema.of(new Field("a", DataType.INT, true), new Field("A", DataType.INT, true)));
    assertTrue(error.getMessage().contains("same name"), error.getMessage());
  }

  @Test
  void aFieldHasANameAndAType() {
    assertThrows(IllegalArgumentException.class, () -> new Field("", DataType.INT, true));
    assertThrows(NullPointerException.class, () -> new Field("a", null, true));
    assertThrows(NullPointerException.class, () -> new Field(null, DataType.INT, true));
  }

  @Test
  void schemasAreValues() {
    Schema copy = new Schema(List.copyOf(people.fields()));
    assertEquals(people, copy);
    assertEquals(people.hashCode(), copy.hashCode());
    assertNotEquals(people, Schema.of(new Field("id", DataType.LONG, false)));
    assertThrows(UnsupportedOperationException.class, () -> people.fields().clear());
  }

  @Test
  void everyTypeHasItsSqlName() {
    List<DataType> types =
        List.of(DataType.BOOLEAN, DataType.INT, DataType.LONG, DataType.DOUBLE, DataType.STRING);
    List<String> names = types.stream().map(DataType::sqlName).toList();
    assertEquals(List.of("BOOLEAN", "INT", "BIGINT", "DOUBLE", "STRING"), names);
    // The set of types is closed: this switch needs no default branch.
    for (DataType type : types) {
      String expected =
          switch (type) {
            case DataType.BooleanType b -> "BOOLEAN";
            case DataType.IntType i -> "INT";
            case DataType.LongType l -> "BIGINT";
            case DataType.DoubleType d -> "DOUBLE";
            case DataType.StringType s -> "STRING";
          };
      assertEquals(expected, type.sqlName());
    }
  }
}
