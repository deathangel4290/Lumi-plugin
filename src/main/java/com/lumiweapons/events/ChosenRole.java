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
      final String id;                                     // stamped on the echo; kept when the Chosen switches
      long lastHit = System.currentTimeMillis();

      Fight(Player opponent, String publicName, String weaponKey) {
         this(opponent, publicName, weaponKey, UUID.randomUUID().toString());
      }

      Fight(Player opponent, String publicName, String weaponKey, String id) {
         this.opponent = opponent.getUniqueId();
         this.opponentName = publicName;
         this.weaponKey = weaponKey;
         this.id = id;
      }
   }

   /** A different legendary the Chosen may switch the echo to: offered in chat, taken with /chosen switch. */
   private record Offer(UUID opponent, String opponentName, String weaponKey, long until) {
   }

   private static final long OFFER_MILLIS = 10_000L;
   private static final long DECLINE_MILLIS = 30_000L;
   private Offer offer;
   /** opponent -> until when "keep" holds: no new offer for them before then. */
   private final java.util.Map<UUID, Long> declined = new java.util.HashMap<>();

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
      // empty slots are null, so Arrays.asList (List.of throws on them)
      List<ItemStack> carried = new ArrayList<>(java.util.Arrays.asList(player.getInventory().getContents()));
      carried.add(player.getItemOnCursor());
      if (this.anyLegendary(carried)) {
         return "inventory";
      }

      return this.anyLegendary(java.util.Arrays.asList(player.getEnderChest().getContents())) ? "ender chest" : null;
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

   /**
    * The Chosen took up a real legendary, so they can't carry the light any more: it goes to
    * someone at random who can (events.chosen.pass-on-legendary), or ends if nobody can.
    */
   private void passFromLegendary(Player player) {
      String fallen = this.manager.publicName(player);
      Player next = this.settings().getBoolean("pass-on-legendary", true) ? this.randomEligible(player) : null;
      if (next == null) {
         this.lose(player, fallen + " took up a legendary of their own.");
         return;
      }

      this.endFight(player, false);
      this.makeChosen(next, Component.text("✦ " + fallen + " took up a legendary and gave up the light. ", NamedTextColor.GOLD)
         .append(Component.text("It has passed to " + this.manager.publicName(next) + ".", NamedTextColor.YELLOW)));
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
      return this.makeEcho(from.serializeAsBytes(), itemName(from), weaponKey, fightId);
   }

   private ItemStack makeEcho(byte[] original, String originalName, String weaponKey, String fightId) {
      ItemStack echo = this.plugin.getWeaponManager().getWeapon(weaponKey);
      if (echo.getType().isAir()) {
         return null;
      }

      ItemMeta meta = echo.getItemMeta();
      meta.getPersistentDataContainer().set(this.echoKey, PersistentDataType.STRING, fightId);
      meta.getPersistentDataContainer().set(this.originalKey, PersistentDataType.BYTE_ARRAY, original);
      List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
      lore.add(Component.empty());
      lore.add(Component.text("✦ The Chosen's echo", NamedTextColor.GOLD).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
      lore.add(Component.text("Turns back into your " + originalName + " when the fight ends.", NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
      meta.lore(lore);
      echo.setItemMeta(meta);
      return echo;
   }

   /** The legendary an opponent fights with: main hand first, then off-hand. */
   private String heldLegendary(Player player) {
      for (ItemStack item : this.searchOrder(player)) {
         String key = this.realLegendaryKey(item);
         if (key != null) {
            return key;
         }
      }

      return null;
   }

   /** Whether this player carries this legendary anywhere in their inventory. */
   private boolean carries(Player player, String weaponKey) {
      for (ItemStack item : this.searchOrder(player)) {
         if (weaponKey.equals(this.realLegendaryKey(item))) {
            return true;
         }
      }

      return false;
   }

   private String realLegendaryKey(ItemStack item) {
      var weapons = this.plugin.getWeaponManager();
      if (!weapons.isLegendaryWeapon(item) || this.isEcho(item)) {
         return null;
      }

      String key = weapons.getWeaponKey(item);
      return key != null && !weapons.getWeapon(key).getType().isAir() ? key : null;
   }

   /**
    * A player's items in the order that matters for a fight: main hand, off-hand, the hotbar, then
    * the rest of the inventory. A legendary counts whether or not it is in their hand.
    */
   private List<ItemStack> searchOrder(Player player) {
      PlayerInventory inventory = player.getInventory();
      List<ItemStack> items = new ArrayList<>();
      items.add(inventory.getItemInMainHand());
      items.add(inventory.getItemInOffHand());
      for (int slot = 0; slot < 36; slot++) {
         items.add(inventory.getItem(slot));
      }

      return items;
   }

   /**
    * The slot the Chosen's echo is made in: the weapon in their hand if it's diamond or netherite,
    * otherwise the first such weapon in the hotbar, then the rest of the inventory, then the
    * off-hand. -1 when they carry none.
    */
   private int echoSlot(PlayerInventory inventory) {
      int held = inventory.getHeldItemSlot();
      if (this.canEcho(inventory.getItem(held))) {
         return held;
      }

      for (int slot = 0; slot < 36; slot++) {
         if (this.canEcho(inventory.getItem(slot))) {
            return slot;
         }
      }

      return this.canEcho(inventory.getItemInOffHand()) ? 40 : -1;      // 40 = the off-hand slot
   }

   private boolean canEcho(ItemStack item) {
      return this.isEchoWeapon(item) && !this.plugin.getWeaponManager().isLegendaryWeapon(item);
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
         } else {
            String other = this.heldLegendary(opponent);
            if (other != null) {
               this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.offerSwitch(chosenPlayer, opponent, other));
            }
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
      int slot = this.echoSlot(inventory);                            // the hand first, else any diamond/netherite weapon they carry
      if (slot < 0) {
         HudManager.message(chosenPlayer, Component.text(this.manager.publicName(opponent) + " carries " + this.weaponName(weaponKey) + ". Carry a diamond or netherite weapon to answer it.", NamedTextColor.GOLD));
         return;
      }

      ItemStack held = inventory.getItem(slot);

      String opponentName = this.manager.publicName(opponent);            // a Veil or Most Wanted name stays hidden
      Fight started = new Fight(opponent, opponentName, weaponKey);
      ItemStack echo = this.makeEcho(held, weaponKey, started.id);
      if (echo == null) {
         return;
      }

      this.fight = started;
      inventory.setItem(slot, echo);
      Location at = chosenPlayer.getLocation().add(0.0, 1.0, 0.0);
      ParticleManager.showcase(Particle.END_ROD, at, 30, 0.4, 0.6, 0.4, 0.1, null);
      ParticleManager.showcase(Particle.FLASH, at, 1, 0.0, 0.0, 0.0, 0.0, this.haloColour());
      this.plugin.getSoundManager().world(at, Sound.BLOCK_BEACON_POWER_SELECT, 1.0F, 1.6F);
      this.plugin.getSoundManager().world(at, Sound.ITEM_TRIDENT_RETURN, 1.0F, 0.8F);
      chosenPlayer.sendMessage(Component.text("✦ Your " + itemName(held) + " answers " + opponentName + ": it is " + this.weaponName(weaponKey) + " until the fight ends.", NamedTextColor.GOLD));
      opponent.sendMessage(Component.text("✦ " + this.manager.publicName(chosenPlayer) + " is the Chosen: their weapon has become your " + this.weaponName(weaponKey) + " for this fight.", NamedTextColor.GOLD));
   }

   /**
    * Mid-fight, someone else with a legendary joins in. The same legendary: the echo simply faces
    * them now. A different one: the Chosen is asked in chat whether to switch (events.chosen.switch-offer).
    */
   private void offerSwitch(Player chosenPlayer, Player opponent, String weaponKey) {
      Fight current = this.fight;
      if (current == null || !this.isChosen(chosenPlayer) || !chosenPlayer.isOnline() || !opponent.isOnline()) {
         return;
      }

      long now = System.currentTimeMillis();
      String name = this.manager.publicName(opponent);
      if (weaponKey.equals(current.weaponKey)) {
         this.fight = new Fight(opponent, name, weaponKey, current.id);
         return;
      }

      if (!this.settings().getBoolean("switch-offer", true)
         || this.offer != null && this.offer.opponent().equals(opponent.getUniqueId()) && this.offer.weaponKey().equals(weaponKey) && this.offer.until() > now
         || this.declined.getOrDefault(opponent.getUniqueId(), 0L) > now) {
         return;
      }

      this.offer = new Offer(opponent.getUniqueId(), name, weaponKey, now + OFFER_MILLIS);
      String next = this.weaponName(weaponKey);
      chosenPlayer.sendMessage(Component.text("⚔ " + name + " carries " + next + ". ", NamedTextColor.GOLD)
         .append(Component.text("[Switch]", NamedTextColor.GREEN, net.kyori.adventure.text.format.TextDecoration.BOLD)
            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/chosen switch"))
            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Your echo becomes " + next + " and you fight " + name, NamedTextColor.GRAY))))
         .append(Component.text(" "))
         .append(Component.text("[Keep " + this.weaponName(current.weaponKey) + "]", NamedTextColor.GRAY)
            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/chosen keep"))
            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Stay as you are", NamedTextColor.GRAY))))
         .append(Component.text("  (" + OFFER_MILLIS / 1000L + "s)", NamedTextColor.DARK_GRAY)));
      this.plugin.getSoundManager().self(chosenPlayer, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8F, 1.6F);
   }

   /** /chosen switch: the echo becomes the offered legendary. Null on success, else why not. */
   public String switchEcho(Player player) {
      Fight current = this.fight;
      Offer pending = this.offer;
      if (!this.isChosen(player)) {
         return "Only the Chosen can switch.";
      } else if (current == null) {
         return "You aren't holding an echo right now.";
      } else if (pending == null || pending.until() < System.currentTimeMillis()) {
         this.offer = null;
         return "There's nothing to switch to right now.";
      }

      Player opponent = Bukkit.getPlayer(pending.opponent());
      if (opponent == null || !opponent.isOnline() || opponent.isDead() || !this.carries(opponent, pending.weaponKey())) {
         this.offer = null;
         return pending.opponentName() + " no longer carries " + this.weaponName(pending.weaponKey()) + ".";
      }

      // every echo of this fight becomes the new legendary, carrying the very same original weapon
      int switched = 0;
      PlayerInventory inventory = player.getInventory();
      ItemStack[] contents = inventory.getContents();
      for (int i = 0; i < contents.length; i++) {
         if (this.isEcho(contents[i]) && this.echoOf(contents[i], current.id)) {
            byte[] original = contents[i].getItemMeta().getPersistentDataContainer().get(this.originalKey, PersistentDataType.BYTE_ARRAY);
            ItemStack back = this.original(contents[i]);
            ItemStack echo = original == null || back == null ? null : this.makeEcho(original, itemName(back), pending.weaponKey(), current.id);
            if (echo != null) {
               inventory.setItem(i, echo);
               switched++;
            }
         }
      }

      if (switched == 0) {
         return "Your echo isn't in your inventory (is it in flight?).";
      }

      this.offer = null;
      Fight next = new Fight(opponent, pending.opponentName(), pending.weaponKey(), current.id);
      this.fight = next;
      Location at = player.getLocation().add(0.0, 1.0, 0.0);
      ParticleManager.showcase(Particle.END_ROD, at, 20, 0.4, 0.6, 0.4, 0.08, null);
      this.plugin.getSoundManager().world(at, Sound.BLOCK_BEACON_POWER_SELECT, 1.0F, 1.9F);
      player.sendMessage(Component.text("✦ Your echo turns to " + pending.opponentName() + ": it is " + this.weaponName(pending.weaponKey()) + " now.", NamedTextColor.GOLD));
      opponent.sendMessage(Component.text("✦ " + this.manager.publicName(player) + " is the Chosen: their weapon has become your " + this.weaponName(pending.weaponKey()) + " for this fight.", NamedTextColor.GOLD));
      return null;
   }

   /** /chosen keep: stay with the current echo; no new offer for that player for a while. */
   public String keepEcho(Player player) {
      Offer pending = this.offer;
      if (!this.isChosen(player) || pending == null) {
         return "There's nothing to decide right now.";
      }

      this.offer = null;
      this.declined.put(pending.opponent(), System.currentTimeMillis() + DECLINE_MILLIS);
      HudManager.message(player, Component.text("You keep your echo.", NamedTextColor.GOLD));
      return null;
   }

   /** The fight is over: every echo the Chosen carries turns back. `quiet` skips the message. */
   private void endFight(Player player, boolean quiet) {
      Fight ended = this.fight;
      this.fight = null;
      this.offer = null;
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
            this.passFromLegendary(player);
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
