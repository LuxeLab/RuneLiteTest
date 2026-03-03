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
		keyName = "modelA",
		name = "Model A",
		description = "First model for parallel eval"
	)
	default String modelA()
	{
		return "openai/gpt-4o-mini";
	}

	@ConfigItem(
		keyName = "modelB",
		name = "Model B",
		description = "Second model for parallel eval"
	)
	default String modelB()
	{
		return "google/gemini-2.0-flash-001";
	}

	@ConfigItem(
		keyName = "modelC",
		name = "Model C",
		description = "Third model for parallel eval"
	)
	default String modelC()
	{
		return "anthropic/claude-3.5-haiku";
	}
}
