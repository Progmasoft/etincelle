/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MPL-2.0 WITH AdditionRef-Progmasoft-Exception-2.0
 */

package com.progmasoft.etincelle.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Names in Etincelle SQL: a plain name is a letter or {@code _} followed by letters, digits and
 * {@code _}; any other name is written between backquotes, with a backquote inside written twice. A
 * qualified name is names joined by dots, such as {@code db.`my table`.id}.
 */
public final class Identifiers {
  private Identifiers() {}

  /** Whether {@code name} can be written without backquotes. */
  public static boolean isPlain(String name) {
    if (name.isEmpty() || !isStart(name.charAt(0))) {
      return false;
    }
    for (int i = 1; i < name.length(); i++) {
      if (!isPart(name.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isStart(char c) {
    return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isPart(char c) {
    return isStart(c) || (c >= '0' && c <= '9');
  }

  /** {@code name} as SQL writes it: as it is when plain, between backquotes otherwise. */
  public static String quote(String name) {
    if (name.isEmpty()) {
      throw new IllegalArgumentException("a name is not empty");
    }
    return isPlain(name) ? name : "`" + name.replace("`", "``") + "`";
  }

  /** The names of {@code parts} joined by dots, each quoted as needed. */
  public static String quoteQualified(List<String> parts) {
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("a qualified name has at least one part");
    }
    List<String> quoted = new ArrayList<>(parts.size());
    for (String part : parts) {
      quoted.add(quote(part));
    }
    return String.join(".", quoted);
  }

  /**
   * The parts of a qualified name as SQL writes it.
   *
   * @throws IllegalArgumentException when {@code text} is not a qualified name, with the offset of
   *     the first character that does not fit
   */
  public static List<String> parseQualified(String text) {
    List<String> parts = new ArrayList<>();
    int i = 0;
    while (true) {
      if (i >= text.length()) {
        throw error(text, i, "a name is expected");
      }
      StringBuilder part = new StringBuilder();
      if (text.charAt(i) == '`') {
        i++;
        while (true) {
          if (i >= text.length()) {
            throw error(text, i, "the backquote is not closed");
          }
          char c = text.charAt(i);
          if (c == '`') {
            if (i + 1 < text.length() && text.charAt(i + 1) == '`') {
              part.append('`');
              i += 2;
              continue;
            }
            i++;
            break;
          }
          part.append(c);
          i++;
        }
        if (part.isEmpty()) {
          throw error(text, i - 2, "a name is not empty");
        }
      } else {
        if (!isStart(text.charAt(i))) {
          throw error(text, i, "a name starts with a letter or _, or is quoted with `");
        }
        while (i < text.length() && isPart(text.charAt(i))) {
          part.append(text.charAt(i));
          i++;
        }
      }
      parts.add(part.toString());
      if (i == text.length()) {
        return List.copyOf(parts);
      }
      if (text.charAt(i) != '.') {
        throw error(text, i, "a . or the end is expected");
      }
      i++;
    }
  }

  private static IllegalArgumentException error(String text, int offset, String reason) {
    return new IllegalArgumentException(
        "'" + text + "' is not a qualified name: " + reason + " at offset " + offset);
  }
}
