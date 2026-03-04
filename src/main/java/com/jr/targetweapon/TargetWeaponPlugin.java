package com.jr.targetweapon;

import com.google.inject.Provides;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Prayer;
import net.runelite.api.events.GameTick;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

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

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TargetWeaponOverlay overlay;

	private final Deque<String> recentLogLines = new ArrayDeque<>();
	private static final int MAX_LINES = 8;
	private static final int THICK_SKIN_WIDGET_ID = 35454985; // captured from prayer trace logger
	private static final int PRAYER_TAB_WIDGET_ID = 10747961; // captured from prayer trace logger

	private String lastTargetName;
	private int lastTargetIndex = -1;
	private int lastWeaponId = Integer.MIN_VALUE;
	private int lastPrayerTriggerTick = -9999;
	private final Map<Integer, Boolean> magicWeaponCache = new HashMap<>();

	private PrayerActionState prayerActionState = PrayerActionState.IDLE;
	private int nextPrayerActionTick = -1;
	private boolean pendingPrayerActivation;

	@Provides
	TargetWeaponConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TargetWeaponConfig.class);
	}

	@Override
	protected void startUp()
	{
		lastTargetName = null;
		lastTargetIndex = -1;
		lastWeaponId = Integer.MIN_VALUE;
		lastPrayerTriggerTick = -9999;
		magicWeaponCache.clear();
		prayerActionState = PrayerActionState.IDLE;
		nextPrayerActionTick = -1;
		pendingPrayerActivation = false;
		recentLogLines.clear();
		if (config.showOverlay())
		{
			overlayManager.add(overlay);
		}
		log.info("Target Weapon ID plugin started");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		log.info("Target Weapon ID plugin stopped");
	}

	@Subscribe
	public void onGameTick(GameTick ignored)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}

		processPrayerStateMachine();

		Actor interacting = client.getLocalPlayer().getInteracting();
		if (!(interacting instanceof Player))
		{
			if (lastTargetIndex != -1)
			{
				lastTargetName = null;
				lastTargetIndex = -1;
				lastWeaponId = Integer.MIN_VALUE;
			}
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

		int targetIndex = target.getId();
		boolean changed = targetIndex != lastTargetIndex || !targetName.equals(lastTargetName) || normalizedWeaponId != lastWeaponId;
		if (!config.logOnlyOnChange() || changed)
		{
			String line = String.format("%s | wpn=%d", targetName, normalizedWeaponId);
			addLine(line);
			log.info("[TARGET] name='{}' combat={} rawWeaponId={} weaponItemId={} tick={}",
				targetName,
				target.getCombatLevel(),
				rawWeaponId,
				normalizedWeaponId,
				client.getTickCount()
			);
		}

		if (changed && config.autoThickSkinOnWeapon())
		{
			boolean shouldTrigger;
			if (config.useMagicWeaponDetection())
			{
				shouldTrigger = isLikelyMagicWeapon(normalizedWeaponId);
			}
			else
			{
				shouldTrigger = normalizedWeaponId == config.triggerWeaponId();
			}

			if (shouldTrigger)
			{
				int tick = client.getTickCount();
				if (tick - lastPrayerTriggerTick >= config.prayerCooldownTicks())
				{
					lastPrayerTriggerTick = tick;
					pendingPrayerActivation = true;
					if (prayerActionState == PrayerActionState.IDLE)
					{
						prayerActionState = config.preferUiPath() ? PrayerActionState.OPEN_PRAYER_TAB : PrayerActionState.ACTIVATE_THICK_SKIN;
						nextPrayerActionTick = tick;
					}
				}
			}
		}

		lastTargetName = targetName;
		lastTargetIndex = targetIndex;
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

	private boolean isLikelyMagicWeapon(int weaponItemId)
	{
		if (weaponItemId <= 0)
		{
			return false;
		}

		Boolean cached = magicWeaponCache.get(weaponItemId);
		if (cached != null)
		{
			return cached;
		}

		boolean result = false;
		ItemStats stats = itemManager.getItemStats(weaponItemId);
		if (stats != null)
		{
			ItemEquipmentStats eq = stats.getEquipment();
			if (eq != null)
			{
				int melee = Math.max(eq.getAstab(), Math.max(eq.getAslash(), eq.getAcrush()));
				int magic = eq.getAmagic();
				int ranged = eq.getArange();
				result = magic > ranged && magic > melee && magic > 0;
			}
		}

		if (!result)
		{
			String name = itemManager.getItemComposition(weaponItemId).getName().toLowerCase();
			result = name.contains("staff") || name.contains("wand") || name.contains("trident") || name.contains("sceptre") || name.contains("kodai") || name.contains("nightmare staff");
		}

		magicWeaponCache.put(weaponItemId, result);
		return result;
	}

	private void processPrayerStateMachine()
	{
		if (!pendingPrayerActivation)
		{
			return;
		}

		int tick = client.getTickCount();
		if (nextPrayerActionTick != -1 && tick < nextPrayerActionTick)
		{
			return;
		}

		if (client.getBoostedSkillLevel(net.runelite.api.Skill.PRAYER) <= 0)
		{
			pendingPrayerActivation = false;
			prayerActionState = PrayerActionState.IDLE;
			return;
		}

		if (client.isPrayerActive(Prayer.THICK_SKIN))
		{
			pendingPrayerActivation = false;
			prayerActionState = PrayerActionState.IDLE;
			return;
		}

		switch (prayerActionState)
		{
			case OPEN_PRAYER_TAB:
				client.menuAction(-1, PRAYER_TAB_WIDGET_ID, MenuAction.CC_OP, 1, 0, "Prayer", "");
				prayerActionState = PrayerActionState.ACTIVATE_THICK_SKIN;
				nextPrayerActionTick = tick + Math.max(0, config.actionDelayTicks());
				break;

			case ACTIVATE_THICK_SKIN:
				client.menuAction(-1, THICK_SKIN_WIDGET_ID, MenuAction.CC_OP, 1, 0, "Activate", "Thick Skin");
				prayerActionState = PrayerActionState.VERIFY;
				nextPrayerActionTick = tick + Math.max(0, config.actionDelayTicks());
				break;

			case VERIFY:
				if (client.isPrayerActive(Prayer.THICK_SKIN))
				{
					addLine("Auto: Thick Skin ON");
					log.info("[TARGET] Trigger condition met, Thick Skin verified ON");
				}
				pendingPrayerActivation = false;
				prayerActionState = PrayerActionState.IDLE;
				nextPrayerActionTick = -1;
				break;

			case IDLE:
			default:
				prayerActionState = config.preferUiPath() ? PrayerActionState.OPEN_PRAYER_TAB : PrayerActionState.ACTIVATE_THICK_SKIN;
				nextPrayerActionTick = tick;
				break;
		}
	}

	private void addLine(String line)
	{
		recentLogLines.addFirst(line);
		while (recentLogLines.size() > MAX_LINES)
		{
			recentLogLines.removeLast();
		}
	}

	List<String> getRecentLogLines()
	{
		return new ArrayList<>(recentLogLines);
	}

	private enum PrayerActionState
	{
		IDLE,
		OPEN_PRAYER_TAB,
		ACTIVATE_THICK_SKIN,
		VERIFY
	}
}
