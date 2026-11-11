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
                + "Language style: sound like a Pakistani Minecraft player. Prefer natural Roman Urdu mixed with everyday English, "
                + "not formal or pure English. Examples: 'kya scene hai bro', 'ap pvp kro ge?', 'kahan ho', "
                + "'ruk aa raha hun', 'nvm', 'gg', 'ez'. Match the conversation language and vary your wording; "
                + "do not force slang into every sentence. "
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
                        if (json.has("error") && !json.get("error").isJsonNull()) {
                            String detail = json.get("error").toString();
                            plugin.getLogger().warning("OpenRouter returned an API error: " + detail);
                        } else if (!json.has("choices") || json.get("choices").isJsonNull()
                                || !json.get("choices").isJsonArray() || json.getAsJsonArray("choices").isEmpty()) {
                            plugin.getLogger().warning("OpenRouter response has no choices. Response: "
                                    + abbreviate(response.body(), 700));
                        } else {
                            var choice = json.getAsJsonArray("choices").get(0);
                            if (choice != null && choice.isJsonObject()
                                    && choice.getAsJsonObject().has("message")
                                    && choice.getAsJsonObject().get("message").isJsonObject()) {
                                JsonObject messageObject = choice.getAsJsonObject().getAsJsonObject("message");
                                if (messageObject.has("content") && !messageObject.get("content").isJsonNull()) {
                                    answer = messageObject.get("content").getAsString();
                                } else {
                                    plugin.getLogger().warning("OpenRouter returned an empty message. Response: "
                                            + abbreviate(response.body(), 700));
                                }
                            } else {
                                plugin.getLogger().warning("OpenRouter choice has no message. Response: "
                                        + abbreviate(response.body(), 700));
                            }
                        }
                    } else if (json.has("message") && json.get("message").isJsonObject()
                            && json.getAsJsonObject("message").has("content")
                            && !json.getAsJsonObject("message").get("content").isJsonNull()) {
                        answer = json.getAsJsonObject("message").get("content").getAsString();
                    } else {
                        plugin.getLogger().warning("Ollama response has no message content. Response: "
                                + abbreviate(response.body(), 700));
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("Could not parse AI response (" + ex.getClass().getSimpleName()
                            + "): " + ex.getMessage() + ". Response: " + abbreviate(response.body(), 700));
                }
            } else {
                plugin.getLogger().warning("AI provider returned HTTP " + response.statusCode() + ": "
                        + response.body());
            }
            deliver(callback, answer);
        });
    }

    private static String abbreviate(String value, int max) {
        if (value == null) return "<empty>";
        String clean = value.replaceAll("[\\r\\n]+", " ");
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private void deliver(Consumer<String> callback, String response) {
        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(response));
    }
}
