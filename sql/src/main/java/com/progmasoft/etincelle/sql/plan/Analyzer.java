/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import com.progmasoft.etincelle.core.DataType;
import com.progmasoft.etincelle.core.Field;
import com.progmasoft.etincelle.core.Schema;
import com.progmasoft.etincelle.sql.SqlException;
import com.progmasoft.etincelle.sql.parse.Ast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
  private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max");
  private static final Set<String> FUNCTIONS =
      Set.of("lower", "upper", "length", "abs", "coalesce");

  private final String sql;
  private final Catalog catalog;

  private Analyzer(String sql, Catalog catalog) {
    this.sql = sql;
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

  private SqlException error(int offset, String message) {
    return new SqlException(sql, offset, message);
  }

  /** The columns a query reads, and the name or alias that qualifies them. */
  private record Scope(Schema schema, String qualifier) {}

  /** An item of the select list once * is expanded. */
  private record Item(Ast.Expr expression, String alias) {}

  private Plan select(Ast.Select select) {
    Plan plan;
    Scope scope;
    if (select.from() == null) {
      plan = new Plan.OneRow();
      scope = new Scope(plan.schema(), null);
    } else {
      Ast.TableRef from = select.from();
      Optional<Catalog.Entry> entry =
          from.name().size() == 1 ? catalog.table(from.name().get(0)) : Optional.empty();
      if (entry.isEmpty()) {
        throw error(from.offset(), "there is no table " + String.join(".", from.name()));
      }
      plan = new Plan.Scan(entry.get().name(), entry.get().table());
      scope = new Scope(plan.schema(), from.alias() != null ? from.alias() : entry.get().name());
    }

    if (select.where() != null) {
      Binder where = new Binder(scope, null, "WHERE");
      plan = new Plan.Filter(plan, where.condition(select.where()));
    }

    List<Item> items = expand(select.items(), scope);
    boolean aggregates =
        !select.groupBy().isEmpty()
            || select.having() != null
            || items.stream().anyMatch(item -> hasAggregate(item.expression()))
            || select.orderBy().stream().anyMatch(order -> hasAggregate(order.expression()));

    Aggregation aggregation = null;
    Binder binder;
    if (aggregates) {
      aggregation = new Aggregation(scope);
      for (Ast.Expr key : select.groupBy()) {
        aggregation.addKey(groupKey(key, items, scope));
      }
      binder = new Binder(scope, aggregation, "the select list");
    } else {
      binder = new Binder(scope, null, "the select list");
    }

    Expression having = null;
    if (select.having() != null) {
      having = new Binder(scope, aggregation, "HAVING").condition(select.having());
    }

    List<Expression> outputs = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (Item item : items) {
      Expression bound = binder.bind(item.expression());
      if (bound.type() == null) {
        throw error(
            item.expression().offset(),
            "NULL alone has no type; write CAST(NULL AS type) for a column of NULLs");
      }
      outputs.add(bound);
      names.add(outputName(item, bound));
    }
    int visible = outputs.size();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < names.size(); i++) {
      if (!seen.add(names.get(i).toLowerCase(Locale.ROOT))) {
        throw error(
            items.get(i).expression().offset(),
            "the result has two columns named " + names.get(i) + "; give one an alias");
      }
    }

    List<Plan.SortKey> sortKeys = new ArrayList<>();
    for (Ast.OrderItem order : select.orderBy()) {
      int column =
          orderColumn(order.expression(), items, names, select.distinct(), binder, outputs);
      boolean nullsFirst = order.nullsFirst() != null ? order.nullsFirst() : !order.descending();
      sortKeys.add(new Plan.SortKey(column, order.descending(), nullsFirst));
    }

    if (aggregation != null) {
      plan = aggregation.plan(plan);
      if (having != null) {
        plan = new Plan.Filter(plan, having);
      }
    }
    List<Field> fields = new ArrayList<>();
    for (int i = 0; i < outputs.size(); i++) {
      String name = i < visible ? names.get(i) : "#order" + (i - visible + 1);
      fields.add(new Field(name, outputs.get(i).type(), outputs.get(i).nullable()));
    }
    plan = new Plan.Project(plan, outputs, new Schema(fields));
    if (select.distinct()) {
      plan = new Plan.Distinct(plan);
    }
    if (!sortKeys.isEmpty()) {
      plan = new Plan.Sort(plan, sortKeys);
    }
    if (select.limit() != null) {
      plan = new Plan.Limit(plan, select.limit());
    }
    if (outputs.size() > visible) {
      List<Expression> kept = new ArrayList<>();
      for (int i = 0; i < visible; i++) {
        Field field = fields.get(i);
        kept.add(new Expression.ColumnRef(i, field.name(), field.type(), field.nullable()));
      }
      plan = new Plan.Project(plan, kept, new Schema(fields.subList(0, visible)));
    }
    return plan;
  }

  private List<Item> expand(List<Ast.SelectItem> items, Scope scope) {
    List<Item> expanded = new ArrayList<>();
    for (Ast.SelectItem item : items) {
      if (item.expression() instanceof Ast.Star star) {
        if (star.qualifier() != null
            && (scope.qualifier() == null
                || !star.qualifier().equalsIgnoreCase(scope.qualifier()))) {
          throw error(star.offset(), "there is no table " + star.qualifier() + " in FROM");
        }
        if (scope.schema().size() == 0) {
          throw error(star.offset(), "* needs a table in FROM");
        }
        for (Field field : scope.schema().fields()) {
          expanded.add(new Item(new Ast.Name(List.of(field.name()), star.offset()), null));
        }
      } else {
        expanded.add(new Item(item.expression(), item.alias()));
      }
    }
    return expanded;
  }

  private static boolean hasAggregate(Ast.Expr expression) {
    return switch (expression) {
      case Ast.Call c ->
          AGGREGATES.contains(c.name().toLowerCase(Locale.ROOT))
              || c.arguments().stream().anyMatch(Analyzer::hasAggregate);
      case Ast.Unary u -> hasAggregate(u.operand());
      case Ast.Binary b -> hasAggregate(b.left()) || hasAggregate(b.right());
      case Ast.IsNull i -> hasAggregate(i.operand());
      case Ast.InList i ->
          hasAggregate(i.operand()) || i.items().stream().anyMatch(Analyzer::hasAggregate);
      case Ast.Between b ->
          hasAggregate(b.operand()) || hasAggregate(b.low()) || hasAggregate(b.high());
      case Ast.Like l -> hasAggregate(l.operand()) || hasAggregate(l.pattern());
      case Ast.Case c ->
          (c.operand() != null && hasAggregate(c.operand()))
              || c.whens().stream()
                  .anyMatch(w -> hasAggregate(w.condition()) || hasAggregate(w.result()))
              || (c.otherwise() != null && hasAggregate(c.otherwise()));
      case Ast.Cast c -> hasAggregate(c.operand());
      default -> false;
    };
  }

  /** A GROUP BY item: a position or a select list alias stands for its item. */
  private Ast.Expr groupKey(Ast.Expr key, List<Item> items, Scope scope) {
    if (key instanceof Ast.NumberLiteral number && !number.decimal()) {
      Item item = position(number, items, "GROUP BY");
      if (hasAggregate(item.expression())) {
        throw error(key.offset(), "GROUP BY " + number.text() + " names an aggregate");
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
      throw error(
          number.offset(),
          clause
              + " "
              + number.text()
              + " is not a position of the select list, 1 to "
              + items.size());
    }
    return items.get((int) index - 1);
  }

  private int orderColumn(
      Ast.Expr expression,
      List<Item> items,
      List<String> names,
      boolean distinct,
      Binder binder,
      List<Expression> outputs) {
    if (expression instanceof Ast.NumberLiteral number && !number.decimal()) {
      return items.indexOf(position(number, items, "ORDER BY"));
    }
    if (expression instanceof Ast.Name name && name.parts().size() == 1) {
      for (int i = 0; i < names.size(); i++) {
        if (names.get(i).equalsIgnoreCase(name.parts().get(0))) {
          return i;
        }
      }
    }
    String canonical = Text.canonical(expression);
    for (int i = 0; i < items.size(); i++) {
      if (Text.canonical(items.get(i).expression()).equals(canonical)) {
        return i;
      }
    }
    if (distinct) {
      throw error(expression.offset(), "with DISTINCT, ORDER BY takes the columns of the result");
    }
    Expression bound = binder.bind(expression);
    if (bound.type() == null) {
      throw error(expression.offset(), "ORDER BY NULL orders nothing");
    }
    outputs.add(bound);
    return outputs.size() - 1;
  }

  private static String outputName(Item item, Expression bound) {
    if (item.alias() != null) {
      return item.alias();
    }
    if (item.expression() instanceof Ast.Name && bound instanceof Expression.ColumnRef column) {
      return column.name();
    }
    return Text.display(item.expression());
  }

  /** The keys and aggregates of an aggregating query, gathered while its expressions resolve. */
  private final class Aggregation {
    private final Scope input;
    private final List<Expression> keys = new ArrayList<>();
    private final List<String> keyNames = new ArrayList<>();
    private final Map<String, Integer> keyIndexes = new HashMap<>();
    private final List<AggregateCall> aggregates = new ArrayList<>();
    private final Map<String, Integer> aggregateIndexes = new HashMap<>();

    Aggregation(Scope input) {
      this.input = input;
    }

    void addKey(Ast.Expr key) {
      String canonical = Text.canonical(key);
      if (keyIndexes.containsKey(canonical)) {
        return;
      }
      if (hasAggregate(key)) {
        throw error(key.offset(), "GROUP BY cannot hold an aggregate");
      }
      Expression bound = new Binder(input, null, "GROUP BY").bind(key);
      if (bound.type() == null) {
        throw error(key.offset(), "GROUP BY NULL groups nothing");
      }
      keyIndexes.put(canonical, keys.size());
      keys.add(bound);
      keyNames.add(
          key instanceof Ast.Name && bound instanceof Expression.ColumnRef column
              ? column.name()
              : Text.display(key));
    }

    /** The key {@code expression} is, as a column of the aggregate's rows, if it is one. */
    Expression key(Ast.Expr expression) {
      Integer index = keyIndexes.get(Text.canonical(expression));
      if (index == null) {
        return null;
      }
      Expression key = keys.get(index);
      return new Expression.ColumnRef(index, keyNames.get(index), key.type(), key.nullable());
    }

    /** The aggregate {@code call}, as a column of the aggregate's rows. */
    Expression aggregate(Ast.Call call) {
      String canonical = Text.canonical(call);
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
      String text = Text.display(call);
      if (call.star()) {
        if (!name.equals("count")) {
          throw error(call.offset(), name + "(*) is not an aggregate; count(*) is");
        }
        return new AggregateCall(
            AggregateCall.Kind.COUNT_STAR, null, false, DataType.LONG, text, call.offset());
      }
      if (call.arguments().size() != 1) {
        throw error(call.offset(), name + " takes one argument");
      }
      Ast.Expr argumentTree = call.arguments().get(0);
      if (hasAggregate(argumentTree)) {
        throw error(argumentTree.offset(), "an aggregate cannot be inside another aggregate");
      }
      Expression argument = new Binder(input, null, name).bind(argumentTree);
      if (argument.type() == null) {
        throw error(argumentTree.offset(), name + "(NULL) has no type; write CAST(NULL AS type)");
      }
      return switch (name) {
        case "count" ->
            new AggregateCall(
                AggregateCall.Kind.COUNT,
                argument,
                call.distinct(),
                DataType.LONG,
                text,
                call.offset());
        case "sum", "avg" -> {
          if (Values.numericRank(argument.type()) < 0) {
            throw error(
                argumentTree.offset(), name + " takes a number, not " + argument.type().sqlName());
          }
          DataType type =
              name.equals("avg") || argument.type().equals(DataType.DOUBLE)
                  ? DataType.DOUBLE
                  : DataType.LONG;
          AggregateCall.Kind kind =
              name.equals("sum") ? AggregateCall.Kind.SUM : AggregateCall.Kind.AVG;
          yield new AggregateCall(kind, argument, call.distinct(), type, text, call.offset());
        }
        default -> {
          AggregateCall.Kind kind =
              name.equals("min") ? AggregateCall.Kind.MIN : AggregateCall.Kind.MAX;
          yield new AggregateCall(
              kind, argument, call.distinct(), argument.type(), text, call.offset());
        }
      };
    }

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

  /** Resolves expressions in a scope, and after aggregation against its keys and aggregates. */
  private final class Binder {
    private final Scope scope;
    private final Aggregation aggregation;
    private final String clause;

    Binder(Scope scope, Aggregation aggregation, String clause) {
      this.scope = scope;
      this.aggregation = aggregation;
      this.clause = clause;
    }

    /** A condition: a BOOLEAN expression. */
    Expression condition(Ast.Expr tree) {
      Expression bound = typed(bind(tree), DataType.BOOLEAN, tree, clause);
      return bound;
    }

    Expression bind(Ast.Expr tree) {
      if (aggregation != null) {
        Expression key = aggregation.key(tree);
        if (key != null) {
          return key;
        }
        if (tree instanceof Ast.Call call
            && AGGREGATES.contains(call.name().toLowerCase(Locale.ROOT))) {
          return aggregation.aggregate(call);
        }
        if (tree instanceof Ast.Name name) {
          throw error(
              name.offset(),
              Text.display(name) + " is neither in GROUP BY nor inside an aggregate");
        }
      }
      return switch (tree) {
        case Ast.Name name -> column(name);
        case Ast.Star star ->
            throw error(star.offset(), "* is an item of the select list or count(*)");
        case Ast.NumberLiteral number -> number(number);
        case Ast.StringLiteral string -> new Expression.Literal(string.value(), DataType.STRING);
        case Ast.BooleanLiteral bool -> new Expression.Literal(bool.value(), DataType.BOOLEAN);
        case Ast.NullLiteral n -> new Expression.Literal(null, null);
        case Ast.Unary unary -> unary(unary);
        case Ast.Binary binary -> binary(binary);
        case Ast.IsNull isNull -> new Expression.IsNull(bind(isNull.operand()), isNull.negated());
        case Ast.InList in -> inList(in);
        case Ast.Between between -> {
          Ast.Expr low = new Ast.Binary(">=", between.operand(), between.low(), between.offset());
          Ast.Expr high = new Ast.Binary("<=", between.operand(), between.high(), between.offset());
          Expression both = bind(new Ast.Binary("AND", low, high, between.offset()));
          yield between.negated() ? new Expression.Not(both) : both;
        }
        case Ast.Like like -> {
          Expression operand = typed(bind(like.operand()), DataType.STRING, like.operand(), "LIKE");
          Expression pattern = typed(bind(like.pattern()), DataType.STRING, like.pattern(), "LIKE");
          Expression match = new Expression.Like(operand, pattern, like.offset());
          yield like.negated() ? new Expression.Not(match) : match;
        }
        case Ast.Case c -> caseWhen(c);
        case Ast.Cast cast -> cast(cast);
        case Ast.Call call -> call(call);
      };
    }

    private Expression column(Ast.Name name) {
      List<String> parts = name.parts();
      String column = parts.get(parts.size() - 1);
      if (parts.size() > 2
          || (parts.size() == 2
              && (scope.qualifier() == null
                  || !parts.get(0).equalsIgnoreCase(scope.qualifier())))) {
        throw error(name.offset(), "there is no table " + parts.get(0) + " in FROM");
      }
      Optional<Integer> index = scope.schema().indexOf(column);
      if (index.isEmpty()) {
        if (scope.schema().size() == 0) {
          throw error(
              name.offset(),
              "there is no column " + Text.display(name) + "; the query has no FROM");
        }
        List<String> columns = new ArrayList<>();
        for (Field field : scope.schema().fields()) {
          columns.add(field.name());
        }
        throw error(
            name.offset(),
            "there is no column "
                + Text.display(name)
                + "; the columns are "
                + String.join(", ", columns));
      }
      Field field = scope.schema().fields().get(index.get());
      return new Expression.ColumnRef(index.get(), field.name(), field.type(), field.nullable());
    }

    private Expression number(Ast.NumberLiteral number) {
      if (number.decimal()) {
        double value = Double.parseDouble(number.text());
        if (Double.isInfinite(value)) {
          throw error(number.offset(), number.text() + " is out of the range of DOUBLE");
        }
        return new Expression.Literal(value, DataType.DOUBLE);
      }
      long value;
      try {
        value = Long.parseLong(number.text());
      } catch (NumberFormatException e) {
        throw error(
            number.offset(),
            number.text() + " is out of the range of BIGINT; write it with a point for a DOUBLE");
      }
      return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
          ? new Expression.Literal((int) value, DataType.INT)
          : new Expression.Literal(value, DataType.LONG);
    }

    private Expression unary(Ast.Unary unary) {
      Expression operand = bind(unary.operand());
      if (unary.operator().equals("NOT")) {
        return new Expression.Not(typed(operand, DataType.BOOLEAN, unary.operand(), "NOT"));
      }
      if (operand.type() == null) {
        operand = new Expression.Literal(null, DataType.INT);
      }
      if (Values.numericRank(operand.type()) < 0) {
        throw error(
            unary.offset(),
            "unary " + unary.operator() + " takes a number, not " + operand.type().sqlName());
      }
      return unary.operator().equals("-")
          ? new Expression.Negate(operand, unary.offset())
          : operand;
    }

    private Expression binary(Ast.Binary binary) {
      String operator = binary.operator();
      Expression left = bind(binary.left());
      Expression right = bind(binary.right());
      switch (operator) {
        case "AND", "OR" -> {
          return new Expression.Logical(
              operator.equals("AND"),
              typed(left, DataType.BOOLEAN, binary.left(), operator),
              typed(right, DataType.BOOLEAN, binary.right(), operator));
        }
        case "||" -> {
          return new Expression.Concat(
              typed(left, DataType.STRING, binary.left(), "||"),
              typed(right, DataType.STRING, binary.right(), "||"));
        }
        case "=", "<>", "<", "<=", ">", ">=" -> {
          DataType type = common(List.of(left, right), binary.offset(), "cannot compare");
          if (type == null) {
            type = DataType.STRING;
          }
          return new Expression.Comparison(
              operator,
              typed(left, type, binary.left(), operator),
              typed(right, type, binary.right(), operator));
        }
        default -> {
          DataType type =
              common(List.of(left, right), binary.offset(), "cannot apply " + operator + " to");
          if (type == null) {
            type = DataType.INT;
          }
          if (Values.numericRank(type) < 0) {
            throw error(binary.offset(), operator + " takes numbers, not " + type.sqlName());
          }
          if (operator.equals("/")) {
            type = DataType.DOUBLE;
          }
          return new Expression.Arithmetic(
              operator,
              typed(left, type, binary.left(), operator),
              typed(right, type, binary.right(), operator),
              type,
              binary.offset());
        }
      }
    }

    private Expression inList(Ast.InList in) {
      Ast.Expr any = null;
      for (Ast.Expr item : in.items()) {
        Ast.Expr equal = new Ast.Binary("=", in.operand(), item, in.offset());
        any = any == null ? equal : new Ast.Binary("OR", any, equal, in.offset());
      }
      Expression bound = bind(any);
      return in.negated() ? new Expression.Not(bound) : bound;
    }

    private Expression caseWhen(Ast.Case c) {
      List<Expression> conditions = new ArrayList<>();
      List<Expression> results = new ArrayList<>();
      for (Ast.When when : c.whens()) {
        Ast.Expr condition =
            c.operand() == null
                ? when.condition()
                : new Ast.Binary("=", c.operand(), when.condition(), when.condition().offset());
        conditions.add(condition(condition));
        results.add(bind(when.result()));
      }
      Expression otherwise = c.otherwise() == null ? null : bind(c.otherwise());
      List<Expression> branches = new ArrayList<>(results);
      if (otherwise != null) {
        branches.add(otherwise);
      }
      DataType type = common(branches, c.offset(), "CASE cannot mix");
      if (type == null) {
        return new Expression.Literal(null, null);
      }
      List<Expression> typedResults = new ArrayList<>();
      for (int i = 0; i < results.size(); i++) {
        typedResults.add(typed(results.get(i), type, c.whens().get(i).result(), "CASE"));
      }
      return new Expression.CaseWhen(
          List.copyOf(conditions),
          List.copyOf(typedResults),
          otherwise == null ? null : typed(otherwise, type, c.otherwise(), "CASE"),
          type);
    }

    private Expression cast(Ast.Cast cast) {
      DataType target =
          switch (cast.type().toUpperCase(Locale.ROOT)) {
            case "INT", "INTEGER" -> DataType.INT;
            case "BIGINT", "LONG" -> DataType.LONG;
            case "DOUBLE" -> DataType.DOUBLE;
            case "STRING" -> DataType.STRING;
            case "BOOLEAN" -> DataType.BOOLEAN;
            default ->
                throw error(
                    cast.typeOffset(),
                    "there is no type "
                        + cast.type()
                        + "; the types are INT, BIGINT, DOUBLE, STRING and BOOLEAN");
          };
      Expression operand = bind(cast.operand());
      if (operand.type() == null) {
        return new Expression.Literal(null, target);
      }
      return operand.type().equals(target)
          ? operand
          : new Expression.Cast(operand, target, cast.offset());
    }

    private Expression call(Ast.Call call) {
      String name = call.name().toLowerCase(Locale.ROOT);
      if (AGGREGATES.contains(name)) {
        throw error(call.offset(), "an aggregate cannot be in " + clause);
      }
      if (!FUNCTIONS.contains(name)) {
        throw error(
            call.offset(),
            "there is no function "
                + call.name()
                + "; the functions are lower, upper, length, abs, coalesce, and the aggregates"
                + " count, sum, avg, min, max");
      }
      if (call.star() || call.distinct()) {
        throw error(call.offset(), name + " takes neither * nor DISTINCT");
      }
      List<Expression> arguments = new ArrayList<>();
      for (Ast.Expr argument : call.arguments()) {
        arguments.add(bind(argument));
      }
      if (name.equals("coalesce")) {
        if (arguments.isEmpty()) {
          throw error(call.offset(), "coalesce takes at least one argument");
        }
        DataType type = common(arguments, call.offset(), "coalesce cannot mix");
        if (type == null) {
          return new Expression.Literal(null, null);
        }
        List<Expression> typedArguments = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
          typedArguments.add(typed(arguments.get(i), type, call.arguments().get(i), "coalesce"));
        }
        return new Expression.Function(name, List.copyOf(typedArguments), type, call.offset());
      }
      if (arguments.size() != 1) {
        throw error(call.offset(), name + " takes one argument");
      }
      Expression argument = arguments.get(0);
      Ast.Expr tree = call.arguments().get(0);
      return switch (name) {
        case "lower", "upper" ->
            new Expression.Function(
                name,
                List.of(typed(argument, DataType.STRING, tree, name)),
                DataType.STRING,
                call.offset());
        case "length" ->
            new Expression.Function(
                name,
                List.of(typed(argument, DataType.STRING, tree, name)),
                DataType.INT,
                call.offset());
        default -> {
          Expression number =
              argument.type() == null ? new Expression.Literal(null, DataType.INT) : argument;
          if (Values.numericRank(number.type()) < 0) {
            throw error(tree.offset(), "abs takes a number, not " + number.type().sqlName());
          }
          yield new Expression.Function(name, List.of(number), number.type(), call.offset());
        }
      };
    }

    /**
     * The type values of {@code expressions} share: numbers widen, NULL takes the type of the
     * others; {@code null} when every one is a NULL.
     */
    private DataType common(List<Expression> expressions, int offset, String problem) {
      DataType type = null;
      for (Expression expression : expressions) {
        DataType next = expression.type();
        if (next == null) {
          continue;
        }
        if (type == null) {
          type = next;
        } else if (!type.equals(next)) {
          if (Values.numericRank(type) >= 0 && Values.numericRank(next) >= 0) {
            type = Values.wider(type, next);
          } else {
            throw error(offset, problem + " " + type.sqlName() + " and " + next.sqlName());
          }
        }
      }
      return type;
    }

    /**
     * {@code expression} as a value of {@code type}: a NULL takes it, a number widens to it, any
     * other type is an error.
     */
    private Expression typed(Expression expression, DataType type, Ast.Expr tree, String place) {
      DataType actual = expression.type();
      if (actual == null) {
        return new Expression.Literal(null, type);
      }
      if (actual.equals(type)) {
        return expression;
      }
      if (Values.numericRank(actual) >= 0
          && Values.numericRank(actual) < Values.numericRank(type)) {
        // A constant widens now, so the plan shows 1L rather than CAST(1 AS BIGINT).
        if (expression instanceof Expression.Literal literal) {
          return new Expression.Literal(
              Values.cast(literal.constant(), actual, type, tree.offset()), type);
        }
        return new Expression.Cast(expression, type, tree.offset());
      }
      throw error(tree.offset(), place + " takes " + type.sqlName() + ", not " + actual.sqlName());
    }
  }
}
