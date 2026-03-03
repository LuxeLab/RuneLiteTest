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
		name = "Auto Thick Skin",
		description = "Automatically turn on Thick Skin based on target weapon"
	)
	default boolean autoThickSkinOnWeapon()
	{
		return true;
	}

	@ConfigItem(
		keyName = "useMagicWeaponDetection",
		name = "Use magic-weapon detection",
		description = "If enabled, turns on Thick Skin when target weapon is classified as magic"
	)
	default boolean useMagicWeaponDetection()
	{
		return true;
	}

	@ConfigItem(
		keyName = "triggerWeaponId",
		name = "Fallback trigger weapon ID",
		description = "Used when magic-weapon detection is off"
	)
	default int triggerWeaponId()
	{
		return 1387;
	}
}
