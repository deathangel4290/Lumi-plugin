package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.events.SeasonManager;
import com.legendaryweapons.events.TokenShop;
import com.legendaryweapons.items.CustomItems;
import com.legendaryweapons.rituals.RitualBlock;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * /lumi -- everything a player can do with Luminara, one click away.
 *
 *   row 1   [Recipes]   [Season Renown]   [Reward Chest]
 *   row 3   [Settings]  [Trusted Players] [World & Events]
 *   corner  [Admin Panel] (admins only)
 */
public final class LumiMenu extends Menu {
   public LumiMenu(LegendaryWeapons plugin, Player viewer) {
      super(plugin, viewer, null);
   }

   public static void open(LegendaryWeapons plugin, Player player) {
      new LumiMenu(plugin, player).open();
   }

   @Override
   protected String title() {
      return "Luminara";
   }

   @Override
   protected int rows() {
      return 5;
   }

   @Override
   protected void draw() {
      var events = this.plugin.getEventManager();
      SeasonManager season = events.season();
      double renown = season.get(this.viewer.getUniqueId(), SeasonManager.Stat.RENOWN);
      int rank = season.rank(this.viewer.getUniqueId(), SeasonManager.Stat.RENOWN);
      int waiting = events.vault().count(this.viewer.getUniqueId());

      this.show(4, Icon.head(this.viewer.getUniqueId()).name(this.viewer.getName(), GOLD)
         .stat("Season " + season.season() + " Renown", SeasonManager.format(renown) + (rank > 0 ? "  (#" + rank + ")" : ""), GOLD)
         .stat("Luminara Tokens", String.valueOf(TokenShop.count(this.viewer)), GOLD)
         .stat("Rewards waiting", String.valueOf(waiting), waiting > 0 ? GOOD : TEXT)
         .build());

      this.button(11, Icon.of(RitualBlock.getRitualBlockItem()).name("Legendary Recipes", ACCENT)
         .clearLore()
         .text("Every legendary, what its ritual needs, and whether it has been forged yet.")
         .blank().click("browse").build(), click -> {
         this.clickSound(click.player());
         new RecipesMenu(this.plugin, click.player(), this).open();
      });

      this.button(13, Icon.of(Material.GOLDEN_HELMET).name("Season Renown", GOLD)
         .text("Your stats this season and the leaderboards.")
         .blank().stat("Renown", SeasonManager.format(renown), GOLD).stat("Rank", rank > 0 ? "#" + rank : "unranked", NamedTextColor.WHITE)
         .blank().click("view").build(), click -> {
         this.clickSound(click.player());
         new RenownMenu(this.plugin, click.player(), this).open();
      });

      this.button(15, Icon.of(Material.CHEST).name("Reward Chest", GOLD).amount(Math.max(1, waiting))
         .text("Event prizes and outpost income wait here until you take them.")
         .blank().stat("Waiting", waiting + (waiting == 1 ? " item" : " items"), waiting > 0 ? GOOD : TEXT)
         .blank().click("open").build(), click -> {
         this.clickSound(click.player());
         events.vault().open(click.player());
      });

      String bind = this.plugin.getKeybindManager().getKeybind(this.viewer);
      this.button(29, Icon.of(Material.COMPARATOR).name("Settings", ACCENT)
         .text("Ability key and how many effects you see.")
         .blank().stat("Ability key", SettingsMenu.bindLabel(bind), NamedTextColor.WHITE)
         .stat("Effects", this.plugin.getParticleManager().levelOf(this.viewer).name().charAt(0) + this.plugin.getParticleManager().levelOf(this.viewer).name().substring(1).toLowerCase(Locale.ROOT), NamedTextColor.WHITE)
         .blank().click("change").build(), click -> {
         this.clickSound(click.player());
         new SettingsMenu(this.plugin, click.player(), this).open();
      });

      int trusted = this.plugin.getTrustManager().getTrustedPlayers(this.viewer).size();
      this.button(31, Icon.of(Material.SHIELD).name("Trusted Players", ACCENT)
         .text("Your abilities skip players you trust. Trust each other and you can't hurt each other at all.")
         .blank().stat("Trusted", String.valueOf(trusted), NamedTextColor.WHITE)
         .blank().click("manage").build(), click -> {
         this.clickSound(click.player());
         new TrustMenu(this.plugin, click.player(), this).open();
      });

      this.button(33, Icon.of(CustomItems.get().create(CustomItems.LUMINARA_TOKEN, 1)).name("World & Events", GOLD)
         .clearLore()
         .text("What's on now, the next event, outposts and where the Token Traders stand.")
         .blank().click("look").build(), click -> {
         this.clickSound(click.player());
         new WorldMenu(this.plugin, click.player(), this).open();
      });

      if (this.viewer.hasPermission("legendaryweapons.admin")) {
         this.button(44, Icon.of(Material.COMMAND_BLOCK).name("Admin Panel", BAD)
            .text("Server settings, events, bosses, items and testing tools.")
            .blank().click("open").build(), click -> {
            this.clickSound(click.player());
            new AdminMenu(this.plugin, click.player(), this).open();
         });
      }
   }
}
