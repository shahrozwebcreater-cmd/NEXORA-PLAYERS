package com.nexora.players;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class PersonaManager {
    private final NexoraPlayers plugin;
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private volatile List<Persona> personas = new ArrayList<>();

    public PersonaManager(NexoraPlayers plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        List<Persona> loaded = new ArrayList<>();
        for (Map<?, ?> row : plugin.getConfig().getMapList("personas")) {
            String name = value(row, "name", "Bot");
            String display = value(row, "display-name", name);
            String personality = value(row, "personality", "Friendly Minecraft player.");
            String greeting = value(row, "greeting", "Hey everyone!");
            loaded.add(new Persona(name, NexoraPlayers.color(display), personality, greeting));
        }
        personas = loaded;
    }

    private static String value(Map<?, ?> row, String key, String fallback) {\n        Object value = row.get(key);\n        return value == null ? fallback : String.valueOf(value);\n    }\n\n    public List<Persona> all() { return Collections.unmodifiableList(personas); }
    public List<String> names() { return personas.stream().map(Persona::name).toList(); }

    public Persona find(String name) {
        return personas.stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public Persona findMentioned(String message) {
        String lower = message.toLowerCase();
        return personas.stream().filter(p -> lower.contains(p.name().toLowerCase())
                || lower.contains("@" + p.name().toLowerCase())).findFirst().orElse(null);
    }

    public Persona random() {
        if (personas.isEmpty()) return null;
        return personas.get(ThreadLocalRandom.current().nextInt(personas.size()));
    }

    public boolean tryCooldown(Persona persona, long seconds) {
        long now = System.currentTimeMillis();
        Long previous = cooldowns.putIfAbsent(persona.name().toLowerCase(), now);
        if (previous == null) return true;
        if (now - previous < seconds * 1000L) return false;
        cooldowns.put(persona.name().toLowerCase(), now);
        return true;
    }
}
