/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql;

import com.progmasoft.etincelle.core.Table;
import com.progmasoft.etincelle.sql.exec.Executor;
import com.progmasoft.etincelle.sql.parse.Parser;
import com.progmasoft.etincelle.sql.plan.Analyzer;
import com.progmasoft.etincelle.sql.plan.Catalog;
import com.progmasoft.etincelle.sql.plan.EvaluationException;
import com.progmasoft.etincelle.sql.plan.Plan;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Tables by name, and the queries of Etincelle SQL over them.
 *
 * <pre>{@code
 * Session session = new Session();
 * session.register("people", people);
 * Table adults = session.sql("SELECT name, age FROM people WHERE age >= 18 ORDER BY age DESC");
 * System.out.print(adults.format(10));
 * }</pre>
 *
 * <p>A session is not safe for use by several threads at once.
 */
public final class Session {
  private final Map<String, Catalog.Entry> tables = new LinkedHashMap<>();
  private final Catalog catalog = name -> Optional.ofNullable(tables.get(key(name)));

  /** A session with no tables. */
  public Session() {}

  private static String key(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  /**
   * Registers {@code table} as {@code name}; names match without regard to case, and a table
   * registered under the same name before is replaced.
   */
  public Session register(String name, Table table) {
    Objects.requireNonNull(table, "table");
    if (Objects.requireNonNull(name, "name").isEmpty()) {
      throw new IllegalArgumentException("a table has a name");
    }
    tables.put(key(name), new Catalog.Entry(name, table));
    return this;
  }

  /** The table registered as {@code name}, case aside. */
  public Optional<Table> table(String name) {
    return catalog.table(name).map(Catalog.Entry::table);
  }

  /**
   * The rows of the SELECT {@code query}.
   *
   * @throws SqlException when the query does not read, does not resolve, or a value cannot be
   *     computed, such as an overflow, a division by zero or a CAST that does not fit
   */
  public Table sql(String query) {
    Plan plan = plan(query);
    try {
      return Executor.run(plan);
    } catch (EvaluationException e) {
      throw new SqlException(query, e.offset(), e.getMessage());
    }
  }

  /**
   * The plan of the SELECT {@code query}, one step a line, each input indented below it.
   *
   * @throws SqlException when the query does not read or does not resolve
   */
  public String explain(String query) {
    return plan(query).explain();
  }

  private Plan plan(String query) {
    Objects.requireNonNull(query, "query");
    return Analyzer.analyze(query, Parser.parse(query), catalog);
  }
}
