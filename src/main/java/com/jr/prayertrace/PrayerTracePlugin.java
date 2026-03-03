package com.jr.prayertrace;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Prayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Prayer Trace Logger",
	description = "Instrumentation-only logger for prayer/menu click research",
	tags = {"debug", "prayer", "menu", "trace"}
)
public class PrayerTracePlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private PrayerTraceConfig config;

	@Provides
	PrayerTraceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PrayerTraceConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.info("Prayer Trace Logger started (instrumentation-only)");
	}

	@Override
	protected void shutDown()
	{
		log.info("Prayer Trace Logger stopped");
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		final String option = safe(event.getMenuOption());
		final String target = safe(event.getMenuTarget());
		final String action = String.valueOf(event.getMenuAction());
		final boolean prayerRelated = isPrayerRelated(option, target, action);

		if (!config.logAllMenuClicks() && !prayerRelated)
		{
			return;
		}

		WorldPoint wp = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
		int thickSkinVarbit = client.getVarbitValue(Prayer.THICK_SKIN.getVarbit());
		boolean thickSkinActive = client.isPrayerActive(Prayer.THICK_SKIN);

		log.info(
			"[MENU] prayerRelated={} tick={} option='{}' target='{}' action={} id={} param0={} param1={} itemOp={} itemId={} consumed={} thickSkinActive={} thickSkinVarbit={} playerWp={}",
			prayerRelated,
			client.getTickCount(),
			option,
			target,
			action,
			event.getId(),
			event.getParam0(),
			event.getParam1(),
			event.getItemOp(),
			event.getItemId(),
			event.isConsumed(),
			thickSkinActive,
			thickSkinVarbit,
			wp
		);
	}

	@Subscribe
	public void onGameTick(GameTick ignored)
	{
		if (!config.logVarbitsEachTick())
		{
			return;
		}

		int thickSkinVarbit = client.getVarbitValue(Prayer.THICK_SKIN.getVarbit());
		boolean thickSkinActive = client.isPrayerActive(Prayer.THICK_SKIN);
		log.info("[TICK] tick={} thickSkinVarbit={} thickSkinActive={}", client.getTickCount(), thickSkinVarbit, thickSkinActive);
	}

	private static String safe(String value)
	{
		return value == null ? "" : value;
	}

	private static boolean isPrayerRelated(String option, String target, String action)
	{
		String o = option.toLowerCase();
		String t = target.toLowerCase();
		String a = action.toLowerCase();

		return o.contains("pray")
			|| o.contains("activate")
			|| o.contains("thick skin")
			|| t.contains("thick skin")
			|| t.contains("quick-prayers")
			|| t.contains("prayer")
			|| a.contains("cc_op");
	}
}
