package com.nexora.players;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ChatMemory {
    private final NexoraPlayers plugin;
    private final Deque<String> recent = new ArrayDeque<>();
    private final Map<String, Deque<String>> playerMemory = new ConcurrentHashMap<>();
    private final File file;

    public ChatMemory(NexoraPlayers plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
        load();
    }

    public synchronized void add(String line) {
        recent.addLast(line);
        int limit = Math.max(1, plugin.getConfig().getInt("memory.recent-messages", 12));
        while (recent.size() > limit) recent.removeFirst();
    }

    public synchronized String context() {
        return String.join("\n", recent);
    }

    public void rememberPlayer(String player, String message) {
        if (!plugin.getConfig().getBoolean("memory.remember-player-messages", true)) return;
        Deque<String> entries = playerMemory.computeIfAbsent(player.toLowerCase(), k -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addLast(message);
            while (entries.size() > Math.max(1, plugin.getConfig().getInt("memory.max-player-messages", 8))) entries.removeFirst();
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            List<String> values = yaml.getStringList(key);
            playerMemory.put(key, new ArrayDeque<>(values));
        }
    }

    public void save() {
        if (!plugin.getConfig().getBoolean("memory.remember-player-messages", true)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        playerMemory.forEach((key, values) -> {
            synchronized (values) { yaml.set(key, List.copyOf(values)); }
        });
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save player memory: " + e.getMessage());
        }
    }
}
