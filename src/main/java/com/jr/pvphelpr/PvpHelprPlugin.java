package com.jr.pvphelpr;

import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Prayer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.kit.KitType;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "PvP Helpr",
	description = "PvP helper with defensive prayer switching + hotkey profile",
	tags = {"pvp", "helper", "prayer", "switch"}
)
public class PvpHelprPlugin extends Plugin implements KeyListener
{
	private static final int PRAYER_TAB_WIDGET_ID = 10747961;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private PvpHelprConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PvpHelprOverlay overlay;

	private boolean defensiveEnabled;
	private final Deque<String> debugLines = new ArrayDeque<>();
	private static final int MAX_DEBUG_LINES = 10;
	private int lastTargetId = -1;
	private int lastWeaponId = Integer.MIN_VALUE;
	private int lastPrayerSwitchTick = -9999;
	private String pendingSpellVerifyLabel;
	private int pendingSpellVerifyTick = -1;
	private final Map<Integer, CombatStyle> weaponStyleCache = new HashMap<>();
	private final Map<Prayer, Integer> prayerWidgetCache = new HashMap<>();
	private boolean pendingCastOnTarget;
	private int pendingCastOnTargetTick = -1;

	@Provides
	PvpHelprConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PvpHelprConfig.class);
	}

	@Override
	protected void startUp()
	{
		defensiveEnabled = config.defensiveEnabledByDefault();
		lastTargetId = -1;
		lastWeaponId = Integer.MIN_VALUE;
		weaponStyleCache.clear();
		prayerWidgetCache.clear();
		debugLines.clear();
		pendingSpellVerifyLabel = null;
		pendingSpellVerifyTick = -1;
		keyManager.registerKeyListener(this);
		if (config.showOverlay())
		{
			overlayManager.add(overlay);
		}
		logStep("Startup complete. Defensive=" + defensiveEnabled);
		logStep("Overlay=" + (config.showOverlay() ? "ON" : "OFF"));
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(this);
		overlayManager.remove(overlay);
		log.info("PvP Helpr stopped");
	}

	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick ignored)
	{
		if (pendingSpellVerifyLabel != null && client.getTickCount() >= pendingSpellVerifyTick)
		{
			verifySelectedSpell();
		}
		if (pendingCastOnTarget && client.getTickCount() >= pendingCastOnTargetTick)
		{
			pendingCastOnTarget = false;
			pendingCastOnTargetTick = -1;
			if (!castSelectedSpellOnCurrentTarget())
			{
				logStep("Deferred cast-on-target failed");
			}
		}
		if (!defensiveEnabled || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}

		Actor interacting = client.getLocalPlayer().getInteracting();
		if (!(interacting instanceof Player))
		{
			lastTargetId = -1;
			lastWeaponId = Integer.MIN_VALUE;
			return;
		}

		Player target = (Player) interacting;
		PlayerComposition comp = target.getPlayerComposition();
		if (comp == null)
		{
			return;
		}

		int targetId = target.getId();
		int rawWeaponId = comp.getEquipmentId(KitType.WEAPON);
		int weaponItemId = normalizeItemId(rawWeaponId);
		boolean changed = targetId != lastTargetId || weaponItemId != lastWeaponId;

		if (changed)
		{
			CombatStyle style = classifyWeaponStyle(weaponItemId);
			Prayer prayer = defensivePrayerForStyle(style);
			logStep("Defensive detect: target=" + safe(target.getName()) + " weapon=" + weaponItemId + " style=" + style + " prayer=" + (prayer == null ? "none" : prayerLabel(prayer)));
			if (prayer != null)
			{
				int tick = client.getTickCount();
				if (tick - lastPrayerSwitchTick >= 1)
				{
					lastPrayerSwitchTick = tick;
					activatePrayer(prayer, false);
				}
				else
				{
					logStep("Defensive prayer skipped due to cooldown");
				}
			}
		}

		lastTargetId = targetId;
		lastWeaponId = weaponItemId;
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (config.defensiveToggleKey().matches(e))
		{
			defensiveEnabled = !defensiveEnabled;
			logStep("Defensive Prayer Switching " + (defensiveEnabled ? "ON" : "OFF"));
			return;
		}

		if (config.enablePvpOne() && config.profileOneToggleKey().matches(e))
		{
			logStep("Profile One hotkey pressed");
			new Thread(this::runProfileOneAsync, "pvphelpr-profile1").start();
		}
	}

	@Override public void keyTyped(KeyEvent e) {}
	@Override public void keyReleased(KeyEvent e) {}

	private void runProfileOneAsync()
	{
		if (!runClientStep(() -> client.getGameState() == GameState.LOGGED_IN, "check-login"))
		{
			logStep("Profile One aborted: not logged in");
			return;
		}

		logStep("Profile One start");

		randomDelay("equip-phase");
		runClientStep(() ->
		{
			equipItemsFromInventory(config.gearToEquip());
			return true;
		}, "equip-items");

		randomDelay("prayer-phase");
		runClientStep(() ->
		{
			enablePrayers(config.prayersToEnable());
			return true;
		}, "enable-prayers");

		randomDelay("spell-phase");
		runClientStep(() ->
		{
			selectSpell(config.spellToCast());
			return true;
		}, "select-spell");

		if (config.attackTarget())
		{
			randomDelay("attack-phase");
			runClientStep(() ->
			{
				if (config.spellToCast() != null && config.spellToCast() != PvpHelprSpell.NONE)
				{
					pendingCastOnTarget = true;
					pendingCastOnTargetTick = client.getTickCount() + 1;
					logStep("Queued cast-on-target for next tick");
				}
				else if (!castSelectedSpellOnCurrentTarget())
				{
					attackCurrentTarget();
				}
				return true;
			}, "attack-target");
		}

		if (config.enableReverseSwapGear())
		{
			logStep("Reverse swap enabled");
			randomDelay("reverse-swap-phase");
			runClientStep(() ->
			{
				equipItemsFromInventory(config.reverseSwapGear());
				return true;
			}, "reverse-swap");
		}

		logStep("Profile One complete");
	}

	private void equipItemsFromInventory(String csvIds)
	{
		if (csvIds == null || csvIds.isBlank())
		{
			return;
		}

		ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv == null)
		{
			logStep("Equip aborted: inventory unavailable");
			return;
		}

		int[] wanted = parseIds(csvIds);
		Item[] items = inv.getItems();

		int invWidgetId = WidgetInfo.INVENTORY.getId();
		for (int wantedId : wanted)
		{
			boolean equipped = false;
			for (int slot = 0; slot < items.length; slot++)
			{
				Item item = items[slot];
				if (item == null || item.getId() != wantedId || item.getQuantity() <= 0)
				{
					continue;
				}

				String name = safe(itemManager.getItemComposition(wantedId).getName());
				String firstInvAction = "";
				String[] invActions = itemManager.getItemComposition(wantedId).getInventoryActions();
				if (invActions != null && invActions.length > 0 && invActions[0] != null)
				{
					firstInvAction = invActions[0];
				}
				logStep("Equip try: " + name + " (" + wantedId + ") slot=" + slot + " firstAction=" + firstInvAction);

				if (!firstInvAction.isBlank() && !firstInvAction.equalsIgnoreCase("Drop"))
				{
					client.menuAction(slot, invWidgetId, MenuAction.ITEM_FIRST_OPTION, wantedId, 0, firstInvAction, name);
					logStep("Equip send ITEM_FIRST_OPTION -> " + firstInvAction);
				}
				else
				{
					// Fallback explicit equip actions. Manual log shows Wield uses CC_OP id=3.
					client.menuAction(slot, invWidgetId, MenuAction.CC_OP, 3, wantedId, "Wield", name);
					client.menuAction(slot, invWidgetId, MenuAction.CC_OP, 4, wantedId, "Wear", name);
					client.menuAction(slot, invWidgetId, MenuAction.CC_OP, 3, wantedId, "Equip", name);
					logStep("Equip send fallback actions: CC_OP id=3/4");
				}

				equipped = true;
				break;
			}
		}
	}

	private void enablePrayers(String csvPrayers)
	{
		if (csvPrayers == null || csvPrayers.isBlank())
		{
			return;
		}

		Arrays.stream(csvPrayers.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.forEach(this::activatePrayerByName);
	}

	private void activatePrayerByName(String prayerName)
	{
		Prayer prayer = parsePrayer(prayerName);
		if (prayer == null)
		{
			logStep("Prayer parse failed for input: " + prayerName);
			return;
		}
		if (client.isPrayerActive(prayer))
		{
			logStep("Prayer already active: " + prayerLabel(prayer));
			return;
		}
		activatePrayer(prayer, false);
	}

	private void activatePrayer(Prayer prayer, boolean openPrayerTabFirst)
	{
		if (openPrayerTabFirst)
		{
			client.menuAction(-1, PRAYER_TAB_WIDGET_ID, MenuAction.CC_OP, 1, 0, "Prayer", "");
		}
		else
		{
			logStep("Prayer action without opening prayer tab");
		}
		int widgetId = widgetIdForPrayer(prayer);
		if (widgetId == -1)
		{
			logStep("Prayer widget not found for " + prayerLabel(prayer) + " (openTab=" + openPrayerTabFirst + ")");
			return;
		}
		client.menuAction(-1, widgetId, MenuAction.CC_OP, 1, 0, "Activate", prayerLabel(prayer));
		logStep("Prayer action sent: " + prayerLabel(prayer));
	}

	private void selectSpell(PvpHelprSpell spell)
	{
		if (spell == null || spell == PvpHelprSpell.NONE)
		{
			logStep("Spell select skipped: NONE");
			return;
		}

		Widget root = client.getWidget(WidgetID.SPELLBOOK_GROUP_ID, 0);
		if (root == null)
		{
			logStep("Spell select aborted: spellbook root missing");
			return;
		}

		Widget spellWidget = findWidgetObjectByName(root, spell.label());
		if (spellWidget == null)
		{
			logStep("Spell widget not found: " + spell.label());
			return;
		}

		client.menuAction(-1, spellWidget.getId(), MenuAction.WIDGET_TARGET, 0, -1, "Cast", spell.label());
		logStep("Spell cast action sent: " + spell.label() + " widgetId=" + spellWidget.getId() + " action=WIDGET_TARGET");

		pendingSpellVerifyLabel = spell.label();
		pendingSpellVerifyTick = client.getTickCount() + 1;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		String msg = safe(event.getMessage()).toLowerCase(Locale.ROOT);
		if (msg.contains("members' world") && msg.contains("cast that spell"))
		{
			logStep("Game message: members-world restriction blocked spell cast");
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = safe(event.getMenuOption()).toLowerCase(Locale.ROOT);
		if (!(option.equals("wield") || option.equals("wear") || option.equals("drop") || option.equals("use") || option.equals("activate") || option.equals("cast") || option.equals("attack")))
		{
			return;
		}
		log.info("[PvP Helpr] MenuOptionClicked option='{}' target='{}' action={} id={} param0={} param1={} itemId={} itemOp={}",
			event.getMenuOption(), event.getMenuTarget(), event.getMenuAction(), event.getId(), event.getParam0(), event.getParam1(), event.getItemId(), event.getItemOp());
		if (option.equals("wield") || option.equals("wear"))
		{
			logStep("Observed manual equip signature: action=" + event.getMenuAction() + " id=" + event.getId() + " param0=" + event.getParam0() + " param1=" + event.getParam1() + " itemId=" + event.getItemId());
		}
		if (option.equals("cast"))
		{
			logStep("Observed cast signature: action=" + event.getMenuAction() + " id=" + event.getId() + " param0=" + event.getParam0() + " param1=" + event.getParam1());
		}
	}

	private boolean castSelectedSpellOnCurrentTarget()
	{
		if (!client.isWidgetSelected())
		{
			logStep("Cast-on-target skipped: no selected spell/widget");
			return false;
		}

		if (client.getLocalPlayer() == null)
		{
			logStep("Cast-on-target skipped: local player null");
			return false;
		}

		Actor interacting = client.getLocalPlayer().getInteracting();
		if (!(interacting instanceof Player))
		{
			logStep("Cast-on-target skipped: no player target");
			return false;
		}

		Player target = (Player) interacting;
		client.menuAction(0, 0, MenuAction.WIDGET_TARGET_ON_PLAYER, target.getId(), 0, "Cast", safe(target.getName()));
		logStep("Cast-on-target action sent -> " + safe(target.getName()) + " (id=" + target.getId() + ")");
		return true;
	}

	private void attackCurrentTarget()
	{
		if (client.getLocalPlayer() == null)
		{
			logStep("Attack skipped: local player null");
			return;
		}
		Actor interacting = client.getLocalPlayer().getInteracting();
		if (!(interacting instanceof Player))
		{
			logStep("Attack skipped: no player target");
			return;
		}

		Player target = (Player) interacting;
		client.menuAction(0, 0, MenuAction.PLAYER_FIRST_OPTION, target.getId(), 0, "Attack", safe(target.getName()));
		logStep("Attack action sent -> " + safe(target.getName()) + " (id=" + target.getId() + ")");
	}

	private Widget findWidgetObjectByName(Widget root, String needle)
	{
		Deque<Widget> q = new ArrayDeque<>();
		q.add(root);
		String n = needle.toLowerCase(Locale.ROOT);
		while (!q.isEmpty())
		{
			Widget w = q.poll();
			if (w == null)
			{
				continue;
			}
			String name = safe(w.getName()).toLowerCase(Locale.ROOT);
			if (!name.isEmpty() && name.contains(n))
			{
				return w;
			}
			enqueue(q, w.getChildren());
			enqueue(q, w.getDynamicChildren());
			enqueue(q, w.getStaticChildren());
			enqueue(q, w.getNestedChildren());
		}
		return null;
	}

	private int widgetIdForPrayer(Prayer prayer)
	{
		Integer cached = prayerWidgetCache.get(prayer);
		if (cached != null)
		{
			return cached;
		}

		Widget root = client.getWidget(WidgetID.PRAYER_GROUP_ID, 0);
		if (root == null)
		{
			return -1;
		}

		Widget prayerWidget = findWidgetObjectByName(root, prayerLabel(prayer));
		int id = prayerWidget == null ? -1 : prayerWidget.getId();
		if (id != -1)
		{
			prayerWidgetCache.put(prayer, id);
		}
		return id;
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
			String name = itemManager.getItemComposition(weaponItemId).getName().toLowerCase(Locale.ROOT);
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

	private Prayer defensivePrayerForStyle(CombatStyle style)
	{
		switch (style)
		{
			case MAGIC:
				return config.considerMagicWeapons() ? Prayer.PROTECT_FROM_MAGIC : null;
			case RANGED:
				return config.considerRangeWeapons() ? Prayer.PROTECT_FROM_MISSILES : null;
			case MELEE:
				return config.considerMeleeWeapons() ? Prayer.PROTECT_FROM_MELEE : null;
			default:
				return null;
		}
	}

	private Prayer parsePrayer(String value)
	{
		String v = value.toLowerCase(Locale.ROOT).trim();
		if (v.equals("thick skin")) return Prayer.THICK_SKIN;
		if (v.equals("protect from magic") || v.equals("pfm")) return Prayer.PROTECT_FROM_MAGIC;
		if (v.equals("protect from missiles") || v.equals("protect from missles") || v.equals("pfr")) return Prayer.PROTECT_FROM_MISSILES;
		if (v.equals("protect from melee") || v.equals("pfmlee")) return Prayer.PROTECT_FROM_MELEE;
		if (v.equals("rigour")) return Prayer.RIGOUR;
		if (v.equals("augury")) return Prayer.AUGURY;
		if (v.equals("piety")) return Prayer.PIETY;
		if (v.equals("eagle eye")) return Prayer.EAGLE_EYE;
		if (v.equals("mystic might")) return Prayer.MYSTIC_MIGHT;
		return null;
	}

	private String prayerLabel(Prayer prayer)
	{
		if (prayer == Prayer.THICK_SKIN) return "Thick Skin";
		if (prayer == Prayer.PROTECT_FROM_MAGIC) return "Protect from Magic";
		if (prayer == Prayer.PROTECT_FROM_MISSILES) return "Protect from Missiles";
		if (prayer == Prayer.PROTECT_FROM_MELEE) return "Protect from Melee";
		if (prayer == Prayer.RIGOUR) return "Rigour";
		if (prayer == Prayer.AUGURY) return "Augury";
		if (prayer == Prayer.PIETY) return "Piety";
		if (prayer == Prayer.EAGLE_EYE) return "Eagle Eye";
		if (prayer == Prayer.MYSTIC_MIGHT) return "Mystic Might";
		return prayer == null ? "Unknown" : prayer.name();
	}

	private static int[] parseIds(String csv)
	{
		return Arrays.stream(csv.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.mapToInt(s ->
			{
				try { return Integer.parseInt(s); }
				catch (Exception e) { return -1; }
			})
			.filter(i -> i > 0)
			.toArray();
	}

	private static int normalizeItemId(int equipmentId)
	{
		if (equipmentId <= 0)
		{
			return -1;
		}
		if (equipmentId >= PlayerComposition.ITEM_OFFSET)
		{
			return equipmentId - PlayerComposition.ITEM_OFFSET;
		}
		return equipmentId;
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

	private static String safe(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replaceAll("<[^>]+>", "");
	}

	private void logStep(String message)
	{
		String line = "[PvP Helpr] " + message;
		log.info(line);
		debugLines.addFirst(message);
		while (debugLines.size() > MAX_DEBUG_LINES)
		{
			debugLines.removeLast();
		}
	}

	private void randomDelay(String phase)
	{
		int min = Math.max(0, config.actionDelayMinMs());
		int max = Math.max(0, config.actionDelayMaxMs());
		if (max < min)
		{
			int t = min;
			min = max;
			max = t;
		}
		if (max <= 0)
		{
			return;
		}
		int delay = (max == min) ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
		if (delay <= 0)
		{
			return;
		}
		logStep("Delay " + delay + "ms before " + phase);
		try
		{
			Thread.sleep(delay);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	private boolean runClientStep(java.util.concurrent.Callable<Boolean> step, String stepName)
	{
		CountDownLatch latch = new CountDownLatch(1);
		final boolean[] ok = new boolean[]{false};
		clientThread.invoke(() ->
		{
			try
			{
				ok[0] = step.call();
			}
			catch (Exception e)
			{
				logStep("Step failed: " + stepName + " -> " + e.getClass().getSimpleName());
			}
			finally
			{
				latch.countDown();
			}
		});

		try
		{
			if (!latch.await(5, TimeUnit.SECONDS))
			{
				logStep("Step timeout: " + stepName);
				return false;
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return false;
		}
		return ok[0];
	}

	List<String> getOverlayLines()
	{
		return new ArrayList<>(debugLines);
	}

	private void verifySelectedSpell()
	{
		String expected = pendingSpellVerifyLabel;
		pendingSpellVerifyLabel = null;
		pendingSpellVerifyTick = -1;

		if (expected == null)
		{
			return;
		}

		Widget selected = client.getSelectedWidget();
		boolean selectedFlag = client.isWidgetSelected();
		String selectedName = selected == null ? "" : safe(selected.getName());

		if (selectedFlag && selectedName.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)))
		{
			logStep("Spell verify OK: " + expected + " selectedWidget='" + selectedName + "'");
		}
		else
		{
			logStep("Spell verify FAIL: expected='" + expected + "' selectedFlag=" + selectedFlag + " selectedWidget='" + selectedName + "'");
		}
	}

	private enum CombatStyle
	{
		MAGIC,
		RANGED,
		MELEE,
		UNKNOWN
	}
}
