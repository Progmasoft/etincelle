/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.exec;

import com.progmasoft.etincelle.core.Batch;
import com.progmasoft.etincelle.core.BooleanColumn;
import com.progmasoft.etincelle.core.Column;
import com.progmasoft.etincelle.core.ColumnBuilder;
import com.progmasoft.etincelle.core.Field;
import com.progmasoft.etincelle.core.Schema;
import com.progmasoft.etincelle.core.Table;
import com.progmasoft.etincelle.sql.plan.AggregateCall;
import com.progmasoft.etincelle.sql.plan.Expression;
import com.progmasoft.etincelle.sql.plan.Plan;
import com.progmasoft.etincelle.sql.plan.Values;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a plan over tables in memory. Filter and Project work batch by batch; Aggregate, Distinct
 * and Sort read all their rows first; Limit stops early.
 */
public final class Executor {
  private Executor() {}

  /** The rows of {@code plan}. */
  public static Table run(Plan plan) {
    return new Table(plan.schema(), batches(plan));
  }

  private static List<Batch> batches(Plan plan) {
    return switch (plan) {
      case Plan.Scan scan -> scan.table().batches();
      case Plan.OneRow one -> List.of(Batch.ofRows(1));
      case Plan.Filter filter -> filter(filter);
      case Plan.Project project -> project(project);
      case Plan.Aggregate aggregate -> aggregate(aggregate);
      case Plan.Distinct distinct -> distinct(distinct);
      case Plan.Sort sort -> sort(sort);
      case Plan.Limit limit -> limit(limit);
    };
  }

  private static List<Batch> filter(Plan.Filter filter) {
    List<Batch> output = new ArrayList<>();
    for (Batch batch : batches(filter.input())) {
      Column condition = filter.condition().evaluate(batch);
      int[] rows = new int[batch.rowCount()];
      int kept = 0;
      for (int row = 0; row < batch.rowCount(); row++) {
        if (!condition.isNull(row) && ((BooleanColumn) condition).getBoolean(row)) {
          rows[kept++] = row;
        }
      }
      if (kept == batch.rowCount()) {
        output.add(batch);
      } else if (kept > 0) {
        output.add(take(batch, Arrays.copyOf(rows, kept)));
      }
    }
    return output;
  }

  private static Batch take(Batch batch, int[] rows) {
    if (batch.schema().size() == 0) {
      return Batch.ofRows(rows.length);
    }
    List<Column> columns = new ArrayList<>();
    for (Column column : batch.columns()) {
      ColumnBuilder builder = Column.builder(column.type(), rows.length);
      for (int row : rows) {
        builder.append(column.get(row));
      }
      columns.add(builder.build());
    }
    return new Batch(batch.schema(), columns);
  }

  private static List<Batch> project(Plan.Project project) {
    List<Batch> output = new ArrayList<>();
    for (Batch batch : batches(project.input())) {
      List<Column> columns = new ArrayList<>();
      for (Expression expression : project.expressions()) {
        columns.add(expression.evaluate(batch));
      }
      output.add(new Batch(project.schema(), columns));
    }
    return output;
  }

  private static List<Batch> aggregate(Plan.Aggregate aggregate) {
    List<Expression> keys = aggregate.keys();
    List<AggregateCall> calls = aggregate.aggregates();
    Map<List<Object>, AggregateCall.Accumulator[]> groups = new LinkedHashMap<>();
    for (Batch batch : batches(aggregate.input())) {
      List<Column> keyColumns = new ArrayList<>();
      for (Expression key : keys) {
        keyColumns.add(key.evaluate(batch));
      }
      List<Column> arguments = new ArrayList<>();
      for (AggregateCall call : calls) {
        arguments.add(call.argument() == null ? null : call.argument().evaluate(batch));
      }
      for (int row = 0; row < batch.rowCount(); row++) {
        List<Object> group = new ArrayList<>(keys.size());
        for (Column column : keyColumns) {
          group.add(Values.normalize(column.get(row)));
        }
        AggregateCall.Accumulator[] state = groups.computeIfAbsent(group, g -> start(calls));
        for (int i = 0; i < calls.size(); i++) {
          state[i].add(arguments.get(i) == null ? null : arguments.get(i).get(row));
        }
      }
    }
    if (groups.isEmpty() && keys.isEmpty()) {
      groups.put(List.of(), start(calls));
    }
    List<Object[]> rows = new ArrayList<>(groups.size());
    for (Map.Entry<List<Object>, AggregateCall.Accumulator[]> group : groups.entrySet()) {
      Object[] row = new Object[keys.size() + calls.size()];
      for (int i = 0; i < keys.size(); i++) {
        row[i] = group.getKey().get(i);
      }
      for (int i = 0; i < calls.size(); i++) {
        row[keys.size() + i] = group.getValue()[i].result();
      }
      rows.add(row);
    }
    return toBatches(aggregate.schema(), rows);
  }

  private static AggregateCall.Accumulator[] start(List<AggregateCall> calls) {
    AggregateCall.Accumulator[] state = new AggregateCall.Accumulator[calls.size()];
    for (int i = 0; i < calls.size(); i++) {
      state[i] = calls.get(i).start();
    }
    return state;
  }

  private static List<Batch> distinct(Plan.Distinct distinct) {
    Set<List<Object>> seen = new LinkedHashSet<>();
    List<Object[]> rows = new ArrayList<>();
    for (Batch batch : batches(distinct.input())) {
      for (int row = 0; row < batch.rowCount(); row++) {
        List<Object> values = new ArrayList<>(batch.columns().size());
        for (Column column : batch.columns()) {
          values.add(Values.normalize(column.get(row)));
        }
        if (seen.add(values)) {
          rows.add(batch.row(row).toArray());
        }
      }
    }
    return toBatches(distinct.schema(), rows);
  }

  private static List<Batch> sort(Plan.Sort sort) {
    List<Object[]> rows = new ArrayList<>();
    for (Batch batch : batches(sort.input())) {
      for (int row = 0; row < batch.rowCount(); row++) {
        rows.add(batch.row(row).toArray());
      }
    }
    Comparator<Object[]> order = null;
    for (Plan.SortKey key : sort.keys()) {
      Comparator<Object[]> next = (a, b) -> compare(a[key.column()], b[key.column()], key);
      order = order == null ? next : order.thenComparing(next);
    }
    rows.sort(order);
    return toBatches(sort.schema(), rows);
  }

  private static int compare(Object a, Object b, Plan.SortKey key) {
    if (a == null || b == null) {
      if (a == b) {
        return 0;
      }
      return (a == null) == key.nullsFirst() ? -1 : 1;
    }
    int order = Values.compare(a, b);
    return key.descending() ? -order : order;
  }

  private static List<Batch> limit(Plan.Limit limit) {
    List<Batch> output = new ArrayList<>();
    long left = limit.count();
    for (Batch batch : batches(limit.input())) {
      if (left == 0) {
        break;
      }
      if (batch.rowCount() <= left) {
        output.add(batch);
        left -= batch.rowCount();
      } else {
        int[] rows = new int[(int) left];
        for (int i = 0; i < rows.length; i++) {
          rows[i] = i;
        }
        output.add(take(batch, rows));
        left = 0;
      }
    }
    return output;
  }

  private static List<Batch> toBatches(Schema schema, List<Object[]> rows) {
    List<Batch> output = new ArrayList<>();
    if (schema.size() == 0) {
      if (!rows.isEmpty()) {
        output.add(Batch.ofRows(rows.size()));
      }
      return output;
    }
    for (int start = 0; start < rows.size(); start += Table.BATCH_ROWS) {
      int end = Math.min(rows.size(), start + Table.BATCH_ROWS);
      List<Column> columns = new ArrayList<>();
      for (int column = 0; column < schema.size(); column++) {
        Field field = schema.fields().get(column);
        ColumnBuilder builder = Column.builder(field.type(), end - start);
        for (int row = start; row < end; row++) {
          builder.append(rows.get(row)[column]);
        }
        columns.add(builder.build());
      }
      output.add(new Batch(schema, columns));
    }
    return output;
  }
}
