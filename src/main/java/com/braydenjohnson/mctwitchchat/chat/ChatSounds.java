package com.braydenjohnson.mctwitchchat.chat;

import com.braydenjohnson.mctwitchchat.MCTwitchChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Notification sounds for chat messages and redemptions. Any sound ID works, including modded ones. */
public final class ChatSounds {
    /** Ready-made choices for the settings screen: sound ID → name. */
    public static final Map<String, String> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("minecraft:block.note_block.bell", "Bell");
        PRESETS.put("minecraft:block.note_block.chime", "Chime");
        PRESETS.put("minecraft:block.note_block.pling", "Pling");
        PRESETS.put("minecraft:block.note_block.harp", "Harp");
        PRESETS.put("minecraft:block.note_block.xylophone", "Xylophone");
        PRESETS.put("minecraft:block.note_block.iron_xylophone", "Iron xylophone");
        PRESETS.put("minecraft:block.amethyst_block.chime", "Amethyst");
        PRESETS.put("minecraft:entity.experience_orb.pickup", "XP orb");
        PRESETS.put("minecraft:entity.item.pickup", "Pop");
        PRESETS.put("minecraft:entity.ocelot.ambient", "Ocelot");
        PRESETS.put("minecraft:entity.cat.ambient", "Cat meow");
        PRESETS.put("minecraft:entity.cat.purr", "Cat purr");
        PRESETS.put("minecraft:entity.player.levelup", "Level up");
        PRESETS.put("minecraft:ui.button.click", "Click");
        PRESETS.put("minecraft:block.note_block.bit", "Bit (8-bit)");
        PRESETS.put("minecraft:block.note_block.flute", "Flute");
        PRESETS.put("minecraft:block.note_block.guitar", "Guitar");
        PRESETS.put("minecraft:block.note_block.banjo", "Banjo");
        PRESETS.put("minecraft:block.note_block.cow_bell", "Cow bell");
        PRESETS.put("minecraft:block.note_block.didgeridoo", "Didgeridoo");
        PRESETS.put("minecraft:block.bell.use", "Village bell");
        PRESETS.put("minecraft:entity.arrow.hit_player", "Ding");
        PRESETS.put("minecraft:ui.toast.in", "Toast");
        PRESETS.put("minecraft:entity.allay.ambient_without_item", "Allay");
        PRESETS.put("minecraft:entity.parrot.ambient", "Parrot");
        PRESETS.put("minecraft:entity.villager.yes", "Villager hmm");
        PRESETS.put("minecraft:entity.chicken.egg", "Egg plop");
        PRESETS.put("minecraft:block.bubble_column.bubble_pop", "Bubble pop");
        PRESETS.put("minecraft:entity.firework_rocket.twinkle", "Twinkle");
        PRESETS.put("minecraft:block.enchantment_table.use", "Enchant");
        PRESETS.put("minecraft:block.beacon.activate", "Beacon");
        PRESETS.put("minecraft:block.copper_bulb.turn_on", "Copper bulb");
        PRESETS.put("minecraft:block.vault.activate", "Vault");
        PRESETS.put("minecraft:entity.puffer_fish.blow_up", "Pufferfish");
    }

    /** Sounds the game played recently, newest first, for the picker's "Heard recently" filter. */
    private static final java.util.LinkedHashMap<String, Long> RECENT = new java.util.LinkedHashMap<>();

    public static void heard(String id) {
        synchronized (RECENT) {
            RECENT.remove(id);
            RECENT.put(id, System.currentTimeMillis());
            if (RECENT.size() > 200) {
                String eldest = RECENT.keySet().iterator().next();
                RECENT.remove(eldest);
            }
        }
    }

    public static java.util.List<String> recent() {
        synchronized (RECENT) {
            java.util.List<String> list = new java.util.ArrayList<>(RECENT.keySet());
            java.util.Collections.reverse(list);
            return list;
        }
    }

    private ChatSounds() {
    }

    /** Friendly name for presets, otherwise the short form of the ID ("block.anvil.land"). */
    public static String label(String id) {
        String name = PRESETS.get(id);
        if (name != null) return name;
        if (id == null) return "None";
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** True if the text is a usable sound ID like "minecraft:block.note_block.bell". */
    public static boolean valid(String id) {
        return id != null && !id.isBlank() && ResourceLocation.tryParse(id.trim()) != null;
    }

    /**
     * Plays a sound on the UI channel (follows the Master volume slider, not affected by where you stand).
     *
     * @param volumePercent 0–100
     * @param pitchPercent  50–200 (100 = normal)
     */
    public static void play(String id, int volumePercent, int pitchPercent) {
        if (!valid(id) || volumePercent <= 0) return;
        ResourceLocation loc = ResourceLocation.tryParse(id.trim());
        Minecraft mc = Minecraft.getInstance();
        Runnable r = () -> {
            try {
                SoundEvent event = SoundEvent.createVariableRangeEvent(loc);
                mc.getSoundManager().play(SimpleSoundInstance.forUI(event, pitchPercent / 100f, volumePercent / 100f));
            } catch (Exception e) {
                MCTwitchChat.LOG.debug("Could not play sound {}", id, e);
            }
        };
        if (mc.isSameThread()) r.run();
        else mc.execute(r);
    }
}
