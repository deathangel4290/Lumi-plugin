package com.legendaryweapons.listeners;

import com.legendaryweapons.managers.ParticleManager;
import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.KillTrackedWeapon;
import com.legendaryweapons.managers.WeaponManager;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Particle.DustOptions;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import com.legendaryweapons.managers.HudManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class SwordOfEclipseListener implements Listener, KillTrackedWeapon {
   // Split swords are recognised by this PDC flag only. The old code also matched on the display
   // name containing "Sword", which any renamed item could spoof.
   private final LegendaryWeapons plugin;
   private final WeaponManager weaponManager;
   private final HashMap<UUID, Integer> killTracker = new HashMap<>();
   private final HashMap<UUID, Boolean> dualWielding = new HashMap<>();
   private final HashMap<UUID, ItemStack> offhandCache = new HashMap<>();
   private final HashMap<UUID, Integer> comboTracker = new HashMap<>();
   private final HashMap<UUID, BukkitRunnable> stageEffects = new HashMap<>();
   private final HashMap<UUID, BukkitRunnable> dualWieldEffects = new HashMap<>();
   private final HashMap<UUID, Boolean> hasSwordInHand = new HashMap<>();
   private final HashMap<UUID, Double> grantedHealth = new HashMap<>();
   private final HashMap<UUID, Integer> mainSlot = new HashMap<>();
   private final NamespacedKey splitSwordKey;
   private static final double DEFAULT_MAX_HEALTH = 20.0;
   private static final String DUAL_WIELD_COOLDOWN_KEY = "eclipse-dual-wield";

   @Override
   public String weaponKey() {
      return "sword-of-eclipse";
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      this.triggerAbility(player);
      return true;
   }

   @Override
   public void setKillCount(Player player, int kills) {
      this.setKills(player, kills);
   }

   @Override
   public int getKillCount(Player player) {
      return this.killTracker.getOrDefault(player.getUniqueId(), 0);
   }

   public SwordOfEclipseListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.weaponManager = plugin.getWeaponManager();
      this.splitSwordKey = new NamespacedKey(plugin, "eclipse-split-sword");
   }

   private boolean isSplitSword(ItemStack item) {
      return item != null && !item.getType().isAir() && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(this.splitSwordKey, PersistentDataType.BYTE);
   }

   private boolean isEclipse(ItemStack item) {
      return this.weaponManager.isLegendaryWeapon(item) && "sword-of-eclipse".equals(this.weaponManager.getWeaponKey(item));
   }

   /** The real sword, not one of the temporary Light/Dark halves. */
   private boolean isRealEclipse(ItemStack item) {
      return this.isEclipse(item) && !this.isSplitSword(item);
   }

   /** Main hand only. The sword has never done anything from the off-hand, and treating it as held there is what let it break. */
   private boolean isHoldingSwordOfEclipse(Player player) {
      return this.isEclipse(player.getInventory().getItemInMainHand());
   }

   @Override
   public Component hud(Player player) {
      ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("sword-of-eclipse");
      int kills = this.killTracker.getOrDefault(player.getUniqueId(), 0);
      int perStage = abilities == null ? 3 : Math.max(1, abilities.getInt("kills-per-stage", 3));
      int stage = Math.min(4, kills / perStage + 1);
      Component line = Component.text("Stage " + stage + "/4", NamedTextColor.LIGHT_PURPLE).append(Component.text("  Kills " + kills, NamedTextColor.GRAY));
      if (this.dualWielding.getOrDefault(player.getUniqueId(), false)) {
         return line.append(Component.text("  SPLIT", NamedTextColor.WHITE));
      } else if (kills < perStage * 3) {
         return line.append(Component.text("  Split at " + perStage * 3, NamedTextColor.DARK_GRAY));
      } else {
         return line.append(Component.text("  ")).append(HudManager.cooldown("Split", this.plugin.getCooldownManager().getRemainingSeconds(player, "eclipse-dual-wield")));
      }
   }

   @EventHandler
   public void onPlayerItemHeld(PlayerItemHeldEvent event) {
      Player player = event.getPlayer();
      this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.checkAndUpdateEffects(player));
   }

   // HIGH so the ability keybind (SWAP_HANDS, handled at NORMAL) gets to consume the key first.
   @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
   public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
      Player player = event.getPlayer();
      if (!this.dualWielding.getOrDefault(player.getUniqueId(), false) && this.isRealEclipse(event.getOffHandItem())) {
         event.setCancelled(true);
         HudManager.message(player, Component.text("The Sword of Eclipse must be wielded in your main hand.", NamedTextColor.LIGHT_PURPLE));
      } else {
         this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.checkAndUpdateEffects(player));
      }
   }

   /**
    * Split swords are full copies of the legendary. They must never leave the player's hands, or
    * one of them survives the split ending and the sword is duplicated.
    */
   @EventHandler(ignoreCancelled = true)
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getWhoClicked() instanceof Player player) {
         boolean splitInvolved = this.isSplitSword(event.getCurrentItem()) || this.isSplitSword(event.getCursor());
         if (!splitInvolved && event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
            splitInvolved = this.isSplitSword(player.getInventory().getItem(event.getHotbarButton()));
         }

         if (!splitInvolved && event.getClick() == ClickType.SWAP_OFFHAND) {
            splitInvolved = this.isSplitSword(player.getInventory().getItemInOffHand());
         }

         if (splitInvolved) {
            event.setCancelled(true);
            HudManager.message(player, Component.text("The split blades cannot be moved.", NamedTextColor.LIGHT_PURPLE));
         } else {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.guardOffhand(player));
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onInventoryDrag(InventoryDragEvent event) {
      if (event.getWhoClicked() instanceof Player player) {
         if (this.isSplitSword(event.getOldCursor())) {
            event.setCancelled(true);
         } else {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.guardOffhand(player));
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onDrop(PlayerDropItemEvent event) {
      if (this.isSplitSword(event.getItemDrop().getItemStack())) {
         event.setCancelled(true);
      }
   }

   /** Anything that still slips a split blade into the world (dispensers, plugins) is deleted. */
   @EventHandler(ignoreCancelled = true)
   public void onItemSpawn(ItemSpawnEvent event) {
      if (this.isSplitSword(event.getEntity().getItemStack())) {
         event.setCancelled(true);
      }
   }

   /** Catch-all for every other route into the off-hand slot: put the sword back in the inventory. */
   private void guardOffhand(Player player) {
      if (player.isOnline() && !this.dualWielding.getOrDefault(player.getUniqueId(), false)) {
         ItemStack off = player.getInventory().getItemInOffHand();
         if (this.isRealEclipse(off)) {
            player.getInventory().setItemInOffHand(null);
            player.getInventory().addItem(new ItemStack[]{off}).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            HudManager.message(player, Component.text("The Sword of Eclipse must be wielded in your main hand.", NamedTextColor.LIGHT_PURPLE));
         }

         this.checkAndUpdateEffects(player);
      }
   }

   /** Split blades left behind by a crash or restart mid-split become one real sword again. */
   private NamespacedKey killsKey() {
      return new NamespacedKey(this.plugin, "eclipse-kills");
   }

   /** Progress survives logging out; only dying resets it, as the lore says. */
   private void saveKills(Player player) {
      int kills = this.killTracker.getOrDefault(player.getUniqueId(), 0);
      if (kills > 0) {
         player.getPersistentDataContainer().set(this.killsKey(), PersistentDataType.INTEGER, kills);
      } else {
         player.getPersistentDataContainer().remove(this.killsKey());
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      Integer saved = player.getPersistentDataContainer().get(this.killsKey(), PersistentDataType.INTEGER);
      if (saved != null && saved > 0) {
         this.killTracker.put(player.getUniqueId(), saved);
      }

      if (this.stripSplitSwords(player) > 0) {
         player.getInventory().addItem(new ItemStack[]{this.weaponManager.getWeapon("sword-of-eclipse")}).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
      }

      this.guardOffhand(player);
   }

   private int stripSplitSwords(Player player) {
      int removed = 0;
      ItemStack[] contents = player.getInventory().getContents();

      for (int i = 0; i < contents.length; i++) {
         if (this.isSplitSword(contents[i])) {
            player.getInventory().setItem(i, null);
            removed++;
         }
      }

      if (this.isSplitSword(player.getItemOnCursor())) {
         player.setItemOnCursor(null);
         removed++;
      }

      return removed;
   }

   public void cleanup() {
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         if (this.dualWielding.getOrDefault(player.getUniqueId(), false)) {
            this.deactivateDualWield(player);
         }

         this.saveKills(player);
      }
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent event) {
      Player player = event.getPlayer();
      UUID uuid = player.getUniqueId();
      // End a split properly while the inventory can still be edited: the halves become the real
      // sword again and the cached off-hand item goes back. Skipping this lost the off-hand item.
      if (this.dualWielding.getOrDefault(uuid, false)) {
         this.deactivateDualWield(player);
      }

      this.saveKills(player);
      if (this.stageEffects.containsKey(uuid)) {
         this.stageEffects.get(uuid).cancel();
         this.stageEffects.remove(uuid);
      }

      if (this.dualWieldEffects.containsKey(uuid)) {
         this.dualWieldEffects.get(uuid).cancel();
         this.dualWieldEffects.remove(uuid);
      }

      this.revokeLightHearts(player);
      this.killTracker.remove(uuid);
      this.dualWielding.remove(uuid);
      this.offhandCache.remove(uuid);
      this.comboTracker.remove(uuid);
      this.plugin.getCooldownManager().clear(player, "eclipse-dual-wield");
      this.hasSwordInHand.remove(uuid);
   }

   private void checkAndUpdateEffects(Player player) {
      UUID uuid = player.getUniqueId();
      boolean currentlyHoldingSword = this.isHoldingSwordOfEclipse(player);
      boolean previouslyHoldingSword = this.hasSwordInHand.getOrDefault(uuid, false);
      if (currentlyHoldingSword != previouslyHoldingSword) {
         if (currentlyHoldingSword) {
            this.updateStageEffects(player);
            if (this.dualWielding.getOrDefault(uuid, false)) {
               this.startDualWieldEffects(player);
            }
         } else {
            if (this.stageEffects.containsKey(uuid)) {
               this.stageEffects.get(uuid).cancel();
               this.stageEffects.remove(uuid);
            }

            if (this.dualWieldEffects.containsKey(uuid)) {
               this.dualWieldEffects.get(uuid).cancel();
               this.dualWieldEffects.remove(uuid);
            }
         }

         this.hasSwordInHand.put(uuid, currentlyHoldingSword);
      }
   }

   public void setKills(Player player, int kills) {
      this.killTracker.put(player.getUniqueId(), kills);
      this.updateStageEffects(player);
   }

   public void resetCooldowns(Player player) {
      this.plugin.getCooldownManager().clear(player, "eclipse-dual-wield", player.getInventory().getItemInMainHand().getType());
   }

   public void triggerAbility(Player player) {
      UUID uuid = player.getUniqueId();
      if (this.dualWielding.getOrDefault(uuid, false)) {
         this.deactivateDualWield(player);
      } else {
         ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("sword-of-eclipse");
         int kills = this.killTracker.getOrDefault(uuid, 0);
         int requiredKills = abilities.getInt("kills-per-stage", 10) * 3;
         if (kills >= requiredKills) {
            long cooldownTime = abilities.getLong("dual-wield-cooldown", 1200L);
            if (!this.plugin.getCooldownManager().tryUseWithItemFeedback(player, "eclipse-dual-wield", cooldownTime)) {
               return;
            }

            this.activateDualWield(player);
         } else {
            player.sendMessage("§cYou need " + (requiredKills - kills) + " more kills to unlock dual wield mode.");
         }
      }
   }

   private void grantLightHearts(Player killer) {
      ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("sword-of-eclipse");
      double perKill = abilities != null ? abilities.getDouble("max-health-bonus", 2.0) : 2.0;
      double cap = abilities != null ? abilities.getDouble("max-health-bonus-cap", 10.0) : 10.0;
      UUID uuid = killer.getUniqueId();
      double alreadyGranted = this.grantedHealth.getOrDefault(uuid, 0.0);
      double headroom = cap - alreadyGranted;
      if (!(headroom <= 0.0)) {
         AttributeInstance attribute = killer.getAttribute(Attribute.MAX_HEALTH);
         if (attribute != null) {
            double granted = Math.min(perKill, headroom);
            attribute.setBaseValue(attribute.getBaseValue() + granted);
            this.grantedHealth.put(uuid, alreadyGranted + granted);
            this.createHealthBonusEffect(killer);
         }
      }
   }

   private void revokeLightHearts(Player player) {
      Double granted = this.grantedHealth.remove(player.getUniqueId());
      if (granted != null && !(granted <= 0.0)) {
         AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
         if (attribute != null) {
            attribute.setBaseValue(Math.max(20.0, attribute.getBaseValue() - granted));
         }
      }
   }

   private void fixDualWieldDrops(PlayerDeathEvent event) {
      if (event.getKeepInventory()) {
         // Nothing drops; deactivateDualWield() restores the sword and off-hand into the kept inventory.
         return;
      }

      UUID uuid = event.getEntity().getUniqueId();
      List<ItemStack> drops = event.getDrops();
      boolean droppedSplitSword = drops.removeIf(this::isSplitSword);
      ItemStack cachedOffhand = this.offhandCache.get(uuid);
      if (cachedOffhand != null && !cachedOffhand.getType().isAir()) {
         drops.add(cachedOffhand.clone());
      }

      if (droppedSplitSword) {
         drops.add(this.weaponManager.getWeapon("sword-of-eclipse"));
      }
   }

   @EventHandler
   public void onPlayerDeath(PlayerDeathEvent event) {
      Player killed = event.getEntity();
      Player killer = killed.getKiller();
      UUID killedUuid = killed.getUniqueId();
      killed.getPersistentDataContainer().remove(this.killsKey());
      if (this.killTracker.containsKey(killedUuid)) {
         this.killTracker.remove(killedUuid);
         if (this.stageEffects.containsKey(killedUuid)) {
            this.stageEffects.get(killedUuid).cancel();
            this.stageEffects.remove(killedUuid);
         }

         this.hasSwordInHand.remove(killedUuid);
         killed.sendMessage("§cYour progress with the Sword of Eclipse has been reset.");
      }

      this.revokeLightHearts(killed);
      if (this.dualWielding.containsKey(killedUuid)) {
         this.fixDualWieldDrops(event);
         this.deactivateDualWield(killed);
      }

      if (killer != null) {
         ItemStack weapon = killer.getInventory().getItemInMainHand();
         if (this.weaponManager.isLegendaryWeapon(weapon) && "sword-of-eclipse".equals(this.weaponManager.getWeaponKey(weapon))) {
            UUID killerUuid = killer.getUniqueId();
            int kills = this.killTracker.getOrDefault(killerUuid, 0) + 1;
            this.killTracker.put(killerUuid, kills);
            this.createSoulAbsorptionEffect(killed.getLocation(), killer, kills);
            killer.sendMessage("§dThe Sword of Eclipse absorbs the soul. Kills: " + kills);
            if (this.dualWielding.getOrDefault(killerUuid, false)) {
               this.grantLightHearts(killer);
            }

            if (this.isHoldingSwordOfEclipse(killer)) {
               this.updateStageEffects(killer);
               this.hasSwordInHand.put(killerUuid, true);
            }
         }
      }
   }

   @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)   // after trust/region checks
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player && event.getEntity() instanceof LivingEntity) {
         Player damager = (Player)event.getDamager();
         LivingEntity victim = (LivingEntity)event.getEntity();
         ItemStack weapon = damager.getInventory().getItemInMainHand();
         if (this.weaponManager.isLegendaryWeapon(weapon) && "sword-of-eclipse".equals(this.weaponManager.getWeaponKey(weapon))) {
            ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("sword-of-eclipse");
            if (abilities == null) {
               abilities = new org.bukkit.configuration.file.YamlConfiguration();
            }

            int kills = this.killTracker.getOrDefault(damager.getUniqueId(), 0);
            int killsPerStage = abilities.getInt("kills-per-stage", 10);
            int stage = Math.min(4, kills / killsPerStage + 1);
            double damageBonus = abilities.getDouble("stage-" + stage + "-damage-bonus", 0.0);
            event.setDamage(event.getDamage() + damageBonus);
            this.createAttackEffects(damager, victim, stage);
            double blindnessChance = abilities.getDouble("blindness-chance", 0.25);
            if (damager.getAttackCooldown() > 0.9F && Math.random() < blindnessChance && this.plugin.getProtectionManager().canAffect(damager, victim)) {
               victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, abilities.getInt("blindness-duration", 60), 0));
               this.createBlindnessEffect(victim.getLocation());
            }

            if (this.dualWielding.getOrDefault(damager.getUniqueId(), false)) {
               int combo = this.comboTracker.getOrDefault(damager.getUniqueId(), 0) + 1;
               this.comboTracker.put(damager.getUniqueId(), combo);
               this.createComboEffects(damager, victim.getLocation(), combo);
               ItemStack mainHand = damager.getInventory().getItemInMainHand();
               ItemStack offHand = damager.getInventory().getItemInOffHand();
               if (this.isSplitSword(mainHand) && this.isSplitSword(offHand)) {
                  damager.getInventory().setItemInMainHand(offHand);
                  damager.getInventory().setItemInOffHand(mainHand);
                  this.createSwordSwapEffect(damager);
               }
            }
         }
      }
   }

   private void updateStageEffects(final Player player) {
      final UUID uuid = player.getUniqueId();
      if (this.stageEffects.containsKey(uuid)) {
         this.stageEffects.get(uuid).cancel();
         this.stageEffects.remove(uuid);
      }

      if (!this.isHoldingSwordOfEclipse(player)) {
         this.hasSwordInHand.put(uuid, false);
      } else {
         this.hasSwordInHand.put(uuid, true);
         final int kills = this.killTracker.getOrDefault(uuid, 0);
         final int killsPerStage = this.plugin.getConfigManager().getWeaponAbilities("sword-of-eclipse").getInt("kills-per-stage", 10);
         final int stage = Math.min(4, kills / killsPerStage + 1);
         BukkitRunnable stageTask = new BukkitRunnable() {
            int ticks = 0;

            public void run() {
               if (player.isOnline() && SwordOfEclipseListener.this.isHoldingSwordOfEclipse(player)) {
                  if (!SwordOfEclipseListener.this.plugin.getParticleManager().passiveEnabled()) {
                     this.ticks++;
                     return;
                  }

                  Location playerLoc = player.getLocation();
                  World world = player.getWorld();
                  Color stageColor = SwordOfEclipseListener.this.getStageColor(stage);
                  SwordOfEclipseListener.this.createStageAura(playerLoc, stageColor, stage);
                  if (this.ticks % 20 == 0) {
                     double progress = (double)(kills % killsPerStage) / killsPerStage;
                     SwordOfEclipseListener.this.createProgressRing(playerLoc, progress, stageColor);
                  }

                  switch (stage) {
                     case 2:
                        if (this.ticks % 15 == 0) {
                           ParticleManager.spawn(Particle.END_ROD, playerLoc, 2, 0.5, 0.5, 0.5, 0.05);
                        }
                        break;
                     case 3:
                        if (this.ticks % 10 == 0) {
                           SwordOfEclipseListener.this.createCosmicOrbits(playerLoc);
                        }
                        break;
                     case 4:
                        if (this.ticks % 5 == 0) {
                           SwordOfEclipseListener.this.createEclipseAura(playerLoc);
                        }
                  }

                  this.ticks++;
               } else {
                  this.cancel();
                  SwordOfEclipseListener.this.stageEffects.remove(uuid);
                  SwordOfEclipseListener.this.hasSwordInHand.put(uuid, false);
               }
            }
         };
         stageTask.runTaskTimer(this.plugin, 1L, 1L);
         this.stageEffects.put(uuid, stageTask);
      }
   }

   private void createStageAura(Location center, Color color, int stage) {
      World world = center.getWorld();
      double radius = 0.8 + stage * 0.3;

      for (int i = 0; i < 8; i++) {
         double angle = System.currentTimeMillis() / 100.0 % 360.0 + i * 45;
         double radians = Math.toRadians(angle);
         Vector point = new Vector(Math.cos(radians) * radius, Math.sin(radians) * radius * 0.5, Math.sin(radians) * radius);
         Location auraPoint = center.clone().add(point);
         ParticleManager.spawn(Particle.DUST, auraPoint, 1, 0.0, 0.0, 0.0, 0.0, new DustOptions(color, 1.5F));
         if (stage >= 3) {
            ParticleManager.spawn(Particle.END_ROD, auraPoint, 1, 0.05, 0.05, 0.05, 0.02);
         }
      }
   }

   private void createProgressRing(Location center, double progress, Color color) {
      World world = center.getWorld();

      for (int i = 0; i < 16; i++) {
         if (i / 16.0 <= progress) {
            double angle = i * 3.141592653589793 / 8.0;
            double radius = 1.5;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location ringPoint = center.clone().add(x, 0.1, z);
            ParticleManager.spawn(Particle.DUST, ringPoint, 1, 0.0, 0.0, 0.0, 0.0, new DustOptions(color, 1.0F));
         }
      }
   }

   private void createCosmicOrbits(Location center) {
      World world = center.getWorld();

      for (int i = 0; i < 3; i++) {
         double time = System.currentTimeMillis() / 500.0 + i * 3.141592653589793 * 2.0 / 3.0;
         double radius = 1.2 + i * 0.4;

         for (int j = 0; j < 4; j++) {
            double angle = time + j * 3.141592653589793 / 2.0;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location orbitPoint = center.clone().add(x, 1.0, z);
            ParticleManager.spawn(Particle.END_ROD, orbitPoint, 1, 0.0, 0.0, 0.0, 0.05);
            ParticleManager.spawn(Particle.END_ROD, orbitPoint, 1, 0.0, 0.0, 0.0, 0.0);
         }
      }
   }

   private void createEclipseAura(Location center) {
      World world = center.getWorld();

      for (int i = 0; i < 2; i++) {
         double time = System.currentTimeMillis() / 300.0 + i * 3.141592653589793;
         double radius = 1.5;

         for (int j = 0; j < 8; j++) {
            double angle = time + j * 3.141592653589793 / 4.0;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location eclipsePoint = center.clone().add(x, 0.8, z);
            Color particleColor = i == 0 ? Color.WHITE : Color.BLACK;
            ParticleManager.spawn(Particle.DUST, eclipsePoint, 1, 0.0, 0.0, 0.0, 0.0, new DustOptions(particleColor, 2.0F));
         }
      }
   }

   private void createSoulAbsorptionEffect(final Location deathLoc, Player killer, int kills) {
      World world = deathLoc.getWorld();

      for (int i = 0; i < 2; i++) {
         final int layer = i;
         (new BukkitRunnable() {
            public void run() {
               double radius = 3.0 - layer * 0.7;
               SwordOfEclipseListener.this.createCosmicVortex(deathLoc, radius);
            }
         }).runTaskLater(this.plugin, layer * 2L);
      }

      this.createCosmicSoulStream(deathLoc, killer.getLocation());
      this.createKillCountEffect(killer.getLocation(), kills);
      int killsPerStage = this.plugin.getConfigManager().getWeaponAbilities("sword-of-eclipse").getInt("kills-per-stage", 10);
      if (kills % killsPerStage == 0) {
         this.createStageUpEffect(killer.getLocation(), kills / killsPerStage + 1);
      }

      this.plugin.getSoundManager().world(deathLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0F, 0.8F);
   }

   private void createCosmicVortex(Location center, double radius) {
      World world = center.getWorld();

      for (int i = 0; i < 12; i++) {
         double angle = System.currentTimeMillis() / 50.0 % 360.0 + i * 30;
         double radians = Math.toRadians(angle);
         double x = Math.cos(radians) * radius;
         double z = Math.sin(radians) * radius;
         Location vortexPoint = center.clone().add(x, 0.5, z);
         ParticleManager.spawn(Particle.SOUL, vortexPoint, 1, 0.0, 0.1, 0.0, 0.1);
         ParticleManager.spawn(Particle.END_ROD, vortexPoint, 1, 0.05, 0.05, 0.05, 0.05);
         ParticleManager.spawn(Particle.DUST, vortexPoint, 1, 0.1, 0.1, 0.1, 0.0, new DustOptions(Color.fromRGB(138, 43, 226), 1.2F));
      }
   }

   private void createCosmicSoulStream(Location start, Location end) {
      World world = start.getWorld();
      Vector direction = end.toVector().subtract(start.toVector());
      double distance = start.distance(end);
      int steps = (int)(distance * 3.0);

      for (int i = 0; i <= steps; i++) {
         double ratio = (double)i / steps;
         Location point = start.clone().add(direction.clone().multiply(ratio));
         ParticleManager.spawn(Particle.SOUL, point, 1, 0.1, 0.1, 0.1, 0.05);
         Color streamColor = i % 2 == 0 ? Color.WHITE : Color.BLACK;
         ParticleManager.spawn(Particle.DUST, point, 1, 0.05, 0.05, 0.05, 0.0, new DustOptions(streamColor, 1.5F));
         if (i % 3 == 0) {
            ParticleManager.spawn(Particle.END_ROD, point, 1, 0.05, 0.05, 0.05, 0.02);
         }
      }
   }

   private void createKillCountEffect(Location center, int kills) {
      World world = center.getWorld();

      for (int i = 0; i < Math.min(kills, 10); i++) {
         double angle = i * 3.141592653589793 / 5.0;
         double radius = 1.0;
         double x = Math.cos(angle) * radius;
         double z = Math.sin(angle) * radius;
         Location killPoint = center.clone().add(x, 2.0, z);
         ParticleManager.spawn(Particle.SQUID_INK, killPoint, 1, 0.1, 0.1, 0.1, 0.02);
      }
   }

   private void createStageUpEffect(final Location center, final int stage) {
      World world = center.getWorld();
      ParticleManager.spawn(Particle.END_ROD, center.clone().add(0.0, 1.0, 0.0), 16, 0.6, 0.8, 0.6, 0.05);

      for (int i = 0; i < 3; i++) {
         final int ring = i;
         (new BukkitRunnable() {
            public void run() {
               double radius = 2.0 + ring * 1.0;
               SwordOfEclipseListener.this.createStageRing(center, radius, stage);
            }
         }).runTaskLater(this.plugin, ring * 5L);
      }


      this.plugin.getSoundManager().world(center, Sound.BLOCK_BEACON_ACTIVATE, 0.9F, 1.2F);
   }

   private void createStageRing(Location center, double radius, int stage) {
      World world = center.getWorld();
      Color stageColor = this.getStageColor(stage);

      for (int i = 0; i < 16; i++) {
         double angle = i * 3.141592653589793 / 8.0;
         double x = Math.cos(angle) * radius;
         double z = Math.sin(angle) * radius;
         Location ringPoint = center.clone().add(x, 1.0, z);
         ParticleManager.spawn(Particle.END_ROD, ringPoint, 3, 0.2, 0.2, 0.2, 0.1);
         ParticleManager.spawn(Particle.DUST, ringPoint, 2, 0.1, 0.1, 0.1, 0.0, new DustOptions(stageColor, 2.0F));
      }
   }

   private void createAttackEffects(Player damager, LivingEntity victim, int stage) {
      World world = damager.getWorld();
      Location damagerLoc = damager.getLocation();
      Location victimLoc = victim.getLocation();
      this.createCosmicBeam(damagerLoc, victimLoc, stage);
      ParticleManager.spawn(Particle.SWEEP_ATTACK, victimLoc, 5, 0.5, 0.5, 0.5, 0.1);
      Color impactColor = this.getStageColor(stage);
      ParticleManager.spawn(Particle.DUST, victimLoc, 10, 0.3, 0.3, 0.3, 0.0, new DustOptions(impactColor, 1.5F));
      this.plugin.getSoundManager().hit(victimLoc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.6F, 1.2F);
      if (stage >= 3) {
         this.plugin.getSoundManager().hit(victimLoc, Sound.BLOCK_BEACON_POWER_SELECT, 0.4F, 1.5F);
      }
   }

   private void createCosmicBeam(Location start, Location end, int stage) {
      World world = start.getWorld();
      Vector direction = end.toVector().subtract(start.toVector());
      double distance = start.distance(end);
      int steps = (int)(distance * 3.0);

      for (int i = 0; i <= steps; i++) {
         double ratio = (double)i / steps;
         Location point = start.clone().add(direction.clone().multiply(ratio));
         Color beamColor = this.getStageColor(stage);
         ParticleManager.spawn(Particle.DUST, point, 1, 0.05, 0.05, 0.05, 0.0, new DustOptions(beamColor, 1.2F));
         if (i % 2 == 0) {
            ParticleManager.spawn(Particle.END_ROD, point, 1, 0.05, 0.05, 0.05, 0.03);
         }

         if (stage >= 3 && i % 4 == 0) {
            ParticleManager.spawn(Particle.END_ROD, point, 1, 0.05, 0.05, 0.05, 0.0);
         }
      }
   }

   private void createBlindnessEffect(Location location) {
      World world = location.getWorld();
      ParticleManager.spawn(Particle.SQUID_INK, location.clone().add(0.0, 0.3, 0.0), 6, 0.5, 0.3, 0.5, 0.02);
      ParticleManager.spawn(Particle.LARGE_SMOKE, location, 10, 0.8, 0.8, 0.8, 0.05);
      this.plugin.getSoundManager().world(location, Sound.ENTITY_WITHER_SHOOT, 0.6F, 1.5F);
   }

   private void createComboEffects(Player player, Location victimLoc, int combo) {
      World world = player.getWorld();
      Location playerLoc = player.getLocation();
      Location comboLoc = playerLoc.clone().add(0.0, 2.2, 0.0);
      
      if (combo >= 3) {
         this.createComboBurst(playerLoc, combo);
      }

      this.createSwordTrail(playerLoc, victimLoc, combo);
   }

   private void createComboBurst(Location center, int combo) {
      World world = center.getWorld();

      for (int i = 0; i < combo; i++) {
         double angle = i * (6.283185307179586 / combo);
         double distance = 1.0 + combo * 0.2;
         double x = Math.cos(angle) * distance;
         double z = Math.sin(angle) * distance;
         Location burstPoint = center.clone().add(x, 0.5, z);
         ParticleManager.spawn(Particle.END_ROD, burstPoint, 2, 0.1, 0.1, 0.1, 0.1);
      }
   }

   private void createSwordTrail(Location start, Location end, int combo) {
      World world = start.getWorld();
      Vector direction = end.toVector().subtract(start.toVector());
      double distance = start.distance(end);
      int steps = (int)(distance * 2.0);

      for (int i = 0; i <= steps; i++) {
         double ratio = (double)i / steps;
         Location point = start.clone().add(direction.clone().multiply(ratio));
         Color trailColor = combo % 2 == 0 ? Color.WHITE : Color.BLACK;
         ParticleManager.spawn(Particle.DUST, point, 1, 0.1, 0.1, 0.1, 0.0, new DustOptions(trailColor, 1.0F));
      }
   }

   private void createSwordSwapEffect(Player player) {
      World world = player.getWorld();
      Location playerLoc = player.getLocation();

      for (int i = 0; i < 8; i++) {
         double angle = i * 3.141592653589793 / 4.0;
         double distance = 1.0;
         double x = Math.cos(angle) * distance;
         double z = Math.sin(angle) * distance;
         Location wavePoint = playerLoc.clone().add(x, 0.5, z);
         ParticleManager.spawn(Particle.SWEEP_ATTACK, wavePoint, 1, 0.0, 0.0, 0.0, 0.0);
      }

      this.plugin.getSoundManager().self(player, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.8F, 1.5F);
   }

   private void createHealthBonusEffect(Player player) {
      final World world = player.getWorld();
      final Location playerLoc = player.getLocation();

      for (int i = 0; i < 3; i++) {
         final int ring = i;
         (new BukkitRunnable() {
            public void run() {
               double radius = 1.0 + ring * 0.5;

               for (int j = 0; j < 12; j++) {
                  double angle = j * 3.141592653589793 / 6.0;
                  double x = Math.cos(angle) * radius;
                  double z = Math.sin(angle) * radius;
                  Location healPoint = playerLoc.clone().add(x, 0.5, z);
                  ParticleManager.spawn(Particle.END_ROD, healPoint, 1, 0.05, 0.1, 0.05, 0.01);
               }
            }
         }).runTaskLater(this.plugin, ring * 3L);
      }
   }

   private void startDualWieldEffects(final Player player) {
      final UUID uuid = player.getUniqueId();
      if (this.dualWieldEffects.containsKey(uuid)) {
         this.dualWieldEffects.get(uuid).cancel();
         this.dualWieldEffects.remove(uuid);
      }

      if (this.dualWielding.getOrDefault(uuid, false)) {
         BukkitRunnable dualWieldTask = new BukkitRunnable() {
            int ticks = 0;

            public void run() {
               if (player.isOnline()
                  && SwordOfEclipseListener.this.dualWielding.getOrDefault(uuid, false)
                  && SwordOfEclipseListener.this.isHoldingSwordOfEclipse(player)) {
                  if (!SwordOfEclipseListener.this.plugin.getParticleManager().passiveEnabled()) {
                     this.ticks++;
                     return;
                  }

                  Location playerLoc = player.getLocation();
                  World world = player.getWorld();

                  for (int i = 0; i < 2; i++) {
                     double time = System.currentTimeMillis() / 200.0 + i * 3.141592653589793;
                     double radius = 1.2;

                     for (int j = 0; j < 6; j++) {
                        double angle = time + j * 3.141592653589793 / 3.0;
                        double x = Math.cos(angle) * radius;
                        double z = Math.sin(angle) * radius;
                        Location auraPoint = playerLoc.clone().add(x, 0.8, z);
                        Color auraColor = i == 0 ? Color.WHITE : Color.BLACK;
                        ParticleManager.spawn(Particle.DUST, auraPoint, 1, 0.0, 0.0, 0.0, 0.0, new DustOptions(auraColor, 1.8F));
                     }
                  }

                  if (this.ticks % 10 == 0) {
                     SwordOfEclipseListener.this.createEclipseSymbol(playerLoc.clone().add(0.0, 2.5, 0.0));
                  }

                  this.ticks++;
               } else {
                  this.cancel();
                  SwordOfEclipseListener.this.dualWieldEffects.remove(uuid);
               }
            }
         };
         dualWieldTask.runTaskTimer(this.plugin, 1L, 1L);
         this.dualWieldEffects.put(uuid, dualWieldTask);
      }
   }

   private void activateDualWield(final Player player) {
      final UUID uuid = player.getUniqueId();
      this.dualWielding.put(uuid, true);
      com.legendaryweapons.managers.AnimationFx.ring(player.getLocation(), Material.WHITE_CONCRETE, 0.4, 2.4, 10, true);
      com.legendaryweapons.managers.AnimationFx.ring(player.getLocation(), Material.BLACK_CONCRETE, 2.4, 0.4, 10, false);
      this.mainSlot.put(uuid, player.getInventory().getHeldItemSlot());
      ItemStack previousOffhand = player.getInventory().getItemInOffHand();
      this.offhandCache.put(uuid, previousOffhand == null || previousOffhand.getType().isAir() ? null : previousOffhand.clone());
      ItemStack lightSword = this.weaponManager.getWeapon("sword-of-eclipse").clone();
      ItemMeta lightMeta = lightSword.getItemMeta();
      lightMeta.setDisplayName("§f§lLight Sword");
      lightMeta.getPersistentDataContainer().set(this.splitSwordKey, PersistentDataType.BYTE, (byte)1);
      lightSword.setItemMeta(lightMeta);
      ItemStack darkSword = this.weaponManager.getWeapon("sword-of-eclipse").clone();
      ItemMeta darkMeta = darkSword.getItemMeta();
      darkMeta.setDisplayName("§8§lDark Sword");
      darkMeta.getPersistentDataContainer().set(this.splitSwordKey, PersistentDataType.BYTE, (byte)1);
      darkSword.setItemMeta(darkMeta);
      player.getInventory().setItemInMainHand(lightSword);
      player.getInventory().setItemInOffHand(darkSword);
      this.comboTracker.put(uuid, 0);
      this.createDualWieldActivation(player.getLocation());
      ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("sword-of-eclipse");
      long duration = abilities.getLong("dual-wield-duration", 200L);
      this.startDualWieldEffects(player);
      (new BukkitRunnable() {
         public void run() {
            if (SwordOfEclipseListener.this.dualWielding.getOrDefault(uuid, false)) {
               SwordOfEclipseListener.this.deactivateDualWield(player);
            }
         }
      }).runTaskLater(this.plugin, duration);
   }

   private void deactivateDualWield(Player player) {
      UUID uuid = player.getUniqueId();
      if (this.dualWielding.getOrDefault(uuid, false)) {
         this.dualWielding.remove(uuid);
         if (this.dualWieldEffects.containsKey(uuid)) {
            this.dualWieldEffects.get(uuid).cancel();
            this.dualWieldEffects.remove(uuid);
         }

         // Remove BOTH halves wherever they ended up, then give back exactly one real sword and the
         // old off-hand item -- into their old slots when those are free, never over another item.
         this.stripSplitSwords(player);
         ItemStack sword = this.weaponManager.getWeapon("sword-of-eclipse");
         Integer slot = this.mainSlot.remove(uuid);
         ItemStack inSlot = slot == null ? null : player.getInventory().getItem(slot);
         if (slot != null && (inSlot == null || inSlot.getType().isAir())) {
            player.getInventory().setItem(slot, sword);
         } else {
            player.getInventory().addItem(new ItemStack[]{sword}).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
         }

         ItemStack cached = this.offhandCache.remove(uuid);
         if (cached != null) {
            ItemStack off = player.getInventory().getItemInOffHand();
            if (off == null || off.getType().isAir()) {
               player.getInventory().setItemInOffHand(cached);
            } else {
               player.getInventory().addItem(new ItemStack[]{cached}).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
         }

         this.comboTracker.remove(uuid);
         this.createDualWieldDeactivation(player.getLocation());
         if (this.isHoldingSwordOfEclipse(player)) {
            this.updateStageEffects(player);
         }
      }
   }

   private void createDualWieldActivation(Location center) {
      World world = center.getWorld();
      

      for (int i = 0; i < 2; i++) {
         Color explosionColor = i == 0 ? Color.WHITE : Color.BLACK;

         for (int j = 0; j < 20; j++) {
            Vector dir = new Vector((Math.random() - 0.5) * 2.0, (Math.random() - 0.5) * 2.0, (Math.random() - 0.5) * 2.0).normalize().multiply(2.0);
            Location explosionPoint = center.clone().add(dir);
            ParticleManager.spawn(Particle.DUST, explosionPoint, 1, 0.1, 0.1, 0.1, 0.0, new DustOptions(explosionColor, 2.0F));
         }
      }

      this.plugin.getSoundManager().world(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.9F, 1.3F);
   }

   private void createEclipseSymbol(Location center) {
      World world = center.getWorld();

      for (int i = 0; i < 12; i++) {
         double angle = i * 3.141592653589793 / 6.0;
         double radius = 0.8;
         double x = Math.cos(angle) * radius;
         double z = Math.sin(angle) * radius;
         Location symbolPoint = center.clone().add(x, 0.0, z);
         ParticleManager.spawn(Particle.END_ROD, symbolPoint, 1, 0.0, 0.0, 0.0, 0.0);
      }

      ParticleManager.spawn(Particle.DUST, center, 3, 0.2, 0.2, 0.2, 0.0, new DustOptions(Color.BLACK, 2.0F));
   }

   private void createDualWieldDeactivation(final Location center) {
      final World world = center.getWorld();

      for (int i = 0; i < 3; i++) {
         final int layer = i;
         (new BukkitRunnable() {
            public void run() {
               double radius = 2.0 - layer * 0.7;

               for (int j = 0; j < 12; j++) {
                  double angle = j * 3.141592653589793 / 6.0;
                  double x = Math.cos(angle) * radius;
                  double z = Math.sin(angle) * radius;
                  Location implosionPoint = center.clone().add(x, 0.5, z);
                  Vector direction = center.toVector().subtract(implosionPoint.toVector()).normalize().multiply(0.3);
                  ParticleManager.spawn(Particle.LARGE_SMOKE, implosionPoint, 1, direction.getX(), direction.getY(), direction.getZ(), 0.05);
               }
            }
         }).runTaskLater(this.plugin, layer * 2L);
      }

      this.plugin.getSoundManager().world(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.7F, 1.2F);
   }

   private Color getStageColor(int stage) {
      return switch (stage) {
         case 1 -> Color.GRAY;
         case 2 -> Color.BLUE;
         case 3 -> Color.PURPLE;
         case 4 -> Color.fromRGB(255, 215, 0);
         default -> Color.WHITE;
      };
   }
}
