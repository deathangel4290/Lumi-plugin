package com.lumiweapons;

import com.lumiweapons.abilities.AbilityDispatcher;
import com.lumiweapons.commands.TrustCommand;
import com.lumiweapons.events.EventManager;
import com.lumiweapons.items.CustomItems;
import com.lumiweapons.listeners.AbilityListener;
import com.lumiweapons.listeners.AxeOfZeusListener;
import com.lumiweapons.listeners.BladeOfDiminutionListener;
import com.lumiweapons.listeners.ChionesBladeListener;
import com.lumiweapons.listeners.EnderGlaiveListener;
import com.lumiweapons.listeners.HellstormCrossbowListener;
import com.lumiweapons.listeners.HermesBladeListener;
import com.lumiweapons.listeners.LostTridentListener;
import com.lumiweapons.listeners.LuminosityListener;
import com.lumiweapons.listeners.PlayerStateListener;
import com.lumiweapons.listeners.RitualListener;
import com.lumiweapons.listeners.ScytheOfDiabloListener;
import com.lumiweapons.listeners.SwordOfEclipseListener;
import com.lumiweapons.listeners.TetherChainListener;
import com.lumiweapons.listeners.TitansAxeListener;
import com.lumiweapons.listeners.TrustListener;
import com.lumiweapons.listeners.VeilOfMimicryListener;
import com.lumiweapons.listeners.WeaponProtectionListener;
import com.lumiweapons.managers.ChargeMeter;
import com.lumiweapons.managers.ChargePool;
import com.lumiweapons.managers.ComboTracker;
import com.lumiweapons.managers.CommandManager;
import com.lumiweapons.managers.ConfigManager;
import com.lumiweapons.managers.CooldownManager;
import com.lumiweapons.managers.DashTracker;
import com.lumiweapons.managers.DisguiseManager;
import com.lumiweapons.managers.GUIManager;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.KeybindManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.PlayerRosterManager;
import com.lumiweapons.managers.ProtectionManager;
import com.lumiweapons.managers.RitualManager;
import com.lumiweapons.managers.SoundManager;
import com.lumiweapons.managers.TrustManager;
import com.lumiweapons.managers.TutorialManager;
import com.lumiweapons.managers.WeaponManager;
import com.lumiweapons.rituals.RitualRecipes;
import org.bukkit.Material;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * LumiWeapons (formerly LegendaryWeapons).
 *
 * The rename keeps two things from the old name so existing servers carry on untouched:
 *  - the data folder: plugins/LegendaryWeapons is moved to plugins/LumiWeapons on first start;
 *  - the namespace of every tag the plugin writes on items, entities and blocks ("legendaryweapons"),
 *    so weapons, altars and traders made before the rename are still recognised. Use {@link #key}.
 */
public class LumiWeapons extends JavaPlugin {
   /** The namespace of the plugin's item, entity and block tags (the plugin's old name). Never change it. */
   public static final String TAG_NAMESPACE = "legendaryweapons";
   private static final String OLD_NAME = "LegendaryWeapons";
   private LostTridentListener lostTridentListener;
   private AxeOfZeusListener axeOfZeusListener;
   private EnderGlaiveListener enderGlaiveListener;
   private HermesBladeListener hermesBladeListener;
   private ConfigManager configManager;
   private com.lumiweapons.listeners.TalariaListener talariaListener;
   private com.lumiweapons.listeners.OculusListener oculusListener;
   private com.lumiweapons.listeners.HorizonListener horizonListener;
   private com.lumiweapons.managers.ConfigCheck configCheck;
   private WeaponManager weaponManager;
   private RitualManager ritualManager;
   private RitualRecipes ritualRecipes;
   private KeybindManager keybindManager;
   private TutorialManager tutorialManager;
   private GUIManager guiManager;
   private TrustManager trustManager;
   private CooldownManager cooldownManager;
   private SoundManager soundManager;
   private ParticleManager particleManager;
   private HudManager hudManager;
   private ProtectionManager protectionManager;
   private ChargePool chargePool;
   private ChargeMeter chargeMeter;
   private ComboTracker comboTracker;
   private DashTracker dashTracker;
   private PlayerRosterManager playerRosterManager;
   private DisguiseManager disguiseManager;
   private com.lumiweapons.teams.TeamManager teamManager;
   private CustomItems customItems;
   private EventManager eventManager;
   private com.lumiweapons.bosses.BossManager bossManager;
   private AbilityDispatcher abilityDispatcher;
   private SwordOfEclipseListener swordOfEclipseListener;
   private ChionesBladeListener chionesBladeListener;
   private BladeOfDiminutionListener bladeOfDiminutionListener;

   /** A tag key in the plugin's fixed namespace. */
   @SuppressWarnings("deprecation")
   public static org.bukkit.NamespacedKey key(String key) {
      return new org.bukkit.NamespacedKey(TAG_NAMESPACE, key);
   }

   @Override
   public void onLoad() {
      java.io.File folder = this.getDataFolder();
      java.io.File old = new java.io.File(folder.getParentFile(), OLD_NAME);
      if (old.isDirectory() && !folder.exists()) {
         if (old.renameTo(folder)) {
            this.getLogger().info("Moved plugins/" + OLD_NAME + " to plugins/" + folder.getName() + " (the plugin is now LumiWeapons).");
         } else {
            this.getLogger().severe("Could not move plugins/" + OLD_NAME + " to plugins/" + folder.getName() + ". Move it by hand, or settings and saved data start fresh.");
         }
      }
   }

   public void onEnable() {
      if (this.getServer().getPluginManager().getPlugin(OLD_NAME) != null) {
         this.getLogger().severe("The old " + OLD_NAME + " jar is still in plugins/. Delete it: both running at once will break things.");
      }

      this.configManager = new ConfigManager(this);
      this.cooldownManager = new CooldownManager(this);
      this.soundManager = new SoundManager(this);
      this.particleManager = new ParticleManager(this);
      com.lumiweapons.managers.AnimationFx.init(this);
      com.lumiweapons.abilities.AbilityDamage.init(this);
      com.lumiweapons.managers.SpriteFx.init(this);
      this.hudManager = new HudManager(this);
      this.protectionManager = new ProtectionManager(this);
      this.chargePool = new ChargePool();
      this.chargeMeter = new ChargeMeter();
      this.comboTracker = new ComboTracker();
      this.dashTracker = new DashTracker();
      this.disguiseManager = new DisguiseManager(this);
      this.playerRosterManager = new PlayerRosterManager(this);
      this.customItems = new CustomItems(this);
      this.weaponManager = new WeaponManager(this);
      this.ritualRecipes = new RitualRecipes(this);
      this.trustManager = new TrustManager(this);
      this.ritualManager = new RitualManager(this);
      this.keybindManager = new KeybindManager(this);
      this.tutorialManager = new TutorialManager(this);
      this.guiManager = new GUIManager(this);
      this.teamManager = new com.lumiweapons.teams.TeamManager(this);
      this.eventManager = new EventManager(this);
      this.bossManager = new com.lumiweapons.bosses.BossManager(this);
      this.swordOfEclipseListener = new SwordOfEclipseListener(this);
      EnderGlaiveListener enderGlaiveListener = this.enderGlaiveListener = new EnderGlaiveListener(this);
      LostTridentListener lostTridentListener = this.lostTridentListener = new LostTridentListener(this);
      AxeOfZeusListener axeOfZeusListener = this.axeOfZeusListener = new AxeOfZeusListener(this);
      HermesBladeListener hermesBladeListener = this.hermesBladeListener = new HermesBladeListener(this);
      this.chionesBladeListener = new ChionesBladeListener(this);
      TitansAxeListener titansAxeListener = new TitansAxeListener(this);
      this.bladeOfDiminutionListener = new BladeOfDiminutionListener(this);
      LuminosityListener luminosityListener = new LuminosityListener(this);
      VeilOfMimicryListener veilOfMimicryListener = new VeilOfMimicryListener(this);
      TetherChainListener tetherChainListener = new TetherChainListener(this);
      com.lumiweapons.listeners.TalariaListener talariaListener = this.talariaListener = new com.lumiweapons.listeners.TalariaListener(this);
      com.lumiweapons.listeners.TwinRequiemListener twinRequiemListener = new com.lumiweapons.listeners.TwinRequiemListener(this);
      com.lumiweapons.listeners.CrescentListener crescentListener = new com.lumiweapons.listeners.CrescentListener(this);
      com.lumiweapons.listeners.OculusListener oculusListener = this.oculusListener = new com.lumiweapons.listeners.OculusListener(this);
      com.lumiweapons.listeners.HorizonListener horizonListener = this.horizonListener = new com.lumiweapons.listeners.HorizonListener(this);
      this.abilityDispatcher = new AbilityDispatcher(this);
      this.abilityDispatcher.register(this.swordOfEclipseListener);
      this.abilityDispatcher.register(enderGlaiveListener);
      this.abilityDispatcher.register(lostTridentListener);
      this.abilityDispatcher.register(axeOfZeusListener);
      this.abilityDispatcher.register(hermesBladeListener);
      this.abilityDispatcher.register(this.chionesBladeListener);
      this.abilityDispatcher.register(titansAxeListener);
      this.abilityDispatcher.register(this.bladeOfDiminutionListener);
      this.abilityDispatcher.register(luminosityListener);
      this.abilityDispatcher.register(veilOfMimicryListener);
      this.abilityDispatcher.register(tetherChainListener);
      this.abilityDispatcher.register(talariaListener);
      this.abilityDispatcher.register(twinRequiemListener);
      this.abilityDispatcher.register(crescentListener);
      this.abilityDispatcher.register(oculusListener);
      this.abilityDispatcher.register(horizonListener);
      new CommandManager(this, this.swordOfEclipseListener, this.abilityDispatcher);
      TrustCommand trustCommand = new TrustCommand(this, this.trustManager);

      for (Listener listener : new Listener[]{
         new PlayerStateListener(this),
         new RitualListener(this),
         new WeaponProtectionListener(this),
         new ScytheOfDiabloListener(this),
         enderGlaiveListener,
         axeOfZeusListener,
         this.swordOfEclipseListener,
         new HellstormCrossbowListener(this),
         lostTridentListener,
         hermesBladeListener,
         this.chionesBladeListener,
         titansAxeListener,
         this.bladeOfDiminutionListener,
         luminosityListener,
         veilOfMimicryListener,
         tetherChainListener,
         talariaListener,
         twinRequiemListener,
         crescentListener,
         oculusListener,
         horizonListener,
         this.customItems,
         new com.lumiweapons.listeners.AmethystToolsListener(this),
         new AbilityListener(this, this.abilityDispatcher, enderGlaiveListener),
         new com.lumiweapons.guis.MenuListener(this),
         new com.lumiweapons.guis.ChatPrompt(this),
         new TrustListener(this, this.trustManager, this.configManager, this.weaponManager)
      }) {
         this.getServer().getPluginManager().registerEvents(listener, this);
      }

      this.getCommand("trust").setExecutor(trustCommand);
      this.getCommand("untrust").setExecutor(trustCommand);
      this.getCommand("trustlist").setExecutor(trustCommand);
      // Soft dependencies are enabled after us; hook them once the server has finished starting.
      this.getServer().getScheduler().runTask(this, () -> this.protectionManager.hook());
      this.configCheck = new com.lumiweapons.managers.ConfigCheck(this);
      this.configCheck.runAndLog();                     // typos and bad values show up in the console, not as silent defaults
      this.getLogger().info("LumiWeapons has been enabled!");
   }

   public void onDisable() {
      if (this.teamManager != null) {
         this.teamManager.shutdown();
      }

      com.lumiweapons.managers.SpriteFx.shutdown();
      if (this.talariaListener != null) {
         this.talariaListener.cleanup();                   // floating shields, the eye, falling arrows
         this.oculusListener.cleanup();
         this.horizonListener.cleanup();
      }

      if (this.bossManager != null) {
         this.bossManager.shutdown();
      }

      if (this.eventManager != null) {
         this.eventManager.shutdown();
      }

      if (this.swordOfEclipseListener != null) {
         this.swordOfEclipseListener.cleanup();
      }

      if (this.chionesBladeListener != null) {
         this.chionesBladeListener.cleanup();
      }

      if (this.bladeOfDiminutionListener != null) {
         this.bladeOfDiminutionListener.cleanup();
      }

      if (this.lostTridentListener != null) {
         this.lostTridentListener.cleanup();
      }

      if (this.axeOfZeusListener != null) {
         this.axeOfZeusListener.cleanup();
      }

      if (this.enderGlaiveListener != null) {
         this.enderGlaiveListener.cleanup();
      }

      if (this.hermesBladeListener != null) {
         this.hermesBladeListener.cleanup();
      }

      if (this.disguiseManager != null) {
         this.disguiseManager.removeAll();
      }

      if (this.playerRosterManager != null) {
         this.playerRosterManager.save();
      }

      if (this.keybindManager != null) {
         this.keybindManager.saveKeybinds();
      }

      if (this.ritualManager != null) {
         this.ritualManager.shutdown();
      }

      if (this.trustManager != null) {
         this.trustManager.saveTrustData();
      }

      this.getLogger().info("LumiWeapons has been disabled!");
   }

   public ConfigManager getConfigManager() {
      return this.configManager;
   }

   public KeybindManager getKeybindManager() {
      return this.keybindManager;
   }

   public WeaponManager getWeaponManager() {
      return this.weaponManager;
   }

   public RitualManager getRitualManager() {
      return this.ritualManager;
   }

   public RitualRecipes getRitualRecipes() {
      return this.ritualRecipes;
   }

   public TutorialManager getTutorialManager() {
      return this.tutorialManager;
   }

   public GUIManager getGuiManager() {
      return this.guiManager;
   }

   public TrustManager getTrustManager() {
      return this.trustManager;
   }

   /** Every cooldown, charge, dash lock and freeze immunity, plus the vanilla item cooldown overlays. */
   public void resetAllCooldowns(org.bukkit.entity.Player target) {
      this.getCooldownManager().clearAll(target);
      this.getChargePool().clear(target);
      this.getDashTracker().clear(target);
      this.getChionesBladeListener().resetImmunity(target);

      for (String key : this.getWeaponManager().getWeaponKeys()) {
         Material material = this.getConfigManager().getWeaponMaterial(key);
         if (material != null && !material.isAir()) {
            target.setCooldown(material, 0);
         }
      }

      target.setCooldown(Material.SHIELD, 0);
      // Anything else still showing the white sweep (a weapon on a material the config no longer lists).
      for (org.bukkit.inventory.ItemStack item : target.getInventory().getContents()) {
         if (item != null && !item.getType().isAir()) {
            if (target.getCooldown(item.getType()) > 0) {
               target.setCooldown(item.getType(), 0);
            }

            if (this.getWeaponManager().isLegendaryWeapon(item)) {
               target.setCooldown(item, 0);                // the weapon's own cooldown group
            }
         }
      }
   }

   public CooldownManager getCooldownManager() {
      return this.cooldownManager;
   }

   public SoundManager getSoundManager() {
      return this.soundManager;
   }

   public ParticleManager getParticleManager() {
      return this.particleManager;
   }

   public HudManager getHudManager() {
      return this.hudManager;
   }

   public ProtectionManager getProtectionManager() {
      return this.protectionManager;
   }

   public ChargePool getChargePool() {
      return this.chargePool;
   }

   public ChargeMeter getChargeMeter() {
      return this.chargeMeter;
   }

   public ComboTracker getComboTracker() {
      return this.comboTracker;
   }

   public DashTracker getDashTracker() {
      return this.dashTracker;
   }

   public PlayerRosterManager getPlayerRosterManager() {
      return this.playerRosterManager;
   }

   public DisguiseManager getDisguiseManager() {
      return this.disguiseManager;
   }

   public CustomItems getCustomItems() {
      return this.customItems;
   }

   public com.lumiweapons.bosses.BossManager getBossManager() {
      return this.bossManager;
   }

   /** /lw reload and the settings menu: config.yml, lore.yml, weapons and the altar recipe. */
   public void reloadSettings() {
      this.configManager.reloadConfig();
      this.weaponManager.reload();
      if (this.ritualManager != null) {
         this.ritualManager.refreshRecipe();
      }

      if (this.configCheck != null) {
         this.configCheck.run();                          // quietly: the settings menu calls this on every click
      }
   }

   public com.lumiweapons.managers.ConfigCheck getConfigCheck() {
      return this.configCheck;
   }

   public com.lumiweapons.teams.TeamManager getTeamManager() {
      return this.teamManager;
   }

   public EventManager getEventManager() {
      return this.eventManager;
   }

   public ChionesBladeListener getChionesBladeListener() {
      return this.chionesBladeListener;
   }

   public BladeOfDiminutionListener getBladeOfDiminutionListener() {
      return this.bladeOfDiminutionListener;
   }
}
