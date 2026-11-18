/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.progmasoft.etincelle.core.DataType;
import com.progmasoft.etincelle.core.Field;
import com.progmasoft.etincelle.core.Schema;
import com.progmasoft.etincelle.core.Table;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionTest {
  private static final Schema PEOPLE =
      Schema.of(
          new Field("id", DataType.INT, false),
          new Field("name", DataType.STRING, false),
          new Field("dept", DataType.STRING, true),
          new Field("age", DataType.INT, true),
          new Field("salary", DataType.DOUBLE, true));

  private final Session session =
      new Session()
          .register(
              "People",
              Table.builder(PEOPLE)
                  .row(1, "Ada", "eng", 36, 120.0)
                  .row(2, "Grace", "eng", 45, 150.0)
                  .row(3, "Alan", "research", 41, 110.0)
                  .row(4, "Edsger", "research", null, 100.0)
                  .row(5, "Barbara", null, 29, null)
                  .row(6, "Ken", "eng", 52, 140.0)
                  .build());

  private List<List<Object>> rows(String query) {
    return session.sql(query).rows();
  }

  private static List<Object> row(Object... values) {
    return Arrays.asList(values);
  }

  @Test
  void selectsColumnsAndExpressions() {
    Table result = session.sql("SELECT name, age + 1 AS next, salary * 2 FROM people WHERE id = 1");
    assertEquals(
        Schema.of(
            new Field("name", DataType.STRING, false),
            new Field("next", DataType.INT, true),
            new Field("(salary * 2)", DataType.DOUBLE, true)),
        result.schema());
    assertEquals(List.of(row("Ada", 37, 240.0)), result.rows());
  }

  @Test
  void starAndQualifiedNames() {
    assertEquals(6, rows("SELECT * FROM people").size());
    assertEquals(
        List.of(row(1, "Ada")), rows("SELECT p.id, P.Name FROM people AS p WHERE p.id = 1"));
    assertEquals(5, session.sql("SELECT p.* FROM people p").schema().size());
  }

  @Test
  void whereFollowsThreeValuedLogic() {
    // age is NULL for Edsger: neither age > 40 nor NOT (age > 40) holds.
    assertEquals(
        List.of(row("Grace"), row("Alan"), row("Ken")),
        rows("SELECT name FROM people WHERE age > 40"));
    assertEquals(
        List.of(row("Ada"), row("Barbara")), rows("SELECT name FROM people WHERE NOT (age > 40)"));
    assertEquals(List.of(row("Edsger")), rows("SELECT name FROM people WHERE age IS NULL"));
    assertEquals(
        List.of(row(false, null, true, null)),
        rows("SELECT FALSE AND NULL, TRUE AND NULL, TRUE OR NULL, FALSE OR NULL"));
  }

  @Test
  void inBetweenLikeAndCase() {
    assertEquals(
        List.of(row("Ada"), row("Alan")),
        rows("SELECT name FROM people WHERE name LIKE 'A%' ORDER BY name"));
    assertEquals(
        List.of(row("Grace"), row("Alan")),
        rows("SELECT name FROM people WHERE age BETWEEN 40 AND 50 ORDER BY age DESC"));
    assertEquals(
        List.of(row("Ada"), row("Ken")), rows("SELECT name FROM people WHERE id IN (1, 6)"));
    assertEquals(
        List.of(row("Ada", "young"), row("Grace", "senior"), row("Edsger", "unknown")),
        rows(
            "SELECT name, CASE WHEN age < 40 THEN 'young' WHEN age >= 40 THEN 'senior'"
                + " ELSE 'unknown' END FROM people WHERE id IN (1, 2, 4) ORDER BY id"));
    assertEquals(
        List.of(row("one"), row("other")),
        rows("SELECT CASE id WHEN 1 THEN 'one' ELSE 'other' END FROM people WHERE id < 3"));
  }

  @Test
  void groupByWithAggregates() {
    assertEquals(
        List.of(
            row("eng", 3L, 410.0, 36, 52, 44.333333333333336),
            row("research", 2L, 210.0, 41, 41, 41.0),
            row(null, 1L, null, 29, 29, 29.0)),
        rows(
            "SELECT dept, count(*), sum(salary), min(age), max(age), avg(age)"
                + " FROM people GROUP BY dept"));
    assertEquals(
        List.of(row("eng", 3L)),
        rows("SELECT dept, count(*) AS n FROM people GROUP BY dept HAVING count(*) > 2"));
    assertEquals(
        List.of(row(6L, 5L, 2L)),
        rows("SELECT count(*), count(age), count(DISTINCT dept) FROM people"));
  }

  @Test
  void anAggregateOfNoRowsIsOneRow() {
    assertEquals(
        List.of(row(0L, null, null)),
        rows("SELECT count(*), sum(age), max(name) FROM people WHERE id > 100"));
    assertEquals(List.of(), rows("SELECT dept, count(*) FROM people WHERE id > 100 GROUP BY dept"));
  }

  @Test
  void groupByTakesPositionsAndAliases() {
    List<List<Object>> expected = List.of(row("ENG", 3L), row("RESEARCH", 2L), row(null, 1L));
    assertEquals(expected, rows("SELECT upper(dept) AS d, count(*) FROM people GROUP BY 1"));
    assertEquals(expected, rows("SELECT upper(dept) AS d, count(*) FROM people GROUP BY d"));
    assertEquals(expected, rows("SELECT upper(dept), count(*) FROM people GROUP BY upper(dept)"));
  }

  @Test
  void orderByNullsLimitAndHiddenColumns() {
    assertEquals(
        List.of(row("Edsger"), row("Barbara"), row("Ada"), row("Alan"), row("Grace"), row("Ken")),
        rows("SELECT name FROM people ORDER BY age"));
    assertEquals(
        List.of(row("Barbara"), row("Ada"), row("Alan"), row("Grace"), row("Ken"), row("Edsger")),
        rows("SELECT name FROM people ORDER BY age NULLS LAST"));
    assertEquals(
        List.of(row("Edsger"), row("Ken"), row("Grace")),
        rows("SELECT name FROM people ORDER BY age DESC NULLS FIRST LIMIT 3"));
    assertEquals(
        List.of(row("research", 210.0), row("eng", 410.0)),
        rows(
            "SELECT dept, sum(salary) AS total FROM people WHERE dept IS NOT NULL"
                + " GROUP BY dept ORDER BY total"));
    assertEquals(
        List.of(row("eng"), row("research")),
        rows(
            "SELECT dept FROM people WHERE dept IS NOT NULL GROUP BY dept"
                + " ORDER BY count(*) DESC"));
    assertEquals(List.of(), rows("SELECT name FROM people LIMIT 0"));
  }

  @Test
  void distinct() {
    assertEquals(
        List.of(row("eng"), row("research"), row((Object) null)),
        rows("SELECT DISTINCT dept FROM people"));
    assertEquals(
        List.of(row((Object) null), row("eng"), row("research")),
        rows("SELECT DISTINCT dept FROM people ORDER BY dept"));
  }

  @Test
  void typesWiden() {
    Table result =
        session.sql(
            "SELECT 1 + 2, 1 + 2147483648, 1 + 0.5, 7 / 2, 7 % 3, -age FROM people LIMIT 1");
    assertEquals(
        List.of(
            DataType.INT,
            DataType.LONG,
            DataType.DOUBLE,
            DataType.DOUBLE,
            DataType.INT,
            DataType.INT),
        result.schema().fields().stream().map(Field::type).toList());
    assertEquals(List.of(row(3, 2147483649L, 1.5, 3.5, 1, -36)), result.rows());
  }

  @Test
  void castsAndFunctions() {
    assertEquals(
        List.of(row(42, 3L, "36", true, "ada", 3, 5, "x")),
        rows(
            "SELECT CAST('42' AS INT), CAST(3.9 AS BIGINT), CAST(age AS STRING),"
                + " CAST('TRUE' AS BOOLEAN), lower(name), length(name), abs(-5),"
                + " coalesce(CAST(NULL AS STRING), 'x') FROM people WHERE id = 1"));
    assertEquals(List.of(row("Ada!")), rows("SELECT name || '!' FROM people WHERE id = 1"));
    assertEquals(List.of(row(2)), rows("SELECT 1 + 1"));
  }

  @Test
  void stringsCompareByCodePoint() {
    session.register(
        "words",
        Table.builder(Schema.of(new Field("w", DataType.STRING, false)))
            .row("～")
            .row("😀")
            .row("b")
            .row("B")
            .build());
    assertEquals(
        List.of(row("B"), row("b"), row("～"), row("😀")), rows("SELECT w FROM words ORDER BY w"));
  }

  @Test
  void namesMatchWithoutCaseAndTablesAreReplaced() {
    assertTrue(session.table("PEOPLE").isPresent());
    assertFalse(session.table("nobody").isPresent());
    session.register(
        "people", Table.builder(Schema.of(new Field("x", DataType.INT, true))).row(9).build());
    assertEquals(List.of(row(9)), rows("SELECT X FROM PEOPLE"));
  }

  @Test
  void explainShowsThePlan() {
    assertEquals(
        """
        Limit 2
          Sort [total DESC NULLS LAST]
            Project [dept, `sum(salary)`] (dept STRING, total DOUBLE)
              Filter (`count(*)` > 1L)
                Aggregate keys=[dept] aggregates=[count(*), sum(salary)]
                  Filter (age > 30)
                    Scan People (id INT NOT NULL, name STRING NOT NULL, dept STRING, age INT, salary DOUBLE)
        """,
        session.explain(
            "SELECT dept, sum(salary) AS total FROM people WHERE age > 30"
                + " GROUP BY dept HAVING count(*) > 1 ORDER BY total DESC LIMIT 2"));
  }
}
