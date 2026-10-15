package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.KeybindManager;
import com.legendaryweapons.managers.ParticleManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * A player's own settings (also /binds and /lwfx without arguments).
 *
 *   row 1   [Ability key]  . [Left] [Right] [Swap F] [Drop Q] [None]
 *   row 2   [Effects]      . [Full] [Reduced] [Minimal] [Off]
 */
public final class SettingsMenu extends Menu {
   private record Bind(String action, String label, Material icon, String how) {
   }

   private record Level(ParticleManager.Level level, Material icon, String what) {
   }

   private static final Bind[] BINDS = {
      new Bind("LEFT_CLICK", "Left Click", Material.IRON_SWORD, "Left-click while holding the weapon."),
      new Bind("RIGHT_CLICK", "Right Click", Material.GOLDEN_APPLE, "Right-click while holding the weapon."),
      new Bind("SWAP_HANDS", "Swap Hands (F)", Material.TOTEM_OF_UNDYING, "Press your swap-hands key while holding the weapon."),
      new Bind("DROP_ITEM", "Drop (Q)", Material.DROPPER, "Press drop while holding the weapon. It is not dropped."),
   };

   private static final Level[] LEVELS = {
      new Level(ParticleManager.Level.FULL, Material.BLAZE_POWDER, "Every particle and effect."),
      new Level(ParticleManager.Level.REDUCED, Material.GLOWSTONE_DUST, "Fewer particles. Recommended."),
      new Level(ParticleManager.Level.MINIMAL, Material.GUNPOWDER, "Only what you need to read a fight. No textured effects."),
      new Level(ParticleManager.Level.OFF, Material.GRAY_DYE, "No legendary particles or effects at all."),
   };

   public SettingsMenu(LegendaryWeapons plugin, Player viewer, Menu parent) {
      super(plugin, viewer, parent);
   }

   public static String bindLabel(String action) {
      for (Bind bind : BINDS) {
         if (bind.action().equalsIgnoreCase(action)) {
            return bind.label();
         }
      }

      return "None";
   }

   @Override
   protected String title() {
      return "Settings";
   }

   @Override
   protected int rows() {
      return 4;
   }

   @Override
   protected void draw() {
      KeybindManager keys = this.plugin.getKeybindManager();
      String current = keys.getKeybind(this.viewer);
      this.show(10, Icon.of(Material.NETHER_STAR).name("Ability key", ACCENT)
         .text("The key that fires the ability of the legendary in your hand.")
         .blank().stat("Now", bindLabel(current), GOLD)
         .blank().text("No key works for you? Bind /wpspark with a client keybind mod instead.", DIM).build());

      int slot = 12;
      for (Bind bind : BINDS) {
         boolean chosen = bind.action().equalsIgnoreCase(current);
         this.button(slot++, Icon.of(bind.icon()).name(bind.label(), chosen ? GOOD : net.kyori.adventure.text.format.NamedTextColor.WHITE)
            .text(bind.how()).blank()
            .line(chosen ? Icon.plain("✔ Selected", GOOD) : Icon.plain("▸ Click to use this key", DIM))
            .glow(chosen).build(), click -> {
            if (!chosen) {
               keys.setKeybind(click.player(), bind.action());
               this.toggleSound(click.player(), true);
               this.refresh();
            }
         });
      }

      boolean none = "NONE".equalsIgnoreCase(current);
      this.button(slot, Icon.of(Material.BARRIER).name("No key", none ? GOOD : BAD)
         .text("No key fires abilities. /wpspark still does.").blank()
         .line(none ? Icon.plain("✔ Selected", GOOD) : Icon.plain("▸ Click to clear your key", DIM))
         .glow(none).build(), click -> {
         if (!none) {
            keys.removeKeybind(click.player());
            this.toggleSound(click.player(), false);
            this.refresh();
         }
      });

      ParticleManager particles = this.plugin.getParticleManager();
      ParticleManager.Level level = particles.levelOf(this.viewer);
      this.show(19, Icon.of(Material.FIREWORK_STAR).name("Effects", ACCENT)
         .text("How many particles and ability effects you see. Only changes your own screen.")
         .blank().stat("Now", pretty(level), GOLD).build());

      slot = 21;
      for (Level option : LEVELS) {
         boolean chosen = option.level() == level;
         this.button(slot++, Icon.of(option.icon()).name(pretty(option.level()), chosen ? GOOD : net.kyori.adventure.text.format.NamedTextColor.WHITE)
            .text(option.what()).blank()
            .line(chosen ? Icon.plain("✔ Selected", GOOD) : Icon.plain("▸ Click to choose", DIM))
            .glow(chosen).build(), click -> {
            if (!chosen) {
               particles.setLevel(click.player(), option.level());
               this.toggleSound(click.player(), option.level() != ParticleManager.Level.OFF);
               this.refresh();
            }
         });
      }
   }

   private static String pretty(ParticleManager.Level level) {
      String name = level.name();
      return name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT);
   }
}
