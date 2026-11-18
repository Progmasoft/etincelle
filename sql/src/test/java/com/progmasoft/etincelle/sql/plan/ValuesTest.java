/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.progmasoft.etincelle.core.DataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ValuesTest {
  @ParameterizedTest
  @CsvSource({
    "abc, abc, true",
    "abc, a%, true",
    "abc, %c, true",
    "abc, %b%, true",
    "abc, a_c, true",
    "abc, a_, false",
    "abc, %d%, false",
    "'', %, true",
    "'', _, false",
    "a%c, a\\%c, true",
    "abc, a\\%c, false",
    "a_c, a\\_c, true",
    "aXbXc, %X%X%, true",
    "mississippi, m%iss%ppi, true",
  })
  void likeMatches(String text, String pattern, boolean expected) {
    assertEquals(expected, Values.like(text, pattern, 0), text + " LIKE " + pattern);
  }

  @Test
  void likeCountsCodePoints() {
    assertTrue(Values.like("😀", "_", 0));
    assertThrows(EvaluationException.class, () -> Values.like("a", "a\\", 0));
  }

  @Test
  void doublesOrderAsSqlDoes() {
    assertEquals(0, Values.compare(-0.0, 0.0));
    assertEquals(0, Values.compare(Double.NaN, Double.NaN));
    assertTrue(Values.compare(Double.NaN, Double.POSITIVE_INFINITY) > 0);
    assertEquals(0.0, Values.normalize(-0.0));
    assertEquals(Values.normalize(Double.NaN), Values.normalize(0.0 / 0.0));
    assertTrue(Values.compare(false, true) < 0);
  }

  @Test
  void castsBetweenTypes() {
    assertEquals(3, Values.cast(3.99, DataType.DOUBLE, DataType.INT, 0));
    assertEquals(-3, Values.cast(-3.99, DataType.DOUBLE, DataType.INT, 0));
    assertEquals(1, Values.cast(true, DataType.BOOLEAN, DataType.INT, 0));
    assertEquals(0.0, Values.cast(false, DataType.BOOLEAN, DataType.DOUBLE, 0));
    assertEquals(2.5, Values.cast(" 2.5 ", DataType.STRING, DataType.DOUBLE, 0));
    assertEquals(12L, Values.cast("12", DataType.STRING, DataType.LONG, 0));
    assertEquals("1.5", Values.cast(1.5, DataType.DOUBLE, DataType.STRING, 0));
    assertEquals(true, Values.cast(2L, DataType.LONG, DataType.BOOLEAN, 0));
    assertFalse((Boolean) Values.cast(" False ", DataType.STRING, DataType.BOOLEAN, 0));
    assertThrows(
        EvaluationException.class, () -> Values.cast(3e9, DataType.DOUBLE, DataType.INT, 0));
    assertThrows(
        EvaluationException.class,
        () -> Values.cast(Double.NaN, DataType.DOUBLE, DataType.LONG, 0));
    assertThrows(
        EvaluationException.class, () -> Values.cast("yes", DataType.STRING, DataType.BOOLEAN, 0));
    assertThrows(
        EvaluationException.class, () -> Values.cast("x", DataType.STRING, DataType.DOUBLE, 0));
  }
}
