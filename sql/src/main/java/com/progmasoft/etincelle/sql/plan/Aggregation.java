/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import com.progmasoft.etincelle.core.DataType;
import com.progmasoft.etincelle.core.Field;
import com.progmasoft.etincelle.core.Schema;
import com.progmasoft.etincelle.sql.parse.Ast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The keys and aggregates of an aggregating query, gathered while its expressions resolve. The rows
 * of the aggregate hold the keys, then the aggregates.
 */
final class Aggregation {
  private final Source source;
  private final Source.Scope input;
  private final List<Expression> keys = new ArrayList<>();
  private final List<String> keyNames = new ArrayList<>();
  private final Map<String, Integer> keyIndexes = new HashMap<>();
  private final List<AggregateCall> aggregates = new ArrayList<>();
  private final Map<String, Integer> aggregateIndexes = new HashMap<>();

  /** The aggregation of rows of {@code input}. */
  Aggregation(Source source, Source.Scope input) {
    this.source = source;
    this.input = input;
  }

  /** Adds a GROUP BY key; a key the same as one added before is not added again. */
  void addKey(Ast.Expr key) {
    String canonical = key.toSql(true);
    if (keyIndexes.containsKey(canonical)) {
      return;
    }
    if (Source.hasAggregate(key)) {
      throw source.error(key.offset(), "GROUP BY cannot hold an aggregate");
    }
    Expression bound = new Binder(source, input, null, "GROUP BY").bind(key);
    if (bound.type() == null) {
      throw source.error(key.offset(), "GROUP BY NULL groups nothing");
    }
    keyIndexes.put(canonical, keys.size());
    keys.add(bound);
    keyNames.add(
        key instanceof Ast.Name && bound instanceof Expression.ColumnRef column
            ? column.name()
            : key.toSql(false));
  }

  /** The key {@code expression} is, as a column of the aggregate's rows, or {@code null}. */
  Expression key(Ast.Expr expression) {
    Integer index = keyIndexes.get(expression.toSql(true));
    if (index == null) {
      return null;
    }
    Expression key = keys.get(index);
    return new Expression.ColumnRef(index, keyNames.get(index), key.type(), key.nullable());
  }

  /** The aggregate {@code call}, as a column of the aggregate's rows. */
  Expression aggregate(Ast.Call call) {
    String canonical = call.toSql(true);
    Integer index = aggregateIndexes.get(canonical);
    if (index == null) {
      index = aggregates.size();
      aggregates.add(resolve(call));
      aggregateIndexes.put(canonical, index);
    }
    AggregateCall aggregate = aggregates.get(index);
    return new Expression.ColumnRef(
        keys.size() + index, aggregate.text(), aggregate.type(), aggregate.nullable());
  }

  private AggregateCall resolve(Ast.Call call) {
    String name = call.name().toLowerCase(Locale.ROOT);
    String text = call.toSql(false);
    if (call.star()) {
      if (!name.equals("count")) {
        throw source.error(call.offset(), name + "(*) is not an aggregate; count(*) is");
      }
      return new AggregateCall(
          AggregateCall.Kind.COUNT_STAR, null, false, DataType.LONG, text, call.offset());
    }
    Expression argument = argument(call, name);
    return switch (name) {
      case "count" ->
          new AggregateCall(
              AggregateCall.Kind.COUNT,
              argument,
              call.distinct(),
              DataType.LONG,
              text,
              call.offset());
      case "sum", "avg" -> sumOrAverage(call, name, argument, text);
      default ->
          new AggregateCall(
              name.equals("min") ? AggregateCall.Kind.MIN : AggregateCall.Kind.MAX,
              argument,
              call.distinct(),
              argument.type(),
              text,
              call.offset());
    };
  }

  private Expression argument(Ast.Call call, String name) {
    if (call.arguments().size() != 1) {
      throw source.error(call.offset(), name + " takes one argument");
    }
    Ast.Expr tree = call.arguments().get(0);
    if (Source.hasAggregate(tree)) {
      throw source.error(tree.offset(), "an aggregate cannot be inside another aggregate");
    }
    Expression argument = new Binder(source, input, null, name).bind(tree);
    if (argument.type() == null) {
      throw source.error(tree.offset(), name + "(NULL) has no type; write CAST(NULL AS type)");
    }
    return argument;
  }

  private AggregateCall sumOrAverage(Ast.Call call, String name, Expression argument, String text) {
    if (Values.numericRank(argument.type()) < 0) {
      throw source.error(
          call.arguments().get(0).offset(),
          name + " takes a number, not " + argument.type().sqlName());
    }
    boolean isSum = name.equals("sum");
    DataType type =
        isSum && !argument.type().equals(DataType.DOUBLE) ? DataType.LONG : DataType.DOUBLE;
    return new AggregateCall(
        isSum ? AggregateCall.Kind.SUM : AggregateCall.Kind.AVG,
        argument,
        call.distinct(),
        type,
        text,
        call.offset());
  }

  /** The Aggregate step over {@code input}, once every expression of the query has resolved. */
  Plan plan(Plan input) {
    List<Field> fields = new ArrayList<>();
    for (int i = 0; i < keys.size(); i++) {
      fields.add(new Field("#key" + (i + 1), keys.get(i).type(), keys.get(i).nullable()));
    }
    for (int i = 0; i < aggregates.size(); i++) {
      AggregateCall aggregate = aggregates.get(i);
      fields.add(new Field("#aggregate" + (i + 1), aggregate.type(), aggregate.nullable()));
    }
    return new Plan.Aggregate(
        input, List.copyOf(keys), List.copyOf(aggregates), new Schema(fields));
  }
}
