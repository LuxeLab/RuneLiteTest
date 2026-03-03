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

		if (q.contains("cooking") && q.contains("bank") && (q.contains("level") || q.contains("xp")))
		{
			log.info("[WikiAssistantService] routing to cooking projection");
			return answerCookingProjection();
		}

		log.info("[WikiAssistantService] routing to wiki search");
		return answerFromWikiSearch(question);
	}

	private String answerCookingProjection()
	{
		log.info("[WikiAssistantService] answerCookingProjection() start");
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			log.info("[WikiAssistantService] bank container is null");
			return "Open your bank first so I can read raw food quantities.";
		}

		double totalXp = 0;
		StringBuilder breakdown = new StringBuilder();
		for (Item item : bank.getItems())
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
			String name = itemManager.getItemComposition(item.getId()).getName();
			breakdown.append(String.format("- %s x%d => %.1f xp%n", name, item.getQuantity(), gained));
		}

		if (totalXp <= 0)
		{
			log.info("[WikiAssistantService] no cookable foods found in bank subset");
			return "I couldn't find recognized raw cookable foods in your bank (MVP list).";
		}

		int currentXp = client.getSkillExperience(Skill.COOKING);
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

	private String answerFromWikiSearch(String question)
	{
		try
		{
			log.info("[WikiAssistantService] answerFromWikiSearch start");
			String searchUrl = WIKI_BASE + "/api.php?action=query&list=search&srsearch="
				+ URLEncoder.encode(question, StandardCharsets.UTF_8)
				+ "&format=json&srlimit=3";

			JsonObject searchJson = getJson(searchUrl);
			JsonArray results = searchJson.getAsJsonObject("query").getAsJsonArray("search");
			if (results.size() == 0)
			{
				log.info("[WikiAssistantService] no wiki results");
				return "No wiki results found for that question.";
			}

			log.info("[WikiAssistantService] wiki results count={}", results.size());

			StringBuilder out = new StringBuilder();
			out.append("Wiki-grounded results:\n\n");

			for (int i = 0; i < Math.min(3, results.size()); i++)
			{
				JsonObject r = results.get(i).getAsJsonObject();
				String title = r.get("title").getAsString();
				String snippet = r.get("snippet").getAsString()
					.replace("<span class=\"searchmatch\">", "")
					.replace("</span>", "")
					.replace("&quot;", "\"");
				String pageUrl = WIKI_BASE + "/w/" + title.replace(' ', '_');

				out.append(String.format("%d) %s\n", i + 1, title))
					.append(snippet).append("\n")
					.append(pageUrl).append("\n\n");
			}

			out.append("I can do stronger calculations when the query references in-game context (bank/inventory/skills).\n")
				.append("Source of truth: https://oldschool.runescape.wiki/");
			log.info("[WikiAssistantService] wiki answer built");
			return out.toString();
		}
		catch (Exception e)
		{
			log.error("Wiki search failed", e);
			return "Failed to query OSRS Wiki right now. Try again.";
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
