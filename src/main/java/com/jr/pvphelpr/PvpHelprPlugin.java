package com.jr.pvphelpr;

import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
import net.runelite.api.kit.KitType;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
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

	private boolean defensiveEnabled;
	private int lastTargetId = -1;
	private int lastWeaponId = Integer.MIN_VALUE;
	private int lastPrayerSwitchTick = -9999;
	private final Map<Integer, CombatStyle> weaponStyleCache = new HashMap<>();
	private final Map<Prayer, Integer> prayerWidgetCache = new HashMap<>();

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
		keyManager.registerKeyListener(this);
		log.info("PvP Helpr started. Defensive enabled={}", defensiveEnabled);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(this);
		log.info("PvP Helpr stopped");
	}

	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick ignored)
	{
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
			if (prayer != null)
			{
				int tick = client.getTickCount();
				if (tick - lastPrayerSwitchTick >= 1)
				{
					lastPrayerSwitchTick = tick;
					activatePrayer(prayer);
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
			log.info("[PvP Helpr] Defensive Prayer Switching {}", defensiveEnabled ? "ON" : "OFF");
			return;
		}

		if (config.enablePvpOne() && config.profileOneToggleKey().matches(e))
		{
			clientThread.invoke(this::runProfileOne);
		}
	}

	@Override public void keyTyped(KeyEvent e) {}
	@Override public void keyReleased(KeyEvent e) {}

	private void runProfileOne()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		equipItemsFromInventory(config.gearToEquip());
		enablePrayers(config.prayersToEnable());
		selectSpell(config.spellToCast());

		if (config.attackTarget())
		{
			attackCurrentTarget();
		}

		if (config.enableReverseSwapGear())
		{
			equipItemsFromInventory(config.reverseSwapGear());
		}
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
			return;
		}

		int[] wanted = parseIds(csvIds);
		Item[] items = inv.getItems();

		for (int wantedId : wanted)
		{
			for (int slot = 0; slot < items.length; slot++)
			{
				Item item = items[slot];
				if (item == null || item.getId() != wantedId || item.getQuantity() <= 0)
				{
					continue;
				}

				String name = safe(itemManager.getItemComposition(wantedId).getName());
				client.menuAction(slot, WidgetID.INVENTORY_GROUP_ID << 16, MenuAction.CC_OP, 1, wantedId, "Wield", name);
				client.menuAction(slot, WidgetID.INVENTORY_GROUP_ID << 16, MenuAction.CC_OP, 1, wantedId, "Wear", name);
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
		if (prayer == null || client.isPrayerActive(prayer))
		{
			return;
		}
		activatePrayer(prayer);
	}

	private void activatePrayer(Prayer prayer)
	{
		client.menuAction(-1, PRAYER_TAB_WIDGET_ID, MenuAction.CC_OP, 1, 0, "Prayer", "");
		int widgetId = widgetIdForPrayer(prayer);
		if (widgetId == -1)
		{
			log.info("[PvP Helpr] Prayer widget not found for {}", prayer);
			return;
		}
		client.menuAction(-1, widgetId, MenuAction.CC_OP, 1, 0, "Activate", prayerLabel(prayer));
	}

	private void selectSpell(PvpHelprSpell spell)
	{
		if (spell == null || spell == PvpHelprSpell.NONE)
		{
			return;
		}

		Widget root = client.getWidget(WidgetID.SPELLBOOK_GROUP_ID, 0);
		if (root == null)
		{
			return;
		}

		int widgetId = findWidgetByName(root, spell.label());
		if (widgetId == -1)
		{
			log.info("[PvP Helpr] Spell widget not found: {}", spell.label());
			return;
		}

		client.menuAction(-1, widgetId, MenuAction.CC_OP, 1, 0, "Cast", spell.label());
	}

	private void attackCurrentTarget()
	{
		if (client.getLocalPlayer() == null)
		{
			return;
		}
		Actor interacting = client.getLocalPlayer().getInteracting();
		if (!(interacting instanceof Player))
		{
			return;
		}

		Player target = (Player) interacting;
		client.menuAction(0, 0, MenuAction.PLAYER_FIRST_OPTION, target.getId(), 0, "Attack", safe(target.getName()));
	}

	private int findWidgetByName(Widget root, String needle)
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
				return w.getId();
			}
			enqueue(q, w.getChildren());
			enqueue(q, w.getDynamicChildren());
			enqueue(q, w.getStaticChildren());
			enqueue(q, w.getNestedChildren());
		}
		return -1;
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

		int id = findWidgetByName(root, prayerLabel(prayer));
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

	private enum CombatStyle
	{
		MAGIC,
		RANGED,
		MELEE,
		UNKNOWN
	}
}
