package com.jr.targetweapon;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class TargetWeaponPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(TargetWeaponPlugin.class);
		RuneLite.main(args);
	}
}
