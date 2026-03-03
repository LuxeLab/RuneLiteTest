package com.jr.wikiassistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

@Slf4j
@Singleton
public class WikiAssistantService
{
	private static final String WIKI_BASE = "https://oldschool.runescape.wiki";

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private WikiAssistantConfig config;

	private static final Map<Integer, Double> COOKING_XP = new HashMap<>();
	static
	{
		// MVP subset, sourced from OSRS Wiki values.
		COOKING_XP.put(ItemID.RAW_SHRIMPS, 30.0);
		COOKING_XP.put(ItemID.RAW_ANCHOVIES, 30.0);
		COOKING_XP.put(ItemID.RAW_SARDINE, 40.0);
		COOKING_XP.put(ItemID.RAW_HERRING, 50.0);
		COOKING_XP.put(ItemID.RAW_TROUT, 70.0);
		COOKING_XP.put(ItemID.RAW_SALMON, 90.0);
		COOKING_XP.put(ItemID.RAW_TUNA, 100.0);
		COOKING_XP.put(ItemID.RAW_LOBSTER, 120.0);
		COOKING_XP.put(ItemID.RAW_SWORDFISH, 140.0);
		COOKING_XP.put(ItemID.RAW_MONKFISH, 150.0);
		COOKING_XP.put(ItemID.RAW_SHARK, 210.0);
		COOKING_XP.put(ItemID.RAW_SEA_TURTLE, 211.3);
		COOKING_XP.put(ItemID.RAW_MANTA_RAY, 216.3);
	}

	public String answer(String question)
	{
		log.info("[WikiAssistantService] answer() question={}", question);
		String q = question.toLowerCase(Locale.ROOT).trim();

		if (isCapabilityQuestion(q))
		{
			return capabilityHelpText();
		}

		if (q.contains("cooking") && q.contains("bank") && (q.contains("level") || q.contains("xp")))
		{
			log.info("[WikiAssistantService] routing to cooking projection");
			return answerCookingProjection();
		}

		log.info("[WikiAssistantService] routing to wiki+ai");
		return answerFromWikiWithAi(question);
	}

	private String answerCookingProjection()
	{
		log.info("[WikiAssistantService] answerCookingProjection() start");

		final Item[] bankItems = getBankItemsSnapshot();
		if (bankItems == null)
		{
			log.info("[WikiAssistantService] bank container is null");
			return "Open your bank first so I can read raw food quantities.";
		}

		double totalXp = 0;
		StringBuilder breakdown = new StringBuilder();
		for (Item item : bankItems)
		{
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}

			Double xp = COOKING_XP.get(item.getId());
			if (xp == null)
			{
				continue;
			}

			double gained = xp * item.getQuantity();
			totalXp += gained;
			String name = getItemNameSnapshot(item.getId());
			breakdown.append(String.format("- %s x%d => %.1f xp%n", name, item.getQuantity(), gained));
		}

		if (totalXp <= 0)
		{
			log.info("[WikiAssistantService] no cookable foods found in bank subset");
			return "I couldn't find recognized raw cookable foods in your bank (MVP list).";
		}

		int currentXp = getCookingXpSnapshot();
		int currentLevel = Experience.getLevelForXp(currentXp);
		double projectedXp = currentXp + totalXp;
		int projectedLevel = Experience.getLevelForXp((int) projectedXp);

		StringBuilder out = new StringBuilder();
		out.append("Cooking projection (bank raw foods)\n\n")
			.append(String.format("Current: level %d (%d xp)%n", currentLevel, currentXp))
			.append(String.format("Potential gain: %.1f xp%n", totalXp))
			.append(String.format("Projected: level %d (%d xp)%n%n", projectedLevel, (int) projectedXp))
			.append("Breakdown:\n")
			.append(breakdown)
			.append("\nSource: OSRS Wiki cooking xp values (MVP subset) + live bank data from RuneLite.\n")
			.append("Wiki: https://oldschool.runescape.wiki/w/Cooking");

		log.info("[WikiAssistantService] cooking projection complete totalXp={} projectedLevel={}", totalXp, projectedLevel);
		return out.toString();
	}

	private String answerFromWikiWithAi(String question)
	{
		try
		{
			log.info("[WikiAssistantService] answerFromWikiWithAi start");
			String searchUrl = WIKI_BASE + "/api.php?action=query&list=search&srsearch="
				+ URLEncoder.encode(question, StandardCharsets.UTF_8)
				+ "&format=json&srlimit=5";

			JsonObject searchJson = getJson(searchUrl);
			JsonArray results = searchJson.getAsJsonObject("query").getAsJsonArray("search");
			if (results.size() == 0)
			{
				return "No wiki results found for that question.";
			}

			StringBuilder sources = new StringBuilder();
			for (int i = 0; i < Math.min(5, results.size()); i++)
			{
				JsonObject r = results.get(i).getAsJsonObject();
				String title = r.get("title").getAsString();
				String snippet = r.get("snippet").getAsString()
					.replace("<span class=\"searchmatch\">", "")
					.replace("</span>", "")
					.replace("&quot;", "\"");
				String pageUrl = WIKI_BASE + "/w/" + title.replace(' ', '_');
				sources.append("- ").append(title).append("\n")
					.append("  ").append(snippet).append("\n")
					.append("  ").append(pageUrl).append("\n");
			}

			if (config.apiKey() == null || config.apiKey().isBlank())
			{
				return "AI mode is enabled, but no API key is configured.\n\n"
					+ "Set Wiki Assistant config -> API key, then ask again.\n\nTop wiki sources:\n"
					+ sources;
			}

			return askModel(question, sources.toString());
		}
		catch (Exception e)
		{
			log.error("Wiki+AI failed", e);
			return "Failed to query wiki/AI right now: " + e.getMessage();
		}
	}

	private Item[] getBankItemsSnapshot()
	{
		CountDownLatch latch = new CountDownLatch(1);
		final Item[][] result = new Item[1][];

		clientThread.invoke(() ->
		{
			try
			{
				ItemContainer bank = client.getItemContainer(InventoryID.BANK);
				result[0] = bank == null ? null : bank.getItems();
			}
			finally
			{
				latch.countDown();
			}
		});

		try
		{
			if (!latch.await(3, TimeUnit.SECONDS))
			{
				log.warn("Timed out reading bank snapshot on client thread");
				return null;
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return null;
		}

		return result[0];
	}

	private String getItemNameSnapshot(int itemId)
	{
		CountDownLatch latch = new CountDownLatch(1);
		final String[] name = new String[]{"item:" + itemId};

		clientThread.invoke(() ->
		{
			try
			{
				name[0] = itemManager.getItemComposition(itemId).getName();
			}
			catch (Exception ignored)
			{
				name[0] = "item:" + itemId;
			}
			finally
			{
				latch.countDown();
			}
		});

		try
		{
			latch.await(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}

		return name[0];
	}

	private int getCookingXpSnapshot()
	{
		CountDownLatch latch = new CountDownLatch(1);
		AtomicInteger xp = new AtomicInteger(0);

		clientThread.invoke(() ->
		{
			try
			{
				xp.set(client.getSkillExperience(Skill.COOKING));
			}
			finally
			{
				latch.countDown();
			}
		});

		try
		{
			latch.await(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}

		return xp.get();
	}

	private boolean isCapabilityQuestion(String q)
	{
		return q.equals("what can you help me with")
			|| q.equals("what can you do")
			|| q.contains("help me with")
			|| q.contains("what can i ask")
			|| q.contains("capabilities");
	}

	private String capabilityHelpText()
	{
		return "I’m best at wiki-grounded OSRS answers + live account context.\n\n"
			+ "What I can do right now:\n"
			+ "• Cooking projection from your bank raw foods\n"
			+ "• Explain quests, items, bosses, skilling methods from OSRS Wiki\n"
			+ "• Compare methods and summarize wiki pages with links\n"
			+ "• Use your current in-game context (skills/inventory/bank when available)\n\n"
			+ "Best question formats:\n"
			+ "1) ‘What cooking level can I reach with raw food in my bank?’\n"
			+ "2) ‘What are good money makers for my stats?’\n"
			+ "3) ‘How do I start [quest name] and what are requirements?’\n"
			+ "4) ‘Compare X vs Y training methods for [skill]’\n\n"
			+ "Source of truth: https://oldschool.runescape.wiki/";
	}

	private String askModel(String question, String sources) throws Exception
	{
		JsonObject body = new JsonObject();
		body.addProperty("model", config.model());

		JsonArray messages = new JsonArray();
		JsonObject system = new JsonObject();
		system.addProperty("role", "system");
		system.addProperty("content", "You are an OSRS assistant. Answer only from the provided OSRS Wiki snippets and links. If uncertain, say so. Keep answer concise and include a Sources section with the links you used.");
		messages.add(system);

		JsonObject user = new JsonObject();
		user.addProperty("role", "user");
		user.addProperty("content", "Question: " + question + "\n\nWiki sources:\n" + sources);
		messages.add(user);

		body.add("messages", messages);
		body.addProperty("temperature", 0.2);

		JsonObject resp = postJson(config.apiBase(), body, config.apiKey());
		JsonArray choices = resp.getAsJsonArray("choices");
		if (choices == null || choices.size() == 0)
		{
			return "Model returned no answer.\n\nSources:\n" + sources;
		}

		JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
		String content = msg.get("content").getAsString();
		return content + "\n\nSource of truth: https://oldschool.runescape.wiki/";
	}

	private static JsonObject postJson(String url, JsonObject payload, String apiKey) throws Exception
	{
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setRequestProperty("Authorization", "Bearer " + apiKey);
		conn.setRequestProperty("User-Agent", "RuneLite-WikiAssistant-MVP");
		conn.setDoOutput(true);
		conn.setConnectTimeout(10000);
		conn.setReadTimeout(20000);

		byte[] out = payload.toString().getBytes(StandardCharsets.UTF_8);
		conn.getOutputStream().write(out);

		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			(conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream()), StandardCharsets.UTF_8)))
		{
			String body = in.lines().collect(Collectors.joining("\n"));
			return JsonParser.parseString(body).getAsJsonObject();
		}
	}

	private static JsonObject getJson(String url) throws Exception
	{
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("User-Agent", "RuneLite-WikiAssistant-MVP");
		conn.setConnectTimeout(8000);
		conn.setReadTimeout(12000);

		try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)))
		{
			String body = in.lines().collect(Collectors.joining("\n"));
			return JsonParser.parseString(body).getAsJsonObject();
		}
	}
}
