package com.jr.targetweapon;

import com.google.inject.Provides;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
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
	private static final int PRAYER_TAB_WIDGET_ID = 10747961; // captured from prayer trace logger

	private String lastTargetName;
	private int lastTargetIndex = -1;
	private int lastWeaponId = Integer.MIN_VALUE;
	private int lastPrayerTriggerTick = -9999;
	private final Map<Integer, CombatStyle> weaponStyleCache = new HashMap<>();
	private final Map<Prayer, Integer> prayerWidgetCache = new HashMap<>();

	private PrayerActionState prayerActionState = PrayerActionState.IDLE;
	private int nextPrayerActionTick = -1;
	private boolean pendingPrayerActivation;
	private Prayer desiredProtectionPrayer;
	private boolean lastActivationAttempted;

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
		weaponStyleCache.clear();
		prayerWidgetCache.clear();
		prayerActionState = PrayerActionState.IDLE;
		nextPrayerActionTick = -1;
		pendingPrayerActivation = false;
		desiredProtectionPrayer = null;
		lastActivationAttempted = false;
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
			CombatStyle style;
			if (config.useMagicWeaponDetection())
			{
				style = classifyWeaponStyle(normalizedWeaponId);
			}
			else
			{
				style = normalizedWeaponId == config.triggerWeaponId() ? CombatStyle.MAGIC : CombatStyle.UNKNOWN;
			}

			Prayer targetPrayer = prayerForStyle(style);
			if (targetPrayer != null)
			{
				int tick = client.getTickCount();
				if (tick - lastPrayerTriggerTick >= config.prayerCooldownTicks())
				{
					lastPrayerTriggerTick = tick;
					pendingPrayerActivation = true;
					desiredProtectionPrayer = targetPrayer;
					lastActivationAttempted = false;
					if (prayerActionState == PrayerActionState.IDLE)
					{
						prayerActionState = config.preferUiPath() ? PrayerActionState.OPEN_PRAYER_TAB : PrayerActionState.ACTIVATE_PROTECTION_PRAYER;
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

	private CombatStyle classifyWeaponStyle(int weaponItemId)
	{
		if (weaponItemId <= 0)
		{
			return CombatStyle.UNKNOWN;
		}

		CombatStyle cached = weaponStyleCache.get(weaponItemId);
		if (cached != null)
		{
			return cached;
		}

		CombatStyle style = CombatStyle.UNKNOWN;
		ItemStats stats = itemManager.getItemStats(weaponItemId);
		if (stats != null)
		{
			ItemEquipmentStats eq = stats.getEquipment();
			if (eq != null)
			{
				int melee = Math.max(eq.getAstab(), Math.max(eq.getAslash(), eq.getAcrush()));
				int magic = eq.getAmagic();
				int ranged = eq.getArange();

				if (magic > ranged && magic > melee && magic > 0)
				{
					style = CombatStyle.MAGIC;
				}
				else if (ranged > magic && ranged > melee && ranged > 0)
				{
					style = CombatStyle.RANGED;
				}
				else if (melee > 0)
				{
					style = CombatStyle.MELEE;
				}
			}
		}

		if (style == CombatStyle.UNKNOWN)
		{
			String name = itemManager.getItemComposition(weaponItemId).getName().toLowerCase();
			if (name.contains("staff") || name.contains("wand") || name.contains("trident") || name.contains("sceptre") || name.contains("kodai"))
			{
				style = CombatStyle.MAGIC;
			}
			else if (name.contains("bow") || name.contains("crossbow") || name.contains("blowpipe") || name.contains("ballista"))
			{
				style = CombatStyle.RANGED;
			}
			else if (name.contains("sword") || name.contains("scimitar") || name.contains("whip") || name.contains("mace") || name.contains("axe") || name.contains("dagger") || name.contains("maul") || name.contains("halberd") || name.contains("spear"))
			{
				style = CombatStyle.MELEE;
			}
		}

		weaponStyleCache.put(weaponItemId, style);
		return style;
	}

	private void processPrayerStateMachine()
	{
		if (!pendingPrayerActivation || desiredProtectionPrayer == null)
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

		if (client.isPrayerActive(desiredProtectionPrayer))
		{
			pendingPrayerActivation = false;
			prayerActionState = PrayerActionState.IDLE;
			desiredProtectionPrayer = null;
			lastActivationAttempted = false;
			return;
		}

		switch (prayerActionState)
		{
			case OPEN_PRAYER_TAB:
				client.menuAction(-1, PRAYER_TAB_WIDGET_ID, MenuAction.CC_OP, 1, 0, "Prayer", "");
				prayerActionState = PrayerActionState.ACTIVATE_PROTECTION_PRAYER;
				nextPrayerActionTick = tick + Math.max(0, config.actionDelayTicks());
				break;

			case ACTIVATE_PROTECTION_PRAYER:
				int widgetId = widgetIdForPrayer(desiredProtectionPrayer);
				if (widgetId == -1)
				{
					addLine("Auto: prayer widget not found for " + prayerLabel(desiredProtectionPrayer));
					pendingPrayerActivation = false;
					prayerActionState = PrayerActionState.IDLE;
					desiredProtectionPrayer = null;
					break;
				}
				client.menuAction(-1, widgetId, MenuAction.CC_OP, 1, 0, "Activate", prayerLabel(desiredProtectionPrayer));
				lastActivationAttempted = true;
				if (config.preferUiPath())
				{
					prayerActionState = PrayerActionState.VERIFY;
					nextPrayerActionTick = tick + Math.max(0, config.actionDelayTicks());
				}
				else
				{
					pendingPrayerActivation = false;
					prayerActionState = PrayerActionState.IDLE;
					nextPrayerActionTick = -1;
					desiredProtectionPrayer = null;
				}
				break;

			case VERIFY:
				if (client.isPrayerActive(desiredProtectionPrayer))
				{
					addLine("Auto: " + prayerLabel(desiredProtectionPrayer) + " ON");
					log.info("[TARGET] Trigger condition met, {} verified ON", desiredProtectionPrayer);
				}
				pendingPrayerActivation = false;
				prayerActionState = PrayerActionState.IDLE;
				nextPrayerActionTick = -1;
				desiredProtectionPrayer = null;
				lastActivationAttempted = false;
				break;

			case IDLE:
			default:
				prayerActionState = config.preferUiPath() ? PrayerActionState.OPEN_PRAYER_TAB : PrayerActionState.ACTIVATE_PROTECTION_PRAYER;
				nextPrayerActionTick = tick;
				break;
		}
	}

	private Prayer prayerForStyle(CombatStyle style)
	{
		switch (style)
		{
			case MAGIC:
				return Prayer.PROTECT_FROM_MAGIC;
			case RANGED:
				return Prayer.PROTECT_FROM_MISSILES;
			case MELEE:
				return Prayer.PROTECT_FROM_MELEE;
			default:
				return null;
		}
	}

	private int widgetIdForPrayer(Prayer prayer)
	{
		Integer cached = prayerWidgetCache.get(prayer);
		if (cached != null)
		{
			return cached;
		}

		String needle = prayerLabel(prayer).toLowerCase();
		Widget root = client.getWidget(WidgetID.PRAYER_GROUP_ID, 0);
		if (root == null)
		{
			return -1;
		}

		Deque<Widget> queue = new ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty())
		{
			Widget w = queue.poll();
			if (w == null)
			{
				continue;
			}

			String n = safe(w.getName()).replace("<col=ff9040>", "").replace("</col>", "").toLowerCase();
			if (n.contains(needle))
			{
				prayerWidgetCache.put(prayer, w.getId());
				return w.getId();
			}

			enqueue(queue, w.getChildren());
			enqueue(queue, w.getDynamicChildren());
			enqueue(queue, w.getStaticChildren());
			enqueue(queue, w.getNestedChildren());
		}

		return -1;
	}

	private static void enqueue(Deque<Widget> queue, Widget[] widgets)
	{
		if (widgets == null)
		{
			return;
		}
		for (Widget w : widgets)
		{
			if (w != null)
			{
				queue.add(w);
			}
		}
	}

	private String prayerLabel(Prayer prayer)
	{
		if (prayer == Prayer.PROTECT_FROM_MAGIC)
		{
			return "Protect from Magic";
		}
		if (prayer == Prayer.PROTECT_FROM_MISSILES)
		{
			return "Protect from Missiles";
		}
		if (prayer == Prayer.PROTECT_FROM_MELEE)
		{
			return "Protect from Melee";
		}
		return Objects.toString(prayer);
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
		ACTIVATE_PROTECTION_PRAYER,
		VERIFY
	}

	private enum CombatStyle
	{
		MAGIC,
		RANGED,
		MELEE,
		UNKNOWN
	}
}
