# MC Twitch Chat

A client-side NeoForge mod that shows your Twitch chat inside the normal Minecraft chat window. It renders the emotes viewers actually see:

- **Twitch emotes**: global, your channel's, and emotes from *other* channels a viewer is subscribed to. Animated emotes, like Tier 2 animated emotes, play as GIFs.
- **FrankerFaceZ, BetterTTV and 7TV** emotes, both global and your channel's
- **Emoji**, drawn as colour images (Twemoji)
- Name colours, role badges (★ broadcaster, ⚔ mod, ♦ VIP, ✦ sub), sub and raid notices, and `/me` messages
- Messages a mod deletes, or from someone who gets timed out, are removed from Minecraft chat too

Built for **Minecraft 1.21.1 / NeoForge 21.1.x**, which is what FTB Stoneblock 4 runs on. Only you need it: servers don't need the mod and can't tell you have it.

## Install

1. Download the latest `mc-twitch-chat-neoforge-1.21.1-<version>.jar` from **[Releases](https://github.com/brayden-johnson/mc-twitch-chat/releases)**.
2. Put it in your instance's `mods` folder. In the CurseForge app, that's right-click the pack → *Open Folder* → `mods`.
3. Start the game. It connects to the channel in the settings, `slayyyden` by default.

**No login, OAuth token or stream key is needed.** Twitch chat is read anonymously. Never put your stream key into a mod or config file.

## Settings

Open them with **Mods → MC Twitch Chat → Config**, with the `/streamchat` command, or with a key you bind under *Controls → MC Twitch Chat*. A live preview line at the bottom shows your changes as you make them.

| Tab | What you can change |
|---|---|
| Connection | Channel, on/off, status messages, reconnect / disconnect |
| Look | Prefix (with presets), separator, message colour, name colours (Twitch or fixed), brighten dark names, timestamps, badges, sub/raid notices, the coloured Twitch bar, `/me` italics |
| Emotes | Twitch / FFZ / BTTV / 7TV / emoji on or off, animated GIFs on or off, emote size, how long to wait for images, reload emotes, clear the image cache |
| Filters | Hide `!commands`, ignored users, mention highlighting, extra highlight words, highlight colour, ping sound, mod deletions |

The prefix and colour fields use `&` codes: `&d` pink, `&5` purple, `&f` white, `&7` grey, `&l` bold, `&o` italic, `&r` reset, and `&#RRGGBB` for any colour. Examples:

- `&d[Stream]&r ` shows **[Stream]** in pink
- `&5&l<&dstream&5&l>&r ` shows `<stream>` in purple and pink

Settings are saved to `config/mctwitchchat.json`, so they persist across restarts. You can also edit that file by hand while the game is closed. Downloaded emote images are cached in `mctwitchchat-cache/`.

## Commands

| Command | Does |
|---|---|
| `/streamchat` | Open settings |
| `/streamchat connect <channel>` | Switch channel (saved) |
| `/streamchat reconnect` | Reconnect now |
| `/streamchat disconnect` | Stop reading chat until reconnect or restart |
| `/streamchat pause` | Pause or resume showing messages |
| `/streamchat reloademotes` | Re-download the FFZ, BTTV and 7TV emote lists |

## Limits

- Badges are drawn as symbols, not Twitch's badge images, because Twitch's badge API needs a logged-in app.
- Twitch emotes are always GIF or PNG. Most FFZ, BTTV and 7TV emotes also come as GIF or PNG, which is what the mod requests. A few are WebP-only and show as their name instead.
- The mod reads chat only. Typing in Minecraft chat still talks to the Minecraft server, never to Twitch.

## Building and releases

GitHub Actions builds the JAR on every push. Download it from the run's *Artifacts*.

To publish a release, push a version tag:

```bash
git tag v1.0.1
git push origin v1.0.1
```

The workflow builds `mc-twitch-chat-neoforge-1.21.1-1.0.1.jar` and attaches it to a new release on the Releases page.

To build locally you need Java 21. Run `./gradlew build`, and the JAR appears in `build/libs/`. `./gradlew runClient` starts a dev client.
