package com.einsteinmods.opendoors.core.access;

import com.einsteinmods.opendoors.core.signaling.Validation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The invited and blocked players of one world, in a mod-owned file in the world folder ({@code
 * opendoors.json}). Entries with an invalid UUID or name are dropped on load; a broken file reads
 * as empty lists. Written to a temporary file first, so a crash never leaves half a file.
 */
public final class AccessListsFile {
  public static final String FILE_NAME = "opendoors.json";
  static final int CURRENT_SCHEMA_VERSION = 1;
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final String TEMPORARY_SUFFIX = ".tmp";

  /** One player in a list. */
  record Entry(String id, String name) {}

  /** File contents. */
  record Contents(int schemaVersion, List<Entry> invited, List<Entry> blocked) {}

  /** Both lists. */
  public record Lists(Map<UUID, String> invited, Map<UUID, String> blocked) {
    public static final Lists EMPTY = new Lists(Map.of(), Map.of());
  }

  private AccessListsFile() {}

  public static Lists load(Path worldFolder) {
    Path file = worldFolder.resolve(FILE_NAME);
    if (Files.notExists(file)) {
      return Lists.EMPTY;
    }
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Contents contents = GSON.fromJson(reader, Contents.class);
      if (contents == null) {
        return Lists.EMPTY;
      }
      return new Lists(toMap(contents.invited()), toMap(contents.blocked()));
    } catch (IOException | JsonParseException exception) {
      return Lists.EMPTY;
    }
  }

  public static void save(Path worldFolder, Lists lists) throws IOException {
    Contents contents =
        new Contents(
            CURRENT_SCHEMA_VERSION, toEntries(lists.invited()), toEntries(lists.blocked()));
    Path file = worldFolder.resolve(FILE_NAME);
    Path temporary = worldFolder.resolve(FILE_NAME + TEMPORARY_SUFFIX);
    try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
      GSON.toJson(contents, Contents.class, writer);
    }
    Files.move(
        temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static Map<UUID, String> toMap(List<Entry> entries) {
    Map<UUID, String> map = new HashMap<>();
    if (entries == null) {
      return map;
    }
    for (Entry entry : entries) {
      if (entry != null && Validation.isUuid(entry.id()) && Validation.isName(entry.name())) {
        map.put(UUID.fromString(entry.id()), entry.name());
      }
    }
    return map;
  }

  private static List<Entry> toEntries(Map<UUID, String> map) {
    return map.entrySet().stream()
        .map(entry -> new Entry(entry.getKey().toString(), entry.getValue()))
        .sorted(java.util.Comparator.comparing(Entry::name))
        .toList();
  }
}
