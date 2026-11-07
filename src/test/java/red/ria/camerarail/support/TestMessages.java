package red.ria.camerarail.support;

import red.ria.camerarail.message.Messages;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads the bundled {@code messages.yml} without a running server. */
public final class TestMessages {
    private TestMessages() {
    }

    public static Messages bundled() {
        Path path = Path.of("src", "main", "resources", "messages.yml");
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Map<String, Object> values = new LinkedHashMap<>();
            flatten(YamlConfiguration.loadConfiguration(reader), "", values);
            return Messages.of(values);
        } catch (IOException exception) {
            throw new IllegalStateException("could not read " + path, exception);
        }
    }

    private static void flatten(ConfigurationSection section, String prefix, Map<String, Object> output) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                flatten(child, path, output);
            } else {
                output.put(path, value);
            }
        }
    }
}
