/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import com.progmasoft.etincelle.core.Schema;
import com.progmasoft.etincelle.core.Table;
import com.progmasoft.etincelle.sql.Identifiers;
import java.util.ArrayList;
import java.util.List;

/** A resolved query: a tree of steps, each with the schema of its rows. */
public sealed interface Plan {
  /** The schema of the rows of this step. */
  Schema schema();

  /** The inputs of this step. */
  List<Plan> inputs();

  /** This step alone, as EXPLAIN writes it. */
  String describe();

  /** The plan as EXPLAIN writes it: one step a line, each input indented below it. */
  default String explain() {
    StringBuilder text = new StringBuilder();
    explain(text, 0);
    return text.toString();
  }

  private void explain(StringBuilder text, int depth) {
    text.append("  ".repeat(depth)).append(describe()).append('\n');
    for (Plan input : inputs()) {
      input.explain(text, depth + 1);
    }
  }

  private static String list(List<Expression> expressions) {
    List<String> parts = new ArrayList<>();
    for (Expression expression : expressions) {
      parts.add(expression.describe());
    }
    return "[" + String.join(", ", parts) + "]";
  }

  /** The rows of a table. */
  record Scan(String name, Table table) implements Plan {
    @Override
    public Schema schema() {
      return table.schema();
    }

    @Override
    public List<Plan> inputs() {
      return List.of();
    }

    @Override
    public String describe() {
      return "Scan " + Identifiers.quote(name) + " " + table.schema();
    }
  }

  /** One row of no columns: the input of a SELECT without FROM. */
  record OneRow() implements Plan {
    @Override
    public Schema schema() {
      return new Schema(List.of());
    }

    @Override
    public List<Plan> inputs() {
      return List.of();
    }

    @Override
    public String describe() {
      return "OneRow";
    }
  }

  /** The rows for which {@code condition} is TRUE. */
  record Filter(Plan input, Expression condition) implements Plan {
    @Override
    public Schema schema() {
      return input.schema();
    }

    @Override
    public List<Plan> inputs() {
      return List.of(input);
    }

    @Override
    public String describe() {
      return "Filter " + condition.describe();
    }
  }

  /** Each row as the values of {@code expressions}, named by {@code schema}. */
  record Project(Plan input, List<Expression> expressions, Schema schema) implements Plan {
    @Override
    public List<Plan> inputs() {
      return List.of(input);
    }

    @Override
    public String describe() {
      return "Project " + list(expressions) + " " + schema;
    }
  }

  /**
   * One row for each distinct value of {@code keys}, the keys then the aggregates; with no keys,
   * one row even for no input.
   */
  record Aggregate(Plan input, List<Expression> keys, List<AggregateCall> aggregates, Schema schema)
      implements Plan {
    @Override
    public List<Plan> inputs() {
      return List.of(input);
    }

    @Override
    public String describe() {
      List<String> parts = new ArrayList<>();
      for (AggregateCall aggregate : aggregates) {
        parts.add(aggregate.text());
      }
      return "Aggregate keys=" + list(keys) + " aggregates=[" + String.join(", ", parts) + "]";
    }
  }

  /** The distinct rows, each once, in the order they first appear. */
  record Distinct(Plan input) implements Plan {
    @Override
    public Schema schema() {
      return input.schema();
    }

    @Override
    public List<Plan> inputs() {
      return List.of(input);
    }

    @Override
    public String describe() {
      return "Distinct";
    }
  }

  /** The rows in the order of {@code keys}; rows with equal keys keep their order. */
  record Sort(Plan input, List<SortKey> keys) implements Plan {
    @Override
    public Schema schema() {
      return input.schema();
    }

    @Override
    public List<Plan> inputs() {
      return List.of(input);
    }

    @Override
    public String describe() {
      List<String> parts = new ArrayList<>();
      for (SortKey key : keys) {
        parts.add(
            Identifiers.quote(input.schema().fields().get(key.column()).name())
                + (key.descending() ? " DESC" : " ASC")
                + (key.nullsFirst() ? " NULLS FIRST" : " NULLS LAST"));
      }
      return "Sort [" + String.join(", ", parts) + "]";
    }
  }

  /** A column to sort by. */
  record SortKey(int column, boolean descending, boolean nullsFirst) {}

  /** The first {@code count} rows. */
  record Limit(Plan input, long count) implements Plan {
    @Override
    public Schema schema() {
      return input.schema();
    }

    @Override
    public List<Plan> inputs() {
      return List.of(input);
    }

    @Override
    public String describe() {
      return "Limit " + count;
    }
  }
}
