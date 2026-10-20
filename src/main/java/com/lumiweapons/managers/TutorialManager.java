package com.lumiweapons.managers;

import com.lumiweapons.LumiWeapons;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

public class TutorialManager {
   private final LumiWeapons plugin;
   private final File dataFile;
   private final FileConfiguration dataConfig;
   private final List<String> receivedBookPlayers = new ArrayList<>();

   public TutorialManager(LumiWeapons plugin) {
      this.plugin = plugin;
      this.dataFile = new File(plugin.getDataFolder(), "tutorial.yml");
      if (!this.dataFile.exists()) {
         try {
            this.dataFile.getParentFile().mkdirs();
            this.dataFile.createNewFile();
         } catch (IOException var3) {
            plugin.getLogger().severe("Could not create tutorial.yml!");
            var3.printStackTrace();
         }
      }

      this.dataConfig = YamlConfiguration.loadConfiguration(this.dataFile);
      this.loadReceivedBookPlayers();
   }

   private void loadReceivedBookPlayers() {
      this.receivedBookPlayers.addAll(this.dataConfig.getStringList("received-book"));
   }

   private void saveReceivedBookPlayers() {
      this.dataConfig.set("received-book", this.receivedBookPlayers);

      try {
         this.dataConfig.save(this.dataFile);
      } catch (IOException var2) {
         this.plugin.getLogger().severe("Could not save tutorial.yml!");
         var2.printStackTrace();
      }
   }

   public boolean hasReceivedBook(Player player) {
      return this.receivedBookPlayers.contains(player.getUniqueId().toString());
   }

   public void giveTutorialBook(Player player) {
      if (!this.hasReceivedBook(player)) {
         ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
         BookMeta meta = (BookMeta)book.getItemMeta();
         meta.setTitle("§5Ritual Crafting Guide");
         meta.setAuthor("The Ancients");
         List<String> pages = new ArrayList<>();
         pages.add(
            "§5§lRitual Crafting\n\n§0Welcome, craftsman, to the art of forging Legendary Weapons.\n\nThis guide will walk you through the process of using a Ritual Altar."
         );
         pages.add(
            "§5§lThe Altar\n\n§0The Ritual Altar is the block you just placed. Right-clicking it opens the crafting interface.\n\nTo begin, you will need to find a recipe for a Legendary Weapon. You can view all available recipes with the §5/recipes §0command."
         );
         pages.add(
            "§5§lThe Ritual\n\n§0Once you have a recipe, place the ingredients into the five slots of the altar. The top slot previews what they will forge.\n\nPress Begin Ritual. Everyone on the server sees where the ritual is, so guard the altar."
         );
         pages.add(
            "§5§lCompletion\n\n§0When the timer ends the weapon drops at the altar. If the altar is broken first, the ingredients spill out.\n\n§0Weapon Fragments come from bosses, supply drops and events. Four of a tier fuse into the next."
         );
         meta.setPages(pages);
         book.setItemMeta(meta);
         player.getInventory().addItem(new ItemStack[]{book});
         player.sendMessage("§5You have received the Ritual Crafting Guide!");
         this.receivedBookPlayers.add(player.getUniqueId().toString());
         this.saveReceivedBookPlayers();
      }
   }
}
