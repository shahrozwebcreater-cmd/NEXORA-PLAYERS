# NEXORA-PLAYERS 1.5.0

AI-powered **public text-chat personas** for Paper 26.2. Personas can respond to players, hold short conversations using recent chat context, remember recent player messages, and occasionally start a conversation.

> Important: NEXORA-PLAYERS does not create its own Minecraft client/player entities. Physical movement and combat are delegated to the separate FightBot plugin. This release can start FightBot free-roaming in one configured world and start/stop one-on-one duels from chat. It does not implement autonomous resource mining, farming, crafting, or inventory management.

## Requirements

- Paper 26.2
- Java 25
- Gradle 8.14+ to build
- A Groq API key (free developer access is subject to Groq's current rate limits)

## Install

1. Download the latest successful build artifact from this repository's **Actions** tab.
2. Stop your server and back up the existing `plugins/NEXORA-PLAYERS/` folder.
3. Replace the old plugin JAR with the new `NEXORA-PLAYERS-1.5.0.jar`.
4. Start the server once, then stop it and open `plugins/NEXORA-PLAYERS/config.yml`.
5. Paste your Groq key into `ai.api-key: ""`, keeping the quotation marks. Alternatively set the `GROQ_API_KEY` environment variable in your hosting panel.
6. Confirm the AI settings below, then start the server.

## Groq configuration

The default configuration is:

```yaml
ai:
  enabled: true
  provider: "groq"
  endpoint: "https://api.groq.com/openai/v1"
  model: "llama-3.3-70b-versatile"
  api-key: "YOUR_GROQ_API_KEY"
  timeout-seconds: 45
  max-tokens: 120
  temperature: 0.85
```

Groq uses its OpenAI-compatible Chat Completions endpoint. The plugin sends the key as a Bearer token and reads the assistant reply from `choices[0].message.content`.

## Other supported providers

- OpenRouter: `provider: "openrouter"`, endpoint `https://openrouter.ai/api/v1`, model `openrouter/free`; key can be supplied using `NEXORA_AI_API_KEY`.
- Ollama: `provider: "ollama"`, endpoint `http://127.0.0.1:11434`, model such as `qwen2.5:3b`.

The plugin does not silently switch providers. To change provider, update its endpoint, model, and key together.

## FightBot gameplay (1.5.0)

Requires the separate FightBot plugin to be installed and enabled.

- On startup, active FightBots in the configured world are ordered to use FightBot free roaming.
- A chat challenge such as `1v1 me`, `duel me`, or `fight me` starts `/fb fight <bot> <player>` for one available bot in that world. If a bot name is included, that bot is selected; otherwise an available bot is chosen.
- Saying `stop`, `stop pvp`, or `stop fighting` ends the duel started through this bridge with `/fb stop <bot>`. By default the bot then resumes free roaming.
- The bridge tracks duels in memory; a plugin/server restart clears that temporary tracking.

Configuration:

```yaml
gameplay:
  enabled: true
  world: "world"
  start-free-roaming: true
  challenge-only-pvp: true
  stop-returns-to-roaming: true
```

FightBot's free roaming makes bots move around; it is not the same as survival grinding. Mining ores, chopping trees, farming, crafting, storing items, and managing food are not automated by this release.

## Commands

- `/np` or `/np bots` — list configured personas
- `/np talk <persona> <message>` — send a message to one persona
- `/np reload` — reload config (admin permission)

Mention a persona's name in public chat (for example, `Alex wanna 1v1?`) to address it directly. Other public messages may receive an occasional reply, controlled by the config.

## Privacy and performance

- Public chat messages and recent context needed to answer them are sent to the configured AI provider.
- Recent chat context is kept in memory; limited player message history is stored in `plugins/NEXORA-PLAYERS/players.yml`.
- Replies are generated asynchronously so the server main thread is not blocked.
- Never commit or share your API key. If it is exposed, revoke it and create a replacement.
- Free API access has usage limits and is not guaranteed to be unlimited. HTTP 429 means the provider quota/rate limit was reached; reduce request frequency or wait for reset.

## Troubleshooting

- HTTP 401: check that the API key is valid and active.
- HTTP 404: check the model ID and endpoint.
- HTTP 429: the provider's rate limit or free quota may have been reached.
- No replies: check console logs, provider settings, and the API key.
- Use `/np reload` after editing the configuration.

