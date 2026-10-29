package com.einsteinmods.opendoors.client.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Options column laid out like YACL's option list: full-width option rows with a small reset button
 * on their right, and collapsible group headers (centred name, arrow button on the left). No list
 * background: the screen's menu background shows through, as in YACL.
 *
 * <p>The hovered (or else focused) row is reported to the description panel while drawing.
 */
final class SettingsList extends ContainerObjectSelectionList<SettingsList.Row> {
  /** Option height plus the gap below it, as YACL rows. */
  private static final int ROW_HEIGHT = OptionControl.HEIGHT + 2;

  /** Rows are shifted left of centre by this much, as YACL. */
  private static final int ROW_LEFT_SHIFT = 6;

  /** Room kept free on the right for the scroll bar. */
  private static final int SCROLLBAR_SPACE = 20;

  private static final int RESET_BUTTON_SIZE = 20;
  private static final Component RESET_SYMBOL = Component.literal("⟲");
  private static final Tooltip RESET_TOOLTIP =
      Tooltip.create(Component.translatable("controls.reset"));
  private static final Component EXPANDED_SYMBOL = Component.literal("▼");
  private static final Component COLLAPSED_SYMBOL = Component.literal("▶");

  private final Consumer<DescribedOption> describer;

  /** Every row, including those hidden inside collapsed groups, in display order. */
  private final List<Row> allRows = new ArrayList<>();

  private @Nullable GroupHeaderRow currentGroup;

  SettingsList(Minecraft minecraft, Consumer<DescribedOption> describer) {
    super(minecraft, 0, 0, 0, ROW_HEIGHT);
    this.describer = describer;
    this.centerListVertically = false;
  }

  /** Adds an option to the group opened last, or to the top level before any group. */
  void addOption(OptionControl<?> control) {
    addRow(new OptionRow(control, currentGroup, describer));
  }

  /** Opens a collapsible group; options added afterwards belong to it. */
  void addGroup(Component name, Component description) {
    GroupHeaderRow header = new GroupHeaderRow(minecraft.font, name, description, this);
    currentGroup = header;
    addRow(header);
  }

  private void addRow(Row row) {
    allRows.add(row);
    addEntry(row);
  }

  private void refreshVisibleRows() {
    replaceEntries(allRows.stream().filter(Row::isVisible).toList());
    refreshScrollAmount();
  }

  @Override
  public int getRowWidth() {
    return getWidth() - ROW_LEFT_SHIFT - SCROLLBAR_SPACE;
  }

  @Override
  public int getRowLeft() {
    return super.getRowLeft() - ROW_LEFT_SHIFT;
  }

  @Override
  protected void extractListBackground(GuiGraphicsExtractor graphics) {
    // No darker list background, as YACL: the description pane carries it instead.
  }

  @Override
  protected void extractListSeparators(GuiGraphicsExtractor graphics) {
    // The tab bar above draws the header line; the list reaches the bottom of the screen.
  }

  abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
    abstract boolean isVisible();
  }

  /** Option box on the left, reset button on its right. */
  private static final class OptionRow extends Row {
    private final OptionControl<?> control;
    private final Button reset;
    private final @Nullable GroupHeaderRow group;
    private final Consumer<DescribedOption> describer;

    private OptionRow(
        OptionControl<?> control,
        @Nullable GroupHeaderRow group,
        Consumer<DescribedOption> describer) {
      this.control = control;
      this.group = group;
      this.describer = describer;
      this.reset =
          Button.builder(RESET_SYMBOL, button -> control.resetToDefault())
              .size(RESET_BUTTON_SIZE, RESET_BUTTON_SIZE)
              .tooltip(RESET_TOOLTIP)
              .build();
    }

    @Override
    boolean isVisible() {
      return group == null || group.expanded;
    }

    @Override
    public void extractContent(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
      int x = getContentX();
      int y = getContentY();
      control.setRectangle(getContentWidth() - RESET_BUTTON_SIZE, OptionControl.HEIGHT, x, y);
      control.extractRenderState(graphics, mouseX, mouseY, partialTick);
      reset.active = control.active && !control.isDefault();
      reset.setPosition(control.getRight(), y);
      reset.extractRenderState(graphics, mouseX, mouseY, partialTick);
      if (hovered || control.isFocused()) {
        describer.accept(control);
      }
    }

    @Override
    public List<? extends GuiEventListener> children() {
      return List.of(control, reset);
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
      return List.of(control, reset);
    }
  }

  /** Group name centred, with an arrow button on the left that collapses or expands the group. */
  private static final class GroupHeaderRow extends Row implements DescribedOption {
    private final Font font;
    private final Component name;
    private final Component description;
    private final ExpandButton toggle;
    private final SettingsList list;
    private boolean expanded = true;

    private GroupHeaderRow(Font font, Component name, Component description, SettingsList list) {
      this.font = font;
      this.name = name;
      this.description = description;
      this.list = list;
      this.toggle = new ExpandButton(this::toggle);
    }

    private void toggle() {
      expanded = !expanded;
      toggle.setMessage(expanded ? EXPANDED_SYMBOL : COLLAPSED_SYMBOL);
      list.refreshVisibleRows();
    }

    @Override
    public Component name() {
      return name;
    }

    @Override
    public Component description() {
      return description;
    }

    @Override
    boolean isVisible() {
      return true;
    }

    @Override
    public void extractContent(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
      toggle.setPosition(getContentX(), getContentY());
      toggle.extractRenderState(graphics, mouseX, mouseY, partialTick);
      graphics.text(
          font,
          name,
          getContentXMiddle() - font.width(name) / 2,
          toggle.getY() + (OptionControl.HEIGHT - font.lineHeight) / 2 + 1,
          OptionControl.TEXT_COLOR,
          true);
      if (hovered || toggle.isFocused()) {
        list.describer.accept(this);
      }
    }

    @Override
    public List<? extends GuiEventListener> children() {
      return List.of(toggle);
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
      return List.of(toggle);
    }
  }

  /** Arrow button without a box until hovered or focused, as YACL's low-profile button. */
  private static final class ExpandButton extends Button {
    private ExpandButton(Runnable onPress) {
      super(
          0,
          0,
          OptionControl.HEIGHT,
          OptionControl.HEIGHT,
          EXPANDED_SYMBOL,
          button -> onPress.run(),
          DEFAULT_NARRATION);
    }

    @Override
    protected void extractContents(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      if (isHoveredOrFocused()) {
        extractDefaultSprite(graphics);
      }
      extractDefaultLabel(
          graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
    }
  }
}
