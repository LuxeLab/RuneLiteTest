package com.jr.thickskin;

import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup("thickskin")
public interface ThickSkinConfig extends Config
{
	@ConfigItem(
		keyName = "hotkey",
		name = "Toggle hotkey",
		description = "Press to activate Thick Skin"
	)
	default Keybind hotkey()
	{
		return new Keybind(KeyEvent.VK_F6, 0);
	}

	@ConfigItem(
		keyName = "autoOnLogin",
		name = "Auto activate on login",
		description = "Attempts to turn on Thick Skin once after login"
	)
	default boolean autoOnLogin()
	{
		return false;
	}
}
