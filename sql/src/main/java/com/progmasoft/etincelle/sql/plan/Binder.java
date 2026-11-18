/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import com.progmasoft.etincelle.core.DataType;
import com.progmasoft.etincelle.core.Field;
import com.progmasoft.etincelle.sql.parse.Ast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves expressions of the syntax tree in a scope; after aggregation, against the keys and the
 * aggregates of the query instead of its columns.
 */
final class Binder {
  private final Source source;
  private final Source.Scope scope;
  private final Aggregation aggregation;
  private final String clause;

  /**
   * A binder over {@code scope}, or over the keys and aggregates of {@code aggregation} when it is
   * not {@code null}; {@code clause} names the place in errors, such as WHERE.
   */
  Binder(Source source, Source.Scope scope, Aggregation aggregation, String clause) {
    this.source = source;
    this.scope = scope;
    this.aggregation = aggregation;
    this.clause = clause;
  }

  /** A condition: a BOOLEAN expression. */
  Expression condition(Ast.Expr tree) {
    return typed(bind(tree), DataType.BOOLEAN, tree, clause);
  }

  /** {@code tree}, resolved and typed; a NULL alone has the type {@code null}. */
  Expression bind(Ast.Expr tree) {
    if (aggregation != null) {
      Expression aggregated = aggregated(tree);
      if (aggregated != null) {
        return aggregated;
      }
    }
    return switch (tree) {
      case Ast.Name name -> column(name);
      case Ast.Star star ->
          throw source.error(star.offset(), "* is an item of the select list or count(*)");
      case Ast.NumberLiteral number -> number(number);
      case Ast.StringLiteral string -> new Expression.Literal(string.value(), DataType.STRING);
      case Ast.BooleanLiteral bool -> new Expression.Literal(bool.value(), DataType.BOOLEAN);
      case Ast.NullLiteral nothing -> new Expression.Literal(null, null);
      default -> compound(tree);
    };
  }

  /** {@code tree} as a key or an aggregate of the query, or {@code null} when it is neither. */
  private Expression aggregated(Ast.Expr tree) {
    Expression key = aggregation.key(tree);
    if (key != null) {
      return key;
    }
    if (tree instanceof Ast.Call call && Source.isAggregate(call.name())) {
      return aggregation.aggregate(call);
    }
    if (tree instanceof Ast.Name name) {
      throw source.error(
          name.offset(), name.toSql(false) + " is neither in GROUP BY nor inside an aggregate");
    }
    return null;
  }

  private Expression compound(Ast.Expr tree) {
    return switch (tree) {
      case Ast.Unary unary -> unary(unary);
      case Ast.Binary binary -> binary(binary);
      case Ast.IsNull isNull -> new Expression.IsNull(bind(isNull.operand()), isNull.negated());
      case Ast.InList in -> inList(in);
      case Ast.Between between -> between(between);
      case Ast.Like like -> like(like);
      case Ast.Case c -> caseWhen(c);
      case Ast.Cast cast -> cast(cast);
      case Ast.Call call -> call(call);
      default -> throw new IllegalStateException("not compound: " + tree);
    };
  }

  private Expression column(Ast.Name name) {
    List<String> parts = name.parts();
    if (parts.size() > 2
        || (parts.size() == 2
            && (scope.qualifier() == null || !parts.get(0).equalsIgnoreCase(scope.qualifier())))) {
      throw source.error(name.offset(), "there is no table " + parts.get(0) + " in FROM");
    }
    Optional<Integer> index = scope.schema().indexOf(parts.get(parts.size() - 1));
    if (index.isEmpty()) {
      throw source.error(name.offset(), "there is no column " + name.toSql(false) + columnsHint());
    }
    Field field = scope.schema().fields().get(index.get());
    return new Expression.ColumnRef(index.get(), field.name(), field.type(), field.nullable());
  }

  private String columnsHint() {
    if (scope.schema().size() == 0) {
      return "; the query has no FROM";
    }
    List<String> columns = new ArrayList<>();
    for (Field field : scope.schema().fields()) {
      columns.add(field.name());
    }
    return "; the columns are " + String.join(", ", columns);
  }

  private Expression number(Ast.NumberLiteral number) {
    if (number.decimal()) {
      double value = Double.parseDouble(number.text());
      if (Double.isInfinite(value)) {
        throw source.error(number.offset(), number.text() + " is out of the range of DOUBLE");
      }
      return new Expression.Literal(value, DataType.DOUBLE);
    }
    long value;
    try {
      value = Long.parseLong(number.text());
    } catch (NumberFormatException e) {
      throw source.error(
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
    Expression number = numeric(operand, unary.operand(), "unary " + unary.operator());
    return unary.operator().equals("-") ? new Expression.Negate(number, unary.offset()) : number;
  }

  /** {@code operand} as a number; a NULL becomes an INT NULL. */
  private Expression numeric(Expression operand, Ast.Expr tree, String what) {
    if (operand.type() == null) {
      return new Expression.Literal(null, DataType.INT);
    }
    if (Values.numericRank(operand.type()) < 0) {
      throw source.error(tree.offset(), what + " takes a number, not " + operand.type().sqlName());
    }
    return operand;
  }

  private Expression binary(Ast.Binary binary) {
    String operator = binary.operator();
    Expression left = bind(binary.left());
    Expression right = bind(binary.right());
    return switch (operator) {
      case "AND", "OR" ->
          new Expression.Logical(
              operator.equals("AND"),
              typed(left, DataType.BOOLEAN, binary.left(), operator),
              typed(right, DataType.BOOLEAN, binary.right(), operator));
      case "||" ->
          new Expression.Concat(
              typed(left, DataType.STRING, binary.left(), "||"),
              typed(right, DataType.STRING, binary.right(), "||"));
      case "=", "<>", "<", "<=", ">", ">=" -> comparison(binary, left, right);
      default -> arithmetic(binary, left, right);
    };
  }

  private Expression comparison(Ast.Binary binary, Expression left, Expression right) {
    DataType type = common(List.of(left, right), binary.offset(), "cannot compare");
    if (type == null) {
      type = DataType.STRING;
    }
    return new Expression.Comparison(
        binary.operator(),
        typed(left, type, binary.left(), binary.operator()),
        typed(right, type, binary.right(), binary.operator()));
  }

  private Expression arithmetic(Ast.Binary binary, Expression left, Expression right) {
    String operator = binary.operator();
    DataType type =
        common(List.of(left, right), binary.offset(), "cannot apply " + operator + " to");
    if (type == null) {
      type = DataType.INT;
    }
    if (Values.numericRank(type) < 0) {
      throw source.error(binary.offset(), operator + " takes numbers, not " + type.sqlName());
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

  private Expression inList(Ast.InList in) {
    Ast.Expr any = null;
    for (Ast.Expr item : in.items()) {
      Ast.Expr equal = new Ast.Binary("=", in.operand(), item, in.offset());
      any = any == null ? equal : new Ast.Binary("OR", any, equal, in.offset());
    }
    Expression bound = bind(any);
    return in.negated() ? new Expression.Not(bound) : bound;
  }

  private Expression between(Ast.Between between) {
    Ast.Expr low = new Ast.Binary(">=", between.operand(), between.low(), between.offset());
    Ast.Expr high = new Ast.Binary("<=", between.operand(), between.high(), between.offset());
    Expression both = bind(new Ast.Binary("AND", low, high, between.offset()));
    return between.negated() ? new Expression.Not(both) : both;
  }

  private Expression like(Ast.Like like) {
    Expression operand = typed(bind(like.operand()), DataType.STRING, like.operand(), "LIKE");
    Expression pattern = typed(bind(like.pattern()), DataType.STRING, like.pattern(), "LIKE");
    Expression match = new Expression.Like(operand, pattern, like.offset());
    return like.negated() ? new Expression.Not(match) : match;
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
    DataType target = castType(cast);
    Expression operand = bind(cast.operand());
    if (operand.type() == null) {
      return new Expression.Literal(null, target);
    }
    return operand.type().equals(target)
        ? operand
        : new Expression.Cast(operand, target, cast.offset());
  }

  private DataType castType(Ast.Cast cast) {
    return switch (cast.type().toUpperCase(Locale.ROOT)) {
      case "INT", "INTEGER" -> DataType.INT;
      case "BIGINT", "LONG" -> DataType.LONG;
      case "DOUBLE" -> DataType.DOUBLE;
      case "STRING" -> DataType.STRING;
      case "BOOLEAN" -> DataType.BOOLEAN;
      default ->
          throw source.error(
              cast.typeOffset(),
              "there is no type "
                  + cast.type()
                  + "; the types are INT, BIGINT, DOUBLE, STRING and BOOLEAN");
    };
  }

  private Expression call(Ast.Call call) {
    String name = call.name().toLowerCase(Locale.ROOT);
    if (Source.isAggregate(name)) {
      throw source.error(call.offset(), "an aggregate cannot be in " + clause);
    }
    if (!Source.FUNCTIONS.contains(name)) {
      throw source.error(
          call.offset(),
          "there is no function "
              + call.name()
              + "; the functions are lower, upper, length, abs, coalesce, and the aggregates"
              + " count, sum, avg, min, max");
    }
    if (call.star() || call.distinct()) {
      throw source.error(call.offset(), name + " takes neither * nor DISTINCT");
    }
    List<Expression> arguments = new ArrayList<>();
    for (Ast.Expr argument : call.arguments()) {
      arguments.add(bind(argument));
    }
    if (name.equals("coalesce")) {
      return coalesce(call, arguments);
    }
    if (arguments.size() != 1) {
      throw source.error(call.offset(), name + " takes one argument");
    }
    return function(name, arguments.get(0), call);
  }

  private Expression coalesce(Ast.Call call, List<Expression> arguments) {
    if (arguments.isEmpty()) {
      throw source.error(call.offset(), "coalesce takes at least one argument");
    }
    DataType type = common(arguments, call.offset(), "coalesce cannot mix");
    if (type == null) {
      return new Expression.Literal(null, null);
    }
    List<Expression> typedArguments = new ArrayList<>();
    for (int i = 0; i < arguments.size(); i++) {
      typedArguments.add(typed(arguments.get(i), type, call.arguments().get(i), "coalesce"));
    }
    return new Expression.Function("coalesce", List.copyOf(typedArguments), type, call.offset());
  }

  private Expression function(String name, Expression argument, Ast.Call call) {
    Ast.Expr tree = call.arguments().get(0);
    if (name.equals("abs")) {
      Expression number = numeric(argument, tree, "abs");
      return new Expression.Function(name, List.of(number), number.type(), call.offset());
    }
    Expression text = typed(argument, DataType.STRING, tree, name);
    DataType type = name.equals("length") ? DataType.INT : DataType.STRING;
    return new Expression.Function(name, List.of(text), type, call.offset());
  }

  /**
   * The type values of {@code expressions} share: numbers widen, NULL takes the type of the others;
   * {@code null} when every one is a NULL.
   */
  private DataType common(List<Expression> expressions, int offset, String problem) {
    DataType type = null;
    for (Expression expression : expressions) {
      DataType next = expression.type();
      if (next == null || next.equals(type)) {
        continue;
      }
      if (type == null) {
        type = next;
      } else if (Values.numericRank(type) >= 0 && Values.numericRank(next) >= 0) {
        type = Values.wider(type, next);
      } else {
        throw source.error(offset, problem + " " + type.sqlName() + " and " + next.sqlName());
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
    if (Values.numericRank(actual) < 0 || Values.numericRank(actual) >= Values.numericRank(type)) {
      throw source.error(
          tree.offset(), place + " takes " + type.sqlName() + ", not " + actual.sqlName());
    }
    // A constant widens now, so the plan shows 1L rather than CAST(1 AS BIGINT).
    if (expression instanceof Expression.Literal literal) {
      return new Expression.Literal(
          Values.cast(literal.constant(), actual, type, tree.offset()), type);
    }
    return new Expression.Cast(expression, type, tree.offset());
  }
}
