package com.nexora.players;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ThreadLocalRandom;

public final class NexoraPlayers extends JavaPlugin implements Listener {
    private OllamaClient ai;
    private PersonaManager personas;
    private ChatMemory memory;
    private FightBotBridge fightBotBridge;\n    private BotMenu botMenu;
    private volatile long lastGlobalReply;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.memory = new ChatMemory(this);
        this.personas = new PersonaManager(this);
        this.ai = new OllamaClient(this);
        this.fightBotBridge = new FightBotBridge(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("NEXORA-PLAYERS enabled with " + personas.all().size() + " AI personas.");
        scheduleConversationStarters();
        fightBotBridge.start();\n        botMenu = new BotMenu(this);
    }

    @Override
    public void onDisable() {
        if (fightBotBridge != null) fightBotBridge.stop();
        if (memory != null) memory.save();
    }

    public OllamaClient getAi() { return ai; }
    public ChatMemory getMemory() { return memory; }\n    public FightBotBridge getFightBotBridge() { return fightBotBridge; }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (getConfig().getBoolean("chat.ignore-commands", true) && event.getMessage().startsWith("/")) return;
        String message = event.getMessage().trim();
        if (message.length() < getConfig().getInt("chat.minimum-message-length", 3)) return;
        memory.add("[" + event.getPlayer().getName() + "]: " + message);
        if (!getConfig().getBoolean("ai.enabled", true)) return;

        Persona persona = personas.findMentioned(message);
        boolean direct = persona != null;
        if (!direct && !getConfig().getBoolean("chat.respond-to-normal-chat", true)) return;
        if (!direct && ThreadLocalRandom.current().nextInt(100) >= getConfig().getInt("chat.respond-chance-percent", 18)) return;

        long now = System.currentTimeMillis();
        if (now - lastGlobalReply < getConfig().getLong("chat.global-cooldown-seconds", 5) * 1000L) return;
        if (persona == null) persona = personas.random();
        if (persona == null || !personas.tryCooldown(persona, getConfig().getLong("chat.reply-cooldown-seconds", 18))) return;

        final Persona selected = persona;
        final String playerName = event.getPlayer().getName();
        ai.reply(selected, playerName, message, memory.contextFor(playerName), response -> {
            if (response == null || response.isBlank()) return;
            lastGlobalReply = System.currentTimeMillis();
            String safe = ChatColor.stripColor(response).replaceAll("[\\r\\n]+", " ").trim();
            int max = getConfig().getInt("chat.maximum-reply-length", 180);
            if (safe.length() > max) safe = safe.substring(0, max).trim();
            Bukkit.broadcastMessage(color(getConfig().getString("chat.prefix", "&8[&bAI&8] "))
                    + selected.displayName() + ChatColor.GRAY + ": " + ChatColor.WHITE + safe);
            memory.add("[" + selected.name() + "]: " + safe);
            memory.rememberPlayer(playerName, message);
        });
    }

    private void scheduleConversationStarters() {
        long minutes = Math.max(1, getConfig().getLong("chat.initiate-every-minutes", 8));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!getConfig().getBoolean("ai.enabled", true)
                    || !getConfig().getBoolean("chat.allow-initiate-conversations", false)) return;
            if (Bukkit.getOnlinePlayers().size() < getConfig().getInt("chat.only-initiate-when-players-online", 1)) return;
            if (ThreadLocalRandom.current().nextInt(100) >= getConfig().getInt("chat.initiate-chance-percent", 30)) return;
            Persona persona = personas.random();
            if (persona == null || !personas.tryCooldown(persona, getConfig().getLong("chat.reply-cooldown-seconds", 18))) return;
            String starter = persona.greeting();
            if (starter == null || starter.isBlank()) starter = "What's everyone up to?";
            Bukkit.broadcastMessage(color(getConfig().getString("chat.prefix", "&8[&bAI&8] "))
                    + persona.displayName() + ChatColor.GRAY + ": " + ChatColor.WHITE + starter);
            memory.add("[" + persona.name() + "]: " + starter);
        }, minutes * 60L * 20L, minutes * 60L * 20L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            if (sender instanceof Player player) {
                botMenu.open(player);
            } else {
                sender.sendMessage(color("&cRun /nexora in-game to open the bot GUI."));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("bots")) {
            sender.sendMessage(color("&bNEXORA-PLAYERS &7| AI personas: &f" + String.join(", ", personas.names())));
            sender.sendMessage(color("&7Use &f/np talk <persona> <message> &7to speak to one."));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("nexoraplayers.admin")) {
                sender.sendMessage(color("&cYou don't have permission."));
                return true;
            }
            reloadConfig();
            personas.reload();
            fightBotBridge.start();
            sender.sendMessage(color("&aNEXORA-PLAYERS config reloaded."));
            return true;
        }
        if (args[0].equalsIgnoreCase("talk")) {
            if (!sender.hasPermission("nexoraplayers.talk")) {
                sender.sendMessage(color("&cYou don't have permission."));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(color("&cUsage: /np talk <persona> <message>"));
                return true;
            }
            Persona persona = personas.find(args[1]);
            if (persona == null) {
                sender.sendMessage(color("&cUnknown persona. Use /np bots."));
                return true;
            }
            String prompt = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            String playerName = sender instanceof Player p ? p.getName() : "Console";
            ai.reply(persona, playerName, prompt, memory.contextFor(playerName), response -> {
                if (response == null || response.isBlank()) {
                    sender.sendMessage(color("&cAI did not respond. Check Ollama and the model name."));
                    return;
                }
                String safe = ChatColor.stripColor(response).replaceAll("[\\r\\n]+", " ").trim();
                Bukkit.broadcastMessage(color(getConfig().getString("chat.prefix", "&8[&bAI&8] "))
                        + persona.displayName() + ChatColor.GRAY + ": " + ChatColor.WHITE + safe);
                memory.add("[" + persona.name() + "]: " + safe);
            });
            sender.sendMessage(color("&7Sent your message to &f" + persona.name() + "&7..."));
            return true;
        }
        sender.sendMessage(color("&cUsage: /np <bots|talk|reload>"));
        return true;
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
