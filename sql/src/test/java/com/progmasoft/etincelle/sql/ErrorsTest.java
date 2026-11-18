/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.progmasoft.etincelle.core.DataType;
import com.progmasoft.etincelle.core.Field;
import com.progmasoft.etincelle.core.Schema;
import com.progmasoft.etincelle.core.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ErrorsTest {
  private final Session session =
      new Session()
          .register(
              "t",
              Table.builder(
                      Schema.of(
                          new Field("a", DataType.INT, true),
                          new Field("s", DataType.STRING, true),
                          new Field("big", DataType.LONG, true)))
                  .row(1, "x", Long.MAX_VALUE)
                  .row(0, "1.5", 1L)
                  .build());

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          SELECT                                   | line 1, column 7: an expression is expected; found the end of the query
          SELECT a FROM                            | line 1, column 14: a table name is expected; found the end of the query
          SELECT a FROM t WHERE                    | line 1, column 22: an expression is expected; found the end of the query
          SELECT 'open                             | line 1, column 8: the string is not closed
          SELECT a FROM t x y                      | line 1, column 19: the query ends here; found 'y'
          SELECT a FROM t JOIN u ON a = b          | line 1, column 17: joins are not supported
          SELECT (SELECT 1)                        | line 1, column 9: subqueries are not supported
          SELECT b FROM t                          | line 1, column 8: there is no column b; the columns are a, s, big
          SELECT a FROM u                          | line 1, column 15: there is no table u
          SELECT u.a FROM t                        | line 1, column 8: there is no table u in FROM
          SELECT a + s FROM t                      | line 1, column 10: cannot apply + to INT and STRING
          SELECT a FROM t WHERE a                  | line 1, column 23: WHERE takes BOOLEAN, not INT
          SELECT a = s FROM t                      | line 1, column 10: cannot compare INT and STRING
          SELECT a, count(*) FROM t                | line 1, column 8: a is neither in GROUP BY nor inside an aggregate
          SELECT a FROM t WHERE count(*) > 0       | line 1, column 23: an aggregate cannot be in WHERE
          SELECT sum(count(*)) FROM t              | line 1, column 12: an aggregate cannot be inside another aggregate
          SELECT sum(s) FROM t                     | line 1, column 12: sum takes a number, not STRING
          SELECT NULL                              | line 1, column 8: NULL alone has no type; write CAST(NULL AS type) for a column of NULLs
          SELECT CAST(a AS DATE) FROM t            | line 1, column 18: there is no type DATE; the types are INT, BIGINT, DOUBLE, STRING and BOOLEAN
          SELECT a, a FROM t                       | line 1, column 11: the result has two columns named a; give one an alias
          SELECT DISTINCT a FROM t ORDER BY s      | line 1, column 35: with DISTINCT, ORDER BY takes the columns of the result
          SELECT a FROM t ORDER BY 3               | line 1, column 26: ORDER BY 3 is not a position of the select list, 1 to 1
          SELECT nope(a) FROM t                    | line 1, column 8: there is no function nope; the functions are lower, upper, length, abs, coalesce, and the aggregates count, sum, avg, min, max
          SELECT 99999999999999999999              | line 1, column 8: 99999999999999999999 is out of the range of BIGINT; write it with a point for a DOUBLE
          """)
  void refusesWhatDoesNotResolve(String query, String message) {
    SqlException error = assertThrows(SqlException.class, () -> session.sql(query));
    assertEquals(message, error.getMessage());
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          SELECT 10 / a FROM t                     | line 1, column 11: division by zero in (10.0 / CAST(a AS DOUBLE))
          SELECT 10 % a FROM t                     | line 1, column 11: division by zero in (10 % a)
          SELECT big + 1 FROM t                    | line 1, column 12: BIGINT overflow in (big + 1L)
          SELECT sum(big) FROM t                   | line 1, column 8: BIGINT overflow in sum(big)
          SELECT CAST(s AS INT) FROM t             | line 1, column 8: 'x' is not a whole number
          SELECT -2147483647 - 2                   | line 1, column 20: INT overflow in (-2147483647 - 2)
          """)
  void refusesValuesItCannotCompute(String query, String message) {
    SqlException error = assertThrows(SqlException.class, () -> session.sql(query));
    assertEquals(message, error.getMessage());
  }

  @Test
  void placesErrorsOnLaterLines() {
    SqlException error =
        assertThrows(SqlException.class, () -> session.sql("SELECT a\nFROM t\nWHERE nope = 1"));
    assertEquals(3, error.line());
    assertEquals(7, error.column());
  }
}
