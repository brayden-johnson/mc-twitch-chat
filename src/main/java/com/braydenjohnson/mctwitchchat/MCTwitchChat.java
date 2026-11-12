package com.braydenjohnson.mctwitchchat;

import com.braydenjohnson.mctwitchchat.chat.StreamChatManager;
import com.braydenjohnson.mctwitchchat.config.ChatConfig;
import com.braydenjohnson.mctwitchchat.emote.EmoteRegistry;
import com.braydenjohnson.mctwitchchat.screen.StreamChatScreen;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(value = MCTwitchChat.MODID, dist = Dist.CLIENT)
public final class MCTwitchChat {
    public static final String MODID = "mctwitchchat";
    public static final Logger LOG = LogUtils.getLogger();
    public static final ResourceLocation EMOTE_FONT = ResourceLocation.fromNamespaceAndPath(MODID, "emote");

    private static final String CATEGORY = "key.categories.mctwitchchat";
    private static final KeyMapping TOGGLE_KEY = new KeyMapping("key.mctwitchchat.toggle", InputConstants.UNKNOWN.getValue(), CATEGORY);
    private static final KeyMapping SETTINGS_KEY = new KeyMapping("key.mctwitchchat.settings", InputConstants.UNKNOWN.getValue(), CATEGORY);

    private static boolean started;
    private static boolean openSettings;

    public MCTwitchChat(IEventBus modBus, ModContainer container) {
        ChatConfig.load();
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new StreamChatScreen(parent));
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.register(TOGGLE_KEY);
            event.register(SETTINGS_KEY);
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> onTick());
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, MCTwitchChat::registerCommands);
        // Remember sounds as they play, for the sound picker's "Heard recently" list (like Sound Muffler).
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.sound.PlaySoundEvent.class, event -> {
            var sound = event.getOriginalSound();
            if (sound != null) com.braydenjohnson.mctwitchchat.chat.ChatSounds.heard(sound.getLocation().toString());
        });
    }

    private static void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (!started) {
            started = true;
            StreamChatManager.get().applyConfig();
        }
        while (TOGGLE_KEY.consumeClick()) StreamChatManager.get().togglePause();
        while (SETTINGS_KEY.consumeClick()) openSettings = true;
        if (openSettings) {
            openSettings = false;
            mc.setScreen(new StreamChatScreen(mc.screen));
        }
    }

    private static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("streamchat")
                .executes(c -> {
                    openSettings = true;
                    return 1;
                })
                .then(Commands.literal("settings").executes(c -> {
                    openSettings = true;
                    return 1;
                }))
                .then(Commands.literal("reconnect").executes(c -> {
                    StreamChatManager.get().restart();
                    return 1;
                }))
                .then(Commands.literal("disconnect").executes(c -> {
                    StreamChatManager.get().disconnect();
                    return 1;
                }))
                .then(Commands.literal("pause").executes(c -> {
                    StreamChatManager.get().togglePause();
                    return 1;
                }))
                .then(Commands.literal("reloademotes").executes(c -> {
                    EmoteRegistry.reloadAll();
                    StreamChatManager.get().postStatus("Reloading emotes…");
                    return 1;
                }))
                .then(Commands.literal("connect")
                        .then(Commands.argument("channel", StringArgumentType.word()).executes(c -> {
                            StreamChatManager.get().connectTo(StringArgumentType.getString(c, "channel"));
                            return 1;
                        }))));
    }
}
