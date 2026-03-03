package com.jr.prayertoggle;

import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup("prayertoggle")
public interface PrayerToggleConfig extends Config
{
	@ConfigItem(
		keyName = "prayerName",
		name = "Prayer name",
		description = "Prayer to toggle, e.g. Thick Skin"
	)
	default String prayerName()
	{
		return "Thick Skin";
	}

	@ConfigItem(
		keyName = "hotkey",
		name = "Toggle hotkey",
		description = "Press to toggle the selected prayer"
	)
	default Keybind hotkey()
	{
		return new Keybind(KeyEvent.VK_F6, 0);
	}
}
