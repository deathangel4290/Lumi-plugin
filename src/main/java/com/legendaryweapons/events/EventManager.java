package com.legendaryweapons.events;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.items.CustomItems;
import com.legendaryweapons.managers.ProtectionManager;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Events: Bounty Hunt, King of the Hill, Supply Drops, Most Wanted, Fallen Star, Blood Moon and
 * Outposts, tied together by Season Renown, the reward chest (/rewards) and carry objectives
 * (Boss Heart / Star Core). The Event Director (director.*) starts events on a schedule; admins
 * can still run any of them with /lwevent. State that has to survive a restart (the hill centre,
 * a pending/active Most Wanted, the Director's plan, a crater to heal, rewards owed to offline
 * winners) lives in events.yml. Running bounty hunts and KotH rounds end on shutdown.
 */
public class EventManager implements Listener {
   private final LegendaryWeapons plugin;
   private final Loot loot;
   private final BountyEvent bounty;
   private final KothEvent koth;
   private final SupplyDrops supplyDrops;
   private final MostWantedEvent mostWanted;
   private final RewardVault vault;
   private final SeasonManager season;
   private final RelicManager relics;
   private final FallenStarEvent fallenStar;
   private final BloodMoonEvent bloodMoon;
   private final OutpostManager outposts;
   private final TokenShop tokenShop;
   private final EventDirector director;
   private final File file;
   private final YamlConfiguration data;

   public EventManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.loot = new Loot(plugin);
      this.bounty = new BountyEvent(plugin, this);
      this.koth = new KothEvent(plugin, this);
      this.supplyDrops = new SupplyDrops(plugin, this);
      this.mostWanted = new MostWantedEvent(plugin, this);
      this.vault = new RewardVault(plugin);
      this.season = new SeasonManager(plugin, this);
      this.relics = new RelicManager(plugin, this);
      this.fallenStar = new FallenStarEvent(plugin, this);
      this.bloodMoon = new BloodMoonEvent(plugin, this);
      this.outposts = new OutpostManager(plugin, this);
      this.tokenShop = new TokenShop(plugin);
      this.director = new EventDirector(plugin, this);
      this.file = new File(plugin.getDataFolder(), "events.yml");
      this.data = YamlConfiguration.loadConfiguration(this.file);
      this.mostWanted.load(this.data.getConfigurationSection("most-wanted"));
      this.director.load(this.data.getConfigurationSection("director"));
      this.fallenStar.recover(this.data.getConfigurationSection("fallen-star"));
      String hill = this.data.getString("koth.center");
      if (hill != null) {
         String[] parts = hill.split(";");
         World world = parts.length == 4 ? Bukkit.getWorld(parts[0]) : null;
         if (world != null) {
            this.koth.setCenterSilently(new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
         }
      }

      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      plugin.getHudManager().register(this.bounty::hud);
   }

   public Loot loot() {
      return this.loot;
   }

   public BountyEvent bounty() {
      return this.bounty;
   }

   public KothEvent koth() {
      return this.koth;
   }

   public SupplyDrops supplyDrops() {
      return this.supplyDrops;
   }

   public MostWantedEvent mostWanted() {
      return this.mostWanted;
   }

   public RewardVault vault() {
      return this.vault;
   }

   public SeasonManager season() {
      return this.season;
   }

   public RelicManager relics() {
      return this.relics;
   }

   public FallenStarEvent fallenStar() {
      return this.fallenStar;
   }

   public BloodMoonEvent bloodMoon() {
      return this.bloodMoon;
   }

   public OutpostManager outposts() {
      return this.outposts;
   }

   public TokenShop tokenShop() {
      return this.tokenShop;
   }

   public EventDirector director() {
      return this.director;
   }

   public boolean isMostWanted(Player player) {
      return this.mostWanted.isWanted(player);
   }

   /** The name other players should see: the disguise if there is one, never the hidden real name. */
   public String publicName(Player player) {
      String fake = this.plugin.getDisguiseManager().fakeNameOf(player);
      return fake != null ? fake : player.getName();
   }

   public void save() {
      Location hill = this.koth.getCenter();
      this.data.set("koth.center", hill == null || hill.getWorld() == null ? null : hill.getWorld().getName() + ";" + hill.getBlockX() + ";" + hill.getBlockY() + ";" + hill.getBlockZ());
      ConfigurationSection mw = this.data.getConfigurationSection("most-wanted");
      this.mostWanted.save(mw != null ? mw : this.data.createSection("most-wanted"));
      if (this.director != null) {
         ConfigurationSection dir = this.data.getConfigurationSection("director");
         this.director.save(dir != null ? dir : this.data.createSection("director"));
      }

      if (this.fallenStar != null) {
         ConfigurationSection star = this.data.getConfigurationSection("fallen-star");
         this.fallenStar.save(star != null ? star : this.data.createSection("fallen-star"));
      }

      try {
         this.file.getParentFile().mkdirs();
         this.data.save(this.file);
      } catch (IOException var4) {
         this.plugin.getLogger().severe("Could not save events.yml: " + var4.getMessage());
      }
   }

   /** Winners who were offline when an event ended get their prize the next time they join. */
   public void queueReward(UUID player, List<String> rewards) {
      List<String> owed = new ArrayList<>(this.data.getStringList("pending-rewards." + player));
      owed.addAll(rewards);
      this.data.set("pending-rewards." + player, owed);
      this.save();
   }

   public void shutdown() {
      this.bounty.stop(false);
      this.koth.shutdown();
      this.mostWanted.shutdown();
      this.relics.shutdown();
      this.fallenStar.shutdown();
      this.bloodMoon.stop();
      this.outposts.shutdown();
      this.season.save();
      this.save();
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      if (this.mostWanted.isWanted(player)) {
         event.joinMessage(null);
         this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline()) {
               this.mostWanted.onWantedJoined(player);
            }
         });
      }

      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         if (player.isOnline()) {
            this.bounty.onJoin(player);
            this.koth.onJoin(player);
            this.mostWanted.onJoinAny(player);
            this.relics.onJoin(player);
            this.bloodMoon.onJoin(player);
            this.vault.remind(player);
            List<String> owed = this.data.getStringList("pending-rewards." + player.getUniqueId());
            if (!owed.isEmpty()) {
               this.data.set("pending-rewards." + player.getUniqueId(), null);
               this.save();
               this.loot.give(player, owed);
               player.sendMessage(Component.text("You were given the event prizes you won while offline.", NamedTextColor.GOLD));
            }
         }
      }, 20L);
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onQuit(PlayerQuitEvent event) {
      if (this.mostWanted.isWanted(event.getPlayer())) {
         event.quitMessage(null);
         this.mostWanted.onQuit(event.getPlayer());
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onDeath(PlayerDeathEvent event) {
      Player victim = event.getEntity();
      Player killer = victim.getKiller();
      this.bounty.onKill(victim, killer);
      this.mostWanted.onDeath(victim, killer);
      this.koth.onKill(victim, killer);
      this.season.onDeath(victim, killer);
      this.bloodMoon.onPlayerDeath(victim, killer);
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onPlace(BlockPlaceEvent event) {
      if (CustomItems.HILL_MARKER.equals(CustomItems.get().idOf(event.getItemInHand()))) {
         if (!event.getPlayer().hasPermission("legendaryweapons.admin")) {
            event.setCancelled(true);
         } else {
            this.koth.setCenter(event.getBlock().getLocation());
            event.getPlayer().sendMessage(Component.text("King of the Hill centre set here. Start it with /lwevent koth start [minutes].", NamedTextColor.GOLD));
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onBreak(BlockBreakEvent event) {
      Location hill = this.koth.getCenter();
      if (!ProtectionManager.isProbing() && hill != null && hill.equals(event.getBlock().getLocation()) && !this.koth.isRunning()) {
         this.koth.setCenter(null);
         event.getPlayer().sendMessage(Component.text("King of the Hill centre cleared.", NamedTextColor.GRAY));
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onOpen(InventoryOpenEvent event) {
      if (event.getPlayer() instanceof Player player) {
         this.supplyDrops.onOpen(player, event.getInventory().getLocation());
      }
   }
}
