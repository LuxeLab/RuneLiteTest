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

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Display recent target weapon logs in overlay"
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoThickSkinOnWeapon",
		name = "Auto Thick Skin on weapon",
		description = "Turn on Thick Skin if target equips trigger weapon ID"
	)
	default boolean autoThickSkinOnWeapon()
	{
		return true;
	}

	@ConfigItem(
		keyName = "triggerWeaponId",
		name = "Trigger weapon ID",
		description = "Normalized weapon item ID that triggers Thick Skin"
	)
	default int triggerWeaponId()
	{
		return 1387;
	}
}
