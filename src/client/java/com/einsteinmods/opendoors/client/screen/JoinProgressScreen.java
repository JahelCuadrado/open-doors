package com.einsteinmods.opendoors.client.screen;

import com.einsteinmods.opendoors.client.OpenDoorsClient;
import com.einsteinmods.opendoors.core.online.JoinAttempt;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Shown while joining a friend's world: waiting for the host, then "Connecting directly…" or "Using
 * relay…". Once the path is up, the vanilla {@link ConnectScreen} connects to the local loopback
 * port, so login, encryption and the loading screens are the vanilla ones.
 */
public final class JoinProgressScreen extends net.minecraft.client.gui.screens.Screen
    implements JoinAttempt.Listener {
  private static final String LOOPBACK = "127.0.0.1";
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int BUTTON_WIDTH = 200;
  private static final int BUTTON_HEIGHT = 20;
  private static final int BUTTON_MARGIN = 40;
  private static final int LINE_GAP = 12;

  private final UUID host;
  private final String hostName;
  private Component status;
  private List<FormattedCharSequence> statusLines = List.of();
  private int wrappedWidth;
  private Component wrappedStatus;
  private boolean started;
  private boolean finished;
  private JoinAttempt.Failure failure;
  private Button cancel;

  public JoinProgressScreen(UUID host, String hostName) {
    super(Component.translatable("opendoors.join.title", hostName));
    this.host = host;
    this.hostName = hostName;
    this.status = Component.translatable("opendoors.join.waiting", hostName);
  }

  @Override
  protected void init() {
    cancel =
        addRenderableWidget(
            Button.builder(
                    finished ? CommonComponents.GUI_BACK : CommonComponents.GUI_CANCEL,
                    b -> onClose())
                .bounds(
                    width / 2 - BUTTON_WIDTH / 2,
                    height - BUTTON_MARGIN,
                    BUTTON_WIDTH,
                    BUTTON_HEIGHT)
                .build());
    if (!started) {
      started = true;
      OpenDoorsClient.online().service().join(host.toString(), this);
    }
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    int middle = height / 2;
    graphics.centeredText(font, title, width / 2, middle - LINE_GAP * 2, TEXT_COLOR);
    if (status != wrappedStatus || width != wrappedWidth) {
      wrappedStatus = status;
      wrappedWidth = width;
      statusLines = font.split(status, width - BUTTON_MARGIN * 2);
    }
    int y = middle;
    for (FormattedCharSequence line : statusLines) {
      graphics.centeredText(font, line, width / 2, y, TEXT_COLOR);
      y += LINE_GAP;
    }
  }

  /** Why the join failed, or null while it runs or after it succeeded. */
  public JoinAttempt.Failure failure() {
    return failure;
  }

  @Override
  public void onClose() {
    if (!finished) {
      OpenDoorsClient.online().service().cancelJoin();
    }
    minecraft.gui.setScreen(new TitleScreen());
  }

  @Override
  public void onStage(JoinAttempt.Stage stage) {
    Component text =
        switch (stage) {
          case WAITING_FOR_HOST -> Component.translatable("opendoors.join.waiting", hostName);
          case CONNECTING_DIRECTLY -> Component.translatable("opendoors.join.direct");
          case USING_RELAY -> Component.translatable("opendoors.join.relay");
        };
    minecraft.execute(() -> status = text);
  }

  @Override
  public void onReady(int port, boolean relayed) {
    minecraft.execute(
        () -> {
          finished = true;
          // Dev identities cannot pass Mojang's session check; a LAN entry lets the vanilla
          // client continue (the host still matches the backend-verified identity).
          ServerData data =
              new ServerData(
                  hostName,
                  LOOPBACK + ":" + port,
                  OpenDoorsClient.devAuth() ? ServerData.Type.LAN : ServerData.Type.OTHER);
          ConnectScreen.startConnecting(
              new TitleScreen(), minecraft, new ServerAddress(LOOPBACK, port), data, false, null);
        });
  }

  @Override
  public void onFailed(JoinAttempt.Failure failure) {
    Component text =
        switch (failure) {
          case CLOSED -> Component.translatable("opendoors.join.failed.closed", hostName);
          case DENIED -> Component.translatable("opendoors.join.failed.denied", hostName);
          case BLOCKED -> Component.translatable("opendoors.join.failed.blocked");
          case FULL -> Component.translatable("opendoors.join.failed.full", hostName);
          case BUSY -> Component.translatable("opendoors.join.failed.busy", hostName);
          case UNREACHABLE -> Component.translatable("opendoors.join.failed.unreachable", hostName);
          case MONTHLY_LIMIT -> Component.translatable("opendoors.join.failed.monthly_limit");
          case TIMED_OUT -> Component.translatable("opendoors.join.failed.timed_out", hostName);
          case OFFLINE -> Component.translatable("opendoors.join.failed.offline");
        };
    minecraft.execute(
        () -> {
          finished = true;
          this.failure = failure;
          status = text;
          if (cancel != null) {
            cancel.setMessage(CommonComponents.GUI_BACK);
          }
        });
  }
}
