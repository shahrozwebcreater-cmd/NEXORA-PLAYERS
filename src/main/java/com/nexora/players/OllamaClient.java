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
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OllamaClient(NexoraPlayers plugin) {
        this.plugin = plugin;
    }

    public void reply(Persona persona, String player, String message, String context, Consumer<String> callback) {
        if (!plugin.getConfig().getBoolean("ai.enabled", true)) {
            deliver(callback, null);
            return;
        }

        String provider = plugin.getConfig().getString("ai.provider", "openrouter").toLowerCase();
        boolean openRouter = provider.equals("openrouter");
        String endpoint = plugin.getConfig().getString("ai.endpoint",
                openRouter ? "https://openrouter.ai/api/v1" : "http://127.0.0.1:11434");
        String model = plugin.getConfig().getString("ai.model",
                openRouter ? "openrouter/free" : "qwen2.5:3b");
        String apiKey = plugin.getConfig().getString("ai.api-key", "");
        if (openRouter && (apiKey == null || apiKey.isBlank())) {
            apiKey = System.getenv("NEXORA_AI_API_KEY");
        }
        if (openRouter && (apiKey == null || apiKey.isBlank())) {
            plugin.getLogger().warning("OpenRouter API key missing. Add ai.api-key in config.yml or set NEXORA_AI_API_KEY.");
            deliver(callback, null);
            return;
        }

        int timeout = Math.max(5, plugin.getConfig().getInt("ai.timeout-seconds", 35));
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", plugin.getConfig().getDouble("ai.temperature", 0.85));
        body.addProperty(openRouter ? "max_tokens" : "num_predict",
                plugin.getConfig().getInt("ai.max-tokens", 120));
        if (!openRouter) {
            body.addProperty("stream", false);
            JsonObject options = new JsonObject();
            options.addProperty("temperature", plugin.getConfig().getDouble("ai.temperature", 0.85));
            options.addProperty("num_predict", plugin.getConfig().getInt("ai.max-tokens", 120));
            body.add("options", options);
        }

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

        HttpRequest.Builder requestBuilder;
        try {
            String suffix = openRouter ? "/chat/completions" : "/api/chat";
            requestBuilder = HttpRequest.newBuilder(URI.create(endpoint.replaceAll("/+$", "") + suffix))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json");
            if (openRouter) {
                requestBuilder.header("Authorization", "Bearer " + apiKey)
                        .header("X-Title", "NEXORA-PLAYERS");
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid AI endpoint: " + ex.getMessage());
            deliver(callback, null);
            return;
        }

        HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            String answer = null;
            if (error != null) {
                plugin.getLogger().warning("AI request failed: " + error.getMessage());
            } else if (response.statusCode() >= 200 && response.statusCode() < 300) {
                try {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (openRouter) {
                        answer = json.getAsJsonArray("choices").get(0).getAsJsonObject()
                                .getAsJsonObject("message").get("content").getAsString();
                    } else {
                        answer = json.getAsJsonObject("message").get("content").getAsString();
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("Could not parse AI response: " + ex.getMessage());
                }
            } else {
                plugin.getLogger().warning("AI provider returned HTTP " + response.statusCode() + ": "
                        + response.body());
            }
            deliver(callback, answer);
        });
    }

    private void deliver(Consumer<String> callback, String response) {
        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(response));
    }
}
