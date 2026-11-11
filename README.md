# NEXORA-PLAYERS

AI-powered **public text-chat personas** for Paper 26.2. Personas can respond to players, hold short conversations using recent chat context, remember recent player messages, and occasionally start a conversation.

> Important: this release creates chat personas, **not real Minecraft client/player entities**. They do not physically walk, mine, attack mobs, or use FightBot. Those require a separate NPC/bot implementation and a compatible integration.

## Requirements

- Paper 26.2
- Java 25
- Gradle 8.14+ (to build)
- An OpenRouter API key (free models are available; usage is rate-limited)

## Install

1. Create an account at https://openrouter.ai/ and open https://openrouter.ai/keys.
2. Create an API key. Do not post it in Discord, chat, screenshots, or GitHub.
3. Download the latest successful build artifact from this repository's **Actions** tab.
4. Extract the ZIP and place `NEXORA-PLAYERS-1.0.0.jar` in your Paper server's `plugins/` folder.
5. Start the server once, then stop it and open `plugins/NEXORA-PLAYERS/config.yml`.
6. Paste your key into `ai.api-key: ""`, keeping the quotation marks, then save.
7. Start the server again. The plugin will use OpenRouter's free-model router by default.

You can alternatively set the environment variable `NEXORA_AI_API_KEY` and leave `ai.api-key` blank. The environment variable is preferred where the host panel supports it.

## Free API notes

The default model is `openrouter/free`, which routes requests to an available free model. Free model availability and limits can change. OpenRouter currently documents a limit of 20 requests per minute and 50 requests per day for free model variants. This is not unlimited hosting; if the daily quota is reached, bots may stop replying until the quota resets.

Do not expose or commit your API key. If it is accidentally shared, revoke it in your OpenRouter dashboard and create another one.

## Commands

- `/np` or `/np bots` — list configured personas
- `/np talk <persona> <message>` — send a message to one persona
- `/np reload` — reload config (admin permission)

Mention a persona's name in public chat (for example, `Alex wanna 1v1?`) to address it directly. Other public messages may receive an occasional reply, controlled by the config.

## Configuration

Edit `plugins/NEXORA-PLAYERS/config.yml` to change:
- AI provider, endpoint, model, and API key
- Reply chance and cooldowns
- Conversation starter frequency
- Persona names, colors, personalities, and opening lines
- Memory limits

Use `/np reload` after editing. Keep the API key private.

## Privacy and performance

- Public chat messages and the recent context needed to answer them are sent to the configured AI provider.
- Recent chat context is kept in memory; limited player message history is stored in `plugins/NEXORA-PLAYERS/players.yml`.
- Replies are generated asynchronously so the server main thread is not blocked.
- Review the provider's current data and privacy terms before use.

## Troubleshooting

- No replies: check the console for a missing key, HTTP status, model availability, or quota message.
- HTTP 401/403: check that the API key is valid and active.
- HTTP 429: the free model quota or provider rate limit may have been reached; wait and try later.
- Change `ai.model` to another currently available free model ID from OpenRouter if needed.
- The plugin still supports local Ollama by setting `ai.provider: "ollama"`, `ai.endpoint: "http://127.0.0.1:11434"`, and `ai.model: "qwen2.5:3b"`.

## Roadmap

- Optional Citizens NPC bridge for visible NPC entities
- Safe, explicit integration adapter for supported bot frameworks
- Per-persona long-term memory controls and admin moderation
- Activity/roaming features through a dedicated NPC or client-bot backend
