/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import com.progmasoft.etincelle.core.Field;
import com.progmasoft.etincelle.core.Schema;
import com.progmasoft.etincelle.sql.SqlException;
import com.progmasoft.etincelle.sql.parse.Ast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the syntax tree of a SELECT against the tables of a catalog into a typed {@link Plan}.
 *
 * <p>Types: INT, BIGINT and DOUBLE widen to each other in that order where an operator meets two of
 * them; other types never convert without CAST. A NULL takes the type its place asks for, and a
 * result column that is NULL alone needs a CAST.
 *
 * <p>A query aggregates when it has GROUP BY or HAVING or an aggregate in its select list or ORDER
 * BY. Then every column it names outside an aggregate must be a GROUP BY expression. GROUP BY takes
 * expressions, select list aliases and positions; ORDER BY takes positions, the names of result
 * columns and expressions, which need not be in the result unless the query is DISTINCT.
 */
public final class Analyzer {
  private final Source source;
  private final Catalog catalog;

  private Analyzer(String sql, Catalog catalog) {
    this.source = new Source(sql);
    this.catalog = catalog;
  }

  /**
   * The plan of {@code select}, the syntax tree of {@code sql}.
   *
   * @throws SqlException when a name, a type or an aggregate does not resolve
   */
  public static Plan analyze(String sql, Ast.Select select, Catalog catalog) {
    return new Analyzer(sql, catalog).select(select);
  }

  /** An item of the select list once * is expanded. */
  private record Item(Ast.Expr expression, String alias) {}

  /** The input of the query and the scope its names resolve in. */
  private record Input(Plan plan, Source.Scope scope) {}

  /** The resolved select list: the expressions, then the hidden ORDER BY columns, and the names. */
  private record Outputs(List<Expression> expressions, List<String> names, int visible) {}

  private Plan select(Ast.Select select) {
    Input input = from(select.from());
    Plan plan = input.plan();
    Source.Scope scope = input.scope();
    if (select.where() != null) {
      plan =
          new Plan.Filter(plan, new Binder(source, scope, null, "WHERE").condition(select.where()));
    }
    List<Item> items = expand(select.items(), scope);
    Aggregation aggregation = aggregation(select, items, scope);
    Binder binder = new Binder(source, scope, aggregation, "the select list");
    Expression having =
        select.having() == null
            ? null
            : new Binder(source, scope, aggregation, "HAVING").condition(select.having());
    Outputs outputs = outputs(items, binder);
    List<Plan.SortKey> sortKeys = sortKeys(select, items, outputs, binder);
    if (aggregation != null) {
      plan = aggregation.plan(plan);
      if (having != null) {
        plan = new Plan.Filter(plan, having);
      }
    }
    return finish(plan, outputs, select, sortKeys);
  }

  private Input from(Ast.TableRef from) {
    if (from == null) {
      Plan one = new Plan.OneRow();
      return new Input(one, new Source.Scope(one.schema(), null));
    }
    Optional<Catalog.Entry> entry =
        from.name().size() == 1 ? catalog.table(from.name().get(0)) : Optional.empty();
    if (entry.isEmpty()) {
      throw source.error(from.offset(), "there is no table " + String.join(".", from.name()));
    }
    Plan scan = new Plan.Scan(entry.get().name(), entry.get().table());
    String qualifier = from.alias() != null ? from.alias() : entry.get().name();
    return new Input(scan, new Source.Scope(scan.schema(), qualifier));
  }

  private List<Item> expand(List<Ast.SelectItem> items, Source.Scope scope) {
    List<Item> expanded = new ArrayList<>();
    for (Ast.SelectItem item : items) {
      if (item.expression() instanceof Ast.Star star) {
        expandStar(star, scope, expanded);
      } else {
        expanded.add(new Item(item.expression(), item.alias()));
      }
    }
    return expanded;
  }

  private void expandStar(Ast.Star star, Source.Scope scope, List<Item> expanded) {
    if (star.qualifier() != null
        && (scope.qualifier() == null || !star.qualifier().equalsIgnoreCase(scope.qualifier()))) {
      throw source.error(star.offset(), "there is no table " + star.qualifier() + " in FROM");
    }
    if (scope.schema().size() == 0) {
      throw source.error(star.offset(), "* needs a table in FROM");
    }
    for (Field field : scope.schema().fields()) {
      expanded.add(new Item(new Ast.Name(List.of(field.name()), star.offset()), null));
    }
  }

  /**
   * The aggregation of the query with its GROUP BY keys, or {@code null} when it does not
   * aggregate.
   */
  private Aggregation aggregation(Ast.Select select, List<Item> items, Source.Scope scope) {
    boolean aggregates =
        !select.groupBy().isEmpty()
            || select.having() != null
            || items.stream().anyMatch(item -> Source.hasAggregate(item.expression()))
            || select.orderBy().stream().anyMatch(order -> Source.hasAggregate(order.expression()));
    if (!aggregates) {
      return null;
    }
    Aggregation aggregation = new Aggregation(source, scope);
    for (Ast.Expr key : select.groupBy()) {
      aggregation.addKey(groupKey(key, items, scope));
    }
    return aggregation;
  }

  /** A GROUP BY item: a position or a select list alias stands for its item. */
  private Ast.Expr groupKey(Ast.Expr key, List<Item> items, Source.Scope scope) {
    if (key instanceof Ast.NumberLiteral number && !number.decimal()) {
      Item item = position(number, items, "GROUP BY");
      if (Source.hasAggregate(item.expression())) {
        throw source.error(key.offset(), "GROUP BY " + number.text() + " names an aggregate");
      }
      return item.expression();
    }
    if (key instanceof Ast.Name name
        && name.parts().size() == 1
        && scope.schema().indexOf(name.parts().get(0)).isEmpty()) {
      for (Item item : items) {
        if (item.alias() != null && item.alias().equalsIgnoreCase(name.parts().get(0))) {
          return item.expression();
        }
      }
    }
    return key;
  }

  private Item position(Ast.NumberLiteral number, List<Item> items, String clause) {
    long index;
    try {
      index = Long.parseLong(number.text());
    } catch (NumberFormatException e) {
      index = -1;
    }
    if (index < 1 || index > items.size()) {
      throw source.error(
          number.offset(),
          clause
              + " "
              + number.text()
              + " is not a position of the select list, 1 to "
              + items.size());
    }
    return items.get((int) index - 1);
  }

  private Outputs outputs(List<Item> items, Binder binder) {
    List<Expression> expressions = new ArrayList<>();
    List<String> names = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Item item : items) {
      Expression bound = binder.bind(item.expression());
      if (bound.type() == null) {
        throw source.error(
            item.expression().offset(),
            "NULL alone has no type; write CAST(NULL AS type) for a column of NULLs");
      }
      String name = outputName(item, bound);
      if (!seen.add(name.toLowerCase(Locale.ROOT))) {
        throw source.error(
            item.expression().offset(),
            "the result has two columns named " + name + "; give one an alias");
      }
      expressions.add(bound);
      names.add(name);
    }
    return new Outputs(expressions, names, items.size());
  }

  private static String outputName(Item item, Expression bound) {
    if (item.alias() != null) {
      return item.alias();
    }
    if (item.expression() instanceof Ast.Name && bound instanceof Expression.ColumnRef column) {
      return column.name();
    }
    return item.expression().toSql(false);
  }

  private List<Plan.SortKey> sortKeys(
      Ast.Select select, List<Item> items, Outputs outputs, Binder binder) {
    List<Plan.SortKey> keys = new ArrayList<>();
    for (Ast.OrderItem order : select.orderBy()) {
      int column = orderColumn(order.expression(), items, outputs, select.distinct(), binder);
      boolean nullsFirst = order.nullsFirst() != null ? order.nullsFirst() : !order.descending();
      keys.add(new Plan.SortKey(column, order.descending(), nullsFirst));
    }
    return keys;
  }

  /**
   * The column of the result an ORDER BY item names: a position, a result column name, an item's
   * expression, or a hidden column added for an expression outside the result.
   */
  private int orderColumn(
      Ast.Expr expression, List<Item> items, Outputs outputs, boolean distinct, Binder binder) {
    if (expression instanceof Ast.NumberLiteral number && !number.decimal()) {
      return items.indexOf(position(number, items, "ORDER BY"));
    }
    int named = resultColumn(expression, items, outputs.names());
    if (named >= 0) {
      return named;
    }
    if (distinct) {
      throw source.error(
          expression.offset(), "with DISTINCT, ORDER BY takes the columns of the result");
    }
    Expression bound = binder.bind(expression);
    if (bound.type() == null) {
      throw source.error(expression.offset(), "ORDER BY NULL orders nothing");
    }
    outputs.expressions().add(bound);
    return outputs.expressions().size() - 1;
  }

  /** The result column {@code expression} names or is, or -1. */
  private static int resultColumn(Ast.Expr expression, List<Item> items, List<String> names) {
    if (expression instanceof Ast.Name name && name.parts().size() == 1) {
      for (int i = 0; i < names.size(); i++) {
        if (names.get(i).equalsIgnoreCase(name.parts().get(0))) {
          return i;
        }
      }
    }
    String canonical = expression.toSql(true);
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).expression().toSql(true).equals(canonical)) {
        return i;
      }
    }
    return -1;
  }

  /** Project, then DISTINCT, ORDER BY and LIMIT, and a last Project that drops hidden columns. */
  private static Plan finish(
      Plan input, Outputs outputs, Ast.Select select, List<Plan.SortKey> sortKeys) {
    List<Expression> expressions = outputs.expressions();
    List<Field> fields = new ArrayList<>();
    for (int i = 0; i < expressions.size(); i++) {
      String name =
          i < outputs.visible() ? outputs.names().get(i) : "#order" + (i - outputs.visible() + 1);
      fields.add(new Field(name, expressions.get(i).type(), expressions.get(i).nullable()));
    }
    Plan plan = new Plan.Project(input, List.copyOf(expressions), new Schema(fields));
    if (select.distinct()) {
      plan = new Plan.Distinct(plan);
    }
    if (!sortKeys.isEmpty()) {
      plan = new Plan.Sort(plan, sortKeys);
    }
    if (select.limit() != null) {
      plan = new Plan.Limit(plan, select.limit());
    }
    if (expressions.size() == outputs.visible()) {
      return plan;
    }
    List<Expression> kept = new ArrayList<>();
    for (int i = 0; i < outputs.visible(); i++) {
      Field field = fields.get(i);
      kept.add(new Expression.ColumnRef(i, field.name(), field.type(), field.nullable()));
    }
    return new Plan.Project(plan, kept, new Schema(fields.subList(0, outputs.visible())));
  }
}
