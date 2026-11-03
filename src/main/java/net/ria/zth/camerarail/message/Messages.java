package net.ria.zth.camerarail.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MiniMessage-backed messages. The bundled messages.yml is the fallback, so upgrading the
 * plugin never leaves the user with missing keys after a partial translation.
 */
public final class Messages {
    private final Map<String, Object> values;
    private final String prefix;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private Messages(Map<String, Object> values, String prefix) {
        this.values = Map.copyOf(values);
        this.prefix = prefix == null ? "" : prefix;
    }

    public static Messages load(Plugin plugin) {
        Map<String, Object> merged = new LinkedHashMap<>();
        try (InputStreamReader reader = new InputStreamReader(
                plugin.getResource("messages.yml"), StandardCharsets.UTF_8)) {
            flatten(YamlConfiguration.loadConfiguration(reader), "", merged);
        } catch (RuntimeException | java.io.IOException exception) {
            plugin.getLogger().warning("Could not read bundled messages.yml: " + exception.getMessage());
        }
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (file.isFile()) {
            YamlConfiguration external = YamlConfiguration.loadConfiguration(file);
            flatten(external, "", merged);
        }
        Object prefix = merged.get("prefix");
        return new Messages(merged, prefix instanceof String string ? string : "");
    }

    public Component component(String path, String... placeholders) {
        String raw = string(path);
        if (raw == null) {
            return Component.text(path);
        }
        String replaced = replace(raw, placeholders);
        try {
            return miniMessage.deserialize(replaced);
        } catch (RuntimeException exception) {
            return Component.text(replaced);
        }
    }

    public Component prefixed(String path, String... placeholders) {
        return prefixComponent().append(component(path, placeholders));
    }

    public Component prefixComponent() {
        return component("prefix");
    }

    public void send(CommandSender sender, String path, String... placeholders) {
        sender.sendMessage(prefixed(path, placeholders));
    }

    public void sendRaw(CommandSender sender, String path, String... placeholders) {
        sender.sendMessage(component(path, placeholders));
    }

    public void actionBar(Player player, String path, String... placeholders) {
        player.sendActionBar(component(path, placeholders));
    }

    public List<Component> list(String path, String... placeholders) {
        Object value = values.get(path);
        List<Component> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                String raw = String.valueOf(entry);
                result.add(deserialize(replace(raw, placeholders)));
            }
        } else if (value instanceof String string) {
            result.add(deserialize(replace(string, placeholders)));
        }
        return List.copyOf(result);
    }

    public String string(String path) {
        Object value = values.get(path);
        return value instanceof String string ? string : null;
    }

    public String prefix() {
        return prefix;
    }

    public Component deserialize(String raw) {
        try {
            return miniMessage.deserialize(raw);
        } catch (RuntimeException exception) {
            return Component.text(raw);
        }
    }

    private static String replace(String raw, String... placeholders) {
        if (placeholders.length == 0) {
            return raw;
        }
        String result = raw;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            result = result.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return result;
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
