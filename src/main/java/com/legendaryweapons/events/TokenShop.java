package com.legendaryweapons.events;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.items.CustomItems;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Token Traders: villagers an admin places (/lwevent trader spawn) that trade Luminara Tokens for
 * the offers in token-shop.offers ("ITEM [amount] @ cost", the loot format), through the normal
 * villager trading screen. Offers are kept to consumables, materials and fragments on purpose:
 * events should not mint power faster than the rituals do.
 *
 * A trader is part of the scenery. It never moves (no AI; anything that shoves it is undone)
 * and only turns to face the nearest player. Nothing hurts it, sets it alight, poisons it, turns
 * it into a witch or zombie, or makes mobs go after it. Within token-shop.trader.protect-radius
 * only ops can place or break blocks, empty buckets or light fires, and lava and water cannot
 * flow in, so nobody can wall it off or bury it.
 */
public final class TokenShop implements Listener {
   private final LegendaryWeapons plugin;
   private final NamespacedKey traderKey;
   private final File file;
   private final Map<UUID, Location> traders = new LinkedHashMap<>();       // entity -> home
   private final Map<UUID, Long> warned = new HashMap<>();
   private final Map<UUID, com.legendaryweapons.bosses.BossModel> models = new HashMap<>();
   private org.bukkit.configuration.ConfigurationSection modelConfig;

   private record Offer(String spec, int cost) {
   }

   public TokenShop(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.traderKey = new NamespacedKey(plugin, "token-trader");
      this.file = new File(plugin.getDataFolder(), "traders.yml");
      this.load();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
   }

   // ------------------------------------------------------------------ persistence

   private void load() {
      YamlConfiguration data = YamlConfiguration.loadConfiguration(this.file);
      for (String line : data.getStringList("traders")) {
         String[] p = line.split(";");
         if (p.length < 6) {
            continue;
         }

         try {
            World world = Bukkit.getWorld(p[1]);
            if (world != null) {
               this.traders.put(UUID.fromString(p[0]), new Location(world, Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]), Float.parseFloat(p[5]), 0.0F));
            }
         } catch (IllegalArgumentException var6) {
            this.plugin.getLogger().warning("Skipping a bad line in traders.yml: " + line);
         }
      }
   }

   private void save() {
      YamlConfiguration data = new YamlConfiguration();
      List<String> lines = new ArrayList<>();
      for (Map.Entry<UUID, Location> entry : this.traders.entrySet()) {
         Location home = entry.getValue();
         lines.add(entry.getKey() + ";" + home.getWorld().getName() + ";" + home.getX() + ";" + home.getY() + ";" + home.getZ() + ";" + home.getYaw());
      }

      data.set("traders", lines);
      try {
         data.save(this.file);
      } catch (IOException var4) {
         this.plugin.getLogger().warning("Could not save traders.yml: " + var4.getMessage());
      }
   }

   // ------------------------------------------------------------------ offers and trading

   private List<Offer> offers() {
      List<Offer> out = new ArrayList<>();
      for (String line : this.plugin.getConfig().getStringList("token-shop.offers")) {
         int at = line.lastIndexOf('@');
         if (at < 0) {
            continue;
         }

         try {
            out.add(new Offer(line.substring(0, at).trim(), Math.max(1, Integer.parseInt(line.substring(at + 1).trim()))));
         } catch (NumberFormatException var5) {
            this.plugin.getLogger().warning("Bad token-shop offer: " + line);
         }
      }

      return out;
   }

   private static ItemStack token(int amount) {
      ItemStack token = CustomItems.get().create(CustomItems.LUMINARA_TOKEN, 1);
      token.setAmount(amount);
      return token;
   }

   private List<MerchantRecipe> recipes() {
      List<MerchantRecipe> recipes = new ArrayList<>();
      int stack = token(1).getMaxStackSize();
      for (Offer offer : this.offers()) {
         List<ItemStack> built = this.plugin.getEventManager().loot().build(List.of(offer.spec()));
         if (built.isEmpty()) {
            continue;
         }

         if (offer.cost() > stack * 2) {
            this.plugin.getLogger().warning("Token-shop offer '" + offer.spec() + "' costs more than two stacks of tokens; a trade can only ask for two.");
            continue;
         }

         // never runs out, never changes price, gives no villager XP
         MerchantRecipe recipe = new MerchantRecipe(built.get(0), Integer.MAX_VALUE);
         recipe.setExperienceReward(false);
         recipe.addIngredient(token(Math.min(offer.cost(), stack)));
         if (offer.cost() > stack) {
            recipe.addIngredient(token(offer.cost() - stack));
         }

         recipes.add(recipe);
      }

      return recipes;
   }

   /**
    * A trade only takes items that match its ingredient exactly, so tokens made by an older version
    * (different lore, say) are swapped for the current token first.
    */
   private static void normaliseTokens(Player player) {
      ItemStack canonical = token(1);
      ItemStack[] contents = player.getInventory().getContents();
      for (int i = 0; i < contents.length; i++) {
         ItemStack item = contents[i];
         if (item != null && CustomItems.LUMINARA_TOKEN.equals(CustomItems.get().idOf(item)) && !item.isSimilar(canonical)) {
            player.getInventory().setItem(i, token(item.getAmount()));
         }
      }
   }

   public void open(Player player) {
      normaliseTokens(player);
      Merchant merchant = Bukkit.createMerchant(Component.text("Token Trader", NamedTextColor.GOLD));
      merchant.setRecipes(this.recipes());
      player.openMerchant(merchant, true);
      this.plugin.getSoundManager().self(player, Sound.ENTITY_VILLAGER_TRADE, 0.8F, 1.0F);
   }

   public static int count(Player player) {
      int total = 0;
      for (ItemStack item : player.getInventory().getContents()) {
         if (CustomItems.LUMINARA_TOKEN.equals(CustomItems.get().idOf(item))) {
            total += item.getAmount();
         }
      }

      return total;
   }

   // ------------------------------------------------------------------ the traders

   public Villager spawn(Location at) {
      Location home = at.getBlock().getLocation().add(0.5, 0.0, 0.5);
      home.setYaw(at.getYaw() + 180.0F);                // facing whoever placed it
      home.setPitch(0.0F);
      Villager villager = at.getWorld().spawn(home, Villager.class, v -> {
         v.setPersistent(true);
         v.setRemoveWhenFarAway(false);
         v.setAI(false);
         v.setInvulnerable(true);
         v.setSilent(true);
         v.setCollidable(false);
         v.setProfession(Villager.Profession.CLERIC);
         v.setVillagerLevel(5);
         v.customName(Component.text("Token Trader", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
         v.setCustomNameVisible(true);
         v.getPersistentDataContainer().set(this.traderKey, PersistentDataType.BYTE, (byte)1);
      });
      this.traders.put(villager.getUniqueId(), home);
      this.save();
      return villager;
   }

   /** Removes every trader within `radius`; returns how many. */
   public int removeNear(Location at, double radius) {
      int removed = 0;
      for (Entity entity : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
         if (this.isTrader(entity)) {
            this.traders.remove(entity.getUniqueId());
            this.dropModel(entity.getUniqueId());
            entity.remove();
            removed++;
         }
      }

      if (removed > 0) {
         this.save();
      }

      return removed;
   }

   public List<Location> homes() {
      List<Location> out = new ArrayList<>();
      for (Location home : this.traders.values()) {
         out.add(home.clone());
      }

      return out;
   }

   private boolean isTrader(Entity entity) {
      return entity != null && entity.getPersistentDataContainer().has(this.traderKey, PersistentDataType.BYTE);
   }

   private double protectRadius() {
      return Math.max(0.0, this.plugin.getConfig().getDouble("token-shop.trader.protect-radius", 4.0));
   }

   /** Stay put, stay unlit, and turn to face the nearest player. */
   /**
    * The trader's custom model (token-shop.trader.model, template luminara:token_trader): shown on
    * an item display that follows the villager, which turns invisible. Off, it is a plain cleric.
    */
   private void dress(Villager villager) {
      org.bukkit.configuration.ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("token-shop.trader.model");
      if (section != this.modelConfig) {                 // config reloaded: rebuild every model
         this.modelConfig = section;
         for (com.legendaryweapons.bosses.BossModel model : this.models.values()) {
            model.remove();
         }

         this.models.clear();
      }

      com.legendaryweapons.bosses.BossModel model = this.models.computeIfAbsent(villager.getUniqueId(), id -> new com.legendaryweapons.bosses.BossModel(section));
      if (model.enabled()) {
         model.follow(villager);
      } else if (villager.isInvisible()) {
         villager.setInvisible(false);
      }
   }

   private void dropModel(UUID id) {
      com.legendaryweapons.bosses.BossModel model = this.models.remove(id);
      if (model != null) {
         model.remove();
      }
   }

   private void tick() {
      double look = Math.max(2.0, this.plugin.getConfig().getDouble("token-shop.trader.look-range", 8.0));
      for (Map.Entry<UUID, Location> entry : this.traders.entrySet()) {
         if (!(this.plugin.getServer().getEntity(entry.getKey()) instanceof Villager villager) || !villager.isValid()) {
            continue;
         }

         Location home = entry.getValue();
         Location now = villager.getLocation();
         if (!now.getWorld().equals(home.getWorld()) || now.distanceSquared(home) > 0.01) {
            Location back = home.clone();
            back.setYaw(now.getYaw());
            villager.teleport(back);
            villager.setVelocity(new Vector());
         }

         this.dress(villager);
         if (villager.getFireTicks() > 0) {
            villager.setFireTicks(0);
         }

         Player nearest = null;
         double best = look * look;
         for (Player player : home.getWorld().getPlayers()) {
            double d = player.getLocation().distanceSquared(home);
            if (d < best && player.getGameMode() != GameMode.SPECTATOR) {
               best = d;
               nearest = player;
            }
         }

         if (nearest != null) {
            Vector to = nearest.getEyeLocation().toVector().subtract(villager.getEyeLocation().toVector());
            double flat = Math.hypot(to.getX(), to.getZ());
            float yaw = (float)Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
            float pitch = (float)Math.max(-40.0, Math.min(40.0, Math.toDegrees(-Math.atan2(to.getY(), flat))));
            villager.setRotation(yaw, pitch);
         }
      }
   }

   // ------------------------------------------------------------------ the trader itself

   @EventHandler(priority = EventPriority.LOWEST)
   public void onInteract(PlayerInteractEntityEvent event) {
      if (this.isTrader(event.getRightClicked())) {
         // no vanilla trading, name tags, leads or anything else: just the token trades
         event.setCancelled(true);
         if (event.getHand() == EquipmentSlot.HAND) {
            this.open(event.getPlayer());
         }
      }
   }

   @EventHandler(priority = EventPriority.LOWEST)
   public void onDamage(EntityDamageEvent event) {
      if (this.isTrader(event.getEntity())) {
         event.setCancelled(true);
         event.getEntity().setFireTicks(0);
      }
   }

   @EventHandler(priority = EventPriority.LOWEST)
   public void onCombust(EntityCombustEvent event) {
      if (this.isTrader(event.getEntity())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.LOWEST)
   public void onPotion(EntityPotionEffectEvent event) {
      if (this.isTrader(event.getEntity()) && event.getNewEffect() != null) {
         event.setCancelled(true);
      }
   }

   /** Lightning would make it a witch; nothing gets to turn it into anything. */
   @EventHandler(priority = EventPriority.LOWEST)
   public void onTransform(EntityTransformEvent event) {
      if (this.isTrader(event.getEntity())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.LOWEST)
   public void onTarget(EntityTargetEvent event) {
      if (this.isTrader(event.getTarget())) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onDeath(EntityDeathEvent event) {
      this.dropModel(event.getEntity().getUniqueId());
      if (this.traders.remove(event.getEntity().getUniqueId()) != null) {
         this.save();
      }
   }

   // ------------------------------------------------------------------ the ground around it

   private boolean nearTrader(Location location) {
      double radius = this.protectRadius();
      if (radius <= 0.0 || location == null || location.getWorld() == null) {
         return false;
      }

      for (Location home : this.traders.values()) {
         if (home.getWorld().equals(location.getWorld())
            && Math.abs(home.getX() - (location.getBlockX() + 0.5)) <= radius
            && Math.abs(home.getZ() - (location.getBlockZ() + 0.5)) <= radius
            && location.getY() >= home.getY() - radius && location.getY() <= home.getY() + radius + 2.0) {
            return true;
         }
      }

      return false;
   }

   private boolean blocked(Player player, Block block) {
      if (player != null && (player.isOp() || player.hasPermission("legendaryweapons.admin"))) {
         return false;
      } else if (!this.nearTrader(block.getLocation())) {
         return false;
      }

      if (player != null) {
         long now = System.currentTimeMillis();
         Long last = this.warned.get(player.getUniqueId());
         if (last == null || now - last > 2000L) {
            this.warned.put(player.getUniqueId(), now);
            com.legendaryweapons.managers.HudManager.message(player, Component.text("The Token Trader's stall is protected.", NamedTextColor.RED));
         }
      }

      return true;
   }

   @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
   public void onPlace(BlockPlaceEvent event) {
      if (this.blocked(event.getPlayer(), event.getBlock())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
   public void onBreak(BlockBreakEvent event) {
      if (this.blocked(event.getPlayer(), event.getBlock())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
   public void onBucket(PlayerBucketEmptyEvent event) {
      if (this.blocked(event.getPlayer(), event.getBlock())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
   public void onIgnite(BlockIgniteEvent event) {
      if (this.nearTrader(event.getBlock().getLocation()) && (event.getPlayer() == null || this.blocked(event.getPlayer(), event.getBlock()))) {
         event.setCancelled(true);
      }
   }

   /** Lava and water from outside never run in. */
   @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
   public void onFlow(BlockFromToEvent event) {
      if (this.nearTrader(event.getToBlock().getLocation()) && !this.nearTrader(event.getBlock().getLocation())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onEntityExplode(EntityExplodeEvent event) {
      event.blockList().removeIf(block -> this.nearTrader(block.getLocation()));
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onBlockExplode(BlockExplodeEvent event) {
      event.blockList().removeIf(block -> this.nearTrader(block.getLocation()));
   }
}
