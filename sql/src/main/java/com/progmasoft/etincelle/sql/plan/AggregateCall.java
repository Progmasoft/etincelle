/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import com.progmasoft.etincelle.core.DataType;
import java.util.HashSet;
import java.util.Set;

/**
 * An aggregate of the rows of a group: count, sum, avg, min or max; {@code argument} is {@code
 * null} for {@code count(*)}.
 */
public record AggregateCall(
    Kind kind, Expression argument, boolean distinct, DataType type, String text, int offset) {
  /** The aggregates. */
  public enum Kind {
    /** {@code count(*)}: the rows. */
    COUNT_STAR,
    /** {@code count(x)}: the values that are not null. */
    COUNT,
    /** {@code sum(x)}: BIGINT for whole numbers, DOUBLE for DOUBLE. */
    SUM,
    /** {@code avg(x)}: a DOUBLE. */
    AVG,
    /** {@code min(x)}. */
    MIN,
    /** {@code max(x)}. */
    MAX,
  }

  /** Whether the result can be null: every aggregate but count is null for no values. */
  public boolean nullable() {
    return kind != Kind.COUNT_STAR && kind != Kind.COUNT;
  }

  /** A new accumulator for one group. */
  public Accumulator start() {
    return new Accumulator(this);
  }

  /** The state of one aggregate for one group. */
  public static final class Accumulator {
    private final AggregateCall call;
    private final Set<Object> seen;
    private long count;
    private long wholeSum;
    private double floatingSum;
    private Object extreme;

    private Accumulator(AggregateCall call) {
      this.call = call;
      this.seen = call.distinct ? new HashSet<>() : null;
    }

    /** Adds the value of one row; {@code count(*)} counts the row whatever the value. */
    public void add(Object value) {
      if (call.kind == Kind.COUNT_STAR) {
        count++;
        return;
      }
      if (value == null || (seen != null && !seen.add(Values.normalize(value)))) {
        return;
      }
      count++;
      switch (call.kind) {
        case SUM, AVG -> {
          if (value instanceof Double d) {
            floatingSum += d;
          } else {
            try {
              wholeSum = Math.addExact(wholeSum, ((Number) value).longValue());
            } catch (ArithmeticException e) {
              throw new EvaluationException(call.offset, "BIGINT overflow in " + call.text);
            }
          }
        }
        case MIN ->
            extreme = extreme == null || Values.compare(value, extreme) < 0 ? value : extreme;
        case MAX ->
            extreme = extreme == null || Values.compare(value, extreme) > 0 ? value : extreme;
        default -> {}
      }
    }

    /** The result for the values added. */
    public Object result() {
      return switch (call.kind) {
        case COUNT_STAR, COUNT -> count;
        case SUM -> {
          if (count == 0) {
            yield null;
          }
          yield call.type.equals(DataType.DOUBLE) ? (Object) floatingSum : (Object) wholeSum;
        }
        case AVG -> {
          if (count == 0) {
            yield null;
          }
          boolean whole = !call.argument.type().equals(DataType.DOUBLE);
          yield (whole ? (double) wholeSum : floatingSum) / count;
        }
        case MIN, MAX -> extreme;
      };
    }
  }
}
