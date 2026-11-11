package com.nexora.players;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

public final class OllamaClient {
    private final NexoraPlayers plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OllamaClient(NexoraPlayers plugin) {
        this.plugin = plugin;
    }

    public void reply(Persona persona, String player, String message, String context, Consumer<String> callback) {
        if (!plugin.getConfig().getBoolean("ai.enabled", true)) {
            callback.accept(null);
            return;
        }
        String endpoint = plugin.getConfig().getString("ai.endpoint", "http://127.0.0.1:11434");
        String model = plugin.getConfig().getString("ai.model", "qwen2.5:3b");
        int timeout = Math.max(5, plugin.getConfig().getInt("ai.timeout-seconds", 35));
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        JsonObject options = new JsonObject();
        options.addProperty("temperature", plugin.getConfig().getDouble("ai.temperature", 0.85));
        options.addProperty("num_predict", plugin.getConfig().getInt("ai.max-tokens", 120));
        body.add("options", options);
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", "You are " + persona.name() + ", a real-sounding Minecraft server player persona. "
                + "Personality: " + persona.personality() + " Be natural, concise, friendly, and conversational. "
                + "Answer the player's actual message, including invitations such as 1v1. Never claim you performed game actions. "
                + "You are a chat persona, not a real connected Minecraft client. Avoid markdown, links, slurs, harassment, and excessive emojis. "
                + "Use the recent public chat context when useful. Recent context:\n" + context);
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", player + " says: " + message);
        messages.add(user);
        body.add("messages", messages);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint.replaceAll("/+$", "") + "/api/chat"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid Ollama endpoint: " + ex.getMessage());
            callback.accept(null);
            return;
        }
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            String answer = null;
            if (error != null) {
                plugin.getLogger().warning("Ollama request failed: " + error.getMessage());
            } else if (response.statusCode() >= 200 && response.statusCode() < 300) {
                try {
                    answer = JsonParser.parseString(response.body()).getAsJsonObject()
                            .getAsJsonObject("message").get("content").getAsString();
                } catch (Exception ex) {
                    plugin.getLogger().warning("Could not parse Ollama response: " + ex.getMessage());
                }
            } else {
                plugin.getLogger().warning("Ollama returned HTTP " + response.statusCode() + ": " + response.body());
            }
            final String result = answer;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }
}
