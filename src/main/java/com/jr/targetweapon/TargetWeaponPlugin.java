package com.jr.targetweapon;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.events.GameTick;
import net.runelite.api.kit.KitType;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Target Weapon ID",
	description = "Reads equipped weapon ID of your current player target",
	tags = {"target", "weapon", "id", "debug"}
)
public class TargetWeaponPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private TargetWeaponConfig config;

	private String lastTargetName;
	private int lastWeaponId = Integer.MIN_VALUE;

	@Provides
	TargetWeaponConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TargetWeaponConfig.class);
	}

	@Override
	protected void startUp()
	{
		lastTargetName = null;
		lastWeaponId = Integer.MIN_VALUE;
		log.info("Target Weapon ID plugin started");
	}

	@Override
	protected void shutDown()
	{
		log.info("Target Weapon ID plugin stopped");
	}

	@Subscribe
	public void onGameTick(GameTick ignored)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}

		Actor interacting = client.getLocalPlayer().getInteracting();
		if (!(interacting instanceof Player))
		{
			if (!config.logOnlyOnChange())
			{
				log.info("[TARGET] no player target");
			}
			return;
		}

		Player target = (Player) interacting;
		PlayerComposition comp = target.getPlayerComposition();
		if (comp == null)
		{
			return;
		}

		int rawWeaponId = comp.getEquipmentId(KitType.WEAPON);
		int normalizedWeaponId = normalizeItemId(rawWeaponId);
		String targetName = safe(target.getName());

		boolean changed = !targetName.equals(lastTargetName) || normalizedWeaponId != lastWeaponId;
		if (!config.logOnlyOnChange() || changed)
		{
			log.info("[TARGET] name='{}' combat={} rawWeaponId={} weaponItemId={} tick={}",
				targetName,
				target.getCombatLevel(),
				rawWeaponId,
				normalizedWeaponId,
				client.getTickCount()
			);
		}

		lastTargetName = targetName;
		lastWeaponId = normalizedWeaponId;
	}

	private static int normalizeItemId(int equipmentId)
	{
		if (equipmentId <= 0)
		{
			return -1;
		}

		// RuneLite PlayerComposition item equipment IDs are usually itemId + ITEM_OFFSET (512)
		if (equipmentId >= PlayerComposition.ITEM_OFFSET)
		{
			return equipmentId - PlayerComposition.ITEM_OFFSET;
		}

		// Non-item values (kit IDs) are returned as-is for visibility
		return equipmentId;
	}

	private static String safe(String s)
	{
		return s == null ? "" : s;
	}
}
