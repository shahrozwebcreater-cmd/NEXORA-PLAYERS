package com.nexora.players;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Set<String> selectedBots = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> playerReplyCooldowns = new ConcurrentHashMap<>();
    // A challenge is tracked per real player so "stop" only stops that player's duel.
    private final Map<String, String> activeFightsByPlayer = new ConcurrentHashMap<>();
    private final Map<String, String> activeFightPlayersByBot = new ConcurrentHashMap<>();

    public FightBotBridge(NexoraPlayers plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (plugin.getConfig().getBoolean("gameplay.enabled", true)
                && plugin.getConfig().getBoolean("gameplay.start-free-roaming", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, this::startFreeRoamingBots, 40L);
        }
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

    private void startFreeRoamingBots() {
        String worldName = plugin.getConfig().getString("gameplay.world", "world");
        for (BotRecord bot : discoverActiveBots()) {
            if (!bot.body().getWorld().getName().equalsIgnoreCase(worldName)) continue;
            if (activeFightPlayersByBot.containsKey(bot.name().toLowerCase(java.util.Locale.ROOT))) continue;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fb roamingfree " + bot.name());
        }
        plugin.getLogger().info("Started free roaming for available FightBots in world '" + worldName + "'.");
    }

    public List<String> activeBotNames() {
        return List.copyOf(discoverActiveBots().stream().map(BotRecord::name).toList());
    }

    public boolean isFightBot(String name) {
        return activeBotNames().stream().anyMatch(bot -> bot.equalsIgnoreCase(name));
    }

    public boolean isSelected(String name) {
        List<String> configured = plugin.getConfig().getStringList("fightbot-chat.selected-bots");
        if (configured.isEmpty() && plugin.getConfig().getBoolean("fightbot-chat.auto-select-all-active-bots", true)) return true;
        return configured.stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }

    public boolean toggleSelected(String name) {
        List<String> configured = new ArrayList<>(plugin.getConfig().getStringList("fightbot-chat.selected-bots"));
        String existing = configured.stream().filter(n -> n.equalsIgnoreCase(name)).findFirst().orElse(null);
        boolean enabled;
        if (existing != null) {
            configured.remove(existing);
            enabled = false;
        } else {
            configured.add(name);
            enabled = true;
        }
        plugin.getConfig().set("fightbot-chat.selected-bots", configured);
        plugin.saveConfig();
        return enabled;
    }

    private record BotRecord(String name, LivingEntity body) {}

    private List<BotRecord> discoverActiveBots() {
        List<BotRecord> result = new ArrayList<>();
        Plugin fightBot = plugin.getServer().getPluginManager().getPlugin("FightBot");
        if (fightBot == null || !fightBot.isEnabled()) return result;
        try {
            Object manager = readField(fightBot, "manager");
            if (manager == null) return result;
            Object raw = readField(manager, "bots");
            if (!(raw instanceof Map<?, ?> bots)) return result;
            for (Object record : bots.values()) {
                Object nameValue = readField(record, "name");
                Object bodyValue = readField(record, "body");
                if (!(nameValue instanceof String name) || !(bodyValue instanceof LivingEntity body)) continue;
                if (body.isValid() && !body.isDead() && !name.isBlank()) result.add(new BotRecord(name, body));
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("FightBot discovery failed: " + ex.getMessage());
        }
        return result;
    }

    /** Routes normal player chat to one selected, live FightBot. */
    public boolean handlePlayerMessage(String playerName, String message) {
        if (!plugin.getConfig().getBoolean("fightbot-chat.enabled", true)) return false;
        String normalized = message.toLowerCase(java.util.Locale.ROOT).trim().replaceAll("\\s+", " ");
        if (isStopRequest(normalized)) {
            Bukkit.getScheduler().runTask(plugin, () -> stopPlayerFight(playerName));
            return true;
        }
        if (isChallengeRequest(normalized)) {
            Bukkit.getScheduler().runTask(plugin, () -> startPlayerChallenge(playerName, message));
            return true;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            List<String> selected = activeBotNames().stream().filter(this::isSelected).toList();
            if (selected.isEmpty()) return;
            String mentioned = selected.stream()
                    .filter(name -> message.toLowerCase().contains(name.toLowerCase())
                            || message.toLowerCase().contains("@" + name.toLowerCase()))
                    .findFirst().orElse(null);
            int chance = Math.max(0, Math.min(100,
                    plugin.getConfig().getInt("fightbot-chat.player-reply-chance-percent", 100)));
            if (mentioned == null && ThreadLocalRandom.current().nextInt(100) >= chance) return;
            String speaker = mentioned != null ? mentioned
                    : selected.get(ThreadLocalRandom.current().nextInt(selected.size()));
            long now = System.currentTimeMillis();
            long cooldown = Math.max(0, plugin.getConfig().getLong("fightbot-chat.player-reply-cooldown-seconds", 12)) * 1000L;
            Long previous = playerReplyCooldowns.putIfAbsent(speaker.toLowerCase(), now);
            if (previous != null && now - previous < cooldown) return;
            playerReplyCooldowns.put(speaker.toLowerCase(), now);

            String personality = plugin.getConfig().getString("fightbot-chat.default-personality",
                    "You are a regular Minecraft player. Be casual, funny, sociable and natural.");
            Persona botPersona = new Persona(speaker, speaker, personality, "Hey!");
            String context = plugin.getMemory().contextFor(playerName);
            plugin.getAi().reply(botPersona, playerName, message, context, answer -> {
                if (answer == null || answer.isBlank()) {
                    playerReplyCooldowns.remove(speaker.toLowerCase());
                    return;
                }
                String safe = ChatColor.stripColor(answer).replaceAll("[\\r\\n]+", " ").trim();
                safe = safe.replaceAll("(?i)^" + java.util.regex.Pattern.quote(speaker) + "\\s*[:>,-]\\s*", "");
                if (safe.isBlank()) return;
                int max = Math.max(40, plugin.getConfig().getInt("chat.maximum-reply-length", 180));
                if (safe.length() > max) safe = safe.substring(0, max).trim();
                Player bot = Bukkit.getPlayerExact(speaker);
                if (bot == null || !bot.isOnline()) {
                    plugin.getLogger().warning("Selected FightBot '" + speaker + "' is not exposed as an online Bukkit player; cannot send genuine bot chat.");
                    return;
                }
                bot.chat(safe);
                plugin.getMemory().add("[" + speaker + "]: " + safe);
            });
        });
        return true;
    }


    private boolean isChallengeRequest(String message) {
        return message.matches(".*\\\\b(1v1|1\\\\s*vs\\\\s*1|duel|challenge|fight me|pvp me|wanna fight|want to fight|come fight|fight you)\\\\b.*");
    }

    private boolean isStopRequest(String message) {
        return message.matches("^(stop|stop pvp|stop fight|stop fighting|stop now|end fight|end pvp|cancel fight|cancel pvp)[.! ]*$")
                || message.matches(".*\\\\b(stop pvp|stop fighting|end the fight|end pvp|cancel the fight)\\\\b.*");
    }

    private void startPlayerChallenge(String playerName, String originalMessage) {
        if (!plugin.getConfig().getBoolean("gameplay.enabled", true)
                || !plugin.getConfig().getBoolean("gameplay.challenge-only-pvp", true)) return;
        Player challenger = Bukkit.getPlayerExact(playerName);
        if (challenger == null || !challenger.isOnline()) return;
        String key = playerName.toLowerCase(java.util.Locale.ROOT);
        if (activeFightsByPlayer.containsKey(key)) {
            challenger.sendMessage(ChatColor.YELLOW + "You're already in a FightBot duel. Say 'stop' to end it.");
            return;
        }

        List<BotRecord> candidates = discoverActiveBots().stream()
                .filter(bot -> bot.body().getWorld().getName().equalsIgnoreCase(
                        plugin.getConfig().getString("gameplay.world", "world")))
                .filter(bot -> !activeFightPlayersByBot.containsKey(bot.name().toLowerCase(java.util.Locale.ROOT)))
                .filter(bot -> bot.name().matches("[A-Za-z0-9_]{1,16}"))
                .toList();
        if (candidates.isEmpty()) {
            challenger.sendMessage(ChatColor.YELLOW + "No free FightBot is available in world '"
                    + plugin.getConfig().getString("gameplay.world", "world") + "' right now.");
            return;
        }

        String lower = originalMessage.toLowerCase(java.util.Locale.ROOT);
        BotRecord chosen = candidates.stream()
                .filter(bot -> lower.contains(bot.name().toLowerCase(java.util.Locale.ROOT)))
                .findFirst()
                .orElseGet(() -> candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));

        boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "fb fight " + chosen.name() + " " + challenger.getName());
        if (!dispatched) {
            challenger.sendMessage(ChatColor.RED + "FightBot command could not be dispatched. Check FightBot is enabled.");
            return;
        }
        activeFightsByPlayer.put(key, chosen.name());
        activeFightPlayersByBot.put(chosen.name().toLowerCase(java.util.Locale.ROOT), key);
        String line = "Challenge accepted, " + playerName + "! Let's fight.";
        if (chosen.body() instanceof Player botPlayer) {
            botPlayer.chat(line);
        } else {
            Bukkit.broadcastMessage(ChatColor.WHITE + "<" + chosen.name() + "> " + ChatColor.RESET + line);
        }
        plugin.getLogger().info("Started FightBot duel: " + chosen.name() + " vs " + playerName);
    }

    private void stopPlayerFight(String playerName) {
        Player player = Bukkit.getPlayerExact(playerName);
        String key = playerName.toLowerCase(java.util.Locale.ROOT);
        String botName = activeFightsByPlayer.remove(key);
        if (botName == null) {
            if (player != null) player.sendMessage(ChatColor.GRAY + "You don't have an active AI-started FightBot duel.");
            return;
        }
        activeFightPlayersByBot.remove(botName.toLowerCase(java.util.Locale.ROOT));
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fb stop " + botName);
        // FightBot's stop command ends the current job; resume free roaming when enabled.
        if (plugin.getConfig().getBoolean("gameplay.stop-returns-to-roaming", true)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fb roamingfree " + botName);
        }
        if (player != null) player.sendMessage(ChatColor.GREEN + botName + " stopped PvP and returned to roaming.");
        plugin.getLogger().info("Stopped FightBot duel: " + botName + " (requested by " + playerName + ")");
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

        List<String> botNames = activeBotNames().stream().filter(this::isSelected).toList();
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
            // Use the FightBot player object when Bukkit exposes it, so the server
            // emits a genuine player chat event under that bot's username.
            Player botPlayer = activeBotPlayer(speaker);
            if (botPlayer != null) {
                botPlayer.chat(safe);
            } else {
                // Compatibility fallback for FightBot builds whose bot body is not
                // exposed as a Bukkit Player object.
                Bukkit.broadcastMessage(ChatColor.WHITE + "<" + speaker + "> " + ChatColor.RESET + safe);
            }
            plugin.getMemory().add("[" + speaker + "]: " + safe);
            messagesThisHour++;
        });
    }

    private Player activeBotPlayer(String wantedName) {
        Plugin fightBot = plugin.getServer().getPluginManager().getPlugin("FightBot");
        if (fightBot == null || !fightBot.isEnabled()) return null;
        try {
            Object manager = readField(fightBot, "manager");
            Object raw = readField(manager, "bots");
            if (!(raw instanceof Map<?, ?> bots)) return null;
            for (Object record : bots.values()) {
                Object name = readField(record, "name");
                Object body = readField(record, "body");
                if (wantedName.equals(name) && body instanceof Player player && player.isOnline()) return player;
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Could not resolve FightBot chat sender: " + ex.getMessage());
        }
        return null;
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
