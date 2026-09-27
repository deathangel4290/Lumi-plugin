package com.legendaryweapons;

import com.legendaryweapons.abilities.AbilityDispatcher;
import com.legendaryweapons.commands.TrustCommand;
import com.legendaryweapons.events.EventManager;
import com.legendaryweapons.items.CustomItems;
import com.legendaryweapons.listeners.AbilityListener;
import com.legendaryweapons.listeners.AxeOfZeusListener;
import com.legendaryweapons.listeners.BladeOfDiminutionListener;
import com.legendaryweapons.listeners.ChionesBladeListener;
import com.legendaryweapons.listeners.EnderGlaiveListener;
import com.legendaryweapons.listeners.GUIListener;
import com.legendaryweapons.listeners.HellstormCrossbowListener;
import com.legendaryweapons.listeners.HermesBladeListener;
import com.legendaryweapons.listeners.LostTridentListener;
import com.legendaryweapons.listeners.LuminosityListener;
import com.legendaryweapons.listeners.PlayerStateListener;
import com.legendaryweapons.listeners.RitualListener;
import com.legendaryweapons.listeners.ScytheOfDiabloListener;
import com.legendaryweapons.listeners.SwordOfEclipseListener;
import com.legendaryweapons.listeners.TetherChainListener;
import com.legendaryweapons.listeners.TitansAxeListener;
import com.legendaryweapons.listeners.TrustListener;
import com.legendaryweapons.listeners.VeilOfMimicryListener;
import com.legendaryweapons.listeners.WeaponProtectionListener;
import com.legendaryweapons.managers.ChargeMeter;
import com.legendaryweapons.managers.ChargePool;
import com.legendaryweapons.managers.ComboTracker;
import com.legendaryweapons.managers.CommandManager;
import com.legendaryweapons.managers.ConfigManager;
import com.legendaryweapons.managers.CooldownManager;
import com.legendaryweapons.managers.DashTracker;
import com.legendaryweapons.managers.DisguiseManager;
import com.legendaryweapons.managers.GUIManager;
import com.legendaryweapons.managers.HudManager;
import com.legendaryweapons.managers.KeybindManager;
import com.legendaryweapons.managers.ParticleManager;
import com.legendaryweapons.managers.PlayerRosterManager;
import com.legendaryweapons.managers.ProtectionManager;
import com.legendaryweapons.managers.RitualManager;
import com.legendaryweapons.managers.SoundManager;
import com.legendaryweapons.managers.TrustManager;
import com.legendaryweapons.managers.TutorialManager;
import com.legendaryweapons.managers.WeaponManager;
import com.legendaryweapons.rituals.RitualRecipes;
import org.bukkit.Material;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public class LegendaryWeapons extends JavaPlugin {
   private LostTridentListener lostTridentListener;
   private AxeOfZeusListener axeOfZeusListener;
   private EnderGlaiveListener enderGlaiveListener;
   private ConfigManager configManager;
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
   private CustomItems customItems;
   private EventManager eventManager;
   private AbilityDispatcher abilityDispatcher;
   private SwordOfEclipseListener swordOfEclipseListener;
   private ChionesBladeListener chionesBladeListener;
   private BladeOfDiminutionListener bladeOfDiminutionListener;

   public void onEnable() {
      this.configManager = new ConfigManager(this);
      this.cooldownManager = new CooldownManager(this);
      this.soundManager = new SoundManager(this);
      this.particleManager = new ParticleManager(this);
      com.legendaryweapons.managers.AnimationFx.init(this);
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
      this.eventManager = new EventManager(this);
      this.swordOfEclipseListener = new SwordOfEclipseListener(this);
      EnderGlaiveListener enderGlaiveListener = this.enderGlaiveListener = new EnderGlaiveListener(this);
      LostTridentListener lostTridentListener = this.lostTridentListener = new LostTridentListener(this);
      AxeOfZeusListener axeOfZeusListener = this.axeOfZeusListener = new AxeOfZeusListener(this);
      HermesBladeListener hermesBladeListener = new HermesBladeListener(this);
      this.chionesBladeListener = new ChionesBladeListener(this);
      TitansAxeListener titansAxeListener = new TitansAxeListener(this);
      this.bladeOfDiminutionListener = new BladeOfDiminutionListener(this);
      LuminosityListener luminosityListener = new LuminosityListener(this);
      VeilOfMimicryListener veilOfMimicryListener = new VeilOfMimicryListener(this);
      TetherChainListener tetherChainListener = new TetherChainListener(this);
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
         this.customItems,
         new com.legendaryweapons.listeners.AmethystToolsListener(this),
         new AbilityListener(this, this.abilityDispatcher, enderGlaiveListener),
         new GUIListener(this.guiManager),
         new TrustListener(this, this.trustManager, this.configManager, this.weaponManager)
      }) {
         this.getServer().getPluginManager().registerEvents(listener, this);
      }

      this.getCommand("trust").setExecutor(trustCommand);
      this.getCommand("untrust").setExecutor(trustCommand);
      this.getCommand("trustlist").setExecutor(trustCommand);
      // Soft dependencies are enabled after us; hook them once the server has finished starting.
      this.getServer().getScheduler().runTask(this, () -> this.protectionManager.hook());
      this.getLogger().info("LegendaryWeapons has been enabled!");
   }

   public void onDisable() {
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

      this.getLogger().info("LegendaryWeapons has been disabled!");
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
         if (item != null && !item.getType().isAir() && target.getCooldown(item.getType()) > 0) {
            target.setCooldown(item.getType(), 0);
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

   public EventManager getEventManager() {
      return this.eventManager;
   }

   public AbilityDispatcher getAbilityDispatcher() {
      return this.abilityDispatcher;
   }

   public ChionesBladeListener getChionesBladeListener() {
      return this.chionesBladeListener;
   }

   public BladeOfDiminutionListener getBladeOfDiminutionListener() {
      return this.bladeOfDiminutionListener;
   }
}
