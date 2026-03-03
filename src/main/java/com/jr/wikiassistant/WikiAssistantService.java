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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

@Slf4j
@Singleton
public class WikiAssistantService
{
	private static final String WIKI_BASE = "https://oldschool.runescape.wiki";
	private static final Path EVAL_LOG_PATH = Path.of(System.getProperty("user.home"), ".runelite", "wikiassistant-evals.jsonl");

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private WikiAssistantConfig config;

	private static final Map<Integer, Double> COOKING_XP = new HashMap<>();
	private static final Map<String, double[]> MODEL_PRICES_PER_M = new HashMap<>();
	private static final List<AreaBound> AREA_BOUNDS = new ArrayList<>();

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

		// Estimated $ / 1M tokens: [input, output]. Update as provider prices change.
		MODEL_PRICES_PER_M.put("openai/gpt-4o-mini", new double[]{0.15, 0.60});
		MODEL_PRICES_PER_M.put("google/gemini-2.0-flash-001", new double[]{0.10, 0.40});
		MODEL_PRICES_PER_M.put("anthropic/claude-3.5-haiku", new double[]{0.80, 4.00});

		// Coarse area bounds (MVP). Expand over time.
		AREA_BOUNDS.add(new AreaBound("Lumbridge", 3216, 3178, 3256, 3234));
		AREA_BOUNDS.add(new AreaBound("Varrock", 3180, 3360, 3250, 3450));
		AREA_BOUNDS.add(new AreaBound("Falador", 2937, 3310, 3050, 3400));
		AREA_BOUNDS.add(new AreaBound("Grand Exchange", 3140, 3460, 3195, 3510));
		AREA_BOUNDS.add(new AreaBound("Edgeville", 3070, 3450, 3118, 3522));
		AREA_BOUNDS.add(new AreaBound("Al Kharid", 3265, 3140, 3335, 3218));
		AREA_BOUNDS.add(new AreaBound("Draynor Village", 3070, 3200, 3135, 3290));
		AREA_BOUNDS.add(new AreaBound("Barbarian Village", 3050, 3380, 3130, 3460));
	}

	public String answer(String question)
	{
		log.info("[WikiAssistantService] answer() question={}", question);
		String q = question.toLowerCase(Locale.ROOT).trim();

		if (isCapabilityQuestion(q))
		{
			return capabilityHelpText();
		}

		if (isLocationQuestion(q))
		{
			return answerLocationQuestion();
		}

		if (q.contains("cooking") && q.contains("bank") && (q.contains("level") || q.contains("xp")))
		{
			log.info("[WikiAssistantService] routing to cooking projection");
			return answerCookingProjection();
		}

		log.info("[WikiAssistantService] routing to wiki+ai parallel eval");
		return answerFromWikiWithAiParallel(question);
	}

	private String answerCookingProjection()
	{
		final Item[] bankItems = getBankItemsSnapshot();
		if (bankItems == null)
		{
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
			return "I couldn't find recognized raw cookable foods in your bank (MVP list).";
		}

		int currentXp = getCookingXpSnapshot();
		int currentLevel = Experience.getLevelForXp(currentXp);
		double projectedXp = currentXp + totalXp;
		int projectedLevel = Experience.getLevelForXp((int) projectedXp);

		return new StringBuilder()
			.append("Cooking projection (bank raw foods)\n\n")
			.append(String.format("Current: level %d (%d xp)%n", currentLevel, currentXp))
			.append(String.format("Potential gain: %.1f xp%n", totalXp))
			.append(String.format("Projected: level %d (%d xp)%n%n", projectedLevel, (int) projectedXp))
			.append("Breakdown:\n")
			.append(breakdown)
			.append("\nSource: OSRS Wiki cooking xp values (MVP subset) + live bank data from RuneLite.\n")
			.append("Wiki: https://oldschool.runescape.wiki/w/Cooking")
			.toString();
	}

	private String answerFromWikiWithAiParallel(String question)
	{
		try
		{
			String sources = buildWikiSources(question);
			if (sources == null)
			{
				return "No wiki results found for that question.";
			}

			if (config.apiKey() == null || config.apiKey().isBlank())
			{
				return "AI mode needs an API key in Wiki Assistant config.\n\nTop wiki sources:\n" + sources;
			}

			String[] models = new String[]{config.modelA(), config.modelB(), config.modelC()};
			ExecutorService pool = Executors.newFixedThreadPool(3);
			try
			{
				Future<ModelResult>[] futures = new Future[3];
				for (int i = 0; i < 3; i++)
				{
					final String m = models[i];
					futures[i] = pool.submit((Callable<ModelResult>) () -> askModel(m, question, sources));
				}

				ModelResult[] results = new ModelResult[3];
				for (int i = 0; i < 3; i++)
				{
					results[i] = futures[i].get(45, TimeUnit.SECONDS);
					appendEvalLog(results[i]);
				}

				return renderParallelResults(question, results);
			}
			finally
			{
				pool.shutdownNow();
			}
		}
		catch (Exception e)
		{
			log.error("Wiki+AI parallel failed", e);
			return "Failed to query wiki/AI right now: " + e.getClass().getSimpleName() + " - " + e.getMessage();
		}
	}

	private String buildWikiSources(String question) throws Exception
	{
		String searchUrl = WIKI_BASE + "/api.php?action=query&list=search&srsearch="
			+ URLEncoder.encode(question, StandardCharsets.UTF_8)
			+ "&format=json&srlimit=5";

		JsonObject searchJson = getJson(searchUrl);
		JsonArray results = searchJson.getAsJsonObject("query").getAsJsonArray("search");
		if (results == null || results.size() == 0)
		{
			return null;
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
		return sources.toString();
	}

	private String buildLiveContext()
	{
		WorldPoint wp = getWorldPointSnapshot();
		if (wp == null)
		{
			return "Live context unavailable (not logged in).";
		}
		return "WorldPoint=" + wp.getX() + "," + wp.getY() + ",plane=" + wp.getPlane() + "; area=" + resolveAreaName(wp);
	}

	private ModelResult askModel(String model, String question, String sources) throws Exception
	{
		long start = System.currentTimeMillis();

		JsonObject body = new JsonObject();
		body.addProperty("model", model);

		JsonArray messages = new JsonArray();
		JsonObject system = new JsonObject();
		system.addProperty("role", "system");
		system.addProperty("content", "You are an OSRS assistant. Answer only from the provided OSRS Wiki snippets and links. If uncertain, say so. Keep answer concise and include a Sources section with links used.");
		messages.add(system);

		JsonObject user = new JsonObject();
		user.addProperty("role", "user");
		user.addProperty("content", "Question: " + question + "\n\nLive RuneLite context:\n" + buildLiveContext() + "\n\nWiki sources:\n" + sources);
		messages.add(user);

		body.add("messages", messages);
		body.addProperty("temperature", 0.2);

		JsonObject resp = postJson(config.apiBase(), body, config.apiKey());
		long ms = System.currentTimeMillis() - start;

		ModelResult r = new ModelResult();
		r.timestamp = Instant.now().toString();
		r.model = model;
		r.question = question;
		r.sources = sources;
		r.latencyMs = ms;

		JsonArray choices = resp.getAsJsonArray("choices");
		if (choices == null || choices.size() == 0)
		{
			r.answer = "Model returned no answer.";
		}
		else
		{
			JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
			r.answer = msg.get("content").getAsString();
		}

		JsonObject usage = resp.getAsJsonObject("usage");
		if (usage != null)
		{
			r.promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsInt() : 0;
			r.completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsInt() : 0;
			r.totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").getAsInt() : (r.promptTokens + r.completionTokens);
		}

		r.estimatedCostUsd = estimateCostUsd(model, r.promptTokens, r.completionTokens);
		return r;
	}

	private String renderParallelResults(String question, ModelResult[] results)
	{
		StringBuilder out = new StringBuilder();
		out.append("Parallel model outputs (for evaluation)\n")
			.append("Question: ").append(question).append("\n\n");

		for (int i = 0; i < results.length; i++)
		{
			ModelResult r = results[i];
			out.append("=== Model ").append(i + 1).append(": ").append(r.model).append(" ===\n")
				.append("Latency: ").append(r.latencyMs).append(" ms\n")
				.append("Tokens: prompt=").append(r.promptTokens)
				.append(", completion=").append(r.completionTokens)
				.append(", total=").append(r.totalTokens).append("\n")
				.append("Estimated cost: ")
				.append(r.estimatedCostUsd >= 0 ? String.format("$%.6f", r.estimatedCostUsd) : "unknown")
				.append("\n\n")
				.append(r.answer == null ? "(no answer)" : r.answer)
				.append("\n\n");
		}

		out.append("All inputs/outputs logged to: ").append(EVAL_LOG_PATH).append("\n")
			.append("Source of truth: https://oldschool.runescape.wiki/");
		return out.toString();
	}

	private static double estimateCostUsd(String model, int promptTokens, int completionTokens)
	{
		double[] rates = MODEL_PRICES_PER_M.get(model);
		if (rates == null)
		{
			return -1;
		}
		double inputCost = (promptTokens / 1_000_000.0) * rates[0];
		double outputCost = (completionTokens / 1_000_000.0) * rates[1];
		return inputCost + outputCost;
	}

	private static synchronized void appendEvalLog(ModelResult r)
	{
		try
		{
			Files.createDirectories(EVAL_LOG_PATH.getParent());
			JsonObject j = new JsonObject();
			j.addProperty("timestamp", r.timestamp);
			j.addProperty("model", r.model);
			j.addProperty("question", r.question);
			j.addProperty("sources", r.sources);
			j.addProperty("answer", r.answer);
			j.addProperty("latencyMs", r.latencyMs);
			j.addProperty("promptTokens", r.promptTokens);
			j.addProperty("completionTokens", r.completionTokens);
			j.addProperty("totalTokens", r.totalTokens);
			j.addProperty("estimatedCostUsd", r.estimatedCostUsd);

			Files.writeString(
				EVAL_LOG_PATH,
				j.toString() + System.lineSeparator(),
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND
			);
		}
		catch (Exception e)
		{
			log.error("Failed writing eval log", e);
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

	private WorldPoint getWorldPointSnapshot()
	{
		CountDownLatch latch = new CountDownLatch(1);
		final WorldPoint[] wp = new WorldPoint[1];

		clientThread.invoke(() ->
		{
			try
			{
				if (client.getLocalPlayer() != null)
				{
					wp[0] = client.getLocalPlayer().getWorldLocation();
				}
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

		return wp[0];
	}

	private String resolveAreaName(WorldPoint wp)
	{
		for (AreaBound a : AREA_BOUNDS)
		{
			if (a.contains(wp.getX(), wp.getY()))
			{
				return a.name;
			}
		}
		return "Unknown area";
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

	private boolean isLocationQuestion(String q)
	{
		return q.contains("where am i") || q.contains("where i'm") || q.contains("where i am")
			|| q.contains("what town") || q.contains("my location") || q.contains("my coords") || q.contains("coordinates");
	}

	private String answerLocationQuestion()
	{
		WorldPoint wp = getWorldPointSnapshot();
		if (wp == null)
		{
			return "I couldn't read your live location right now. Make sure you're logged in.";
		}

		String area = resolveAreaName(wp);
		String confidence = "high";
		if (area.equals("Unknown area"))
		{
			confidence = "low";
		}

		return new StringBuilder()
			.append("Live location context\n\n")
			.append("- WorldPoint: ").append(wp.getX()).append(", ").append(wp.getY()).append(" (plane ").append(wp.getPlane()).append(")\n")
			.append("- Inferred area/town: ").append(area).append("\n")
			.append("- Confidence: ").append(confidence).append("\n\n")
			.append("Note: area mapping is a coarse MVP bounds table. We can expand with precise map polygons next.")
			.toString();
	}

	private String capabilityHelpText()
	{
		return "I run 3 models in parallel and show all outputs for evaluation.\n\n"
			+ "I can:\n"
			+ "• Answer OSRS questions grounded in OSRS Wiki sources\n"
			+ "• Use live RuneLite context for calculations (bank/skills)\n"
			+ "• Log inputs/outputs, token usage, latency, and estimated cost per model\n\n"
			+ "Eval log path: " + EVAL_LOG_PATH + "\n"
			+ "Source of truth: https://oldschool.runescape.wiki/";
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
		conn.setReadTimeout(45000);

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

	private static class AreaBound
	{
		private final String name;
		private final int minX;
		private final int minY;
		private final int maxX;
		private final int maxY;

		private AreaBound(String name, int minX, int minY, int maxX, int maxY)
		{
			this.name = name;
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
		}

		private boolean contains(int x, int y)
		{
			return x >= minX && x <= maxX && y >= minY && y <= maxY;
		}
	}

	private static class ModelResult
	{
		String timestamp;
		String model;
		String question;
		String sources;
		String answer;
		long latencyMs;
		int promptTokens;
		int completionTokens;
		int totalTokens;
		double estimatedCostUsd;
	}
}
