package com.jr.targetweapon;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("targetweapon")
public interface TargetWeaponConfig extends Config
{
	@ConfigItem(
		keyName = "logOnlyOnChange",
		name = "Log only on change",
		description = "If enabled, logs only when target/weapon changes"
	)
	default boolean logOnlyOnChange()
	{
		return true;
	}
}
