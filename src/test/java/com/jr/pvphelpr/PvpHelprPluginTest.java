package com.jr.pvphelpr;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PvpHelprPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PvpHelprPlugin.class);
		RuneLite.main(args);
	}
}
