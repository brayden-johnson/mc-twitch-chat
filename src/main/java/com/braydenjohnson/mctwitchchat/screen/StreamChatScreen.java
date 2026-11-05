package com.braydenjohnson.mctwitchchat.screen;

import com.braydenjohnson.mctwitchchat.chat.ChatLine;
import com.braydenjohnson.mctwitchchat.chat.Formatter;
import com.braydenjohnson.mctwitchchat.chat.MessageParser;
import com.braydenjohnson.mctwitchchat.chat.StreamChatManager;
import com.braydenjohnson.mctwitchchat.config.ChatConfig;
import com.braydenjohnson.mctwitchchat.emote.EmoteRegistry;
import com.braydenjohnson.mctwitchchat.emote.ImageCache;
import com.braydenjohnson.mctwitchchat.util.LegacyText;
import com.braydenjohnson.mctwitchchat.twitch.TwitchAuth;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Settings screen: Connection, Look, Emotes and Filters tabs, with a live preview line. */
public class StreamChatScreen extends Screen {
    private static final int W = 150;
    private static final List<String> PREFIX_PRESETS = List.of(
            "&d[Stream]&r ",
            "&5[&dTwitch&5]&r ",
            "&7<&dstream&7>&r ",
            "&d&lSTREAM:&r ",
            "&5&l| &r",
            "&d✦&r ",
            "");

    private final Screen parent;
    private final ChatConfig work;
    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    private TabNavigationBar tabBar;
    private LinearLayout footer;
    private StringWidget statusLabel;
    private StringWidget emoteLabel;
    private StringWidget rewardsLabel;
    private int previewTop;

    public StreamChatScreen(Screen parent) {
        super(Component.literal("MC Twitch Chat"));
        this.parent = parent;
        this.work = ChatConfig.get().copy();
    }

    @Override
    protected void init() {
        tabBar = TabNavigationBar.builder(tabManager, width)
                .addTabs(new ConnectionTab(), new LookTab(), new EmotesTab(), new RewardsTab(), new FiltersTab())
                .build();
        addRenderableWidget(tabBar);

        footer = LinearLayout.horizontal().spacing(8);
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> saveAndClose()).width(W).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> minecraft.setScreen(parent)).width(W).build());
        footer.visitWidgets(this::addRenderableWidget);

        tabBar.selectTab(0, false);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (tabBar == null || footer == null) return;
        tabBar.setWidth(width);
        tabBar.arrangeElements();
        footer.arrangeElements();
        FrameLayout.centerInRectangle(footer, 0, height - 30, width, 30);
        int top = tabBar.getRectangle().bottom();
        previewTop = height - 30 - 24;
        tabManager.setTabArea(new ScreenRectangle(0, top, width, Math.max(0, previewTop - top - 4)));
    }

    @Override
    public void tick() {
        super.tick();
        if (statusLabel != null) statusLabel.setMessage(Component.literal("Status: " + StreamChatManager.get().status()).withStyle(ChatFormatting.GRAY));
        if (emoteLabel != null) emoteLabel.setMessage(Component.literal(EmoteRegistry.summary()).withStyle(ChatFormatting.GRAY));
        if (rewardsLabel != null) {
            String s = TwitchAuth.loginRunning() || !TwitchAuth.loggedIn() ? TwitchAuth.status() : StreamChatManager.get().rewardsStatus();
            rewardsLabel.setMessage(Component.literal(s).withStyle(ChatFormatting.GRAY));
            rewardsLabel.setTooltip(Tooltip.create(Component.literal(s)));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int boxW = Math.min(width - 20, 440);
        int x0 = (width - boxW) / 2;
        g.fill(x0, previewTop, x0 + boxW, previewTop + 20, 0x90000000);
        g.drawString(font, Component.literal("Preview").withStyle(ChatFormatting.DARK_GRAY), x0 + boxW - 40, previewTop + 6, 0xFFFFFFFF, false);
        g.enableScissor(x0, previewTop, x0 + boxW - 44, previewTop + 20);
        g.drawString(font, previewLine(), x0 + 4, previewTop + 6, 0xFFFFFFFF);
        g.disableScissor();
    }

    private Component previewLine() {
        String text = "hi chat! Kappa ❤️ welcome to the Leap PogChamp";
        ChatLine line = new ChatLine("", work.channel.isEmpty() ? "slayyyden" : work.channel,
                work.channel.isEmpty() ? "slayyyden" : work.channel, "#C9A0FF",
                List.of("broadcaster/1", "subscriber/12"),
                MessageParser.parse(text, "25:9-13/305954156:38-45", null, work), false, false, null, false);
        return Formatter.format(work, line);
    }

    private void saveAndClose() {
        ChatConfig.set(work);
        ChatConfig.save();
        StreamChatManager.get().applyConfig();
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        saveAndClose();
    }

    // ------------------------------------------------------------------ widget helpers

    private StringWidget label(String text) {
        return new StringWidget(W, 20, Component.literal(text), font).alignLeft();
    }

    private EditBox edit(String value, int maxLength, Consumer<String> onChange, String tooltip) {
        EditBox box = new EditBox(font, 0, 0, W, 20, Component.literal(tooltip));
        box.setMaxLength(maxLength);
        box.setValue(value);
        box.setResponder(onChange);
        box.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return box;
    }

    private CycleButton<Boolean> toggle(String name, boolean value, Consumer<Boolean> onChange, String tooltip) {
        CycleButton<Boolean> b = CycleButton.onOffBuilder(value)
                .create(0, 0, W, 20, Component.literal(name), (btn, v) -> onChange.accept(v));
        b.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return b;
    }

    private Button button(String name, Button.OnPress press, String tooltip) {
        Button b = Button.builder(Component.literal(name), press).width(W).build();
        b.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return b;
    }

    private static GridLayout.RowHelper rows(GridLayout layout) {
        layout.rowSpacing(4).columnSpacing(10);
        return layout.createRowHelper(2);
    }

    // ------------------------------------------------------------------ tabs

    private final class ConnectionTab extends GridLayoutTab {
        ConnectionTab() {
            super(Component.literal("Connection"));
            GridLayout.RowHelper r = rows(layout);
            r.addChild(label("Twitch channel"));
            r.addChild(edit(work.channel, 60, v -> work.channel = ChatConfig.normalizeChannel(v),
                    "Your channel name, like slayyyden. A twitch.tv link works too."));
            r.addChild(toggle("Stream chat", work.enabled, v -> work.enabled = v,
                    "Turn the whole mod on or off. Saved across restarts."));
            r.addChild(toggle("Status messages", work.statusMessages, v -> work.statusMessages = v,
                    "Show lines like \"Connected to #channel\" in chat."));
            r.addChild(button("Save & reconnect", b -> {
                ChatConfig.set(work.copy());
                ChatConfig.save();
                StreamChatManager.get().restart();
            }, "Save these settings and reconnect to Twitch now."));
            r.addChild(button("Disconnect", b -> StreamChatManager.get().disconnect(),
                    "Stop reading chat until you reconnect or restart the game."));
            statusLabel = new StringWidget(W * 2 + 10, 12, Component.literal("Status: " + StreamChatManager.get().status()), font).alignLeft();
            r.addChild(statusLabel, 2);
            MultiLineTextWidget note = new MultiLineTextWidget(Component.literal(
                    "No login or stream key is needed: Twitch chat is read anonymously. "
                            + "Never paste your stream key into a mod or config file.").withStyle(ChatFormatting.DARK_GRAY), font)
                    .setMaxWidth(W * 2 + 10);
            r.addChild(note, 2);
        }
    }

    private final class LookTab extends GridLayoutTab {
        LookTab() {
            super(Component.literal("Look"));
            GridLayout.RowHelper r = rows(layout);

            r.addChild(label("Prefix"));
            EditBox prefixBox = edit(work.prefix, 80, v -> work.prefix = v,
                    "Shown before every stream message. Use & codes: &d pink, &5 purple, &l bold, &o italic, &r reset, &#RRGGBB any colour.");
            r.addChild(prefixBox);

            String initial = PREFIX_PRESETS.contains(work.prefix) ? work.prefix : PREFIX_PRESETS.get(0);
            CycleButton<String> presets = CycleButton.<String>builder(v -> v.isEmpty() ? Component.literal("None") : LegacyText.parse(v))
                    .withValues(PREFIX_PRESETS)
                    .withInitialValue(initial)
                    .create(0, 0, W, 20, Component.literal("Preset"), (btn, v) -> prefixBox.setValue(v));
            presets.setTooltip(Tooltip.create(Component.literal("Click to cycle through ready-made prefixes. It fills in the Prefix box.")));
            r.addChild(presets);
            CycleButton<ChatConfig.TimestampMode> time = CycleButton.<ChatConfig.TimestampMode>builder(m -> Component.literal(switch (m) {
                        case OFF -> "Off";
                        case H24 -> "24h";
                        case H12 -> "12h";
                    }))
                    .withValues(ChatConfig.TimestampMode.values())
                    .withInitialValue(work.timestamps)
                    .create(0, 0, W, 20, Component.literal("Timestamps"), (btn, v) -> work.timestamps = v);
            r.addChild(time);

            r.addChild(label("Separator"));
            r.addChild(edit(work.separator, 40, v -> work.separator = v, "Between the name and the message. Default: &7: "));
            r.addChild(label("Message colour"));
            r.addChild(edit(work.messageColor, 40, v -> work.messageColor = v, "& code or #RRGGBB for message text, e.g. &f or #E0E0E0."));

            CycleButton<ChatConfig.NameColorMode> names = CycleButton.<ChatConfig.NameColorMode>builder(m -> Component.literal(
                            m == ChatConfig.NameColorMode.TWITCH ? "Twitch" : "Fixed"))
                    .withValues(ChatConfig.NameColorMode.values())
                    .withInitialValue(work.nameColorMode)
                    .create(0, 0, W, 20, Component.literal("Name colours"), (btn, v) -> work.nameColorMode = v);
            names.setTooltip(Tooltip.create(Component.literal("Twitch: each viewer's own name colour. Fixed: one colour for everyone.")));
            r.addChild(names);
            r.addChild(toggle("Brighten dark names", work.brightenDarkNames, v -> work.brightenDarkNames = v,
                    "Lightens name colours that are hard to read on the dark chat background."));

            r.addChild(label("Fixed name colour"));
            r.addChild(edit(work.fixedNameColor, 40, v -> work.fixedNameColor = v, "Used when Name colours is Fixed. & code or #RRGGBB."));

            r.addChild(toggle("Role badges", work.showBadges, v -> work.showBadges = v,
                    "Small symbols before names: ★ broadcaster, ⚔ mod, ♦ VIP, ✦ sub."));
            r.addChild(toggle("Sub & raid notices", work.showNotices, v -> work.showNotices = v,
                    "Show subs, resubs, gift subs, raids and announcements."));
            r.addChild(toggle("Twitch bar", work.indicatorBar, v -> work.indicatorBar = v,
                    "A coloured bar at the left edge of stream messages, like Minecraft's own chat tags."));
            r.addChild(toggle("/me in italics", work.actionItalic, v -> work.actionItalic = v,
                    "Write /me messages in italics, in the viewer's name colour."));
        }
    }

    private final class EmotesTab extends GridLayoutTab {
        EmotesTab() {
            super(Component.literal("Emotes"));
            GridLayout.RowHelper r = rows(layout);
            r.addChild(toggle("Twitch emotes", work.twitchEmotes, v -> work.twitchEmotes = v,
                    "Global, channel and other-channel sub emotes that viewers use."));
            r.addChild(toggle("Animated (GIF)", work.animated, v -> work.animated = v,
                    "Play animated emotes. Off shows the first frame."));
            r.addChild(toggle("FrankerFaceZ", work.ffzEmotes, v -> work.ffzEmotes = v, "FFZ global and channel emotes."));
            r.addChild(toggle("BetterTTV", work.bttvEmotes, v -> work.bttvEmotes = v, "BTTV global, channel and shared emotes."));
            r.addChild(toggle("7TV", work.sevenTvEmotes, v -> work.sevenTvEmotes = v, "7TV global and channel emotes."));
            r.addChild(toggle("Emoji", work.emoji, v -> work.emoji = v, "Show emoji as colour images (Twemoji)."));
            r.addChild(toggle("Twitch GIFs", work.twitchGifs, v -> work.twitchGifs = v,
                    "GIPHY GIFs that Tier 2 and Tier 3 subs send from Twitch's GIF keyboard. Off shows the GIF's name."));
            r.addChild(new IntSlider("GIF size", 1, 8, 1, work.gifLines, v -> work.gifLines = v,
                    v -> v == 1 ? "inline" : v + " lines"));
            r.addChild(new IntSlider("Emote size", 6, 18, 1, work.emoteSize, v -> work.emoteSize = v, v -> v + " px"));
            r.addChild(new IntSlider("Wait for images", 0, 5000, 100, work.maxWaitMs, v -> work.maxWaitMs = v,
                    v -> v == 0 ? "never" : String.format("%.1fs", v / 1000.0)));
            r.addChild(button("Reload emotes", b -> EmoteRegistry.reloadAll(),
                    "Download the FFZ, BTTV and 7TV emote lists again (after adding emotes to your channel)."));
            r.addChild(button("Clear disk cache", b -> {
                int n = ImageCache.clearDisk();
                b.setMessage(Component.literal("Cleared " + n + " files"));
            }, "Delete downloaded emote images from the mctwitchchat-cache folder."));
            emoteLabel = new StringWidget(W * 2 + 10, 12, Component.literal(EmoteRegistry.summary()), font).alignLeft();
            r.addChild(emoteLabel, 2);
        }
    }

    private final class RewardsTab extends GridLayoutTab {
        RewardsTab() {
            super(Component.literal("Rewards"));
            GridLayout.RowHelper r = rows(layout);
            r.addChild(toggle("Channel points", work.showRedemptions, v -> work.showRedemptions = v,
                    "Show channel point redemptions in chat. Needs the Twitch login below."));
            r.addChild(toggle("Automatic rewards", work.showAutomaticRewards, v -> work.showAutomaticRewards = v,
                    "Also show Twitch's built-in rewards, like Unlock a Sub Emote and On-Screen Celebration."));
            r.addChild(toggle("Group gift bombs", work.groupGiftBombs, v -> work.groupGiftBombs = v,
                    "When someone gifts several subs at once, show one line listing everyone who got one instead of a line per gift."));
            r.addChild(button("Test redemption", b -> {
                ChatConfig.set(work.copy());
                StreamChatManager.get().postTestRedemption();
            }, "Adds a sample redemption to chat so you can see how it looks."));

            r.addChild(label("Twitch app Client ID"));
            r.addChild(edit(work.twitchClientId, 64, v -> work.twitchClientId = v.trim(),
                    "From dev.twitch.tv/console: register an app with Client Type \"Public\" and paste its Client ID here. It isn't a secret."));
            r.addChild(button("Log in with Twitch", b -> {
                ChatConfig.set(work.copy());
                ChatConfig.save();
                TwitchAuth.startLogin(work.twitchClientId,
                        url -> Util.getPlatform().openUri(url),
                        code -> minecraft.execute(() -> minecraft.keyboardHandler.setClipboard(code)),
                        () -> StreamChatManager.get().syncRewards());
            }, "Opens twitch.tv/activate. Approve the code shown here (it's also copied to your clipboard). Log in as your own channel: Twitch only lets the channel owner read redemptions."));
            r.addChild(button("Log out", b -> {
                TwitchAuth.logout();
                StreamChatManager.get().syncRewards();
            }, "Forget the Twitch login and revoke its token."));

            r.addChild(button("Create a Twitch app ↗", b -> Util.getPlatform().openUri("https://dev.twitch.tv/console/apps/create"),
                    "Opens the Twitch developer console. Name: anything, OAuth Redirect URL: https://localhost (any HTTPS URL; it is never used), Category: Game Integration, Client Type: Public."));
            r.addChild(button("Setup guide ↗", b -> Util.getPlatform().openUri("https://github.com/brayden-johnson/mc-twitch-chat#channel-point-redemptions"),
                    "Step-by-step instructions on GitHub."));

            r.addChild(label("Redemption colour"));
            r.addChild(edit(work.redemptionColor, 40, v -> work.redemptionColor = v, "& code or #RRGGBB for redemption lines. Default &b (aqua)."));

            rewardsLabel = new StringWidget(W * 2 + 10, 12, Component.literal(TwitchAuth.status()), font).alignLeft();
            r.addChild(rewardsLabel, 2);
        }
    }

    private final class FiltersTab extends GridLayoutTab {
        FiltersTab() {
            super(Component.literal("Filters"));
            GridLayout.RowHelper r = rows(layout);
            r.addChild(toggle("Hide !commands", work.hideCommands, v -> work.hideCommands = v,
                    "Don't show messages that start with ! (like !house)."));
            r.addChild(toggle("Mod deletions", work.respectModDeletes, v -> work.respectModDeletes = v,
                    "When a mod deletes a message or times someone out, remove it from Minecraft chat too."));
            r.addChild(label("Ignored users"));
            r.addChild(edit(work.ignoredUsers, 500, v -> work.ignoredUsers = v,
                    "Comma separated logins to hide, e.g. nightbot, streamelements"));
            r.addChild(toggle("Highlight mentions", work.highlightMentions, v -> work.highlightMentions = v,
                    "Highlight messages that mention your channel name."));
            r.addChild(toggle("Ping sound", work.pingSound, v -> work.pingSound = v,
                    "Play a small sound for highlighted messages."));
            r.addChild(label("Highlight words"));
            r.addChild(edit(work.highlightWords, 500, v -> work.highlightWords = v,
                    "Comma separated extra words that highlight a message."));
            r.addChild(label("Highlight colour"));
            r.addChild(edit(work.highlightColor, 40, v -> work.highlightColor = v, "& code or #RRGGBB for highlighted messages."));
        }
    }

    // ------------------------------------------------------------------ slider

    private static final class IntSlider extends AbstractSliderButton {
        private final String label;
        private final int min;
        private final int max;
        private final int step;
        private final IntConsumer setter;
        private final IntFunction<String> format;

        IntSlider(String label, int min, int max, int step, int value, IntConsumer setter, IntFunction<String> format) {
            super(0, 0, W, 20, Component.empty(), (Math.max(min, Math.min(max, value)) - min) / (double) (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.setter = setter;
            this.format = format;
            updateMessage();
        }

        private int current() {
            int steps = (int) Math.round(value * (max - min) / step);
            return Math.max(min, Math.min(max, min + steps * step));
        }

        @Override
        protected void updateMessage() {
            if (format == null) return;
            setMessage(Component.literal(label + ": " + format.apply(current())));
        }

        @Override
        protected void applyValue() {
            setter.accept(current());
        }
    }
}
