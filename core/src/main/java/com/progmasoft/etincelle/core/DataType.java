/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

/**
 * The type of a column. The set is closed, so code that handles every type is checked by the
 * compiler: a {@code switch} over a {@code DataType} needs no default branch.
 */
public sealed interface DataType {
  /** The name SQL and error messages use, such as {@code INT} or {@code STRING}. */
  String sqlName();

  /** {@code true} or {@code false}. */
  record BooleanType() implements DataType {
    @Override
    public String sqlName() {
      return "BOOLEAN";
    }
  }

  /** A 32-bit two's-complement integer. */
  record IntType() implements DataType {
    @Override
    public String sqlName() {
      return "INT";
    }
  }

  /** A 64-bit two's-complement integer. */
  record LongType() implements DataType {
    @Override
    public String sqlName() {
      return "BIGINT";
    }
  }

  /** A 64-bit IEEE 754 floating-point number. */
  record DoubleType() implements DataType {
    @Override
    public String sqlName() {
      return "DOUBLE";
    }
  }

  /** Text in UTF-8. */
  record StringType() implements DataType {
    @Override
    public String sqlName() {
      return "STRING";
    }
  }

  /** The boolean type. */
  DataType BOOLEAN = new BooleanType();

  /** The 32-bit integer type. */
  DataType INT = new IntType();

  /** The 64-bit integer type. */
  DataType LONG = new LongType();

  /** The double type. */
  DataType DOUBLE = new DoubleType();

  /** The string type. */
  DataType STRING = new StringType();
}
