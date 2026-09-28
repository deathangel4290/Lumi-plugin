package com.legendaryweapons.items;

import com.legendaryweapons.LegendaryWeapons;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Non-weapon plugin items. Everything is identified by a string id in the item's PDC, never by
 * name or material, so renaming one in an anvil or a vanilla item of the same material can
 * never stand in for it.
 *
 * Recipe and loot tables refer to them by token: FRAGMENT_T1..FRAGMENT_T4, LUMINOSITY_FRAGMENT,
 * HILL_MARKER, ALL_IN_ONE_POTION, AMETHYST_PICKAXE, AMETHYST_SHOVEL, AMETHYST_AXE.
 */
public class CustomItems implements Listener {
   public static final String LUMINOSITY_FRAGMENT = "luminosity-fragment";
   public static final String HILL_MARKER = "hill-marker";
   public static final String ALL_IN_ONE_POTION = "all-in-one-potion";
   public static final String AMETHYST_PICKAXE = "amethyst-pickaxe";
   public static final String AMETHYST_SHOVEL = "amethyst-shovel";
   public static final String AMETHYST_AXE = "amethyst-axe";
   private static final TextColor[] TIER_COLOURS = new TextColor[]{
      NamedTextColor.GRAY, NamedTextColor.GREEN, NamedTextColor.AQUA, TextColor.color(255, 190, 60)
   };
   private static final String[] NUMERALS = new String[]{"I", "II", "III", "IV"};
   private static CustomItems instance;
   private final LegendaryWeapons plugin;
   private final NamespacedKey idKey;

   public CustomItems(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.idKey = new NamespacedKey(plugin, "lw-item");
      instance = this;
   }

   public static CustomItems get() {
      return instance;
   }

   public static String fragmentId(int tier) {
      return "fragment-t" + Math.max(1, Math.min(4, tier));
   }

   /** FRAGMENT_T3 -> fragment-t3, LUMINOSITY_FRAGMENT -> luminosity-fragment; null for vanilla materials. */
   public static String tokenToId(String token) {
      String upper = token.trim().toUpperCase(Locale.ROOT);
      if (upper.matches("FRAGMENT_T[1-4]")) {
         return "fragment-t" + upper.charAt(upper.length() - 1);
      } else {
         return switch (upper) {
            case "LUMINOSITY_FRAGMENT" -> LUMINOSITY_FRAGMENT;
            case "HILL_MARKER" -> HILL_MARKER;
            case "ALL_IN_ONE_POTION" -> ALL_IN_ONE_POTION;
            case "AMETHYST_PICKAXE" -> AMETHYST_PICKAXE;
            case "AMETHYST_SHOVEL" -> AMETHYST_SHOVEL;
            case "AMETHYST_AXE" -> AMETHYST_AXE;
            default -> null;
         };
      }
   }

   public static List<String> allIds() {
      return List.of("fragment-t1", "fragment-t2", "fragment-t3", "fragment-t4", LUMINOSITY_FRAGMENT, HILL_MARKER, ALL_IN_ONE_POTION, AMETHYST_PICKAXE, AMETHYST_SHOVEL, AMETHYST_AXE);
   }

   public String idOf(ItemStack item) {
      return item != null && item.hasItemMeta() ? item.getItemMeta().getPersistentDataContainer().get(this.idKey, PersistentDataType.STRING) : null;
   }

   public boolean isCustom(ItemStack item) {
      return this.idOf(item) != null;
   }

   /** Tier of a fragment item, or 0 when it is not a tier fragment. */
   public int fragmentTier(ItemStack item) {
      String id = this.idOf(item);
      return id != null && id.startsWith("fragment-t") ? id.charAt(id.length() - 1) - '0' : 0;
   }

   public String displayName(String id) {
      if (id.startsWith("fragment-t")) {
         return "Tier " + NUMERALS[id.charAt(id.length() - 1) - '1'] + " Weapon Fragment";
      } else {
         return switch (id) {
            case LUMINOSITY_FRAGMENT -> "Luminosity Fragment";
            case HILL_MARKER -> "Hill Marker";
            case ALL_IN_ONE_POTION -> "All-in-One Potion";
            case AMETHYST_PICKAXE -> "Amethyst Pickaxe";
            case AMETHYST_SHOVEL -> "Amethyst Shovel";
            case AMETHYST_AXE -> "Amethyst Axe";
            default -> id;
         };
      }
   }

   public ItemStack create(String id, int amount) {
      if (id.startsWith("fragment-t")) {
         int tier = id.charAt(id.length() - 1) - '0';
         return this.build(Material.AMETHYST_SHARD, 200 + tier, id, amount, Component.text(this.displayName(id), TIER_COLOURS[tier - 1]), List.of(
            line("A shard of a broken legendary.", NamedTextColor.DARK_GRAY),
            Component.empty(),
            line("Used in rituals for tier " + NUMERALS[tier - 1] + " weapons.", NamedTextColor.GRAY),
            tier < 4 ? line("4 fuse into one Tier " + NUMERALS[tier] + " at the altar.", NamedTextColor.GRAY) : line("The highest tier.", NamedTextColor.GRAY)
         ));
      } else {
         return switch (id) {
            case LUMINOSITY_FRAGMENT -> this.build(Material.AMETHYST_SHARD, 205, id, amount, Component.text(this.displayName(id), NamedTextColor.YELLOW), List.of(
               line("It is warm, and it hums.", NamedTextColor.DARK_GRAY),
               Component.empty(),
               line("Only won from events.", NamedTextColor.GRAY),
               line("Needed to forge Luminosity.", NamedTextColor.GOLD)
            ));
            case HILL_MARKER -> this.build(Material.LODESTONE, 206, id, amount, Component.text(this.displayName(id), NamedTextColor.GOLD), List.of(
               line("Admin tool.", NamedTextColor.DARK_GRAY),
               line("Place it to set the King of the Hill centre.", NamedTextColor.GRAY),
               line("Break it to clear the hill.", NamedTextColor.GRAY)
            ));
            case ALL_IN_ONE_POTION -> this.allInOnePotion(amount);
            case AMETHYST_PICKAXE -> this.tool(Material.NETHERITE_PICKAXE, 301, id, false);
            case AMETHYST_SHOVEL -> this.tool(Material.NETHERITE_SHOVEL, 302, id, false);
            case AMETHYST_AXE -> this.tool(Material.NETHERITE_AXE, 303, id, true);
            default -> null;
         };
      }
   }

   /** Resolve a recipe/loot token; null when it is not one of ours. */
   public ItemStack fromToken(String token, int amount) {
      String id = tokenToId(token);
      return id == null ? null : this.create(id, amount);
   }

   private ItemStack build(Material material, int modelData, String id, int amount, Component name, List<Component> lore) {
      ItemStack item = new ItemStack(material, Math.max(1, amount));
      ItemMeta meta = item.getItemMeta();
      meta.displayName(name.decoration(TextDecoration.ITALIC, false));
      meta.lore(lore);
      meta.setEnchantmentGlintOverride(true);
      CustomModelDataComponent component = meta.getCustomModelDataComponent();
      component.setFloats(List.of((float)modelData));
      meta.setCustomModelDataComponent(component);
      meta.getPersistentDataContainer().set(this.idKey, PersistentDataType.STRING, id);
      item.setItemMeta(meta);
      return item;
   }

   private ItemStack tool(Material material, int modelData, String id, boolean silkTouch) {
      ItemStack item = this.build(material, modelData, id, 1, Component.text(this.displayName(id), TextColor.color(200, 150, 255)), List.of(
         line(com.legendaryweapons.listeners.AmethystToolsListener.describe(id), NamedTextColor.GRAY),
         Component.empty(),
         line("Amethyst Tool", NamedTextColor.LIGHT_PURPLE)
      ));
      ItemMeta meta = item.getItemMeta();
      meta.setEnchantmentGlintOverride(false);              // let the amethyst texture show
      meta.addEnchant(org.bukkit.enchantments.Enchantment.EFFICIENCY, 5, true);
      meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true);
      if (silkTouch) {
         meta.addEnchant(org.bukkit.enchantments.Enchantment.SILK_TOUCH, 1, true);
      }

      item.setItemMeta(meta);
      return item;
   }

   private ItemStack allInOnePotion(int amount) {
      ItemStack potion = new ItemStack(Material.SPLASH_POTION, Math.max(1, amount));   // thrown, like every loot potion
      PotionMeta meta = (PotionMeta)potion.getItemMeta();
      meta.displayName(Component.text("All-in-One Potion", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
      // Particles off: five effects' worth of swirls (Resistance is purple) float in front of the drinker's camera.
      meta.setColor(Color.fromRGB(200, 60, 255));
      meta.addCustomEffect(new PotionEffect(PotionEffectType.STRENGTH, 3600, 1, false, false, true), true);
      meta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 3600, 1, false, false, true), true);
      meta.addCustomEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1, false, false, true), true);
      meta.addCustomEffect(new PotionEffect(PotionEffectType.RESISTANCE, 3600, 0, false, false, true), true);
      meta.addCustomEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 9600, 0, false, false, true), true);
      meta.getPersistentDataContainer().set(this.idKey, PersistentDataType.STRING, ALL_IN_ONE_POTION);
      potion.setItemMeta(meta);
      return potion;
   }

   private static Component line(String text, TextColor colour) {
      return Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
   }

   /** Fragments are amethyst shards underneath; stop them being spent in vanilla recipes. */
   @EventHandler
   public void onPrepareCraft(PrepareItemCraftEvent event) {
      for (ItemStack item : event.getInventory().getMatrix()) {
         if (this.isCustom(item) || this.plugin.getWeaponManager().isLegendaryWeapon(item)) {
            event.getInventory().setResult(null);
            return;
         }
      }
   }

   /**
    * fragments.mob-drops maps an entity type to "tier:chance", e.g. WARDEN: "3:0.5". Only kills by a
    * player count, so farms and mob grinders do not mint fragments.
    */
   @EventHandler
   public void onEntityDeath(EntityDeathEvent event) {
      if (!(event.getEntity() instanceof Player) && event.getEntity().getKiller() != null) {
         ConfigurationSection drops = this.plugin.getConfig().getConfigurationSection("fragments.mob-drops");
         if (drops != null) {
            String spec = drops.getString(event.getEntity().getType().name());
            if (spec != null) {
               String[] parts = spec.split(":");

               try {
                  int tier = Integer.parseInt(parts[0].trim());
                  double chance = parts.length > 1 ? Double.parseDouble(parts[1].trim()) : 1.0;
                  if (ThreadLocalRandom.current().nextDouble() < chance) {
                     event.getDrops().add(this.create(fragmentId(tier), 1));
                  }
               } catch (NumberFormatException var7) {
                  this.plugin.getLogger().warning("Bad fragments.mob-drops entry for " + event.getEntity().getType() + ": " + spec);
               }
            }
         }
      }
   }

   public List<ItemStack> sampleSet() {
      List<ItemStack> out = new ArrayList<>();

      for (String id : allIds()) {
         out.add(this.create(id, id.startsWith("fragment") ? 8 : 1));
      }

      return out;
   }
}
