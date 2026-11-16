package com.braydenjohnson.mctwitchchat.screen;

import com.braydenjohnson.mctwitchchat.chat.ChatSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Pick any sound the game knows (vanilla and modded). Two panes, like ESM / Sound Muffler:
 * a searchable list of mods on the left, a searchable list of that mod's sounds on the right.
 * Click a sound to hear it; click it again or press "Use this sound" to pick it.
 */
public class SoundPickerScreen extends Screen {
    private enum Filter {
        ALL("All sounds"), SUGGESTED("Suggested"), RECENT("Heard recently");
        final String label;

        Filter(String label) {
            this.label = label;
        }
    }

    private static final int MARGIN = 8;
    private static final int TOP = 22;
    private static final int LIST_TOP = 46;

    private final Screen parent;
    private final Consumer<String> onPick;
    private final int volume;
    private final int pitch;
    private String selected;
    private Filter filter = Filter.ALL;
    private String query = "";
    private String modQuery = "";
    /** Namespace to show ("" = every mod). */
    private String mod = "";

    private List<String> allSounds = List.of();
    private final Map<String, Integer> perMod = new TreeMap<>();
    private final Map<String, String> modNames = new HashMap<>();
    private int shownCount;

    private ModPane modPane;
    private SoundPane soundPane;
    private EditBox modSearch;
    private EditBox search;
    private CycleButton<Filter> filterButton;
    private LinearLayout footer;
    private Button useButton;

    /**
     * @param volume preview volume in percent
     * @param pitch  preview pitch in percent
     */
    public SoundPickerScreen(Screen parent, String title, String current, int volume, int pitch, Consumer<String> onPick) {
        super(Component.literal(title));
        this.parent = parent;
        this.selected = current;
        this.onPick = onPick;
        this.volume = Math.max(30, volume);
        this.pitch = pitch;
    }

    @Override
    protected void init() {
        // Every sound the game knows: the sound registry (all mods' registered sounds) plus every
        // entry from all resource packs' sounds.json files, which can include sounds no mod registers.
        TreeSet<String> ids = new TreeSet<>();
        for (ResourceLocation loc : BuiltInRegistries.SOUND_EVENT.keySet()) ids.add(loc.toString());
        for (ResourceLocation loc : minecraft.getSoundManager().getAvailableSounds()) ids.add(loc.toString());
        allSounds = new ArrayList<>(ids);
        perMod.clear();
        for (String id : allSounds) perMod.merge(namespace(id), 1, Integer::sum);

        modSearch = new EditBox(font, 0, 0, 100, 20, Component.literal("Search mods"));
        modSearch.setHint(Component.literal("Search mods").withStyle(ChatFormatting.DARK_GRAY));
        modSearch.setValue(modQuery);
        modSearch.setResponder(v -> {
            modQuery = v;
            refreshMods();
        });
        addRenderableWidget(modSearch);

        search = new EditBox(font, 0, 0, 150, 20, Component.literal("Search sounds"));
        search.setHint(Component.literal("Search sounds, e.g. bell, cat, anvil").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(query);
        search.setResponder(v -> {
            query = v;
            refreshSounds();
        });
        addRenderableWidget(search);

        filterButton = CycleButton.<Filter>builder(f -> Component.literal(f.label))
                .withValues(Filter.values())
                .withInitialValue(filter)
                .displayOnlyValue()
                .create(0, 0, 110, 20, Component.literal("Show"), (b, v) -> {
                    filter = v;
                    refreshSounds();
                });
        filterButton.setTooltip(Tooltip.create(Component.literal(
                "All sounds: everything, including modded sounds. Suggested: short notification-friendly sounds. "
                        + "Heard recently: sounds the game just played around you, so you can grab one you heard in-game.")));
        addRenderableWidget(filterButton);

        modPane = new ModPane(minecraft);
        soundPane = new SoundPane(minecraft);
        addRenderableWidget(modPane);
        addRenderableWidget(soundPane);

        footer = LinearLayout.horizontal().spacing(8);
        footer.addChild(Button.builder(Component.literal("▶ Play"), b -> preview()).width(100).build());
        useButton = footer.addChild(Button.builder(Component.literal("Use this sound"), b -> {
            if (selected != null) onPick.accept(selected);
            minecraft.setScreen(parent);
        }).width(150).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> minecraft.setScreen(parent)).width(100).build());
        footer.visitWidgets(this::addRenderableWidget);

        repositionElements();
        refreshMods();
        refreshSounds();
        setInitialFocus(search);
    }

    private int modPaneWidth() {
        return Math.max(110, Math.min(180, width * 3 / 10));
    }

    @Override
    protected void repositionElements() {
        if (soundPane == null) return;
        int modW = modPaneWidth();
        int rightX = MARGIN + modW + MARGIN;
        int rightW = width - rightX - MARGIN;
        int listH = Math.max(40, height - LIST_TOP - 52);

        modSearch.setX(MARGIN);
        modSearch.setY(TOP);
        modSearch.setWidth(modW);
        search.setX(rightX);
        search.setY(TOP);
        search.setWidth(Math.max(60, rightW - 116));
        filterButton.setX(rightX + rightW - 110);
        filterButton.setY(TOP);

        modPane.setRectangle(modW, listH, MARGIN, LIST_TOP);
        soundPane.setRectangle(rightW, listH, rightX, LIST_TOP);

        footer.arrangeElements();
        FrameLayout.centerInRectangle(footer, 0, height - 30, width, 30);
    }

    private void refreshMods() {
        String q = modQuery.trim().toLowerCase(Locale.ROOT);
        List<String> mods = new ArrayList<>();
        mods.add("");
        if (perMod.containsKey("minecraft")) mods.add("minecraft");
        perMod.keySet().stream().filter(n -> !n.equals("minecraft"))
                .sorted((a, b) -> modName(a).compareToIgnoreCase(modName(b)))
                .forEach(mods::add);
        modPane.replace(mods.stream()
                .filter(ns -> ns.isEmpty() || q.isEmpty() || ns.contains(q) || modName(ns).toLowerCase(Locale.ROOT).contains(q))
                .toList());
    }

    private void refreshSounds() {
        List<String> ids = new ArrayList<>();
        switch (filter) {
            case SUGGESTED -> ids.addAll(ChatSounds.PRESETS.keySet());
            case RECENT -> ids.addAll(ChatSounds.recent());
            case ALL -> ids.addAll(allSounds);
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<String> shown = ids.stream()
                .filter(id -> mod.isEmpty() || namespace(id).equals(mod))
                .filter(id -> q.isEmpty() || id.toLowerCase(Locale.ROOT).contains(q)
                        || ChatSounds.label(id).toLowerCase(Locale.ROOT).contains(q))
                .toList();
        shownCount = shown.size();
        soundPane.replace(shown);
        if (useButton != null) useButton.active = selected != null;
    }

    private void selectMod(String ns) {
        mod = ns;
        refreshSounds();
    }

    private static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : "minecraft";
    }

    /** "mekanism" → "Mekanism" (from the mod's own display name when it is loaded). */
    private String modName(String ns) {
        if (ns.isEmpty()) return "All mods";
        return modNames.computeIfAbsent(ns, n -> {
            if (n.equals("minecraft")) return "Minecraft";
            return ModList.get().getModContainerById(n)
                    .map(c -> c.getModInfo().getDisplayName())
                    .orElse(n);
        });
    }

    private void preview() {
        if (selected != null) ChatSounds.play(selected, volume, pitch);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, Component.literal(title.getString() + "  ·  ").append(
                Component.literal(shownCount + " sounds in " + modName(mod)).withStyle(ChatFormatting.GRAY)), width / 2, 8, 0xFFFFFFFF);
        String chosen = selected == null ? "nothing selected" : ChatSounds.label(selected) + "  (" + selected + ")";
        g.drawCenteredString(font, Component.literal("Selected: " + chosen).withStyle(ChatFormatting.GRAY), width / 2, height - 44, 0xFFFFFFFF);
        if (soundPane.children().isEmpty()) {
            String msg = filter == Filter.RECENT
                    ? "Nothing heard yet. Play for a bit, then come back."
                    : "No sounds match.";
            g.drawCenteredString(font, Component.literal(msg).withStyle(ChatFormatting.DARK_GRAY),
                    soundPane.getX() + soundPane.getWidth() / 2, LIST_TOP + 20, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------ mod pane

    private final class ModPane extends ObjectSelectionList<ModPane.Row> {
        ModPane(Minecraft mc) {
            super(mc, 100, 100, LIST_TOP, 14);
        }

        void replace(List<String> namespaces) {
            clearEntries();
            Row toSelect = null;
            for (String ns : namespaces) {
                Row row = new Row(ns);
                addEntry(row);
                if (ns.equals(mod)) toSelect = row;
            }
            setSelected(toSelect);
            setScrollAmount(0);
        }

        @Override
        public int getRowWidth() {
            return getWidth() - 12;
        }

        protected int getScrollbarPosition() {
            return getX() + getWidth() - 6;
        }

        final class Row extends ObjectSelectionList.Entry<Row> {
            private final String ns;
            private final Component name;
            private final Component count;

            Row(String ns) {
                this.ns = ns;
                this.name = Component.literal(modName(ns)).withStyle(ns.isEmpty() ? ChatFormatting.GOLD : ChatFormatting.WHITE);
                int n = ns.isEmpty() ? allSounds.size() : perMod.getOrDefault(ns, 0);
                this.count = Component.literal(Integer.toString(n)).withStyle(ChatFormatting.DARK_GRAY);
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                int countW = font.width(count);
                g.enableScissor(left, top, left + width - countW - 8, top + height);
                g.drawString(font, name, left + 3, top + 2, 0xFFFFFFFF);
                g.disableScissor();
                g.drawString(font, count, left + width - countW - 3, top + 2, 0xFFFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                ModPane.this.setSelected(this);
                selectMod(ns);
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(modName(ns));
            }
        }
    }

    // ------------------------------------------------------------------ sound pane

    private final class SoundPane extends ObjectSelectionList<SoundPane.Row> {
        SoundPane(Minecraft mc) {
            super(mc, 100, 100, LIST_TOP, 14);
        }

        void replace(List<String> ids) {
            clearEntries();
            Row toSelect = null;
            for (String id : ids) {
                Row row = new Row(id);
                addEntry(row);
                if (id.equals(selected)) toSelect = row;
            }
            setSelected(toSelect);
            setScrollAmount(0);
            if (toSelect != null) centerScrollOn(toSelect);
        }

        @Override
        public int getRowWidth() {
            return getWidth() - 12;
        }

        protected int getScrollbarPosition() {
            return getX() + getWidth() - 6;
        }

        final class Row extends ObjectSelectionList.Entry<Row> {
            private final String id;
            private final Component text;
            private final Component modLabel;

            Row(String id) {
                this.id = id;
                String friendly = ChatSounds.PRESETS.get(id);
                if (friendly != null) {
                    text = Component.literal("★ " + friendly + "  ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(id).withStyle(ChatFormatting.DARK_GRAY));
                } else {
                    int colon = id.indexOf(':');
                    text = Component.literal(colon > 0 ? id.substring(colon + 1) : id).withStyle(ChatFormatting.WHITE);
                }
                // When one mod is picked on the left, the mod name on every row is just noise.
                modLabel = mod.isEmpty()
                        ? Component.literal(modName(namespace(id))).withStyle(
                        namespace(id).equals("minecraft") ? ChatFormatting.DARK_GRAY : ChatFormatting.DARK_AQUA)
                        : Component.empty();
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                int modWidth = font.width(modLabel);
                g.enableScissor(left, top, left + width - modWidth - 10, top + height);
                g.drawString(font, text, left + 3, top + 2, 0xFFFFFFFF);
                g.disableScissor();
                if (modWidth > 0) g.drawString(font, modLabel, left + width - modWidth - 3, top + 2, 0xFFFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                boolean again = id.equals(selected);
                selected = id;
                SoundPane.this.setSelected(this);
                if (useButton != null) useButton.active = true;
                preview();
                if (again && button == 0) {
                    // Clicking the selected row again picks it, like a double click.
                    onPick.accept(id);
                    minecraft.setScreen(parent);
                }
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(ChatSounds.label(id));
            }
        }
    }
}
