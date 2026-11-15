/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.core;

import java.util.Objects;

/**
 * A named, typed column of a {@link Schema}.
 *
 * @param name the column's name, not empty
 * @param type its type
 * @param nullable whether its values may be null
 */
public record Field(String name, DataType type, boolean nullable) {
  /** Checks that the name is not empty and that nothing is null. */
  public Field {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    if (name.isEmpty()) {
      throw new IllegalArgumentException("a field has a name");
    }
  }

  @Override
  public String toString() {
    return name + " " + type.sqlName() + (nullable ? "" : " NOT NULL");
  }
}
