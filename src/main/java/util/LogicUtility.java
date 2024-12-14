package util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.telegram.telegrambots.meta.api.objects.User;
import org.yaml.snakeyaml.Yaml;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import exceptions.ItemRemovedException;
import exceptions.SizeRemovedException;
import pojo.Offer;
import pojo.Offer.InnerPrice;
import pojo.Offer.Price;
import pojo.Size;
import pojo.Sizes;
import pojo.TrackedItem;
import pojo.TrackedItem.PriceHistory;
import pojo.TrackedItems;
import runner.TelegramBot;

/**
 * Utility class for the bot's logic
 */
public class LogicUtility {

	public static final String CURRENT_FOLDER = System.getProperty("user.dir");

	private static final String TRACKED_JSON_FILE = CURRENT_FOLDER + "/userdata/%s/tracked.json";

	public static final String CONFIG_FILE = CURRENT_FOLDER + "/config.yml";
	public static final String ADMIN_ID = "adminID";
	public static final String BOT_USERNAME = "botUsername";
	public static final String BOT_TOKEN = "botToken";
	public static final String PUBLIC = "public";

	private final Map<String, Object> config;

	// i use a map to reduce file reads
	private static final Map<Long, List<TrackedItem>> ITEMS_CACHE = new ConcurrentHashMap<>();

	/** Users waiting to be enabled. Useful to get the full user object after the admin approval callback */
	private static final Map<Long, User> USERS_APPROVAL_QUEUE = new HashMap<>();

	private static final Set<Long> USERS_TO_DELETE = new HashSet<>();

	public LogicUtility() throws Exception {
		final InputStream inputStream = new FileInputStream(CONFIG_FILE);
		final Yaml yaml = new Yaml();
		config = yaml.load(inputStream);
	}

	/** Returns the admin id */
	public Long getAdminId() {
		final Integer result = (Integer) config.get(ADMIN_ID);
		return Long.valueOf(result);
	}

	/** Returns the bot username */
	public String getBotUsername() {
		return (String) config.get(BOT_USERNAME);
	}

	/** Returns the bot token */
	public String getBotToken() {
		return (String) config.get(BOT_TOKEN);
	}

	/**
	 * Indicates if that the bot is public. If it is not, when a new user tries to use the bot the admin will have to give the permission.
	 */
	public boolean isBotPublic() {
		return (boolean) config.get(PUBLIC);
	}

	/** Checks if the user's folder exists. */
	public boolean userExists(Long userId) {
		return new File(CURRENT_FOLDER + "/userdata/" + userId).exists();
	}

	/** Adds the user to the queue */
	public void addNewUserToApprovalQueue(User user) {
		USERS_APPROVAL_QUEUE.put(user.getId(), user);
	}

	/** Checks if the user is already in the queue */
	public boolean isUserInApprovalQueue(User user) {
		return USERS_APPROVAL_QUEUE.containsKey(user.getId());
	}

	/** Creates a new user previously put in queue. */
	public void createUser(Long userId) throws Exception {
		final User user = USERS_APPROVAL_QUEUE.get(userId);
		createUser(user);
		USERS_APPROVAL_QUEUE.remove(userId);
	}

	/** Creates a new user. */
	public void createUser(User user) throws Exception {
		final Long userId = user.getId();
		if (userExists(userId)) { return; }
		// this creates the user folder and the empty json file
		saveTrackedItems(userId, new ArrayList<>());

		// create a file with the user info
		final String userInfo = """
				id: %s
				username: %s
				firstName: %s
				lastName: %s
				""".formatted(user.getId(), user.getUserName(), user.getFirstName(), user.getLastName());

		final String infoFile = CURRENT_FOLDER + "/userdata/%s/info.txt".formatted(userId);
		final File file = new File(infoFile);
		FileUtils.write(file, userInfo, Charset.defaultCharset());
	}

	/** Marks the user for removal. */
	public void addUserToDelete(Long userId) {
		USERS_TO_DELETE.add(userId);
	}

	/** Deletes the users marked for removal. */
	public void deleteUsers() throws Exception {
		for (final Long userId : USERS_TO_DELETE) {
			ITEMS_CACHE.remove(userId);
			final File folder = new File(CURRENT_FOLDER + "/userdata/" + userId);
			if (folder.exists()) {
				FileUtils.deleteDirectory(folder);
				insertLog("User deleted: " + userId);
			}
		}
		USERS_TO_DELETE.clear();
	}

	/** Returns the items tracked by the specified user. */
	public List<TrackedItem> getTrackedItems(Long userId) throws IOException {
		if (ITEMS_CACHE.containsKey(userId)) { return ITEMS_CACHE.get(userId); }

		final File file = new File(TRACKED_JSON_FILE.formatted(userId));
		final String json = FileUtils.readFileToString(file, Charset.defaultCharset());

		final TrackedItems trackedItems = new Gson().fromJson(json, TrackedItems.class);
		final List<TrackedItem> result = trackedItems.getTrackedItems();

		ITEMS_CACHE.put(userId, result);
		return result;
	}

	/** Saves the specified items for the specified user. */
	public void saveTrackedItems(Long userId, List<TrackedItem> items) throws Exception {
		final List<TrackedItem> toSave = new ArrayList<>(items);
		toSave.sort(Comparator.comparing(TrackedItem::getName));
		final File file = new File(TRACKED_JSON_FILE.formatted(userId));
		final String json = new GsonBuilder().setPrettyPrinting().create().toJson(new TrackedItems(toSave));
		FileUtils.write(file, json, Charset.defaultCharset());
		ITEMS_CACHE.put(userId, toSave);
	}

	private HttpResponse<String> httpGet(String url) throws Exception {
		final HttpClient client = HttpClient.newHttpClient();
		final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0")//
				.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/png,image/svg+xml,*/*;q=0.8")//
				.setHeader("Sec-Fetch-Dest", "document")//
				.setHeader("Sec-Fetch-Mode", "navigate")//
				.setHeader("Sec-Fetch-Site", "cross-site")//
				.build();

		return client.send(request, BodyHandlers.ofString());
	}

	/** Get all the existing sizes for the specified url. */
	public List<String> getSizesFromUrl(String url) throws Exception {
		final String responseBody = httpGet(url).body();
		return getSizesFromBody(responseBody).stream().map(s -> s.size).toList();
	}

	/** Gets all the item sizes from the specified body. */
	private List<Size> getSizesFromBody(String body) {
		final String size = "\"size\":";
		final String simples = "\"simples\":";

		// the body has a json with all the info so i look for it

		final int start = body.indexOf(simples);
		if (start < 0) { return Collections.emptyList(); }

		String partial = body.substring(start);
		partial = partial.substring(partial.indexOf("["));
		int openBrackets = 0;
		int closedBrackets = 0;
		int arrayEndIndex = -1;

		for (int i = 0; i < partial.length(); i++) {
			final String character = String.valueOf(partial.charAt(i));

			if (Objects.equals(character, "[")) { openBrackets++; }
			if (Objects.equals(character, "]")) { closedBrackets++; }

			if (openBrackets > 1 && openBrackets == closedBrackets) {
				arrayEndIndex = i;
				break;
			}
		}

		final String stringedArray = partial.substring(0, arrayEndIndex + 1);
		if (!stringedArray.contains(size)) { return getSizesFromBody(partial); }

		final String simplesJson = "{" + simples + stringedArray + "}";
		final Sizes sizes = new Gson().fromJson(simplesJson, Sizes.class);
		return sizes.simples;
	}

	/**
	 * Fetches the specified item from its url.
	 *
	 * @throws ItemRemovedException if the item is no longer available.
	 * @throws SizeRemovedException if the size is no longer available.
	 */
	public TrackedItem getItemFromUrl(Long userId, TrackedItem item, TelegramBot bot) throws Exception {
		final String url = item.getUrl();
		final String size = item.getSize();

		final HttpResponse<String> response = httpGet(url);
		final String responseBody = response.body();
		final List<Size> sizes = getSizesFromBody(responseBody);

		if (response.statusCode() == 404 || sizes.isEmpty()) {
			throw new ItemRemovedException();
		} // if

		final Size found = sizes.stream().filter(s -> Objects.equals(s.size, size)).findFirst().orElseThrow(SizeRemovedException::new);
		final Offer offer = found.offer;

		final Price options = offer.price;
		final InnerPrice priceObj = options.promotional == null ? options.original : options.promotional;
		final String amount = String.valueOf(priceObj.amount);
		final String price = amount.substring(0, amount.length() - 2) + "," + amount.substring(amount.length() - 2);

		// looks for coupons using the substring
		final boolean hasCoupon = getSearchCouponSubstring(url).map(responseBody::contains).orElse(false);

		// Create the new item
		final TrackedItem fetchedItem = new TrackedItem(item.getUuid(), item.getName(), url, size, price, offer.stock.quantity, offer.isMeaningfulOffer, hasCoupon);

		final LocalDateTime now = LocalDateTime.now();
		final String date = "%s-%s-%s".formatted(now.getDayOfMonth(), now.getMonthValue(), now.getYear());
		fetchedItem.setPriceHistory(new ArrayList<>(Arrays.asList(new PriceHistory(price, date))));

		return fetchedItem;
	}

	/** Returns the substring used to search for coupons in the response body. */
	private Optional<String> getSearchCouponSubstring(String url) throws Exception {
		final String host = URI.create(url).getHost();
		final String tld = host.replace("www.zalando.", "").toUpperCase();

		// substrings used to search for coupons
		// (searching only 'Enter your code at checkout' will give false positives)
		final String substring = switch (tld) {
		case "CO.UK" -> "on this and other selected items";
		case "IE" -> "on this and other selected items";
		case "IT" -> "su questo e altri articoli selezionati con il codice";
		case "ES" -> "en este y otros artículos seleccionados con el código";
		case "DE" -> "auf diesen und andere ausgewählte artikel";
		case "FR" -> "sur cet article et bien d’autres";
		case "NL" -> "op dit en andere geselecteerde items";
		default -> null;
		};

		return Optional.ofNullable(substring);
	}

	/**
	 * Saves the stacktrace in a text file.
	 *
	 * @param th             The exception
	 * @param bot            The bot that will be used to notify the admin
	 * @param additionalInfo Optional additional information to be printed
	 */
	public void insertErrorLog(Throwable th, TelegramBot bot, Object... additionalInfo) {
		try {
			final List<String> list = new ArrayList<>();
			if (additionalInfo != null) { Stream.of(additionalInfo).map(Object::toString).forEach(list::add); }
			list.add(th.getMessage());
			list.addAll(Stream.of(th.getStackTrace()).map(Object::toString).toList());

			final Throwable cause = th.getCause();
			if (cause != null) {
				list.add(cause.getMessage());
				list.addAll(Stream.of(cause.getStackTrace()).map(Object::toString).toList());
			}

			final String message = String.join("\n", list);

			final LocalDateTime now = LocalDateTime.now();
			final String path = CURRENT_FOLDER + "/logs/";
			final String fileName = "errors_" + now.getDayOfMonth() + now.getMonthValue() + now.getYear() + ".txt";
			final File file = new File(path + fileName);
			String existing = "";

			final String log = "\n" + now.truncatedTo(ChronoUnit.SECONDS) + " " + message;

			if (file.exists()) {
				existing = FileUtils.readFileToString(file, Charset.defaultCharset()) + "\n";
			}
			FileUtils.write(file, existing + log, Charset.defaultCharset());

			System.out.println("Error inserted:");
			th.printStackTrace();

			// notify the admin if the error is not caused by network slowdowns
			if (th.getMessage() != null && !th.getMessage().contains("query is too old and response timeout")) {
				bot.sendMessage(getAdminId(), "some errors occurred :(");
			}

		} catch (final Exception e) {

			final List<String> fatal = new ArrayList<>();
			fatal.add(e.getMessage());
			fatal.addAll(Stream.of(e.getStackTrace()).map(Object::toString).toList());

			System.out.println("Critical - couldn't write log:\n" + String.join("\n", fatal));
		}
	}

	/** Saves the log in a text file. */
	public void insertLog(String message) {
		try {
			final LocalDateTime now = LocalDateTime.now();
			final String path = CURRENT_FOLDER + "/logs/";
			final String fileName = "log_%s.txt".formatted(now.getMonth().toString() + now.getYear());
			final File file = new File(path + fileName);
			String existing = "";

			final String log = (now.truncatedTo(ChronoUnit.SECONDS) + " ").replace("T", " ") + message;

			if (file.exists()) {
				existing = FileUtils.readFileToString(file, Charset.defaultCharset()) + "\n";
			}
			FileUtils.write(file, existing + log, Charset.defaultCharset());
			System.out.println("Log inserted: " + message);

		} catch (final IOException e) {

			final List<String> list = new ArrayList<>();
			list.add(e.getMessage());
			list.addAll(Stream.of(e.getStackTrace()).map(Object::toString).toList());

			System.out.println("Critical - couldn't write log:\n" + String.join("\n", list));
		}
	}

}
