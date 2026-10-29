package com.einsteinmods.opendoors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every lang file must translate exactly the keys of en_us, in the same order, with the same
 * specifiers.
 */
class LangFilesTest {
  /** Gradle runs tests with the project directory as working directory. */
  private static final Path LANG_DIRECTORY = Path.of("src/main/resources/assets/opendoors/lang");

  private static final String SOURCE_LANG_FILE = "en_us.json";
  private static final String LANG_FILE_EXTENSION = ".json";

  /** Java format specifiers as used by Minecraft (%s, %1$s, %d, %.1f, %%); no space flag. */
  private static final Pattern FORMAT_SPECIFIER =
      Pattern.compile("%(?:\\d+\\$)?[-#+0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]");

  @Test
  void everyLangFileHasTheSourceKeysInOrderWithMatchingSpecifiers() throws IOException {
    Map<String, String> source = readLangFile(LANG_DIRECTORY.resolve(SOURCE_LANG_FILE));
    List<Path> translations = translationFiles();
    assertFalse(translations.isEmpty(), "no translations found in " + LANG_DIRECTORY);

    for (Path translation : translations) {
      Map<String, String> translated = readLangFile(translation);
      String fileName = translation.getFileName().toString();
      assertEquals(
          List.copyOf(source.keySet()),
          List.copyOf(translated.keySet()),
          fileName + " keys differ from " + SOURCE_LANG_FILE);
      for (Map.Entry<String, String> entry : source.entrySet()) {
        String key = entry.getKey();
        String value = translated.get(key);
        assertFalse(value.isBlank(), fileName + " has a blank value for " + key);
        assertEquals(
            formatSpecifiers(entry.getValue()),
            formatSpecifiers(value),
            fileName + " format specifiers differ for " + key);
      }
    }
  }

  @Test
  void specifierPatternIgnoresPercentagesInProse() {
    assertTrue(formatSpecifiers("Reach 90% of top speed, 90 % de la vitesse").isEmpty());
    assertEquals(List.of("%s", "%1$s", "%.1f"), formatSpecifiers("%s and %1$s at %.1f"));
  }

  private static List<Path> translationFiles() throws IOException {
    try (Stream<Path> files = Files.list(LANG_DIRECTORY)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(LANG_FILE_EXTENSION))
          .filter(path -> !path.getFileName().toString().equals(SOURCE_LANG_FILE))
          .sorted()
          .toList();
    }
  }

  private static Map<String, String> readLangFile(Path path) throws IOException {
    JsonObject json =
        JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    // JsonObject keeps insertion order, so the returned map keeps the file's key order.
    Map<String, String> entries = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
      entries.put(entry.getKey(), entry.getValue().getAsString());
    }
    return entries;
  }

  private static List<String> formatSpecifiers(String text) {
    List<String> specifiers = new ArrayList<>();
    Matcher matcher = FORMAT_SPECIFIER.matcher(text);
    while (matcher.find()) {
      specifiers.add(matcher.group());
    }
    return specifiers;
  }
}
