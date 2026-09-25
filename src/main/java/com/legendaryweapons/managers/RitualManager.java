package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.rituals.Ritual;
import com.legendaryweapons.rituals.RitualBlock;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

public class RitualManager {
   private final LegendaryWeapons plugin;
   private final ConfigManager configManager;
   private final Set<Location> ritualBlockLocations = new HashSet<>();
   private final List<Ritual> activeRituals = new ArrayList<>();
   private final List<String> craftedWeapons = new ArrayList<>();
   private final File dataFile;
   private final FileConfiguration dataConfig;

   public RitualManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.configManager = plugin.getConfigManager();
      this.dataFile = new File(plugin.getDataFolder(), "rituals.yml");
      if (!this.dataFile.exists()) {
         try {
            this.dataFile.getParentFile().mkdirs();
            this.dataFile.createNewFile();
         } catch (IOException var3) {
            plugin.getLogger().severe("Could not create rituals.yml!");
            var3.printStackTrace();
         }
      }

      this.dataConfig = YamlConfiguration.loadConfiguration(this.dataFile);
      this.loadLocations();
      this.loadCraftedWeapons();
      this.loadActiveRituals();
      this.registerRitualBlockRecipe();
   }

   private void registerRitualBlockRecipe() {
      if (!this.configManager.isRitualBlockRecipeEnabled()) {
         this.plugin.getLogger().info("Ritual Block recipe is disabled in the config.");
      } else {
         ItemStack ritualBlockItem = RitualBlock.getRitualBlockItem();
         NamespacedKey recipeKey = new NamespacedKey(this.plugin, "ritual_block_recipe");
         ShapedRecipe recipe = new ShapedRecipe(recipeKey, ritualBlockItem);
         List<String> shape = this.configManager.getRitualBlockRecipeShape();
         if (shape.size() != 3) {
            this.plugin.getLogger().severe("Ritual Block recipe 'shape' in config.yml must have exactly 3 rows!");
         } else {
            recipe.shape(new String[]{shape.get(0), shape.get(1), shape.get(2)});
            Map<Character, Material> ingredients = this.configManager.getRitualBlockRecipeIngredients();
            if (ingredients.isEmpty()) {
               this.plugin.getLogger().severe("No valid 'ingredients' found for Ritual Block recipe in config.yml!");
            } else {
               for (Entry<Character, Material> entry : ingredients.entrySet()) {
                  recipe.setIngredient(entry.getKey(), entry.getValue());
               }

               try {
                  if (Bukkit.addRecipe(recipe)) {
                     this.plugin.getLogger().info("Successfully registered custom Ritual Block recipe.");
                  } else {
                     this.plugin.getLogger().warning("Could not register Ritual Block recipe (a recipe with this ID may already exist).");
                  }
               } catch (Exception var8) {
                  this.plugin.getLogger().severe("An error occurred while registering the Ritual Block recipe:");
                  var8.printStackTrace();
               }
            }
         }
      }
   }

   public void saveLocations() {
      List<Map<String, Object>> locMaps = this.activeRituals.stream().map(ritual -> ritual.getLocation().serialize()).collect(Collectors.toList());
      this.dataConfig.set("ritual-locations", locMaps);

      try {
         this.dataConfig.save(this.dataFile);
      } catch (IOException var3) {
         this.plugin.getLogger().severe("Could not save rituals.yml!");
         var3.printStackTrace();
      }
   }

   private void loadLocations() {
      for (Map<?, ?> map : this.dataConfig.getMapList("ritual-locations")) {
         @SuppressWarnings("unchecked")
         Map<String, Object> typed = (Map<String, Object>)map;
         this.ritualBlockLocations.add(Location.deserialize(typed));
      }
   }

   public void saveCraftedWeapons() {
      this.dataConfig.set("crafted-weapons", this.craftedWeapons);

      try {
         this.dataConfig.save(this.dataFile);
      } catch (IOException var2) {
         this.plugin.getLogger().severe("Could not save rituals.yml!");
         var2.printStackTrace();
      }
   }

   private void loadCraftedWeapons() {
      this.craftedWeapons.addAll(this.dataConfig.getStringList("crafted-weapons"));
   }

   public void saveActiveRituals() {
      List<Map<String, Object>> ritualMaps = new ArrayList<>();

      for (Ritual ritual : this.activeRituals) {
         Map<String, Object> ritualMap = new HashMap<>();
         ritualMap.put("player", ritual.getPlayerUUID().toString());
         ritualMap.put("location", ritual.getLocation().serialize());
         ritualMap.put("weaponKey", ritual.getWeaponKey());
         ritualMap.put("startTime", ritual.getStartTime());
         ritualMaps.add(ritualMap);
      }

      this.dataConfig.set("active-rituals", ritualMaps);

      try {
         this.dataConfig.save(this.dataFile);
      } catch (IOException var5) {
         this.plugin.getLogger().severe("Could not save rituals.yml!");
         var5.printStackTrace();
      }
   }

   private void loadActiveRituals() {
      for (Map<?, ?> map : this.dataConfig.getMapList("active-rituals")) {
         UUID playerUUID = UUID.fromString((String)map.get("player"));
         Location location = Location.deserialize((Map)map.get("location"));
         String weaponKey = (String)map.get("weaponKey");
         long startTime = (Long)map.get("startTime");
         this.activeRituals.add(new Ritual(this.plugin, playerUUID, location, weaponKey, startTime));
      }
   }

   public void addRitualBlock(Location location) {
      this.ritualBlockLocations.add(location);
   }

   public void removeRitualBlock(Location location) {
      this.ritualBlockLocations.remove(location);
      this.activeRituals.stream().filter(ritual -> ritual.getLocation().equals(location)).findFirst().ifPresent(Ritual::cancel);
   }

   public boolean isRitualBlock(Location location) {
      return this.ritualBlockLocations.contains(location);
   }

   public boolean startRitual(Player player, Location location, List<ItemStack> items) {
      String weaponKey = this.getWeaponKeyFromRecipe(items);
      if (weaponKey == null) {
         player.sendMessage("§cInvalid recipe.");
         return false;
      } else if (this.getRitualAtLocation(location) != null) {
         player.sendMessage("§cThis ritual block is already busy.");
         return false;
      } else if (this.plugin.getConfigManager().isSingleCraft() && this.craftedWeapons.contains(weaponKey)) {
         player.sendMessage("§cThis legendary weapon has already been crafted.");
         return false;
      } else {
         this.activeRituals.add(new Ritual(this.plugin, player.getUniqueId(), location, weaponKey, System.currentTimeMillis()));
         this.craftedWeapons.add(weaponKey);
         this.saveCraftedWeapons();
         this.saveActiveRituals();
         return true;
      }
   }

   public void removeRitual(Ritual ritual) {
      this.activeRituals.remove(ritual);
      this.saveActiveRituals();
   }

   public String getWeaponKeyFromRecipe(List<ItemStack> items) {
      List<ItemStack> currentItems = items.stream()
         .filter(item -> item != null && !item.getType().isAir())
         .<ItemStack>map(ItemStack::clone)
         .collect(Collectors.toList());
      if (currentItems.isEmpty()) {
         return null;
      } else {
         for (String weaponKey : this.plugin.getWeaponManager().getWeaponKeys()) {
            List<ItemStack> recipe = this.plugin.getConfigManager().getWeaponRecipe(weaponKey);
            if (recipe != null && !recipe.isEmpty()) {
               List<ItemStack> recipeItems = recipe.stream().filter(item -> item != null && !item.getType().isAir()).collect(Collectors.toList());
               if (currentItems.size() == recipeItems.size()) {
                  List<ItemStack> itemsToCheck = new ArrayList<>(currentItems);
                  boolean recipeMatch = true;

                  for (ItemStack recipeItem : recipeItems) {
                     boolean itemFound = false;

                     for (ItemStack currentItem : itemsToCheck) {
                        if (recipeItem.isSimilar(currentItem) && recipeItem.getAmount() == currentItem.getAmount()) {
                           itemsToCheck.remove(currentItem);
                           itemFound = true;
                           break;
                        }
                     }

                     if (!itemFound) {
                        recipeMatch = false;
                        break;
                     }
                  }

                  if (recipeMatch) {
                     return weaponKey;
                  }
               }
            }
         }

         return null;
      }
   }

   public boolean isRitualLocation(Location location) {
      return this.activeRituals.stream().anyMatch(ritual -> ritual.isRitualLocation(location));
   }

   public Ritual getRitualAtLocation(Location location) {
      return this.activeRituals.stream().filter(ritual -> ritual.getLocation().equals(location)).findFirst().orElse(null);
   }

   public void cancelAllRituals() {
      new ArrayList<>(this.activeRituals).forEach(Ritual::cancel);
      this.activeRituals.clear();
   }

   public List<Ritual> getActiveRituals() {
      return new ArrayList<>(this.activeRituals);
   }
}
