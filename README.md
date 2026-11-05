# MC Twitch Chat

A client-side NeoForge mod that shows your Twitch chat inside the normal Minecraft chat window. It renders the emotes viewers actually see:

- **Twitch emotes**: global, your channel's, and emotes from *other* channels a viewer is subscribed to. Animated emotes play.
- **Twitch GIFs**: the GIPHY GIFs Tier 2 and Tier 3 subs send with Twitch's GIF keyboard. They play animated and take up several chat lines (adjustable).
- **FrankerFaceZ, BetterTTV and 7TV** emotes, both global and your channel's
- **Emoji**, drawn as colour images (Twemoji)
- Name colours, role badges (★ broadcaster, ⚔ mod, ♦ VIP, ✦ sub), sub and raid notices, and `/me` messages
- **Channel point redemptions** (custom and Twitch's automatic rewards), after a one-time Twitch login
- Gift subs show who gifted to whom, and gift bombs are grouped into one line listing every recipient
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
| Emotes | Twitch / FFZ / BTTV / 7TV / emoji on or off, Twitch GIFs on or off and their size (1 to 8 lines), animation on or off, emote size, how long to wait for images, reload emotes, clear the image cache |
| Rewards | Channel point redemptions, automatic rewards, gift-bomb grouping, Twitch login, redemption colour, a test button |
| Filters | Hide `!commands`, ignored users, mention highlighting, extra highlight words, highlight colour, ping sound, mod deletions |

The prefix and colour fields use `&` codes: `&d` pink, `&5` purple, `&f` white, `&7` grey, `&l` bold, `&o` italic, `&r` reset, and `&#RRGGBB` for any colour. Examples:

- `&d[Stream]&r ` shows **[Stream]** in pink
- `&5&l<&dstream&5&l>&r ` shows `<stream>` in purple and pink

Settings are saved to `config/mctwitchchat.json`, so they persist across restarts. You can also edit that file by hand while the game is closed. Downloaded emote images are cached in `mctwitchchat-cache/`.

## Channel point redemptions

Most redemptions never reach Twitch chat; only rewards that ask for text show up there, and even those don't include the reward's name. To see every redemption, the mod needs to read them from Twitch directly, which takes a one-time login as your channel. Twitch only lets the channel owner read redemptions.

1. **Make a Twitch app (once).** Go to [dev.twitch.tv/console/apps/create](https://dev.twitch.tv/console/apps/create), or click *Create a Twitch app* on the mod's Rewards tab, and fill in:
   - **Name:** anything unique, e.g. `slayyyden MC Chat`
   - **OAuth Redirect URLs:** `http://localhost` (it's required but not used)
   - **Category:** Game Integration
   - **Client Type:** **Public**

   Click *Create*, then *Manage*, and copy the **Client ID**. It isn't a secret.
2. **Paste the Client ID** into *Twitch app Client ID* on the Rewards tab.
3. **Click *Log in with Twitch*.** Your browser opens twitch.tv/activate, and the code is also copied to your clipboard. Log in as your channel and approve. The mod only asks for `channel:read:redemptions`, so it can read redemptions but can't change anything.
4. The status line at the bottom of the tab should say *listening for redemptions*. Use *Test redemption* to see how redemption lines look.

The login is stored in `config/mctwitchchat-auth.json`, separate from the settings, and refreshes itself. If you don't use the mod for 30 days, Twitch makes you log in again. *Log out* deletes and revokes it.

Without the login, rewards that ask for text still appear, as "*name* redeemed a reward: *text*".

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

To publish a release, change `mod_version` in `gradle.properties` (for example to `1.0.1`) and push to `main`. The workflow sees that `v1.0.1` doesn't exist yet. It builds `mc-twitch-chat-neoforge-1.21.1-1.0.1.jar`, creates the `v1.0.1` tag and release, and attaches the JAR. Pushing a `v*` tag yourself also works.

To build locally you need Java 21. Run `./gradlew build`, and the JAR appears in `build/libs/`. `./gradlew runClient` starts a dev client.
