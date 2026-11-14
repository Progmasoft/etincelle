/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MPL-2.0 WITH AdditionRef-Progmasoft-Exception-2.0
 */

package com.progmasoft.etincelle.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class IdentifiersTest {
  @Test
  void plainNamesNeedNoQuotes() {
    assertTrue(Identifiers.isPlain("orders"));
    assertTrue(Identifiers.isPlain("_tmp2"));
    assertFalse(Identifiers.isPlain("2nd"));
    assertFalse(Identifiers.isPlain("my table"));
    assertFalse(Identifiers.isPlain("é"));
    assertFalse(Identifiers.isPlain(""));
    assertEquals("orders", Identifiers.quote("orders"));
    assertEquals("`my table`", Identifiers.quote("my table"));
    assertEquals("`a``b`", Identifiers.quote("a`b"));
    assertThrows(IllegalArgumentException.class, () -> Identifiers.quote(""));
  }

  @Test
  void qualifiedNamesRoundTrip() {
    List<String> parts = List.of("db", "my table", "a`b", "id");
    String text = Identifiers.quoteQualified(parts);
    assertEquals("db.`my table`.`a``b`.id", text);
    assertEquals(parts, Identifiers.parseQualified(text));
    assertEquals(List.of("x"), Identifiers.parseQualified("x"));
    assertThrows(IllegalArgumentException.class, () -> Identifiers.quoteQualified(List.of()));
  }

  @Test
  void textThatIsNotANameIsRefusedWithItsOffset() {
    for (String text : List.of("", "a.", ".a", "a..b", "`open", "``", "a b", "1a", "a.`b`c")) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class, () -> Identifiers.parseQualified(text), text);
      assertTrue(error.getMessage().contains("at offset"), error.getMessage());
    }
  }
}
