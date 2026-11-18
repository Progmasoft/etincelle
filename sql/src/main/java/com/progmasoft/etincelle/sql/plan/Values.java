/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import com.progmasoft.etincelle.core.DataType;
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
    if (a instanceof Integer x && b instanceof Integer y) {
      return Integer.compare(x, y);
    }
    if (a instanceof Long x && b instanceof Long y) {
      return Long.compare(x, y);
    }
    if (a instanceof Double x && b instanceof Double y) {
      double p = x;
      double q = y;
      return p == q ? 0 : Double.compare(p, q);
    }
    if (a instanceof String x && b instanceof String y) {
      return compareCodePoints(x, y);
    }
    if (a instanceof Boolean x && b instanceof Boolean y) {
      return Boolean.compare(x, y);
    }
    throw new IllegalArgumentException("cannot compare " + a + " with " + b);
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
      case Double d -> {
        double truncated = d < 0 ? Math.ceil(d) : Math.floor(d);
        if (d.isNaN() || truncated < -0x1p63 || truncated >= 0x1p63) {
          throw new EvaluationException(offset, d + " is out of the range of BIGINT");
        }
        yield (long) truncated;
      }
      case String s -> {
        try {
          yield Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
          throw new EvaluationException(offset, "'" + s + "' is not a whole number");
        }
      }
      default -> throw new IllegalArgumentException("no cast from " + value);
    };
  }

  /**
   * Whether {@code text} matches the LIKE {@code pattern}: % matches any run of characters, _ one
   * character, and \ makes the next character match itself.
   *
   * @throws EvaluationException when the pattern ends in
   */
  public static boolean like(String text, String pattern, int offset) {
    int[] t = text.codePoints().toArray();
    int[] p = pattern.codePoints().toArray();
    // The positions in the pattern after the last %, and in the text where it then stood.
    int ti = 0;
    int pi = 0;
    int starPattern = -1;
    int starText = -1;
    while (ti < t.length) {
      if (pi < p.length && p[pi] == '%') {
        starPattern = ++pi;
        starText = ti;
      } else if (pi < p.length && matchesOne(p, pi, t[ti], offset)) {
        pi += p[pi] == '\\' ? 2 : 1;
        ti++;
      } else if (starPattern >= 0) {
        pi = starPattern;
        ti = ++starText;
      } else {
        return false;
      }
    }
    while (pi < p.length && p[pi] == '%') {
      pi++;
    }
    if (pi < p.length && p[pi] == '\\' && pi + 1 == p.length) {
      throw new EvaluationException(offset, "a LIKE pattern does not end in \\");
    }
    return pi == p.length;
  }

  private static boolean matchesOne(int[] pattern, int at, int character, int offset) {
    if (pattern[at] == '\\') {
      if (at + 1 == pattern.length) {
        throw new EvaluationException(offset, "a LIKE pattern does not end in \\");
      }
      return pattern[at + 1] == character;
    }
    return pattern[at] == '_' || pattern[at] == character;
  }
}
