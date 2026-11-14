/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MPL-2.0 WITH AdditionRef-Progmasoft-Exception-2.0
 */

package com.progmasoft.etincelle.core;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The columns of a table, in order. Column names are matched without regard to case, as SQL matches
 * unquoted names, so two fields whose names differ only in case are refused.
 */
public final class Schema {
  private final List<Field> fields;
  private final Map<String, Integer> indexes;

  /** A schema of {@code fields}, in their order. */
  public Schema(List<Field> fields) {
    this.fields = List.copyOf(fields);
    Map<String, Integer> byName = new HashMap<>();
    for (int i = 0; i < this.fields.size(); i++) {
      String key = key(this.fields.get(i).name());
      Integer previous = byName.putIfAbsent(key, i);
      if (previous != null) {
        throw new IllegalArgumentException(
            "the fields "
                + this.fields.get(previous).name()
                + " and "
                + this.fields.get(i).name()
                + " have the same name");
      }
    }
    this.indexes = Map.copyOf(byName);
  }

  /** A schema of the given fields, in order. */
  public static Schema of(Field... fields) {
    return new Schema(List.of(fields));
  }

  private static String key(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  /** The fields, in order. */
  public List<Field> fields() {
    return fields;
  }

  /** How many fields there are. */
  public int size() {
    return fields.size();
  }

  /** The position of the field named {@code name}, case aside. */
  public Optional<Integer> indexOf(String name) {
    return Optional.ofNullable(indexes.get(key(name)));
  }

  /** The field named {@code name}, case aside. */
  public Optional<Field> field(String name) {
    return indexOf(name).map(fields::get);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Schema schema && fields.equals(schema.fields);
  }

  @Override
  public int hashCode() {
    return fields.hashCode();
  }

  @Override
  public String toString() {
    return fields.stream().map(Field::toString).collect(Collectors.joining(", ", "(", ")"));
  }
}
