package com.jr.wikiassistant;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class WikiAssistantPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(WikiAssistantPlugin.class);
		RuneLite.main(args);
	}
}
