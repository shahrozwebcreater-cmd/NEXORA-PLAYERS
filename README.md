# NEXORA-PLAYERS

AI-powered **public text-chat personas** for Paper 26.2. Personas can respond to players, hold short conversations using recent chat context, remember recent player messages, and occasionally start a conversation.

> Important: this first release creates chat personas, **not real Minecraft client/player entities**. They do not physically walk, mine, attack mobs, or use FightBot. Those require a separate NPC/bot implementation and a compatible integration. This plugin does not pretend chat output is in-game movement.

## Requirements

- Paper 26.2
- Java 25
- Gradle 8.14+ (or compatible)
- Ollama running on the server host or another reachable machine
- A downloaded Ollama chat model, e.g. `qwen2.5:3b`

## Build

```bash
gradle build
```

The shaded plugin JAR is created at:

```
build/libs/NEXORA-PLAYERS-1.0.0.jar
```

Copy that JAR into the Paper server's `plugins/` folder and restart.

## Install the free local AI

Install Ollama from https://ollama.com, then run:

```bash
ollama pull qwen2.5:3b
ollama serve
```

On a Linux VPS, keep Ollama running as a service. If Ollama is on another machine, change `ai.endpoint` in `plugins/NEXORA-PLAYERS/config.yml` to its reachable address. Do not expose Ollama publicly without a firewall or authentication layer.

## Commands

- `/np` or `/np bots` — list configured personas
- `/np talk <persona> <message>` — send a message to one persona
- `/np reload` — reload config (OP/admin permission)

Mention a persona's name in public chat (for example, `Alex wanna 1v1?`) to address it directly. Other public messages may receive an occasional reply, controlled by the config.

## Configuration

Edit `plugins/NEXORA-PLAYERS/config.yml` to change:
- Ollama endpoint and model
- Reply chance and cooldowns
- Conversation starter frequency
- Persona names, colors, personalities, and opening lines
- Memory limits

Use `/np reload` after editing.

## Privacy and performance

- Chat messages are sent to the configured Ollama endpoint to generate replies.
- Recent chat context is kept in memory; limited player message history is stored in `plugins/NEXORA-PLAYERS/players.yml`.
- Keep the Ollama endpoint private. The model runs locally if Ollama is installed on the same host.
- Replies are generated asynchronously so the server main thread is not blocked.

## Troubleshooting

- No replies: check that Ollama is running, `ai.enabled: true`, the model exists (`ollama list`), and the endpoint is reachable from the Minecraft host.
- Wrong model: update `ai.model` and run `ollama pull <model>`.
- Slow replies: use a smaller model, increase `ai.timeout-seconds`, or lower `ai.max-tokens`.
- Console errors: check the Paper console for the HTTP status or Ollama error.

## Roadmap

- Optional Citizens NPC bridge for visible NPC entities
- Safe, explicit integration adapter for supported bot frameworks
- Per-persona long-term memory controls and admin moderation
- Activity/roaming features through a dedicated NPC or client-bot backend
