package com.jr.wikiassistant;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("wikiassistant")
public interface WikiAssistantConfig extends Config
{
	@ConfigItem(
		keyName = "apiBase",
		name = "API base",
		description = "Chat completions endpoint"
	)
	default String apiBase()
	{
		return "https://openrouter.ai/api/v1/chat/completions";
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "API key",
		description = "OpenRouter/OpenAI compatible API key"
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "model",
		name = "Model",
		description = "Model id, e.g. openrouter/auto"
	)
	default String model()
	{
		return "openrouter/auto";
	}
}
