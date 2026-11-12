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
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Pick any sound the game knows (vanilla and modded), Sound Muffler style: search, filter to
 * suggested or recently heard sounds, click a row to hear it, then "Use this sound".
 */
public class SoundPickerScreen extends Screen {
    private enum Filter {
        SUGGESTED("Suggested"), RECENT("Heard recently"), ALL("All sounds");
        final String label;

        Filter(String label) {
            this.label = label;
        }
    }

    private final Screen parent;
    private final Consumer<String> onPick;
    private final int volume;
    private final int pitch;
    private String selected;
    private Filter filter;
    private String query = "";

    private SoundList list;
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
        this.filter = ChatSounds.PRESETS.containsKey(current) ? Filter.SUGGESTED : Filter.ALL;
    }

    @Override
    protected void init() {
        search = new EditBox(font, 0, 0, 200, 20, Component.literal("Search sounds"));
        search.setHint(Component.literal("Search, e.g. bell, cat, anvil").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(query);
        search.setResponder(v -> {
            query = v;
            refresh();
        });
        addRenderableWidget(search);

        filterButton = CycleButton.<Filter>builder(f -> Component.literal(f.label))
                .withValues(Filter.values())
                .withInitialValue(filter)
                .create(0, 0, 150, 20, Component.literal("Show"), (b, v) -> {
                    filter = v;
                    refresh();
                });
        filterButton.setTooltip(Tooltip.create(Component.literal(
                "Suggested: short notification-friendly sounds. Heard recently: sounds the game just played around you, "
                        + "so you can grab one you heard in-game. All sounds: everything, including modded sounds.")));
        addRenderableWidget(filterButton);

        list = new SoundList(minecraft, width, height - 88, 52, 14);
        addRenderableWidget(list);

        footer = LinearLayout.horizontal().spacing(8);
        footer.addChild(Button.builder(Component.literal("▶ Play"), b -> preview()).width(100).build());
        useButton = footer.addChild(Button.builder(Component.literal("Use this sound"), b -> {
            if (selected != null) onPick.accept(selected);
            minecraft.setScreen(parent);
        }).width(150).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> minecraft.setScreen(parent)).width(100).build());
        footer.visitWidgets(this::addRenderableWidget);

        refresh();
        repositionElements();
        setInitialFocus(search);
    }

    @Override
    protected void repositionElements() {
        if (list == null) return;
        int top = 24;
        int total = 200 + 8 + 150;
        int x = (width - total) / 2;
        search.setX(x);
        search.setY(top);
        filterButton.setX(x + 208);
        filterButton.setY(top);
        list.setRectangle(width, height - 88, 0, 52);
        footer.arrangeElements();
        FrameLayout.centerInRectangle(footer, 0, height - 32, width, 32);
    }

    private void refresh() {
        List<String> ids = new ArrayList<>();
        switch (filter) {
            case SUGGESTED -> ids.addAll(ChatSounds.PRESETS.keySet());
            case RECENT -> ids.addAll(ChatSounds.recent());
            case ALL -> {
                for (ResourceLocation loc : minecraft.getSoundManager().getAvailableSounds()) ids.add(loc.toString());
                ids.sort(String::compareTo);
            }
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        list.replace(ids.stream()
                .filter(id -> q.isEmpty() || id.toLowerCase(Locale.ROOT).contains(q)
                        || ChatSounds.label(id).toLowerCase(Locale.ROOT).contains(q))
                .toList());
        if (useButton != null) useButton.active = selected != null;
    }

    private void preview() {
        if (selected != null) ChatSounds.play(selected, volume, pitch);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        String chosen = selected == null ? "nothing selected" : ChatSounds.label(selected) + "  (" + selected + ")";
        g.drawCenteredString(font, Component.literal("Selected: " + chosen).withStyle(ChatFormatting.GRAY), width / 2, height - 44, 0xFFFFFFFF);
        if (list.children().isEmpty()) {
            String msg = filter == Filter.RECENT
                    ? "No sounds heard yet. Play for a bit, then come back."
                    : "No sounds match your search.";
            g.drawCenteredString(font, Component.literal(msg).withStyle(ChatFormatting.DARK_GRAY), width / 2, 70, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------ list

    private final class SoundList extends ObjectSelectionList<SoundList.Row> {
        SoundList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
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
            return Math.min(width - 40, 380);
        }

        final class Row extends ObjectSelectionList.Entry<Row> {
            private final String id;
            private final Component text;

            Row(String id) {
                this.id = id;
                String friendly = ChatSounds.PRESETS.get(id);
                if (friendly != null) {
                    text = Component.literal("★ " + friendly + "  ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(id).withStyle(ChatFormatting.DARK_GRAY));
                } else {
                    int colon = id.indexOf(':');
                    String ns = colon > 0 ? id.substring(0, colon) : "";
                    String path = colon > 0 ? id.substring(colon + 1) : id;
                    text = Component.literal(path).withStyle(ChatFormatting.WHITE)
                            .append(Component.literal(ns.equals("minecraft") ? "" : "  " + ns).withStyle(ChatFormatting.DARK_AQUA));
                }
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                g.drawString(font, text, left + 4, top + 2, 0xFFFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                boolean again = id.equals(selected);
                selected = id;
                SoundList.this.setSelected(this);
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
