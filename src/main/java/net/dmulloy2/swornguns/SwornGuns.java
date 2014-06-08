/**
 * SwornGuns - a bukkit plugin
 * Copyright (C) 2013 - 2014 MineSworn and Affiliates
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.dmulloy2.swornguns;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;

import lombok.Getter;
import net.dmulloy2.swornguns.api.SwornGunsAPI;
import net.dmulloy2.swornguns.commands.CmdHelp;
import net.dmulloy2.swornguns.commands.CmdList;
import net.dmulloy2.swornguns.commands.CmdReload;
import net.dmulloy2.swornguns.commands.CmdToggle;
import net.dmulloy2.swornguns.handlers.CommandHandler;
import net.dmulloy2.swornguns.handlers.LogHandler;
import net.dmulloy2.swornguns.handlers.PermissionHandler;
import net.dmulloy2.swornguns.io.WeaponReader;
import net.dmulloy2.swornguns.listeners.EntityListener;
import net.dmulloy2.swornguns.listeners.PlayerListener;
import net.dmulloy2.swornguns.types.Bullet;
import net.dmulloy2.swornguns.types.EffectType;
import net.dmulloy2.swornguns.types.Gun;
import net.dmulloy2.swornguns.types.GunPlayer;
import net.dmulloy2.swornguns.types.MyMaterial;
import net.dmulloy2.swornguns.util.FormatUtil;
import net.dmulloy2.swornguns.util.Util;
import net.dmulloy2.swornrpg.SwornRPG;
import net.dmulloy2.ultimatearena.UltimateArena;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * @author dmulloy2
 */

public class SwornGuns extends JavaPlugin implements SwornGunsAPI
{
	private @Getter PermissionHandler permissionHandler;
	private @Getter CommandHandler commandHandler;
	private @Getter LogHandler logHandler;

	private @Getter Map<String, Gun> loadedGuns;
	private @Getter Map<String, GunPlayer> players;

	private @Getter List<Bullet> bullets;
	private @Getter List<EffectType> effects;
	private @Getter List<String> disabledWorlds;

	private @Getter String prefix = FormatUtil.format("&3[&eSwornGuns&3]&e ");

	@Override
	public void onEnable()
	{
		long start = System.currentTimeMillis();

		// Configuration
		saveDefaultConfig();
		reloadConfig();

		// Initialize variables
		loadedGuns = new HashMap<String, Gun>();
		players = new HashMap<String, GunPlayer>();

		bullets = new ArrayList<Bullet>();
		effects = new ArrayList<EffectType>();
		disabledWorlds = getConfig().getStringList("disabledWorlds");

		// Handlers
		logHandler = new LogHandler(this);
		permissionHandler = new PermissionHandler(this);
		commandHandler = new CommandHandler(this);

		// Integration
		setupSwornRPGIntegration();
		setupUltimateArenaIntegration();

		// Register commands
		commandHandler.setCommandPrefix("swornguns");
		commandHandler.registerCommand(new CmdHelp(this));
		commandHandler.registerCommand(new CmdList(this));
		commandHandler.registerCommand(new CmdReload(this));
		commandHandler.registerCommand(new CmdToggle(this));

		// Register events
		PluginManager pm = getServer().getPluginManager();
		pm.registerEvents(new PlayerListener(this), this);
		pm.registerEvents(new EntityListener(this), this);

		// Files
		File guns = new File(getDataFolder(), "guns");
		if (! guns.exists())
		{
			guns.mkdir();
		}

		File projectile = new File(getDataFolder(), "projectile");
		if (! projectile.exists())
		{
			projectile.mkdir();
		}

		// Load guns
		loadGuns();
		loadProjectiles();

		getOnlinePlayers();

		registerPermissions();

		// Update timer
		if (getConfig().getBoolean("runUpdateTimerAsync", false))
		{
			logHandler.log("Running Update Timer asynchronously! This may cause crashes, you have been warned!");
			new UpdateTimer().runTaskTimerAsynchronously(this, 20L, 1L);
		}
		else
		{
			new UpdateTimer().runTaskTimer(this, 20L, 1L);
		}

		logHandler.log("{0} has been enabled ({1}ms)", getDescription().getFullName(), System.currentTimeMillis() - start);
	}

	@Override
	public void onDisable()
	{
		long start = System.currentTimeMillis();

		getServer().getScheduler().cancelTasks(this);
		getServer().getServicesManager().unregisterAll(this);

		for (Bullet bullet : Util.newList(bullets))
		{
			bullet.destroy();
		}

		for (GunPlayer gp : players.values())
		{
			if (gp != null)
				gp.unload();
		}

		loadedGuns.clear();
		effects.clear();
		bullets.clear();
		players.clear();
		permissions.clear();

		logHandler.log("{0} has been disabled ({1}ms)", getDescription().getFullName(), System.currentTimeMillis() - start);
	}

	// ---- Integration

	private @Getter boolean useUltimateArena;
	private @Getter UltimateArena ultimateArena;

	private void setupUltimateArenaIntegration()
	{
		try
		{
			PluginManager pm = getServer().getPluginManager();
			if (pm.isPluginEnabled("UltimateArena"))
			{
				Plugin pl = pm.getPlugin("UltimateArena");
				if (pl instanceof UltimateArena)
				{
					ultimateArena = (UltimateArena) pl;
					useUltimateArena = true;
				}
			}
		} catch (Throwable ex) { }
	}

	private @Getter boolean useSwornRPG;
	private @Getter SwornRPG swornRPG;

	private void setupSwornRPGIntegration()
	{
		try
		{
			PluginManager pm = getServer().getPluginManager();
			if (pm.isPluginEnabled("SwornRPG"))
			{
				Plugin pl = pm.getPlugin("SwornRPG");
				if (pl instanceof SwornRPG)
				{
					swornRPG = (SwornRPG) pl;
					useSwornRPG = true;
				}
			}
		} catch (Throwable ex) { }
	}

	private void loadProjectiles()
	{
		int loaded = 0;

		File dir = new File(getDataFolder(), "projectile");

		File[] children = dir.listFiles();
		if (children.length == 0)
		{
			String[] stock = new String[]
			{
					"flashbang", "grenade", "molotov", "smokegrenade"
			};

			for (String s : stock)
			{
				saveResource("projectile" + File.separator + s, false);
			}
		}

		children = dir.listFiles();

		for (File child : children)
		{
			WeaponReader reader = new WeaponReader(this, child);
			if (reader.isLoaded())
			{
				Gun gun = reader.getGun();
				gun.setThrowable(true);
				loadedGuns.put(gun.getFileName(), gun);
				loaded++;
			}
			else
			{
				logHandler.log(Level.WARNING, "Could not load projectile: {0}", child.getName());
			}
		}

		logHandler.log("Loaded {0} projectiles!", loaded);
	}

	private void loadGuns()
	{
		int loaded = 0;

		File dir = new File(getDataFolder(), "guns");

		File[] children = dir.listFiles();
		if (children.length == 0)
		{
			String[] stock = new String[]
			{
					"AutoShotgun", "DoubleBarrel", "Flamethrower", "Pistol", "Rifle", "RocketLauncher", "Shotgun", "Sniper"
			};

			for (String s : stock)
			{
				saveResource("guns" + File.separator + s, false);
			}
		}

		children = dir.listFiles();

		for (File child : children)
		{
			WeaponReader reader = new WeaponReader(this, child);
			if (reader.isLoaded())
			{
				Gun gun = reader.getGun();
				loadedGuns.put(gun.getFileName(), gun);
				loaded++;
			}
			else
			{
				logHandler.log(Level.WARNING, "Could not load gun: {0}", child.getName());
			}
		}

		logHandler.log("Loaded {0} guns!", loaded);
	}

	private Map<String, Permission> permissions;

	private void registerPermissions()
	{
		permissions = new HashMap<String, Permission>();

		for (Gun gun : loadedGuns.values())
		{
			PermissionDefault def = gun.isNeedsPermission() ? PermissionDefault.FALSE : PermissionDefault.TRUE;
			Permission perm = new Permission("swornguns.fire." + gun.getFileName(), def);
			getServer().getPluginManager().addPermission(perm);
			permissions.put(gun.getFileName(), perm);
		}
	}

	@Override
	public Permission getPermission(Gun gun)
	{
		return permissions.get(gun.getFileName());
	}

	private void getOnlinePlayers()
	{
		for (Player player : getServer().getOnlinePlayers())
		{
			if (! players.containsKey(player.getName()))
				players.put(player.getName(), new GunPlayer(this, player));
		}
	}

	@Override
	public GunPlayer getGunPlayer(Player player)
	{
		return players.get(player.getName());
	}

	@Override
	@Deprecated
	public Gun getGun(Material material)
	{
		return getGun(new MyMaterial(material, (short) 0));
	}

	@Override
	public Gun getGun(MyMaterial material)
	{
		for (Gun gun : loadedGuns.values())
		{
			if (gun.getMaterial().equals(material))
				return gun;
		}

		return null;
	}

	@Override
	public Gun getGun(String gunName)
	{
		return loadedGuns.get(gunName);
	}

	public void onJoin(Player player)
	{
		if (! players.containsKey(player.getName()))
			players.put(player.getName(), new GunPlayer(this, player));
	}

	public void onQuit(Player player)
	{
		if (players.containsKey(player.getName()))
		{
			GunPlayer gp = players.get(player.getName());
			gp.unload();

			players.remove(player.getName());
		}
	}

	@Override
	public void removeBullet(Bullet bullet)
	{
		bullets.remove(bullet);
	}

	@Override
	public void addBullet(Bullet bullet)
	{
		bullets.add(bullet);
	}

	@Override
	public Bullet getBullet(Entity proj)
	{
		for (Bullet bullet : Util.newList(bullets))
		{
			if (bullet.getProjectile().getUniqueId() == proj.getUniqueId())
				return bullet;
		}

		return null;
	}

	@Override
	@Deprecated
	public List<Gun> getGunsByType(ItemStack item)
	{
		return getGunsByType(new MyMaterial(item.getType(), item.getDurability()));
	}

	@Override
	public List<Gun> getGunsByType(MyMaterial material)
	{
		List<Gun> ret = new ArrayList<Gun>();

		for (Gun gun : loadedGuns.values())
		{
			if (gun.getMaterial().equals(material))
				ret.add(gun);
		}

		return ret;
	}

	@Override
	public void removeEffect(EffectType effectType)
	{
		effects.remove(effectType);
	}

	@Override
	public void addEffect(EffectType effectType)
	{
		effects.add(effectType);
	}

	public class UpdateTimer extends BukkitRunnable
	{
		@Override
		public void run()
		{
			for (Entry<String, GunPlayer> entry : players.entrySet())
			{
				String name = entry.getKey();
				GunPlayer player = entry.getValue();

				// Don't tick null players
				if (player == null)
				{
					players.remove(name);
					continue;
				}

				try
				{
					player.tick();
				}
				catch (Throwable ex)
				{
					logHandler.log(Level.WARNING, Util.getUsefulStack(ex, "ticking player " + name));
				}
			}

			for (Bullet bullet : Util.newList(bullets))
			{
				// Don't tick null bullets
				if (bullet == null)
				{
					bullets.remove(bullet);
					continue;
				}

				try
				{
					bullet.tick();
				}
				catch (Throwable ex)
				{
					logHandler.log(Level.WARNING, Util.getUsefulStack(ex, "ticking bullet " + bullet));
				}
			}

			for (EffectType effect : Util.newList(effects))
			{
				// Don't tick null effects
				if (effect == null)
				{
					effects.remove(effect);
					continue;
				}

				try
				{
					effect.tick();
				}
				catch (Throwable ex)
				{
					logHandler.log(Level.WARNING, Util.getUsefulStack(ex, "ticking effect " + effect));
				}
			}
		}
	}

	@Override
	public void reload()
	{
		// Config
		reloadConfig();

		// Reload guns
		loadedGuns.clear();
		loadGuns();
		loadProjectiles();

		// Refresh players
		for (Entry<String, GunPlayer> entry : players.entrySet())
		{
			GunPlayer player = entry.getValue();

			// Don't reload null players
			if (player == null)
			{
				players.remove(entry.getKey());
				continue;
			}

			player.reload();
		}

		// Get any players we may have missed
		getOnlinePlayers();
	}
}