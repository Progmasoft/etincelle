/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import com.progmasoft.etincelle.core.DataType;
import java.util.Arrays;
import java.util.Locale;

/** The rules values follow: order, equality, casts and LIKE. */
public final class Values {
  private Values() {}

  /** The rank of a numeric type, INT below BIGINT below DOUBLE, or -1 for another type. */
  public static int numericRank(DataType type) {
    return switch (type) {
      case DataType.IntType t -> 0;
      case DataType.LongType t -> 1;
      case DataType.DoubleType t -> 2;
      default -> -1;
    };
  }

  /** The wider of two numeric types. */
  public static DataType wider(DataType a, DataType b) {
    return numericRank(a) >= numericRank(b) ? a : b;
  }

  /**
   * Orders two values of one type, neither null: numbers by value, with -0.0 equal to 0.0 and NaN
   * equal to itself and above every other number; strings by code point; false before true.
   */
  public static int compare(Object a, Object b) {
    return switch (a) {
      case Integer x -> Integer.compare(x, (Integer) b);
      case Long x -> Long.compare(x, (Long) b);
      case Double x -> compareDoubles(x, (Double) b);
      case String x -> compareCodePoints(x, (String) b);
      case Boolean x -> Boolean.compare(x, (Boolean) b);
      default -> throw new IllegalArgumentException("cannot compare " + a + " with " + b);
    };
  }

  private static int compareDoubles(double a, double b) {
    return a == b ? 0 : Double.compare(a, b);
  }

  private static int compareCodePoints(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int p = a.codePointAt(i);
      int q = b.codePointAt(j);
      if (p != q) {
        return Integer.compare(p, q);
      }
      i += Character.charCount(p);
      j += Character.charCount(q);
    }
    return Boolean.compare(i < a.length(), j < b.length());
  }

  /** The value grouping and DISTINCT use: -0.0 as 0.0, every NaN as one NaN. */
  public static Object normalize(Object value) {
    if (value instanceof Double d) {
      if (d == 0.0) {
        return 0.0;
      }
      if (d.isNaN()) {
        return Double.NaN;
      }
    }
    return value;
  }

  /**
   * {@code value}, of type {@code from} and not null, as a value of {@code to}.
   *
   * @throws EvaluationException when it does not fit, as a number out of range or text that is no
   *     number
   */
  public static Object cast(Object value, DataType from, DataType to, int offset) {
    if (from.equals(to)) {
      return value;
    }
    return switch (to) {
      case DataType.StringType t -> toText(value);
      case DataType.BooleanType t -> toBoolean(value, offset);
      case DataType.DoubleType t -> toDouble(value, offset);
      case DataType.LongType t -> toLong(value, offset);
      case DataType.IntType t -> {
        long wide = toLong(value, offset);
        if (wide < Integer.MIN_VALUE || wide > Integer.MAX_VALUE) {
          throw new EvaluationException(offset, value + " is out of the range of INT");
        }
        yield (int) wide;
      }
    };
  }

  /** {@code value} as text: numbers as Java writes them, booleans as true and false. */
  public static String toText(Object value) {
    return String.valueOf(value);
  }

  private static boolean toBoolean(Object value, int offset) {
    return switch (value) {
      case Integer i -> i != 0;
      case Long l -> l != 0;
      case Double d -> d != 0.0;
      case String s -> {
        String word = s.trim().toLowerCase(Locale.ROOT);
        if (word.equals("true")) {
          yield true;
        }
        if (word.equals("false")) {
          yield false;
        }
        throw new EvaluationException(offset, "'" + s + "' is not a BOOLEAN");
      }
      default -> throw new IllegalArgumentException("no cast from " + value);
    };
  }

  private static double toDouble(Object value, int offset) {
    return switch (value) {
      case Integer i -> i;
      case Long l -> l;
      case Boolean b -> b ? 1.0 : 0.0;
      case String s -> {
        try {
          yield Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
          throw new EvaluationException(offset, "'" + s + "' is not a DOUBLE");
        }
      }
      default -> throw new IllegalArgumentException("no cast from " + value);
    };
  }

  private static long toLong(Object value, int offset) {
    return switch (value) {
      case Integer i -> i;
      case Long l -> l;
      case Boolean b -> b ? 1L : 0L;
      case Double d -> truncate(d, offset);
      case String s -> parseWhole(s, offset);
      default -> throw new IllegalArgumentException("no cast from " + value);
    };
  }

  private static long truncate(double value, int offset) {
    double truncated = value < 0 ? Math.ceil(value) : Math.floor(value);
    if (Double.isNaN(value) || truncated < -0x1p63 || truncated >= 0x1p63) {
      throw new EvaluationException(offset, value + " is out of the range of BIGINT");
    }
    return (long) truncated;
  }

  private static long parseWhole(String text, int offset) {
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException e) {
      throw new EvaluationException(offset, "'" + text + "' is not a whole number");
    }
  }

  /**
   * Whether {@code text} matches the LIKE {@code pattern}: % matches any run of characters, _ one
   * character, and \ makes the next character match itself.
   *
   * @throws EvaluationException when the pattern ends in
   */
  public static boolean like(String text, String pattern, int offset) {
    int[] t = text.codePoints().toArray();
    int[] p = likeTokens(pattern, offset);
    // The positions in the pattern after the last %, and in the text where it then stood.
    int ti = 0;
    int pi = 0;
    int starPattern = -1;
    int starText = -1;
    while (ti < t.length) {
      if (pi < p.length && p[pi] == ANY_RUN) {
        starPattern = ++pi;
        starText = ti;
      } else if (pi < p.length && (p[pi] == ANY_ONE || p[pi] == t[ti])) {
        pi++;
        ti++;
      } else if (starPattern >= 0) {
        pi = starPattern;
        ti = ++starText;
      } else {
        return false;
      }
    }
    while (pi < p.length && p[pi] == ANY_RUN) {
      pi++;
    }
    return pi == p.length;
  }

  /** A % of a LIKE pattern, among its code points. */
  private static final int ANY_RUN = -1;

  /** A _ of a LIKE pattern, among its code points. */
  private static final int ANY_ONE = -2;

  /** The code points of a LIKE pattern, with ANY_RUN and ANY_ONE for the % and _ not escaped. */
  private static int[] likeTokens(String pattern, int offset) {
    int[] points = pattern.codePoints().toArray();
    int[] tokens = new int[points.length];
    int count = 0;
    for (int i = 0; i < points.length; i++) {
      if (points[i] == '\\') {
        if (++i == points.length) {
          throw new EvaluationException(offset, "a LIKE pattern does not end in \\");
        }
        tokens[count++] = points[i];
      } else {
        tokens[count++] = points[i] == '%' ? ANY_RUN : points[i] == '_' ? ANY_ONE : points[i];
      }
    }
    return Arrays.copyOf(tokens, count);
  }
}
