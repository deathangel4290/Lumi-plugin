package com.lumiweapons.events;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

/**
 * The Chosen: one player, picked at random by an admin from everyone who owns no legendary. They
 * wear a halo of light. When they fight someone wielding a legendary with a diamond or netherite
 * weapon in hand, that weapon becomes a copy of the opponent's legendary (its "echo") for as long
 * as the fight lasts, then turns back.
 *
 *   - only someone without a legendary can be the Chosen; taking up a real one ends it
 *   - the echo is fought with, never kept: it can't be dropped, stored, framed or bundled, it
 *     turns back on death, logout, shutdown and the end of the fight, and any echo found later
 *     (a thrown glaive coming home, a crash) turns back into the weapon it was made from
 *   - the original weapon only exists inside the echo while it lasts, so nothing can be duplicated
 *
 * Settings: events.chosen.
 */
public final class ChosenRole implements Listener {
   private final LumiWeapons plugin;
   private final EventManager manager;
   private final NamespacedKey echoKey;
   private final NamespacedKey originalKey;
   private UUID chosen;
   private String chosenName;
   private Fight fight;
   private int ticks;

   /** The fight that made the echo: who it is against, which legendary, and the last hit between them. */
   private static final class Fight {
      final UUID opponent;
      final String opponentName;
      final String weaponKey;
      final String id = UUID.randomUUID().toString();
      long lastHit = System.currentTimeMillis();

      Fight(Player opponent, String publicName, String weaponKey) {
         this.opponent = opponent.getUniqueId();
         this.opponentName = publicName;
         this.weaponKey = weaponKey;
      }
   }

   ChosenRole(LumiWeapons plugin, EventManager manager) {
      this.plugin = plugin;
      this.manager = manager;
      this.echoKey = LumiWeapons.key("chosen-echo");
      this.originalKey = LumiWeapons.key("chosen-original");
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
   }

   // ------------------------------------------------------------------ settings

   private ConfigurationSection settings() {
      ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("events.chosen");
      return section != null ? section : new org.bukkit.configuration.file.YamlConfiguration();
   }

   private boolean isEchoWeapon(ItemStack item) {
      if (item == null || item.getType().isAir()) {
         return false;
      }

      for (String name : this.settings().getStringList("weapons")) {
         Material material = Material.matchMaterial(name.trim());    // spears and friends differ between versions
         if (material == item.getType()) {
            return true;
         }
      }

      return false;
   }

   // ------------------------------------------------------------------ who is the Chosen

   public boolean isChosen(Player player) {
      return player != null && player.getUniqueId().equals(this.chosen);
   }

   public String chosenName() {
      return this.chosenName;
   }

   public boolean hasChosen() {
      return this.chosen != null;
   }

   public String status() {
      if (this.chosen == null) {
         return "Nobody is the Chosen.";
      }

      Player online = Bukkit.getPlayer(this.chosen);
      String name = online != null ? this.manager.publicName(online) : this.chosenName;
      if (this.fight != null) {
         return name + " is the Chosen, fighting " + this.fight.opponentName + " with an echo of " + this.weaponName(this.fight.weaponKey) + ".";
      }

      return name + " is the Chosen" + (online == null ? " (offline)." : ".");
   }

   /** A real legendary anywhere they keep things (an echo doesn't count). */
   public boolean ownsLegendary(Player player) {
      return this.legendaryWhere(player) != null;
   }

   /** Where this player keeps a real legendary ("inventory", "ender chest"), or null. */
   private String legendaryWhere(Player player) {
      List<ItemStack> carried = new ArrayList<>(List.of(player.getInventory().getContents()));
      carried.add(player.getItemOnCursor());
      if (this.anyLegendary(carried)) {
         return "inventory";
      }

      return this.anyLegendary(List.of(player.getEnderChest().getContents())) ? "ender chest" : null;
   }

   private boolean anyLegendary(List<ItemStack> items) {
      var weapons = this.plugin.getWeaponManager();
      for (ItemStack item : items) {
         if (weapons.isLegendaryWeapon(item) && !this.isEcho(item)) {
            return true;
         }
      }

      return false;
   }

   /** Why this player can't be the Chosen, or null when they can. */
   private String whyNot(Player player) {
      if (!player.isOnline()) {
         return "offline";
      } else if (player.getGameMode() == GameMode.SPECTATOR) {
         return "spectating";
      } else if (this.manager.isMostWanted(player)) {
         return "the Most Wanted";
      } else if (!this.settings().getBoolean("include-admins", true) && player.hasPermission("lumiweapons.admin")) {
         return "an admin (events.chosen.include-admins is off)";
      }

      String where = this.legendaryWhere(player);
      return where == null ? null : "has a legendary in their " + where;
   }

   private boolean eligible(Player player) {
      return this.whyNot(player) == null;
   }

   /** Picks someone at random from everyone online who may be the Chosen. Null on success, else why not. */
   public String pick() {
      Player picked = this.randomEligible(null);
      if (picked == null) {
         List<String> reasons = new ArrayList<>();
         for (Player player : Bukkit.getOnlinePlayers()) {
            reasons.add(player.getName() + (this.isChosen(player) ? " (already the Chosen)" : " (" + this.whyNot(player) + ")"));
         }

         return reasons.isEmpty() ? "Nobody is online." : "Nobody online can be the Chosen: " + String.join(", ", reasons) + ".";
      }

      this.makeChosen(picked, null);
      return null;
   }

   /** Someone online who may be the Chosen, other than the current one and `besides`; null if nobody. */
   private Player randomEligible(Player besides) {
      List<Player> pool = new ArrayList<>();
      for (Player player : Bukkit.getOnlinePlayers()) {
         if (this.eligible(player) && !this.isChosen(player) && !player.equals(besides)) {
            pool.add(player);
         }
      }

      return pool.isEmpty() ? null : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
   }

   public String set(String name) {
      Player player = Bukkit.getPlayerExact(name);
      if (player == null) {
         return name + " isn't online.";
      }

      String why = this.whyNot(player);
      if (why != null) {
         return player.getName() + " can't be the Chosen: " + why + "." + (why.startsWith("has a legendary") ? " Only someone without one can be." : "");
      }

      this.makeChosen(player, null);
      return null;
   }

   public String clear() {
      if (this.chosen == null) {
         return "Nobody is the Chosen.";
      }

      this.endFight(Bukkit.getPlayer(this.chosen), false);
      Bukkit.broadcast(Component.text("The Chosen's light fades. ", NamedTextColor.GOLD).append(Component.text(this.chosenName + " is no longer the Chosen.", NamedTextColor.GRAY)));
      this.chosen = null;
      this.chosenName = null;
      this.manager.save();
      return null;
   }

   /** `announcement` replaces the usual "the light has chosen" line (null keeps it). */
   private void makeChosen(Player player, Component announcement) {
      if (this.chosen != null) {
         this.endFight(Bukkit.getPlayer(this.chosen), false);
      }

      this.chosen = player.getUniqueId();
      this.chosenName = player.getName();
      this.manager.save();
      if (this.settings().getBoolean("announce", true)) {
         Bukkit.broadcast(announcement != null ? announcement : Component.text("✦ The light has chosen " + this.manager.publicName(player) + ". ", NamedTextColor.GOLD)
            .append(Component.text("Face a legendary with a diamond or netherite weapon and it answers in kind.", NamedTextColor.GRAY)));
      }

      player.showTitle(Title.title(Component.text("You are the Chosen", NamedTextColor.GOLD),
         Component.text("Fight a legendary with a diamond or netherite weapon", NamedTextColor.YELLOW),
         Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofSeconds(1))));
      for (Player everyone : Bukkit.getOnlinePlayers()) {
         everyone.playSound(everyone.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7F, 1.4F);
      }

      player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0.0, 2.2, 0.0), 40, 0.3, 0.4, 0.3, 0.08);
   }

   /**
    * The Chosen was killed by a player. A killer with no legendary earns the role; a legendary
    * holder can't carry it, so it goes to someone at random instead (never the one who just died).
    * Deaths to anything but a player leave the role where it is.
    */
   private void passOn(Player dead, Player killer, boolean killerHasLegendary) {
      if (!this.isChosen(dead)) {
         return;
      }

      String fallen = this.manager.publicName(dead);
      if (!killerHasLegendary && killer.isOnline() && this.eligible(killer)) {
         String earned = this.manager.publicName(killer);
         this.makeChosen(killer, Component.text("⚔ " + earned + " slew the Chosen " + fallen + " and earned their light.", NamedTextColor.GOLD));
         return;
      }

      Player next = this.randomEligible(dead);
      if (next == null) {
         this.lose(dead, fallen + " fell to a legendary, and nobody is left to carry the light.");
         return;
      }

      this.makeChosen(next, Component.text("✦ " + fallen + " fell to a legendary. ", NamedTextColor.GOLD)
         .append(Component.text("The light has passed to " + this.manager.publicName(next) + ".", NamedTextColor.YELLOW)));
   }

   private void lose(Player player, String why) {
      this.endFight(player, false);
      Bukkit.broadcast(Component.text("The Chosen's light fades: ", NamedTextColor.GOLD).append(Component.text(why, NamedTextColor.GRAY)));
      this.chosen = null;
      this.chosenName = null;
      this.manager.save();
   }

   // ------------------------------------------------------------------ persistence

   void load(ConfigurationSection section) {
      if (section == null) {
         return;
      }

      try {
         String raw = section.getString("uuid");
         this.chosen = raw == null || raw.isBlank() ? null : UUID.fromString(raw);
         this.chosenName = section.getString("name", "someone");
      } catch (IllegalArgumentException ignored) {
         this.chosen = null;
      }
   }

   void save(ConfigurationSection section) {
      section.set("uuid", this.chosen == null ? null : this.chosen.toString());
      section.set("name", this.chosen == null ? null : this.chosenName);
   }

   void shutdown() {
      if (this.chosen != null) {
         this.endFight(Bukkit.getPlayer(this.chosen), true);
      }
   }

   // ------------------------------------------------------------------ the echo

   private boolean isEcho(ItemStack item) {
      return item != null && !item.getType().isAir() && item.getPersistentDataContainer().has(this.echoKey, PersistentDataType.STRING);
   }

   /** The weapon an echo was made from, or null if it can't be read back. */
   private ItemStack original(ItemStack echo) {
      byte[] bytes = echo.getItemMeta().getPersistentDataContainer().get(this.originalKey, PersistentDataType.BYTE_ARRAY);
      if (bytes == null) {
         return null;
      }

      try {
         return ItemStack.deserializeBytes(bytes);
      } catch (RuntimeException failure) {
         this.plugin.getLogger().warning("Could not restore a Chosen's weapon: " + failure.getMessage());
         return null;
      }
   }

   private String weaponName(String weaponKey) {
      return this.plugin.getConfigManager().getWeaponDisplayName(weaponKey).replaceAll("§.", "");
   }

   private static String itemName(ItemStack item) {
      ItemMeta meta = item.getItemMeta();
      if (meta != null && meta.hasDisplayName()) {
         return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
      }

      String name = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
      return Character.toUpperCase(name.charAt(0)) + name.substring(1);
   }

   private ItemStack makeEcho(ItemStack from, String weaponKey, String fightId) {
      ItemStack echo = this.plugin.getWeaponManager().getWeapon(weaponKey);
      if (echo.getType().isAir()) {
         return null;
      }

      ItemMeta meta = echo.getItemMeta();
      meta.getPersistentDataContainer().set(this.echoKey, PersistentDataType.STRING, fightId);
      meta.getPersistentDataContainer().set(this.originalKey, PersistentDataType.BYTE_ARRAY, from.serializeAsBytes());
      List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
      lore.add(Component.empty());
      lore.add(Component.text("✦ The Chosen's echo", NamedTextColor.GOLD).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
      lore.add(Component.text("Turns back into your " + itemName(from) + " when the fight ends.", NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
      meta.lore(lore);
      echo.setItemMeta(meta);
      return echo;
   }

   /** The legendary an opponent fights with: main hand first, then off-hand. */
   private String heldLegendary(Player player) {
      var weapons = this.plugin.getWeaponManager();
      for (ItemStack item : new ItemStack[]{player.getInventory().getItemInMainHand(), player.getInventory().getItemInOffHand()}) {
         if (weapons.isLegendaryWeapon(item) && !this.isEcho(item)) {
            String key = weapons.getWeaponKey(item);
            if (key != null && !weapons.getWeapon(key).getType().isAir()) {
               return key;
            }
         }
      }

      return null;
   }

   private static Player playerBehind(Entity damager) {
      if (damager instanceof Player player) {
         return player;
      }

      return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onFight(EntityDamageByEntityEvent event) {
      if (this.chosen == null || !(event.getEntity() instanceof Player victim)) {
         return;
      }

      Player attacker = playerBehind(event.getDamager());
      if (attacker == null || attacker.equals(victim)) {
         return;
      }

      Player chosenPlayer = this.isChosen(attacker) ? attacker : (this.isChosen(victim) ? victim : null);
      if (chosenPlayer == null) {
         return;
      }

      Player opponent = chosenPlayer == attacker ? victim : attacker;
      if (this.fight != null) {
         if (this.fight.opponent.equals(opponent.getUniqueId())) {
            this.fight.lastHit = System.currentTimeMillis();
         }

         return;                                          // one echo at a time
      }

      String weaponKey = this.heldLegendary(opponent);
      if (weaponKey == null) {
         return;
      }

      // a tick later, so this hit's own handlers (abilities, Eclipse) finish with the item they started with
      this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.awaken(chosenPlayer, opponent, weaponKey));
   }

   private void awaken(Player chosenPlayer, Player opponent, String weaponKey) {
      if (this.fight != null || !this.isChosen(chosenPlayer) || !chosenPlayer.isOnline() || chosenPlayer.isDead() || !opponent.isOnline()) {
         return;
      }

      PlayerInventory inventory = chosenPlayer.getInventory();
      ItemStack held = inventory.getItemInMainHand();
      if (!this.isEchoWeapon(held) || this.plugin.getWeaponManager().isLegendaryWeapon(held)) {
         HudManager.message(chosenPlayer, Component.text(this.manager.publicName(opponent) + " wields " + this.weaponName(weaponKey) + ". Hold a diamond or netherite weapon to answer it.", NamedTextColor.GOLD));
         return;
      }

      String opponentName = this.manager.publicName(opponent);            // a Veil or Most Wanted name stays hidden
      Fight started = new Fight(opponent, opponentName, weaponKey);
      ItemStack echo = this.makeEcho(held, weaponKey, started.id);
      if (echo == null) {
         return;
      }

      this.fight = started;
      inventory.setItemInMainHand(echo);
      Location at = chosenPlayer.getLocation().add(0.0, 1.0, 0.0);
      ParticleManager.showcase(Particle.END_ROD, at, 30, 0.4, 0.6, 0.4, 0.1, null);
      ParticleManager.showcase(Particle.FLASH, at, 1, 0.0, 0.0, 0.0, 0.0, this.haloColour());
      this.plugin.getSoundManager().world(at, Sound.BLOCK_BEACON_POWER_SELECT, 1.0F, 1.6F);
      this.plugin.getSoundManager().world(at, Sound.ITEM_TRIDENT_RETURN, 1.0F, 0.8F);
      chosenPlayer.sendMessage(Component.text("✦ Your " + itemName(held) + " answers " + opponentName + ": it is " + this.weaponName(weaponKey) + " until the fight ends.", NamedTextColor.GOLD));
      opponent.sendMessage(Component.text("✦ " + this.manager.publicName(chosenPlayer) + " is the Chosen: their weapon has become your " + this.weaponName(weaponKey) + " for this fight.", NamedTextColor.GOLD));
   }

   /** The fight is over: every echo the Chosen carries turns back. `quiet` skips the message. */
   private void endFight(Player player, boolean quiet) {
      Fight ended = this.fight;
      this.fight = null;
      if (player == null || ended == null) {
         return;
      }

      int restored = this.sweep(player);               // the fight is already cleared, so every echo goes
      if (!quiet && restored > 0 && player.isOnline()) {
         HudManager.message(player, Component.text("The echo fades. Your weapon is your own again.", NamedTextColor.GOLD));
         this.plugin.getSoundManager().self(player, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8F, 0.7F);
      }
   }

   /** Turns every echo this player carries (that isn't the current fight's) back. Returns how many. */
   private int sweep(Player player) {
      String live = this.fight != null && this.isChosen(player) ? this.fight.id : null;
      int restored = 0;
      PlayerInventory inventory = player.getInventory();
      ItemStack[] contents = inventory.getContents();
      for (int i = 0; i < contents.length; i++) {
         if (this.isEcho(contents[i]) && !this.echoOf(contents[i], live)) {
            inventory.setItem(i, this.original(contents[i]));
            restored++;
         }
      }

      ItemStack cursor = player.getItemOnCursor();
      if (this.isEcho(cursor) && !this.echoOf(cursor, live)) {
         player.setItemOnCursor(this.original(cursor));
         restored++;
      }

      if (player.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING) {
         var grid = player.getOpenInventory().getTopInventory();
         for (int i = 0; i < grid.getSize(); i++) {
            if (this.isEcho(grid.getItem(i)) && !this.echoOf(grid.getItem(i), live)) {
               grid.setItem(i, this.original(grid.getItem(i)));
               restored++;
            }
         }
      }

      return restored;
   }

   private boolean echoOf(ItemStack echo, String fightId) {
      return fightId != null && fightId.equals(echo.getItemMeta().getPersistentDataContainer().get(this.echoKey, PersistentDataType.STRING));
   }

   // ------------------------------------------------------------------ the clock: halo, fight end, checks

   private Color haloColour() {
      if (this.fight != null) {
         List<String> colours = this.plugin.getConfig().getStringList("weapons." + this.fight.weaponKey + ".ritual-colors");
         if (!colours.isEmpty()) {
            Color parsed = parseColour(colours.get(0));
            if (parsed != null) {
               return parsed;
            }
         }
      }

      Color configured = parseColour(this.settings().getString("halo.colour", "#FFD24A"));
      return configured != null ? configured : Color.fromRGB(0xFFD24A);
   }

   private static Color parseColour(String text) {
      try {
         String hex = text.trim().startsWith("#") ? text.trim().substring(1) : text.trim();
         return Color.fromRGB(Integer.parseInt(hex, 16) & 0xFFFFFF);
      } catch (RuntimeException ignored) {
         return null;
      }
   }

   private void tick() {
      this.ticks++;
      Player player = this.chosen == null ? null : Bukkit.getPlayer(this.chosen);
      if (player != null && this.settings().getBoolean("halo.enabled", true)) {
         this.halo(player);
      }

      // the task runs every 2 ticks: the fight is checked every second, the rest every 2 seconds
      if (this.ticks % 10 == 0 && this.fight != null) {
         this.checkFight(player);
      }

      if (this.ticks % 20 == 0) {
         if (player != null && this.ownsLegendary(player)) {
            this.lose(player, this.chosenName + " took up a legendary of their own.");
         }

         for (Player online : Bukkit.getOnlinePlayers()) {
            this.sweep(online);
         }
      }
   }

   private void halo(Player player) {
      if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR || player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
         return;
      }

      ConfigurationSection halo = this.settings();
      int points = Math.max(4, Math.min(32, halo.getInt("halo.points", 12)));
      double radius = halo.getDouble("halo.radius", 0.32);
      double above = halo.getDouble("halo.height", 0.25);
      double top = player.getBoundingBox().getMaxY() + above;
      Location centre = player.getLocation();
      Particle.DustOptions dust = new Particle.DustOptions(this.haloColour(), this.fight != null ? 0.8F : 0.6F);
      double turn = this.ticks * 0.08;
      for (int i = 0; i < points; i++) {
         double angle = turn + Math.PI * 2.0 * i / points;
         Location at = new Location(player.getWorld(), centre.getX() + Math.cos(angle) * radius, top, centre.getZ() + Math.sin(angle) * radius);
         ParticleManager.showcase(Particle.DUST, at, 1, 0.0, 0.0, 0.0, 0.0, dust);
      }
   }

   private void checkFight(Player player) {
      Fight current = this.fight;
      Player opponent = Bukkit.getPlayer(current.opponent);
      long timeout = Math.max(3L, this.settings().getLong("fight-timeout", 15L)) * 1000L;
      double range = Math.max(8.0, this.settings().getDouble("max-distance", 48.0));
      boolean over = player == null || !player.isOnline() || player.isDead()
         || opponent == null || !opponent.isOnline() || opponent.isDead()
         || !opponent.getWorld().equals(player.getWorld())
         || opponent.getLocation().distanceSquared(player.getLocation()) > range * range
         || System.currentTimeMillis() - current.lastHit > timeout;
      if (over) {
         this.endFight(player, false);
      }
   }

   // ------------------------------------------------------------------ keeping the echo from leaving

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onDrop(PlayerDropItemEvent event) {
      if (this.isEcho(event.getItemDrop().getItemStack())) {
         event.setCancelled(true);
         HudManager.message(event.getPlayer(), Component.text("The echo can't be let go of.", NamedTextColor.GOLD));
      }
   }

   /** Anything that would put an echo on the ground puts the weapon it was made from there instead. */
   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onItemSpawn(ItemSpawnEvent event) {
      ItemStack stack = event.getEntity().getItemStack();
      if (this.isEcho(stack)) {
         ItemStack original = this.original(stack);
         if (original == null) {
            event.setCancelled(true);
         } else {
            event.getEntity().setItemStack(original);
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onPickup(EntityPickupItemEvent event) {
      ItemStack stack = event.getItem().getItemStack();
      if (this.isEcho(stack)) {
         ItemStack original = this.original(stack);
         if (original != null) {
            event.getItem().setItemStack(original);
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onDeath(PlayerDeathEvent event) {
      List<ItemStack> drops = event.getDrops();
      for (int i = 0; i < drops.size(); i++) {
         if (this.isEcho(drops.get(i))) {
            ItemStack original = this.original(drops.get(i));
            if (original == null) {
               drops.remove(i--);
            } else {
               drops.set(i, original);
            }
         }
      }

      List<ItemStack> kept = event.getItemsToKeep();
      for (int i = 0; i < kept.size(); i++) {
         if (this.isEcho(kept.get(i))) {
            ItemStack original = this.original(kept.get(i));
            if (original == null) {
               kept.remove(i--);
            } else {
               kept.set(i, original);
            }
         }
      }

      Player dead = event.getEntity();
      if (this.fight != null && (this.isChosen(dead) || this.fight.opponent.equals(dead.getUniqueId()))) {
         Player chosenPlayer = this.chosen == null ? null : Bukkit.getPlayer(this.chosen);
         if (event.getKeepInventory() || !this.isChosen(dead)) {
            this.endFight(chosenPlayer, false);           // a kept inventory still has the echo in it
         } else {
            this.fight = null;                            // the inventory is about to be emptied: drops are handled above
         }
      }

      Player killer = dead.getKiller();
      if (this.isChosen(dead) && killer != null && !killer.equals(dead) && this.settings().getBoolean("pass-on-death", true)) {
         boolean legendary = this.ownsLegendary(killer);                      // checked now, before anyone picks up the drops
         this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.passOn(dead, killer, legendary));
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onClick(InventoryClickEvent event) {
      if (!(event.getWhoClicked() instanceof Player player)) {
         return;
      }

      ItemStack swapped = event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0 ? player.getInventory().getItem(event.getHotbarButton())
         : (event.getClick() == ClickType.SWAP_OFFHAND ? player.getInventory().getItemInOffHand() : null);
      boolean echo = this.isEcho(event.getCurrentItem()) || this.isEcho(event.getCursor()) || this.isEcho(swapped);
      if (!echo) {
         return;
      }

      InventoryType top = event.getView().getTopInventory().getType();
      boolean ownInventory = top == InventoryType.CRAFTING || top == InventoryType.CREATIVE;
      boolean bundle = isBundle(event.getCurrentItem()) || isBundle(event.getCursor());
      if (!ownInventory || bundle) {
         event.setCancelled(true);
         HudManager.message(player, Component.text("The echo stays with you.", NamedTextColor.GOLD));
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onDrag(InventoryDragEvent event) {
      if (!this.isEcho(event.getOldCursor())) {
         return;
      }

      InventoryType top = event.getView().getTopInventory().getType();
      int topSize = event.getView().getTopInventory().getSize();
      for (int raw : event.getRawSlots()) {
         if (raw < topSize && top != InventoryType.CRAFTING) {
            event.setCancelled(true);
            return;
         }
      }
   }

   private static boolean isBundle(ItemStack item) {
      return item != null && item.getType().name().endsWith("BUNDLE");
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onInteractEntity(PlayerInteractEntityEvent event) {
      PlayerInventory inventory = event.getPlayer().getInventory();
      ItemStack held = event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND ? inventory.getItemInOffHand() : inventory.getItemInMainHand();
      if (this.isEcho(held) && (event.getRightClicked() instanceof org.bukkit.entity.ItemFrame || event.getRightClicked() instanceof org.bukkit.entity.Allay
         || event.getRightClicked() instanceof org.bukkit.entity.ArmorStand)) {
         event.setCancelled(true);
      }
   }

   /** An echo is never crafted with (a grid repair would eat it). */
   @EventHandler
   public void onPrepareCraft(org.bukkit.event.inventory.PrepareItemCraftEvent event) {
      for (ItemStack item : event.getInventory().getMatrix()) {
         if (this.isEcho(item)) {
            event.getInventory().setResult(null);
            return;
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onArmorStand(PlayerArmorStandManipulateEvent event) {
      if (this.isEcho(event.getPlayerItem())) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         if (player.isOnline()) {
            this.sweep(player);                           // an echo from before a crash or a restart
            if (this.isChosen(player)) {
               this.chosenName = player.getName();
               HudManager.message(player, Component.text("✦ You are still the Chosen.", NamedTextColor.GOLD));
            }
         }
      }, 10L);
   }

   @EventHandler(priority = EventPriority.LOWEST)
   public void onQuit(PlayerQuitEvent event) {
      Player player = event.getPlayer();
      if (this.fight != null && this.isChosen(player)) {
         this.endFight(player, true);
      } else if (this.fight != null && this.fight.opponent.equals(player.getUniqueId())) {
         this.endFight(this.chosen == null ? null : Bukkit.getPlayer(this.chosen), false);
      }
   }
}
