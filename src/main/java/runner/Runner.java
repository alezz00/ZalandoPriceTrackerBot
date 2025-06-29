package runner;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import exceptions.ItemRemovedException;
import exceptions.SizeRemovedException;
import pojo.TrackedItem;
import pojo.TrackedItem.PriceHistory;
import util.LogicUtility;

/**
 * The main class.
 * <li>Instantiates the utility class</li>
 * <li>Instantiates the bot</li>
 * <li>Schedules the logic to run every 60 minutes</li>
 */
public class Runner {

	private static LogicUtility utility;
	private static TelegramBot bot = null;

	public static void main(String[] args) throws Exception {

		utility = new LogicUtility();

		// Create the bot
		final TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
		bot = new TelegramBot(utility);
		botsApi.registerBot(bot);

		// Schedule the logic every 60 minutes
		scheduleJob(60, TimeUnit.MINUTES);
	}

	private static void scheduleJob(long delay, TimeUnit timeUnit) {
		final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
		final Runnable runnable = () -> {
			try {
				run();
			} catch (final Throwable t) {
				utility.insertErrorLog(t, bot);
			}
		};
		scheduler.scheduleWithFixedDelay(runnable, 0, delay, timeUnit);
	}

	private static void run() throws Exception {
		utility.insertLog("\t\t* Starting to check *");

		boolean anyChange = false;
		int totalItemsSize = 0;

		// Fetch all the users
		final File userdata = new File(LogicUtility.CURRENT_FOLDER + "/userdata");
		final File[] users = userdata.listFiles(File::isDirectory);

		for (final File user : users) {
			final Long userId = Long.valueOf(user.getName());

			final List<TrackedItem> trackedItems = new ArrayList<>();
			final List<String> notifications = new ArrayList<>();

			// fetch each item
			if (utility.getTrackedItems(userId).isEmpty()) { continue; }
			for (final TrackedItem oldItem : utility.getTrackedItems(userId)) {
				totalItemsSize++;
				TrackedItem item;
				try {
					item = utility.getItemFromUrl(userId, oldItem, bot);
				} catch (final ItemRemovedException e) {
					item = oldItem;
					item.incrementNotFoundCount();
					if (item.getNotFoundCount() >= 5) {
						bot.sendMessage(userId, """
								"It appears that the item \"%s\" is no longer available at the specified url :("
								Consider deleting the item from your list if this error persists""".formatted(oldItem.getName()));
						continue;
					}
				} catch (final SizeRemovedException e) {
					item = oldItem;
					item.incrementSizeNotFoundCount();
					if (item.getSizeNotFoundCount() >= 5) {
						bot.sendMessage(userId, """
								"It appears that the size %s is no longer available for item \"%s\":("
								Consider deleting the item from your list if this error persists""".formatted(oldItem.getSize(), oldItem.getName()));
						continue;
					}
				} catch (final Throwable t) {
					// if unmanaged exception occurred don't stop and continue with other items
					trackedItems.add(oldItem);
					utility.insertErrorLog(t, bot, userId, oldItem.getName());
					continue;
				}

				anyChange = anyChange || item.anyChange(oldItem);

				// Update the price history
				final ArrayList<PriceHistory> priceHistory = oldItem.getPriceHistory();
				if (!Objects.equals(item.getPrice(), oldItem.getPrice())) {
					priceHistory.add(item.getPriceHistory().get(0));
				}
				item.setPriceHistory(priceHistory);

				// Add fetched item to the new list
				trackedItems.add(item);

				// Check if the item needs to be notified
				buildItemNotification(oldItem, item).ifPresent(notifications::add);
			}

			// Send the notifications
			if (!notifications.isEmpty()) {
				for (final String notification : notifications) {
					bot.sendMessage(userId, notification);
				}
				utility.insertLog("Message sent: prices lowered for user: %s".formatted(userId));
			}

			// Update the items file
			if (anyChange) {
				utility.saveTrackedItems(userId, trackedItems);
			}

			// wait 10 seconds for next user
			Thread.sleep(1000 * 10);
		}

		utility.insertLog("\t\t*** Check executed for %s users and a total of %s items ***".formatted(users.length, totalItemsSize));

		// delete the marked users
		utility.deleteUsers();
	}

	/**
	 * Checks if there are changes that need to be notified and returns the notification.
	 */
	private static Optional<String> buildItemNotification(TrackedItem oldItem, TrackedItem newItem) {
		if (!newItem.isAvailable()) { return Optional.empty(); }

		final String newPrice = newItem.getPrice();
		final List<PriceHistory> priceHistory = newItem.getPriceHistory();

		final boolean couponAdded = !oldItem.isHasCoupon() && newItem.isHasCoupon();
		final boolean priceLowered = priceLowered(oldItem, newItem);

		if (!priceLowered && !couponAdded) { return Optional.empty(); }

		final String history = priceHistory.size() > 20 //
				? describePriceHistory(priceHistory)
				: priceHistory.stream().map(PriceHistory::getStringPrice).collect(Collectors.joining(" -> "));

		final List<String> headers = new ArrayList<>();
		if (priceLowered) { headers.add("Price lowered"); }
		if (couponAdded) { headers.add("Coupon added"); }
		final String headerString = String.join(" + ", headers) + "!";

		final List<String> prices = new ArrayList<>();
		if (priceLowered) { prices.add(oldItem.getPrice()); }
		prices.add(newPrice + (couponAdded ? " + coupon" : ""));
		final String priceString = String.join(" ---> ", prices);

		utility.insertLog(newItem.getName() + " - " + priceString);

		final String message = """
				%s
				%s
				<b>%s</b>
				quantity: %s
				<b>price history:</b>
				%s
				%s""".formatted(headerString, newItem.getName(), priceString, newItem.getQuantity(), history, newItem.getUrl());

		return Optional.of(message);
	}

	/** Checks if the price lowered. */
	private static boolean priceLowered(TrackedItem oldItem, TrackedItem newItem) {
		final String oldPriceString = oldItem.getPrice();
		final String newPriceString = newItem.getPrice();
		final Double oldPrice = Double.valueOf(oldPriceString.replace(",", "."));
		final Double newPrice = Double.valueOf(newPriceString.replace(",", "."));

		// ignore price changes smaller than 1 unit
		return oldPrice - newPrice > 1;
	}

	private static String describePriceHistory(List<PriceHistory> list) {

		final PriceHistory min = Collections.min(list, Comparator.comparing(PriceHistory::getPrice));
		final PriceHistory max = Collections.max(list, Comparator.comparing(PriceHistory::getPrice));

		final int average90Days = (int) list.stream()//
				.filter(e -> e.getLocalDate().isAfter(LocalDate.now().plusDays(-91)))//
				.mapToDouble(PriceHistory::getPrice)//
				.average().getAsDouble();

		final int average180Days = (int) list.stream()//
				.filter(e -> e.getLocalDate().isAfter(LocalDate.now().plusDays(-181)))//
				.mapToDouble(PriceHistory::getPrice)//
				.average().getAsDouble();

		return """
				min: %s - %s
				max: %s - %s
				average last 90 days: %s
				average last 180 days: %s""" //
				.formatted(min.getPrice(), min.getDate(), max.getPrice(), max.getDate(), average90Days, average180Days);
	}

}
