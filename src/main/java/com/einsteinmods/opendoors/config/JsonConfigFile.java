package com.einsteinmods.opendoors.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and saves one JSON config file. Invalid or missing files fall back to defaults (and are
 * rewritten) so a broken file never prevents the game from starting.
 */
public final class JsonConfigFile<T extends Record> {
  private static final Logger LOGGER = LoggerFactory.getLogger(JsonConfigFile.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private final Path path;
  private final Class<T> type;
  private final T defaults;

  public JsonConfigFile(Path path, Class<T> type, T defaults) {
    this.path = path;
    this.type = type;
    this.defaults = defaults;
  }

  /** Reads the file; writes and returns defaults when it is missing or unreadable. */
  public T load() {
    if (Files.notExists(path)) {
      save(defaults);
      return defaults;
    }
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      T value = GSON.fromJson(reader, type);
      if (value != null) {
        return value;
      }
      LOGGER.warn("Config file {} is empty; using defaults", path);
    } catch (IOException | JsonParseException exception) {
      LOGGER.warn("Config file {} is invalid; using defaults", path, exception);
    }
    save(defaults);
    return defaults;
  }

  public void save(T value) {
    try {
      Files.createDirectories(path.getParent());
      try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        GSON.toJson(value, type, writer);
      }
    } catch (IOException exception) {
      LOGGER.error("Could not write config file {}", path, exception);
    }
  }
}
