/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import com.progmasoft.etincelle.core.Batch;
import com.progmasoft.etincelle.core.Column;
import com.progmasoft.etincelle.core.ColumnBuilder;
import com.progmasoft.etincelle.core.DataType;
import com.progmasoft.etincelle.sql.Identifiers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A resolved, typed expression over the rows of a batch. Values are Java objects: {@link Integer}
 * for INT, {@link Long} for BIGINT, {@link Double}, {@link Boolean}, {@link String}, and {@code
 * null}.
 */
public sealed interface Expression {
  /** The type of the values. */
  DataType type();

  /** Whether a value can be null. */
  boolean nullable();

  /** The value for {@code row} of {@code batch}. */
  Object value(Batch batch, int row);

  /** The expression as EXPLAIN writes it. */
  String describe();

  /** The values for every row of {@code batch}. */
  default Column evaluate(Batch batch) {
    ColumnBuilder builder = Column.builder(type(), batch.rowCount());
    for (int row = 0; row < batch.rowCount(); row++) {
      builder.append(value(batch, row));
    }
    return builder.build();
  }

  /** Column {@code index} of the input. */
  record ColumnRef(int index, String name, DataType type, boolean nullable) implements Expression {
    @Override
    public Object value(Batch batch, int row) {
      return batch.column(index).get(row);
    }

    @Override
    public Column evaluate(Batch batch) {
      return batch.column(index);
    }

    @Override
    public String describe() {
      return Identifiers.quote(name);
    }
  }

  /** A constant; a NULL that the analyzer has not typed yet has the type {@code null}. */
  record Literal(Object constant, DataType type) implements Expression {
    @Override
    public boolean nullable() {
      return constant == null;
    }

    @Override
    public Object value(Batch batch, int row) {
      return constant;
    }

    @Override
    public String describe() {
      if (constant == null) {
        return "NULL";
      }
      if (constant instanceof String s) {
        return "'" + s.replace("'", "''") + "'";
      }
      if (constant instanceof Long l) {
        return l + "L";
      }
      if (constant instanceof Boolean b) {
        return b ? "TRUE" : "FALSE";
      }
      return String.valueOf(constant);
    }
  }

  /**
   * {@code + - * / %} on numbers of one type, the type of the result but for /, which is DOUBLE.
   */
  record Arithmetic(String operator, Expression left, Expression right, DataType type, int offset)
      implements Expression {
    @Override
    public boolean nullable() {
      return left.nullable() || right.nullable();
    }

    @Override
    public Object value(Batch batch, int row) {
      Object a = left.value(batch, row);
      if (a == null) {
        return null;
      }
      Object b = right.value(batch, row);
      if (b == null) {
        return null;
      }
      try {
        return switch (a) {
          case Integer x -> integer(x, (Integer) b);
          case Long x -> bigint(x, (Long) b);
          case Double x -> floating(x, (Double) b);
          default -> throw new IllegalStateException("arithmetic on " + a);
        };
      } catch (ArithmeticException e) {
        throw new EvaluationException(offset, type.sqlName() + " overflow in " + describe());
      }
    }

    private Object integer(int x, int y) {
      return switch (operator) {
        case "+" -> Math.addExact(x, y);
        case "-" -> Math.subtractExact(x, y);
        case "*" -> Math.multiplyExact(x, y);
        case "%" -> {
          zero(y == 0);
          yield x % y;
        }
        default -> throw new IllegalStateException(operator);
      };
    }

    private Object bigint(long x, long y) {
      return switch (operator) {
        case "+" -> Math.addExact(x, y);
        case "-" -> Math.subtractExact(x, y);
        case "*" -> Math.multiplyExact(x, y);
        case "%" -> {
          zero(y == 0);
          yield x % y;
        }
        default -> throw new IllegalStateException(operator);
      };
    }

    private Object floating(double x, double y) {
      return switch (operator) {
        case "+" -> x + y;
        case "-" -> x - y;
        case "*" -> x * y;
        case "/" -> {
          zero(y == 0.0);
          yield x / y;
        }
        case "%" -> {
          zero(y == 0.0);
          yield x % y;
        }
        default -> throw new IllegalStateException(operator);
      };
    }

    private void zero(boolean isZero) {
      if (isZero) {
        throw new EvaluationException(offset, "division by zero in " + describe());
      }
    }

    @Override
    public String describe() {
      return "(" + left.describe() + " " + operator + " " + right.describe() + ")";
    }
  }

  /** {@code -x}. */
  record Negate(Expression operand, int offset) implements Expression {
    @Override
    public DataType type() {
      return operand.type();
    }

    @Override
    public boolean nullable() {
      return operand.nullable();
    }

    @Override
    public Object value(Batch batch, int row) {
      Object a = operand.value(batch, row);
      try {
        return switch (a) {
          case null -> null;
          case Integer x -> Math.negateExact(x);
          case Long x -> Math.negateExact(x);
          case Double x -> -x;
          default -> throw new IllegalStateException("negation of " + a);
        };
      } catch (ArithmeticException e) {
        throw new EvaluationException(offset, type().sqlName() + " overflow in " + describe());
      }
    }

    @Override
    public String describe() {
      return "(- " + operand.describe() + ")";
    }
  }

  /** A comparison of two values of one type: {@code = <> < <= > >=}. */
  record Comparison(String operator, Expression left, Expression right) implements Expression {
    @Override
    public DataType type() {
      return DataType.BOOLEAN;
    }

    @Override
    public boolean nullable() {
      return left.nullable() || right.nullable();
    }

    @Override
    public Object value(Batch batch, int row) {
      Object a = left.value(batch, row);
      if (a == null) {
        return null;
      }
      Object b = right.value(batch, row);
      if (b == null) {
        return null;
      }
      int order = Values.compare(a, b);
      return switch (operator) {
        case "=" -> order == 0;
        case "<>" -> order != 0;
        case "<" -> order < 0;
        case "<=" -> order <= 0;
        case ">" -> order > 0;
        case ">=" -> order >= 0;
        default -> throw new IllegalStateException(operator);
      };
    }

    @Override
    public String describe() {
      return "(" + left.describe() + " " + operator + " " + right.describe() + ")";
    }
  }

  /** AND or OR with three-valued logic: FALSE AND NULL is FALSE, TRUE OR NULL is TRUE. */
  record Logical(boolean isAnd, Expression left, Expression right) implements Expression {
    @Override
    public DataType type() {
      return DataType.BOOLEAN;
    }

    @Override
    public boolean nullable() {
      return left.nullable() || right.nullable();
    }

    @Override
    public Object value(Batch batch, int row) {
      Object a = left.value(batch, row);
      // The value that decides the result alone: FALSE for AND, TRUE for OR.
      Boolean decisive = !isAnd;
      if (decisive.equals(a)) {
        return decisive;
      }
      Object b = right.value(batch, row);
      if (decisive.equals(b)) {
        return decisive;
      }
      return a == null || b == null ? null : !decisive;
    }

    @Override
    public String describe() {
      return "(" + left.describe() + (isAnd ? " AND " : " OR ") + right.describe() + ")";
    }
  }

  /** NOT. */
  record Not(Expression operand) implements Expression {
    @Override
    public DataType type() {
      return DataType.BOOLEAN;
    }

    @Override
    public boolean nullable() {
      return operand.nullable();
    }

    @Override
    public Object value(Batch batch, int row) {
      Object a = operand.value(batch, row);
      return a == null ? null : !(Boolean) a;
    }

    @Override
    public String describe() {
      return "(NOT " + operand.describe() + ")";
    }
  }

  /** IS NULL or IS NOT NULL. */
  record IsNull(Expression operand, boolean negated) implements Expression {
    @Override
    public DataType type() {
      return DataType.BOOLEAN;
    }

    @Override
    public boolean nullable() {
      return false;
    }

    @Override
    public Object value(Batch batch, int row) {
      return (operand.value(batch, row) == null) != negated;
    }

    @Override
    public String describe() {
      return "(" + operand.describe() + (negated ? " IS NOT NULL)" : " IS NULL)");
    }
  }

  /** CAST to another type. */
  record Cast(Expression operand, DataType type, int offset) implements Expression {
    @Override
    public boolean nullable() {
      return operand.nullable();
    }

    @Override
    public Object value(Batch batch, int row) {
      Object a = operand.value(batch, row);
      return a == null ? null : Values.cast(a, operand.type(), type, offset);
    }

    @Override
    public String describe() {
      return "CAST(" + operand.describe() + " AS " + type.sqlName() + ")";
    }
  }

  /** {@code a || b} on two strings. */
  record Concat(Expression left, Expression right) implements Expression {
    @Override
    public DataType type() {
      return DataType.STRING;
    }

    @Override
    public boolean nullable() {
      return left.nullable() || right.nullable();
    }

    @Override
    public Object value(Batch batch, int row) {
      Object a = left.value(batch, row);
      if (a == null) {
        return null;
      }
      Object b = right.value(batch, row);
      return b == null ? null : (String) a + b;
    }

    @Override
    public String describe() {
      return "(" + left.describe() + " || " + right.describe() + ")";
    }
  }

  /** {@code x LIKE pattern}. */
  record Like(Expression operand, Expression pattern, int offset) implements Expression {
    @Override
    public DataType type() {
      return DataType.BOOLEAN;
    }

    @Override
    public boolean nullable() {
      return operand.nullable() || pattern.nullable();
    }

    @Override
    public Object value(Batch batch, int row) {
      Object a = operand.value(batch, row);
      if (a == null) {
        return null;
      }
      Object p = pattern.value(batch, row);
      return p == null ? null : Values.like((String) a, (String) p, offset);
    }

    @Override
    public String describe() {
      return "(" + operand.describe() + " LIKE " + pattern.describe() + ")";
    }
  }

  /** CASE WHEN condition THEN result ... ELSE otherwise END; the else is NULL when left out. */
  record CaseWhen(
      List<Expression> conditions, List<Expression> results, Expression otherwise, DataType type)
      implements Expression {
    @Override
    public boolean nullable() {
      return otherwise == null
          || otherwise.nullable()
          || results.stream().anyMatch(Expression::nullable);
    }

    @Override
    public Object value(Batch batch, int row) {
      for (int i = 0; i < conditions.size(); i++) {
        if (Boolean.TRUE.equals(conditions.get(i).value(batch, row))) {
          return results.get(i).value(batch, row);
        }
      }
      return otherwise == null ? null : otherwise.value(batch, row);
    }

    @Override
    public String describe() {
      StringBuilder text = new StringBuilder("CASE");
      for (int i = 0; i < conditions.size(); i++) {
        text.append(" WHEN ")
            .append(conditions.get(i).describe())
            .append(" THEN ")
            .append(results.get(i).describe());
      }
      if (otherwise != null) {
        text.append(" ELSE ").append(otherwise.describe());
      }
      return text.append(" END").toString();
    }
  }

  /** A function of the rows: lower, upper, length, abs or coalesce. */
  record Function(String name, List<Expression> arguments, DataType type, int offset)
      implements Expression {
    @Override
    public boolean nullable() {
      return name.equals("coalesce")
          ? arguments.stream().allMatch(Expression::nullable)
          : arguments.get(0).nullable();
    }

    @Override
    public Object value(Batch batch, int row) {
      if (name.equals("coalesce")) {
        for (Expression argument : arguments) {
          Object a = argument.value(batch, row);
          if (a != null) {
            return a;
          }
        }
        return null;
      }
      Object a = arguments.get(0).value(batch, row);
      if (a == null) {
        return null;
      }
      return switch (name) {
        case "lower" -> ((String) a).toLowerCase(Locale.ROOT);
        case "upper" -> ((String) a).toUpperCase(Locale.ROOT);
        case "length" -> ((String) a).codePointCount(0, ((String) a).length());
        case "abs" -> abs(a);
        default -> throw new IllegalStateException(name);
      };
    }

    private Object abs(Object a) {
      try {
        return switch (a) {
          case Integer x -> Math.absExact(x);
          case Long x -> Math.absExact(x);
          case Double x -> Math.abs(x);
          default -> throw new IllegalStateException("abs of " + a);
        };
      } catch (ArithmeticException e) {
        throw new EvaluationException(offset, type.sqlName() + " overflow in " + describe());
      }
    }

    @Override
    public String describe() {
      List<String> parts = new ArrayList<>();
      for (Expression argument : arguments) {
        parts.add(argument.describe());
      }
      return name + "(" + String.join(", ", parts) + ")";
    }
  }
}
