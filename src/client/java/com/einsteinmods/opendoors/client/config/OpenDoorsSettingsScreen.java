package com.einsteinmods.opendoors.client.config;

import com.einsteinmods.opendoors.client.OpenDoorsClient;
import com.einsteinmods.opendoors.client.OpenDoorsKeyMappings;
import com.einsteinmods.opendoors.config.OpenDoorsSettings;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Mod settings built from vanilla widgets, laid out like a YACL screen: a tab bar with the
 * category, the options list on the left two thirds, and on the right a description of the hovered
 * option above the Cancel/Reset, Undo and Save/Done buttons.
 *
 * <p>As in YACL, changes stay pending until "Save Changes", which keeps the screen open; with
 * nothing pending that button reads "Done" and closes. "Cancel" (or Escape) discards pending
 * changes and closes; with nothing pending it reads "Reset" and sets every option to its default
 * (still pending, so Undo brings the previous values back).
 *
 * <p>Keys are edited as in {@code KeyBindsScreen}: click a key row, then press a key or mouse
 * button; Escape unbinds. The vanilla {@link KeyMapping} stays the only store, so Options →
 * Controls shows the same bindings; new keys apply on save.
 */
public final class OpenDoorsSettingsScreen extends Screen {
  private static final String KEY_PREFIX = "config.opendoors.";
  private static final String DESCRIPTION_SUFFIX = ".description";
  private static final String TOOLTIP_SUFFIX = ".tooltip";

  private static final Component SAVE = Component.translatable(KEY_PREFIX + "save");
  private static final Tooltip SAVE_TOOLTIP = tooltip("save");
  private static final Tooltip DONE_TOOLTIP = tooltip("done");
  private static final Tooltip CANCEL_TOOLTIP = tooltip("cancel");
  private static final Component RESET_ALL = Component.translatable("controls.reset");
  private static final Tooltip RESET_ALL_TOOLTIP = tooltip("reset");
  private static final Component UNDO = Component.translatable(KEY_PREFIX + "undo");
  private static final Tooltip UNDO_TOOLTIP = tooltip("undo");

  /** Darker texture behind the description pane, as YACL. */
  private static final Identifier MENU_LIST_BACKGROUND =
      Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");

  /** Background and separator textures tile in squares of this size. */
  private static final int TEXTURE_SIZE = 32;

  private static final int TAB_HEADER_TEXTURE_SIZE = 16;

  private static final int SEPARATOR_THICKNESS = 2;
  private static final float VERTICAL_RADIANS = (float) Math.toRadians(90.0);

  /** The options list takes two of three columns; the right pane the third. */
  private static final int COLUMNS = 3;

  private static final int LIST_COLUMNS = 2;

  /** Pane padding is this fraction of the column width, as YACL. */
  private static final int PADDING_DIVISOR = 20;

  private static final int MAX_COLUMN_WIDTH = 400;
  private static final int BUTTON_HEIGHT = 20;
  private static final int BUTTON_ROW_GAP = 2;
  private static final int BUTTON_GAP = 4;

  private final @Nullable Screen parent;
  private final TextControl backendUrl;
  private final TickBoxControl connectionDiagnostics;

  /** Key rows in display order, each with the mapping it edits. */
  private final Map<KeyMapping, KeyBindControl> keyControls = new LinkedHashMap<>();

  /** Every control, for Save, Undo and Reset. */
  private final List<OptionControl<?>> controls = new ArrayList<>();

  private final SettingsList list;
  private final OptionDescriptionPanel description;
  private final Button cancelOrReset;
  private final Button undo;
  private final Button saveOrDone;
  private final TabManager tabManager =
      new TabManager(this::addRenderableWidget, this::removeWidget);
  private final GeneralTab generalTab = new GeneralTab();
  private @Nullable MenuTabBar tabBar;
  private @Nullable ScreenRectangle descriptionArea;
  private @Nullable ScreenRectangle rightPane;
  private @Nullable KeyBindControl capturingKey;

  public OpenDoorsSettingsScreen(@Nullable Screen parent, OpenDoorsKeyMappings keyMappings) {
    super(Component.translatable(KEY_PREFIX + "title"));
    this.parent = parent;
    backendUrl =
        register(
            new TextControl(
                font,
                Component.translatable(KEY_PREFIX + "backend_url"),
                Component.translatable(KEY_PREFIX + "backend_url" + DESCRIPTION_SUFFIX),
                Component.translatable(KEY_PREFIX + "backend_url.hint"),
                OpenDoorsClient.settings().backendUrl(),
                OpenDoorsSettings.DEFAULTS.backendUrl(),
                value -> updateButtons()));
    connectionDiagnostics =
        register(
            new TickBoxControl(
                font,
                Component.translatable(KEY_PREFIX + "connection_diagnostics"),
                Component.translatable(KEY_PREFIX + "connection_diagnostics" + DESCRIPTION_SUFFIX),
                OpenDoorsClient.settings().connectionDiagnostics(),
                OpenDoorsSettings.DEFAULTS.connectionDiagnostics(),
                value -> updateButtons()));
    Component keyDescription = Component.translatable(KEY_PREFIX + "key" + DESCRIPTION_SUFFIX);
    Component waiting = Component.translatable(KEY_PREFIX + "key.waiting");
    for (KeyMapping mapping : keyMappings.configurable()) {
      keyControls.put(
          mapping,
          register(
              new KeyBindControl(
                  font,
                  Component.translatable(mapping.getName()),
                  keyDescription,
                  waiting,
                  KeyMappingHelper.getBoundKeyOf(mapping),
                  mapping.getDefaultKey(),
                  this::startCapture,
                  key -> updateButtons())));
    }

    description = new OptionDescriptionPanel(font, backendUrl);
    list = new SettingsList(minecraft, description::show);
    list.addGroup(group("online"), groupDescription("online"));
    list.addOption(backendUrl);
    list.addOption(connectionDiagnostics);
    list.addGroup(group("keys"), groupDescription("keys"));
    keyControls.values().forEach(list::addOption);

    cancelOrReset = Button.builder(CommonComponents.GUI_CANCEL, button -> cancelOrReset()).build();
    undo = Button.builder(UNDO, button -> undoAll()).tooltip(UNDO_TOOLTIP).build();
    saveOrDone = Button.builder(CommonComponents.GUI_DONE, button -> saveOrDone()).build();
    updateButtons();
  }

  @Override
  protected void init() {
    MenuTabBar bar = MenuTabBar.builder(tabManager, width).addTabs(generalTab).build();
    tabBar = bar;
    addRenderableWidget(bar);
    bar.selectTab(0, false);
    repositionElements();
  }

  @Override
  protected void repositionElements() {
    if (tabBar == null) {
      return;
    }
    tabBar.arrangeElements(width);
    int top = tabBar.getRectangle().bottom();
    tabManager.setTabArea(new ScreenRectangle(0, top, width, height - top));
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (capturingKey == null) {
      return super.keyPressed(event);
    }
    assignCapturedKey(event.isEscape() ? InputConstants.UNKNOWN : InputConstants.getKey(event));
    return true;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (capturingKey == null) {
      return super.mouseClicked(event, doubleClick);
    }
    assignCapturedKey(InputConstants.Type.MOUSE.getOrCreate(event.button()));
    return true;
  }

  /** Tab header texture above, menu background below, as vanilla tabbed screens. */
  @Override
  protected void extractMenuBackground(GuiGraphicsExtractor graphics) {
    int headerHeight = tabBar == null ? 0 : tabBar.getRectangle().bottom();
    graphics.blit(
        RenderPipelines.GUI_TEXTURED,
        CreateWorldScreen.TAB_HEADER_BACKGROUND,
        0,
        0,
        0.0F,
        0.0F,
        width,
        headerHeight,
        TAB_HEADER_TEXTURE_SIZE,
        TAB_HEADER_TEXTURE_SIZE);
    extractMenuBackground(graphics, 0, headerHeight, width, height - headerHeight);
  }

  /** Darker description pane with a separator along its left edge, as YACL. */
  @Override
  public void extractBackground(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    super.extractBackground(graphics, mouseX, mouseY, partialTick);
    if (rightPane == null) {
      return;
    }
    graphics.blit(
        RenderPipelines.GUI_TEXTURED,
        minecraft.level == null
            ? MENU_LIST_BACKGROUND
            : AbstractSelectionList.INWORLD_MENU_LIST_BACKGROUND,
        rightPane.left(),
        rightPane.top(),
        rightPane.right(),
        rightPane.bottom(),
        rightPane.width(),
        rightPane.height(),
        TEXTURE_SIZE,
        TEXTURE_SIZE);
    graphics.pose().pushMatrix();
    graphics.pose().translate(rightPane.left(), rightPane.top());
    graphics.pose().rotate(VERTICAL_RADIANS);
    graphics.blit(
        RenderPipelines.GUI_TEXTURED,
        minecraft.level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR,
        0,
        0,
        0.0F,
        0.0F,
        rightPane.height(),
        SEPARATOR_THICKNESS,
        TEXTURE_SIZE,
        SEPARATOR_THICKNESS);
    graphics.pose().popMatrix();
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    if (descriptionArea != null) {
      description.extract(graphics, descriptionArea);
    }
  }

  private <C extends OptionControl<?>> C register(C control) {
    controls.add(control);
    return control;
  }

  private static Component group(String key) {
    return Component.translatable(KEY_PREFIX + "group." + key);
  }

  private static Component groupDescription(String key) {
    return Component.translatable(KEY_PREFIX + "group." + key + DESCRIPTION_SUFFIX);
  }

  private static Tooltip tooltip(String button) {
    return Tooltip.create(Component.translatable(KEY_PREFIX + button + TOOLTIP_SUFFIX));
  }

  private void startCapture(KeyBindControl control) {
    if (capturingKey != null) {
      capturingKey.setCapturing(false);
    }
    capturingKey = control;
    control.setCapturing(true);
  }

  private void assignCapturedKey(InputConstants.Key key) {
    KeyBindControl control = capturingKey;
    capturingKey = null;
    if (control != null) {
      control.assign(key);
    }
  }

  private boolean hasPendingChanges() {
    return controls.stream().anyMatch(OptionControl::isChanged);
  }

  /** Button labels follow YACL: Save Changes / Done, Cancel / Reset; Undo only with changes. */
  private void updateButtons() {
    boolean pending = hasPendingChanges();
    saveOrDone.setMessage(pending ? SAVE : CommonComponents.GUI_DONE);
    saveOrDone.setTooltip(pending ? SAVE_TOOLTIP : DONE_TOOLTIP);
    cancelOrReset.setMessage(pending ? CommonComponents.GUI_CANCEL : RESET_ALL);
    cancelOrReset.setTooltip(pending ? CANCEL_TOOLTIP : RESET_ALL_TOOLTIP);
    undo.active = pending;
    // The depth only matters while crafting from boxes is on.
  }

  private void saveOrDone() {
    if (!hasPendingChanges()) {
      onClose();
      return;
    }
    OpenDoorsClient.saveSettings(
        new OpenDoorsSettings(
            OpenDoorsSettings.CURRENT_SCHEMA_VERSION,
            backendUrl.get(),
            connectionDiagnostics.get()));
    applyPendingKeys();
    controls.forEach(OptionControl::markSaved);
    updateButtons();
  }

  private void cancelOrReset() {
    if (hasPendingChanges()) {
      onClose();
      return;
    }
    controls.forEach(OptionControl::resetQuietly);
    updateButtons();
  }

  private void undoAll() {
    controls.forEach(OptionControl::undo);
    updateButtons();
  }

  /** Same steps as vanilla {@code KeyBindsScreen}: set, rebuild the key lookup, save options. */
  private void applyPendingKeys() {
    boolean changed = false;
    for (Map.Entry<KeyMapping, KeyBindControl> entry : keyControls.entrySet()) {
      KeyMapping mapping = entry.getKey();
      InputConstants.Key key = entry.getValue().get();
      if (!key.equals(KeyMappingHelper.getBoundKeyOf(mapping))) {
        mapping.setKey(key);
        changed = true;
      }
    }
    if (changed) {
      KeyMapping.resetMapping();
      minecraft.options.save();
    }
  }

  /**
   * The single category. Lays out YACL's columns: list on the left two thirds; on the right, the
   * description above Cancel/Reset + Undo, with Save/Done along the bottom.
   */
  private final class GeneralTab implements Tab {
    private final FrameLayout bounds = new FrameLayout();

    @Override
    public Component getTabTitle() {
      return Component.translatable(KEY_PREFIX + "category.general");
    }

    @Override
    public Component getTabExtraNarration() {
      return Component.empty();
    }

    @Override
    public void visitChildren(Consumer<AbstractWidget> childrenConsumer) {
      childrenConsumer.accept(list);
      childrenConsumer.accept(cancelOrReset);
      childrenConsumer.accept(undo);
      childrenConsumer.accept(saveOrDone);
    }

    @Override
    public void doLayout(ScreenRectangle area) {
      bounds.setPosition(area.left(), area.top());
      bounds.setMinDimensions(area.width(), area.height());
      bounds.arrangeElements();
      int column = area.width() / COLUMNS;
      int padding = column / PADDING_DIVISOR;
      int paneWidth = Math.min(column, MAX_COLUMN_WIDTH) - padding * 2;
      int listWidth = column * LIST_COLUMNS;
      int paneCenter = listWidth + column / 2;
      int paneLeft = paneCenter - paneWidth / 2;

      list.updateSizeAndPosition(listWidth + 1, area.height(), area.left(), area.top());
      rightPane =
          new ScreenRectangle(
              area.left() + listWidth + 1, area.top(), area.width() - listWidth - 1, area.height());

      int saveY = area.bottom() - padding - BUTTON_HEIGHT;
      saveOrDone.setRectangle(paneWidth, BUTTON_HEIGHT, paneLeft, saveY);
      int halfWidth = paneWidth / 2 - BUTTON_GAP / 2;
      int topRowY = saveY - BUTTON_HEIGHT - BUTTON_ROW_GAP;
      cancelOrReset.setRectangle(halfWidth, BUTTON_HEIGHT, paneLeft, topRowY);
      undo.setRectangle(halfWidth, BUTTON_HEIGHT, paneLeft + halfWidth + BUTTON_GAP, topRowY);

      int descriptionTop = area.top() + padding;
      descriptionArea =
          new ScreenRectangle(
              paneLeft, descriptionTop, paneWidth, Math.max(0, topRowY - padding - descriptionTop));
    }

    @Override
    public Layout getLayout() {
      return bounds;
    }
  }
}
