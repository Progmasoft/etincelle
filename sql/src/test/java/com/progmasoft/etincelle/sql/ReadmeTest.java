/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.progmasoft.etincelle.core.DataType;
import com.progmasoft.etincelle.core.Field;
import com.progmasoft.etincelle.core.Schema;
import com.progmasoft.etincelle.core.Table;
import org.junit.jupiter.api.Test;

/** The example of the README, so that what it shows is what Etincelle does. */
class ReadmeTest {
  @Test
  void theExampleGivesWhatTheReadmeShows() {
    Schema schema =
        Schema.of(
            new Field("name", DataType.STRING, false),
            new Field("dept", DataType.STRING, true),
            new Field("salary", DataType.DOUBLE, true));
    Table people =
        Table.builder(schema)
            .row("Ada", "eng", 120.0)
            .row("Grace", "eng", 150.0)
            .row("Alan", "research", 110.0)
            .build();

    Session session = new Session().register("people", people);
    String query =
        """
        SELECT dept, count(*) AS n, sum(salary) AS total
        FROM people
        GROUP BY dept
        ORDER BY total DESC\
        """;
    Table totals = session.sql(query);

    assertEquals(
        """
        +----------+---+-------+
        | dept     | n | total |
        +----------+---+-------+
        | eng      | 2 | 270.0 |
        | research | 1 | 110.0 |
        +----------+---+-------+
        """,
        totals.format(10));
    assertEquals(
        """
        Sort [total DESC NULLS LAST]
          Project [dept, `count(*)`, `sum(salary)`] (dept STRING, n BIGINT NOT NULL, total DOUBLE)
            Aggregate keys=[dept] aggregates=[count(*), sum(salary)]
              Scan people (name STRING NOT NULL, dept STRING, salary DOUBLE)
        """,
        session.explain(query));
  }
}
