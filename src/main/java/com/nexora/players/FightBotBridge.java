package com.nexora.players;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Optional bridge to FightBot 6.x. It discovers FightBot's active bot records
 * reflectively so NEXORA-PLAYERS does not need FightBot classes at compile time.
 * Messages are broadcast in normal player-chat style under the bot's configured name.
 */
public final class FightBotBridge {
    private final NexoraPlayers plugin;
    private BukkitTask task;
    private long hourStarted = System.currentTimeMillis();
    private int messagesThisHour;

    public FightBotBridge(NexoraPlayers plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("fightbot-chat.enabled", true)) return;
        long minutes = Math.max(1, plugin.getConfig().getLong("fightbot-chat.interval-minutes", 5));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tryConversation,
                minutes * 60L * 20L, minutes * 60L * 20L);
        plugin.getLogger().info("FightBot chat bridge enabled; checking active FightBot bots every " + minutes + " minutes.");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tryConversation() {
        if (!plugin.getConfig().getBoolean("fightbot-chat.enabled", true)) return;
        if (Bukkit.getOnlinePlayers().size() < plugin.getConfig().getInt("fightbot-chat.minimum-online-players", 1)) return;
        if (ThreadLocalRandom.current().nextInt(100) >= plugin.getConfig().getInt("fightbot-chat.chance-percent", 70)) return;

        long now = System.currentTimeMillis();
        if (now - hourStarted >= 3_600_000L) {
            hourStarted = now;
            messagesThisHour = 0;
        }
        int hourlyLimit = Math.max(1, plugin.getConfig().getInt("fightbot-chat.max-messages-per-hour", 12));
        if (messagesThisHour >= hourlyLimit) return;

        List<String> botNames = activeBotNames();
        if (botNames.size() < Math.max(2, plugin.getConfig().getInt("fightbot-chat.minimum-bots", 2))) {
            if (!plugin.getServer().getPluginManager().isPluginEnabled("FightBot")) {
                plugin.getLogger().fine("FightBot is not enabled; chat bridge is waiting.");
            }
            return;
        }

        int first = ThreadLocalRandom.current().nextInt(botNames.size());
        int second = ThreadLocalRandom.current().nextInt(botNames.size() - 1);
        if (second >= first) second++;
        String speaker = botNames.get(first);
        String partner = botNames.get(second);

        String personality = plugin.getConfig().getString("fightbot-chat.default-personality",
                "You are a regular Minecraft server player. Be sociable, funny, casual, and varied. Sometimes discuss PvP, builds, survival, games, music, or random everyday topics. Never sound like an assistant.");
        Persona botPersona = new Persona(speaker, speaker, personality,
                "Hey " + partner + ", what are you up to?");
        String context = plugin.getMemory().contextFor(speaker);
        String prompt = "Start or continue a casual public conversation with the other online FightBot named "
                + partner + ". Recent server chat: " + context
                + ". Write exactly one short natural Minecraft chat message (maximum 18 words). "
                + "Do not use roleplay actions or claim you performed game actions. "
                + "Do not include your username or a name prefix.";

        plugin.getAi().reply(botPersona, partner, prompt, context, answer -> {
            if (answer == null || answer.isBlank()) return;
            String safe = ChatColor.stripColor(answer).replaceAll("[\\r\\n]+", " ").trim();
            safe = safe.replaceAll("(?i)^" + java.util.regex.Pattern.quote(speaker) + "\\s*[:>,-]\\s*", "");
            if (safe.isBlank()) return;
            int max = Math.max(40, plugin.getConfig().getInt("chat.maximum-reply-length", 180));
            if (safe.length() > max) safe = safe.substring(0, max).trim();
            // Chat-style line, attributed to the actual active FightBot name.
            Bukkit.broadcastMessage(ChatColor.WHITE + "<" + speaker + "> " + ChatColor.RESET + safe);
            plugin.getMemory().add("[" + speaker + "]: " + safe);
            messagesThisHour++;
        });
    }

    private List<String> activeBotNames() {
        List<String> result = new ArrayList<>();
        Plugin fightBot = plugin.getServer().getPluginManager().getPlugin("FightBot");
        if (fightBot == null || !fightBot.isEnabled()) return result;
        try {
            Object manager = readField(fightBot, "manager");
            if (manager == null) return result;
            Object raw = readField(manager, "bots");
            if (!(raw instanceof Map<?, ?> bots)) return result;
            for (Map.Entry<?, ?> entry : bots.entrySet()) {
                Object record = entry.getValue();
                Object nameValue = readField(record, "name");
                Object bodyValue = readField(record, "body");
                if (!(nameValue instanceof String name) || !(bodyValue instanceof LivingEntity body)) continue;
                if (!body.isValid() || body.isDead() || name.isBlank()) continue;
                result.add(name);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Could not read active FightBot bots. Check FightBot compatibility: " + ex.getMessage());
        }
        return result;
    }

    private static Object readField(Object instance, String name) throws ReflectiveOperationException {
        if (instance == null) return null;
        Class<?> type = instance.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
