package com.lumiweapons.guis;

import com.lumiweapons.LumiWeapons;
import java.util.function.Consumer;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The admin panel (/lw, /lumi admin). Everything here runs the same code as the matching command,
 * so the chat replies and checks are the ones admins already know.
 *
 *   row 1   [Server Settings] [Events] [Bosses] [Teams & Draft]
 *   row 3   [Give Items]  [Testing Tools]  [Reload Config]
 */
public final class AdminMenu extends Menu {
   public static final String PERMISSION = "lumiweapons.admin";

   public AdminMenu(LumiWeapons plugin, Player viewer, Menu parent) {
      super(plugin, viewer, parent);
   }

   @Override
   protected String title() {
      return "Admin Panel";
   }

   @Override
   protected int rows() {
      return 5;
   }

   /** Runs a command as the viewer, then redraws (a tick later, once its effects have landed). */
   static void run(Menu menu, Player player, String command) {
      player.performCommand(command);
      menu.plugin.getServer().getScheduler().runTask(menu.plugin, menu::refresh);
   }

   static Consumer<Menu.Click> opens(Menu menu, java.util.function.Function<Player, Menu> next) {
      return click -> {
         menu.clickSound(click.player());
         next.apply(click.player()).open();
      };
   }

   @Override
   protected void draw() {
      var bosses = this.plugin.getBossManager();
      this.show(4, Icon.of(Material.COMMAND_BLOCK).name("Admin Panel", BAD)
         .text("Only players with " + PERMISSION + " see this.", DIM)
         .blank().text(this.plugin.getTeamManager().teams().size() + " teams" + (this.plugin.getTeamManager().draft() != null ? ", a draft is running" : "") + ".", NamedTextColor.WHITE)
         .text(bosses == null ? "Bosses are off." : bosses.status(), NamedTextColor.WHITE).build());

      this.button(10, Icon.of(Material.REPEATER).name("Server Settings", ACCENT)
         .text("Turn features on and off and tune numbers without opening config.yml. Changes save and apply straight away.")
         .blank().click("open").build(), opens(this, p -> new ConfigMenu(this.plugin, p, this, null)));
      this.button(12, Icon.of(Material.BELL).name("Events", GOLD)
         .text("Start and stop events, and run the Event Director.")
         .blank().click("open").build(), opens(this, p -> new AdminEventsMenu(this.plugin, p, this)));
      this.button(14, Icon.of(Material.WITHER_SKELETON_SKULL).name("Bosses", GOLD)
         .text("Set arenas, summon a boss, or dismiss one.")
         .blank().click("open").build(), opens(this, p -> new AdminBossMenu(this.plugin, p, this)));
      var teams = this.plugin.getTeamManager();
      this.button(16, Icon.of(teams.draft() != null ? Material.LIME_BANNER : Material.WHITE_BANNER).name("Teams & Draft", GOLD)
         .text("Run a captain draft, or make and edit teams by hand.")
         .blank().stat("Teams", String.valueOf(teams.teams().size()), NamedTextColor.WHITE)
         .stat("Draft", teams.draft() != null ? "running" : "not running", teams.draft() != null ? GOOD : TEXT)
         .blank().click("open").glow(teams.draft() != null).build(), opens(this, p -> new AdminTeamsMenu(this.plugin, p, this)));

      this.button(29, Icon.of(Material.CHEST).name("Give Items", ACCENT)
         .text("Every legendary, fragment, event item and tool.")
         .blank().click("open").build(), opens(this, p -> new GiveMenu(this.plugin, p, this)));
      this.button(31, Icon.of(Material.CLOCK).name("Testing Tools", ACCENT)
         .text("Reset cooldowns, fill meters, finish rituals.")
         .blank().click("open").build(), opens(this, p -> new Tools(this.plugin, p, this)));
      this.button(33, Icon.of(Material.WRITABLE_BOOK).name("Reload Config", NamedTextColor.WHITE)
         .text("Re-reads config.yml from disk, for edits made outside the game.")
         .blank().click("reload").build(), click -> {
         this.toggleSound(click.player(), true);
         run(this, click.player(), "lw reload");
      });
   }

   /** Testing tools: the /resetcd, /charge, /lwtest and trader commands as buttons. */
   static final class Tools extends Menu {
      Tools(LumiWeapons plugin, Player viewer, Menu parent) {
         super(plugin, viewer, parent);
      }

      @Override
      protected String title() {
         return "Testing Tools";
      }

      @Override
      protected int rows() {
         return 5;
      }

      private void action(int slot, ItemStack icon, String command) {
         this.button(slot, icon, click -> {
            this.toggleSound(click.player(), true);
            run(this, click.player(), command);
         });
      }

      @Override
      protected void draw() {
         this.action(11, Icon.of(Material.CLOCK).name("Reset my cooldowns", NamedTextColor.WHITE)
            .text("Every cooldown, dash charge, dash lock and freeze immunity.").blank().click("reset").build(), "resetcd");
         this.action(12, Icon.of(Material.EXPERIENCE_BOTTLE).name("Fill my meter", NamedTextColor.WHITE)
            .text("The ultimate meter of the charged weapon in your hand (Luminosity, Titan's Axe).").blank().click("fill it").build(), "charge 100");
         this.action(13, Icon.of(Material.MILK_BUCKET).name("Undo every effect on me", NamedTextColor.WHITE)
            .text("Size, freeze, disguise, meters and cooldowns.").blank().click("reset").build(), "lwtest reset");
         this.action(14, Icon.of(Material.ENCHANTING_TABLE).name("Finish every ritual", NamedTextColor.WHITE)
            .text("Completes all running rituals now.").blank().click("finish them").build(), "lwtest finishrituals");
         this.action(15, Icon.of(Material.AMETHYST_SHARD).name("Fragments and event items", NamedTextColor.WHITE)
            .text("One stack of each.").blank().click("take").build(), "lwtest fragments");

         this.action(29, Icon.of(Material.VILLAGER_SPAWN_EGG).name("Place a Token Trader here", GOLD)
            .text("Stands where you are, facing you. Nobody can hurt it or build next to it.").blank().click("place").build(), "lwevent trader spawn");
         this.action(30, Icon.of(Material.LAVA_BUCKET).name("Remove nearby Token Trader", BAD)
            .text("Any trader within 4 blocks of you.").blank().click("remove").build(), "lwevent trader remove");
         this.action(31, Icon.of(Material.SPAWNER).name("Ritual Altar", ACCENT)
            .text("Gives you a Ritual Altar block.").blank().click("take").build(), "lw giveritualblock");
         var rituals = this.plugin.getRitualManager();
         this.button(32, Icon.of(Material.CRYING_OBSIDIAN).name("Ancient Altars", ACCENT)
            .stat("Mode", rituals.altarMode(), NamedTextColor.WHITE)
            .stat("Placed", rituals.ancientAltars().size() + "/" + rituals.ancientMax(), NamedTextColor.WHITE)
            .blank().hint("Left-click", "place one where you stand").hint("Right-click", "take the Ancient Altar item")
            .hint("Shift-click", "list them in chat").build(), click -> {
            this.toggleSound(click.player(), true);
            if (click.shift()) {
               run(this, click.player(), "lwevent altar list");
            } else if (click.right()) {
               click.player().getInventory().addItem(com.lumiweapons.rituals.RitualBlock.getAncientAltarItem());
            } else {
               run(this, click.player(), "lwevent altar place");
            }
         });
         this.action(33, Icon.of(Material.NETHERITE_SWORD).name("Set kills to 10", NamedTextColor.WHITE)
            .text("Sets the kill count on the kill-based weapon in your hand to 10.").blank().click("set").build(), "setkills 10");
      }
   }
}
