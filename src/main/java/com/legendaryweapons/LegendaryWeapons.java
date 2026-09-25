package com.legendaryweapons;

import com.legendaryweapons.abilities.AbilityDispatcher;
import com.legendaryweapons.commands.TrustCommand;
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
import com.legendaryweapons.managers.DisguiseManager;
import com.legendaryweapons.managers.GUIManager;
import com.legendaryweapons.managers.KeybindManager;
import com.legendaryweapons.managers.ParticleManager;
import com.legendaryweapons.managers.PlayerRosterManager;
import com.legendaryweapons.managers.RitualManager;
import com.legendaryweapons.managers.SoundManager;
import com.legendaryweapons.managers.TrustManager;
import com.legendaryweapons.managers.TutorialManager;
import com.legendaryweapons.managers.WeaponManager;
import org.bukkit.plugin.java.JavaPlugin;

public class LegendaryWeapons extends JavaPlugin {
   private ConfigManager configManager;
   private WeaponManager weaponManager;
   private RitualManager ritualManager;
   private CommandManager commandManager;
   private KeybindManager keybindManager;
   private TutorialManager tutorialManager;
   private GUIManager guiManager;
   private TrustManager trustManager;
   private CooldownManager cooldownManager;
   private SoundManager soundManager;
   private ParticleManager particleManager;
   private ChargePool chargePool;
   private ChargeMeter chargeMeter;
   private ComboTracker comboTracker;
   private PlayerRosterManager playerRosterManager;
   private DisguiseManager disguiseManager;

   public void onEnable() {
      this.configManager = new ConfigManager(this);
      this.cooldownManager = new CooldownManager(this);
      this.soundManager = new SoundManager(this);
      this.particleManager = new ParticleManager(this);
      this.chargePool = new ChargePool();
      this.chargeMeter = new ChargeMeter();
      this.comboTracker = new ComboTracker();
      this.playerRosterManager = new PlayerRosterManager(this);
      this.disguiseManager = new DisguiseManager(this);
      this.weaponManager = new WeaponManager(this);
      this.ritualManager = new RitualManager(this);
      this.keybindManager = new KeybindManager(this);
      this.tutorialManager = new TutorialManager(this);
      this.guiManager = new GUIManager(this);
      this.trustManager = new TrustManager(this);
      SwordOfEclipseListener swordOfEclipseListener = new SwordOfEclipseListener(this);
      EnderGlaiveListener enderGlaiveListener = new EnderGlaiveListener(this);
      LostTridentListener lostTridentListener = new LostTridentListener(this);
      AxeOfZeusListener axeOfZeusListener = new AxeOfZeusListener(this);
      HermesBladeListener hermesBladeListener = new HermesBladeListener(this);
      ChionesBladeListener chionesBladeListener = new ChionesBladeListener(this);
      TitansAxeListener titansAxeListener = new TitansAxeListener(this);
      BladeOfDiminutionListener bladeOfDiminutionListener = new BladeOfDiminutionListener(this);
      LuminosityListener luminosityListener = new LuminosityListener(this);
      VeilOfMimicryListener veilOfMimicryListener = new VeilOfMimicryListener(this);
      AbilityDispatcher abilityDispatcher = new AbilityDispatcher(this);
      abilityDispatcher.register(swordOfEclipseListener);
      abilityDispatcher.register(enderGlaiveListener);
      abilityDispatcher.register(lostTridentListener);
      abilityDispatcher.register(axeOfZeusListener);
      abilityDispatcher.register(hermesBladeListener);
      abilityDispatcher.register(chionesBladeListener);
      abilityDispatcher.register(titansAxeListener);
      abilityDispatcher.register(bladeOfDiminutionListener);
      abilityDispatcher.register(luminosityListener);
      abilityDispatcher.register(veilOfMimicryListener);
      this.commandManager = new CommandManager(this, swordOfEclipseListener, abilityDispatcher);
      TrustCommand trustCommand = new TrustCommand(this, this.trustManager);
      this.getServer().getPluginManager().registerEvents(new PlayerStateListener(this), this);
      this.getServer().getPluginManager().registerEvents(new RitualListener(this), this);
      this.getServer().getPluginManager().registerEvents(new WeaponProtectionListener(this), this);
      this.getServer().getPluginManager().registerEvents(new ScytheOfDiabloListener(this), this);
      this.getServer().getPluginManager().registerEvents(enderGlaiveListener, this);
      this.getServer().getPluginManager().registerEvents(axeOfZeusListener, this);
      this.getServer().getPluginManager().registerEvents(swordOfEclipseListener, this);
      this.getServer().getPluginManager().registerEvents(new HellstormCrossbowListener(this), this);
      this.getServer().getPluginManager().registerEvents(lostTridentListener, this);
      this.getServer().getPluginManager().registerEvents(hermesBladeListener, this);
      this.getServer().getPluginManager().registerEvents(chionesBladeListener, this);
      this.getServer().getPluginManager().registerEvents(titansAxeListener, this);
      this.getServer().getPluginManager().registerEvents(bladeOfDiminutionListener, this);
      this.getServer().getPluginManager().registerEvents(luminosityListener, this);
      this.getServer().getPluginManager().registerEvents(veilOfMimicryListener, this);
      this.getServer().getPluginManager().registerEvents(new AbilityListener(this, abilityDispatcher, enderGlaiveListener), this);
      this.getServer().getPluginManager().registerEvents(new GUIListener(this.guiManager), this);
      this.getServer().getPluginManager().registerEvents(new TrustListener(this, this.trustManager, this.configManager, this.weaponManager), this);
      this.getCommand("trust").setExecutor(trustCommand);
      this.getCommand("untrust").setExecutor(trustCommand);
      this.getCommand("trustlist").setExecutor(trustCommand);
      this.getLogger().info("LegendaryWeapons has been enabled!");
   }

   public void onDisable() {
      this.disguiseManager.removeAll();
      this.playerRosterManager.save();
      this.keybindManager.saveKeybinds();
      this.ritualManager.saveActiveRituals();
      this.trustManager.saveTrustData();
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

   public TutorialManager getTutorialManager() {
      return this.tutorialManager;
   }

   public GUIManager getGuiManager() {
      return this.guiManager;
   }

   public TrustManager getTrustManager() {
      return this.trustManager;
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

   public ChargePool getChargePool() {
      return this.chargePool;
   }

   public ChargeMeter getChargeMeter() {
      return this.chargeMeter;
   }

   public ComboTracker getComboTracker() {
      return this.comboTracker;
   }

   public PlayerRosterManager getPlayerRosterManager() {
      return this.playerRosterManager;
   }

   public DisguiseManager getDisguiseManager() {
      return this.disguiseManager;
   }
}
