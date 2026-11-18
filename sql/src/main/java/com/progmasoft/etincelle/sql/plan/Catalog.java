/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MIT
 */

package com.progmasoft.etincelle.sql.plan;

import com.progmasoft.etincelle.core.Table;
import java.util.Optional;

/** The tables a query can read, by name. */
public interface Catalog {
  /** The table named {@code name}, case aside, with the name it was registered under. */
  Optional<Entry> table(String name);

  /** A table and the name it was registered under. */
  record Entry(String name, Table table) {}
}
