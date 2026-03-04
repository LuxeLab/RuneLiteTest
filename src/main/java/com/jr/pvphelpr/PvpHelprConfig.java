package com.jr.pvphelpr;

import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup("pvphelpr")
public interface PvpHelprConfig extends Config
{
	@ConfigSection(
		name = "Defensive Prayer Switching",
		description = "Auto defensive prayer switching based on target weapon style",
		position = 0
	)
	String defensiveSection = "defensiveSection";

	@ConfigItem(
		keyName = "defensiveEnabledByDefault",
		name = "Enabled by Default",
		description = "Enable defensive prayer switching on startup",
		position = 1,
		section = defensiveSection
	)
	default boolean defensiveEnabledByDefault()
	{
		return true;
	}

	@ConfigItem(
		keyName = "defensiveToggleKey",
		name = "Toggle Key",
		description = "Toggle defensive prayer switching",
		position = 2,
		section = defensiveSection
	)
	default Keybind defensiveToggleKey()
	{
		return new Keybind(KeyEvent.VK_NUMPAD9, 0);
	}

	@ConfigItem(
		keyName = "considerMeleeWeapons",
		name = "Consider Melee Weapons",
		description = "Switch to Protect from Melee",
		position = 3,
		section = defensiveSection
	)
	default boolean considerMeleeWeapons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "considerRangeWeapons",
		name = "Consider Range Weapons",
		description = "Switch to Protect from Missiles",
		position = 4,
		section = defensiveSection
	)
	default boolean considerRangeWeapons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "considerMagicWeapons",
		name = "Consider Magic Weapons",
		description = "Switch to Protect from Magic",
		position = 5,
		section = defensiveSection
	)
	default boolean considerMagicWeapons()
	{
		return true;
	}

	@ConfigSection(
		name = "Hotkey Profile One",
		description = "One-key gear/prayer/spell/action profile",
		position = 10
	)
	String profileOneSection = "profileOneSection";

	@ConfigItem(
		keyName = "enablePvpOne",
		name = "Enable PvP One",
		description = "Enable profile one hotkey",
		position = 11,
		section = profileOneSection
	)
	default boolean enablePvpOne()
	{
		return true;
	}

	@ConfigItem(
		keyName = "profileOneToggleKey",
		name = "Toggle Key",
		description = "Trigger Profile One",
		position = 12,
		section = profileOneSection
	)
	default Keybind profileOneToggleKey()
	{
		return new Keybind(KeyEvent.VK_F7, 0);
	}

	@ConfigItem(
		keyName = "gearToEquip",
		name = "Gear to Equip",
		description = "Comma-separated item IDs to equip from inventory",
		position = 13,
		section = profileOneSection
	)
	default String gearToEquip()
	{
		return "";
	}

	@ConfigItem(
		keyName = "prayersToEnable",
		name = "Prayers to Enable",
		description = "Comma-separated prayer names",
		position = 14,
		section = profileOneSection
	)
	default String prayersToEnable()
	{
		return "";
	}

	@ConfigItem(
		keyName = "spellToCast",
		name = "Spells to Cast",
		description = "Select a spell in spellbook",
		position = 15,
		section = profileOneSection
	)
	default PvpHelprSpell spellToCast()
	{
		return PvpHelprSpell.NONE;
	}

	@ConfigItem(
		keyName = "attackTarget",
		name = "Attack Target",
		description = "Attempt attack on current target",
		position = 16,
		section = profileOneSection
	)
	default boolean attackTarget()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableReverseSwapGear",
		name = "Enable Reverse Swap Gear",
		description = "Equip reverse-swap gear after attack",
		position = 17,
		section = profileOneSection
	)
	default boolean enableReverseSwapGear()
	{
		return false;
	}

	@ConfigItem(
		keyName = "reverseSwapGear",
		name = "Reverse Swap Gear",
		description = "Comma-separated item IDs for reverse swap",
		position = 18,
		section = profileOneSection
	)
	default String reverseSwapGear()
	{
		return "";
	}
}
