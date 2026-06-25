package xyz.jpenilla.tabtps.neoforge;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.permission.PermissionChecker;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.Translator;
import net.kyori.adventure.util.TriState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import org.checkerframework.checker.nullness.qual.NonNull;

public abstract class NeoForgeAudience implements Audience {
  static Component asAdventure(final net.minecraft.network.chat.Component component, final HolderLookup.Provider registries) {
    return GsonComponentSerializer.gson().deserialize(net.minecraft.network.chat.Component.Serializer.toJson(component, registries));
  }

  static net.minecraft.network.chat.Component asNative(final Component component, final HolderLookup.Provider registries) {
    return asNative(component, Locale.getDefault(), registries);
  }

  static net.minecraft.network.chat.Component asNative(final Component component, final Locale locale, final HolderLookup.Provider registries) {
    if (component == Component.empty()) {
      return net.minecraft.network.chat.Component.empty();
    }
    return net.minecraft.network.chat.Component.Serializer.fromJson(
      GsonComponentSerializer.gson().serializeToTree(GlobalTranslator.render(component, locale)),
      registries
    );
  }

  @Override
  public void sendMessage(final @NonNull Identity source, final @NonNull Component message, final @NonNull MessageType type) {
    this.sendMessage(message);
  }

  static final class Player extends NeoForgeAudience implements BossBar.Listener {
    private final ServerPlayer player;
    private final Predicate<String> permissionChecker;
    private final Map<BossBar, ServerBossEvent> bossBars = new IdentityHashMap<>();

    Player(final ServerPlayer player, final Predicate<String> permissionChecker) {
      this.player = player;
      this.permissionChecker = permissionChecker;
    }

    @Override
    public @NonNull Pointers pointers() {
      return Pointers.builder()
        .withStatic(Identity.UUID, this.player.getUUID())
        .withStatic(Identity.NAME, this.player.getScoreboardName())
        .withDynamic(Identity.DISPLAY_NAME, () -> asAdventure(this.player.getDisplayName(), this.registries()))
        .withStatic(PermissionChecker.POINTER, (PermissionChecker) permission -> TriState.byBoolean(this.permissionChecker.test(permission)))
        .build();
    }

    @Override
    public void sendMessage(final @NonNull Component message) {
      this.player.sendSystemMessage(asNative(message, this.locale(), this.registries()));
    }

    @Override
    public void sendActionBar(final @NonNull Component message) {
      this.player.sendSystemMessage(asNative(message, this.locale(), this.registries()), true);
    }

    @Override
    public void sendPlayerListHeader(final @NonNull Component header) {
      this.player.setTabListHeaderFooter(asNative(header, this.locale(), this.registries()), this.player.getTabListFooter());
    }

    @Override
    public void sendPlayerListFooter(final @NonNull Component footer) {
      this.player.setTabListHeaderFooter(this.player.getTabListHeader(), asNative(footer, this.locale(), this.registries()));
    }

    @Override
    public void sendPlayerListHeaderAndFooter(final @NonNull Component header, final @NonNull Component footer) {
      this.player.setTabListHeaderFooter(asNative(header, this.locale(), this.registries()), asNative(footer, this.locale(), this.registries()));
    }

    @Override
    public void showBossBar(final @NonNull BossBar bar) {
      this.bossBars.computeIfAbsent(bar, key -> {
        final ServerBossEvent event = new ServerBossEvent(asNative(key.name(), this.locale(), this.registries()), color(key.color()), overlay(key.overlay()));
        event.setProgress(key.progress());
        updateFlags(event, key);
        key.addListener(this);
        return event;
      }).addPlayer(this.player);
    }

    @Override
    public void hideBossBar(final @NonNull BossBar bar) {
      final ServerBossEvent event = this.bossBars.remove(bar);
      if (event != null) {
        event.removePlayer(this.player);
        bar.removeListener(this);
      }
    }

    @Override
    public void bossBarNameChanged(final @NonNull BossBar bar, final @NonNull Component oldName, final @NonNull Component newName) {
      final ServerBossEvent event = this.bossBars.get(bar);
      if (event != null) {
        event.setName(asNative(newName, this.locale(), this.registries()));
      }
    }

    @Override
    public void bossBarProgressChanged(final @NonNull BossBar bar, final float oldProgress, final float newProgress) {
      final ServerBossEvent event = this.bossBars.get(bar);
      if (event != null) {
        event.setProgress(newProgress);
      }
    }

    @Override
    public void bossBarPercentChanged(final @NonNull BossBar bar, final float oldPercent, final float newPercent) {
      this.bossBarProgressChanged(bar, oldPercent, newPercent);
    }

    @Override
    public void bossBarColorChanged(final @NonNull BossBar bar, final BossBar.@NonNull Color oldColor, final BossBar.@NonNull Color newColor) {
      final ServerBossEvent event = this.bossBars.get(bar);
      if (event != null) {
        event.setColor(color(newColor));
      }
    }

    @Override
    public void bossBarOverlayChanged(final @NonNull BossBar bar, final BossBar.@NonNull Overlay oldOverlay, final BossBar.@NonNull Overlay newOverlay) {
      final ServerBossEvent event = this.bossBars.get(bar);
      if (event != null) {
        event.setOverlay(overlay(newOverlay));
      }
    }

    @Override
    public void bossBarFlagsChanged(final @NonNull BossBar bar, final @NonNull Set<BossBar.Flag> flagsAdded, final @NonNull Set<BossBar.Flag> flagsRemoved) {
      final ServerBossEvent event = this.bossBars.get(bar);
      if (event != null) {
        updateFlags(event, bar);
      }
    }

    private HolderLookup.Provider registries() {
      return this.player.server.registryAccess();
    }

    private Locale locale() {
      final Locale locale = Translator.parseLocale(this.player.getLanguage());
      return locale == null ? Locale.ENGLISH : locale;
    }
  }

  public static final class CommandSource extends NeoForgeAudience {
    private final CommandSourceStack source;

    public CommandSource(final CommandSourceStack source) {
      this.source = source;
    }

    @Override
    public @NonNull Pointers pointers() {
      return Pointers.builder()
        .withStatic(Identity.NAME, this.source.getTextName())
        .withDynamic(Identity.DISPLAY_NAME, () -> asAdventure(this.source.getDisplayName(), this.source.registryAccess()))
        .build();
    }

    @Override
    public void sendMessage(final @NonNull Component message) {
      this.source.sendSystemMessage(asNative(message, this.source.registryAccess()));
    }

    @Override
    public void sendActionBar(final @NonNull Component message) {
      this.sendMessage(message);
    }
  }

  private static net.minecraft.world.BossEvent.BossBarColor color(final BossBar.Color color) {
    return switch (color) {
      case PINK -> net.minecraft.world.BossEvent.BossBarColor.PINK;
      case BLUE -> net.minecraft.world.BossEvent.BossBarColor.BLUE;
      case RED -> net.minecraft.world.BossEvent.BossBarColor.RED;
      case GREEN -> net.minecraft.world.BossEvent.BossBarColor.GREEN;
      case YELLOW -> net.minecraft.world.BossEvent.BossBarColor.YELLOW;
      case PURPLE -> net.minecraft.world.BossEvent.BossBarColor.PURPLE;
      case WHITE -> net.minecraft.world.BossEvent.BossBarColor.WHITE;
    };
  }

  private static net.minecraft.world.BossEvent.BossBarOverlay overlay(final BossBar.Overlay overlay) {
    return switch (overlay) {
      case PROGRESS -> net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS;
      case NOTCHED_6 -> net.minecraft.world.BossEvent.BossBarOverlay.NOTCHED_6;
      case NOTCHED_10 -> net.minecraft.world.BossEvent.BossBarOverlay.NOTCHED_10;
      case NOTCHED_12 -> net.minecraft.world.BossEvent.BossBarOverlay.NOTCHED_12;
      case NOTCHED_20 -> net.minecraft.world.BossEvent.BossBarOverlay.NOTCHED_20;
    };
  }

  private static void updateFlags(final ServerBossEvent event, final BossBar bar) {
    event.setDarkenScreen(bar.hasFlag(BossBar.Flag.DARKEN_SCREEN));
    event.setPlayBossMusic(bar.hasFlag(BossBar.Flag.PLAY_BOSS_MUSIC));
    event.setCreateWorldFog(bar.hasFlag(BossBar.Flag.CREATE_WORLD_FOG));
  }
}
