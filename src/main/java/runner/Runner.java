package runner;

import java.io.File;
import java.util.ArrayList;
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
			} catch (final Exception e) {
				utility.insertErrorLog(e, bot);
			}
		};
		scheduler.scheduleWithFixedDelay(runnable, 0, delay, timeUnit);
	}

	private static void run() throws Exception {
		// delete the marked users
		utility.deleteUsers();

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
			final List<TrackedItem> itemsToNotify = new ArrayList<>();

			// fetch each item
			for (final TrackedItem oldItem : utility.getTrackedItems(userId)) {
				totalItemsSize++;
				TrackedItem item;
				try {
					item = utility.getItemFromUrl(userId, oldItem, bot);
				} catch (final ItemRemovedException e) {
					bot.sendMessage(userId, """
							"It appears that the item \"%s\" is no longer available at the specified url :("
							Consider deleting the item from your list if this error persists""".formatted(oldItem.getName()));
					continue;
				} catch (final SizeRemovedException e) {
					bot.sendMessage(userId, """
							"It appears that the size %s is no longer available for item \"%s\":("
							Consider deleting the item from your list if this error persists""".formatted(oldItem.getSize(), oldItem.getName()));
					continue;
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
				item.setBackInStockNotifiedPrice(oldItem.getBackInStockNotifiedPrice());

				// Add fetched item to the new list
				trackedItems.add(item);

				// Check if the item needs to be notified
				buildItemNotification(oldItem, item).ifPresent(not -> {
					notifications.add(not);
					itemsToNotify.add(item);
				});
			}

			// Send the notifications
			if (!notifications.isEmpty()) {
				for (final String notification : notifications) {
					bot.sendMessage(userId, notification);
				}
				final String info = itemsToNotify.stream().map(TrackedItem::getName).collect(Collectors.joining("\n"));
				utility.insertLog("Message sent: prices lowered for user: %s\n%s".formatted(userId, info));
			}

			// Update the items file
			if (anyChange) {
				utility.saveTrackedItems(userId, trackedItems);
			}

			// wait 10 seconds for next user
			Thread.sleep(1000 * 10);
		}

		utility.insertLog("\t\t*** Check executed for %s users and a total of %s items ***".formatted(users.length, totalItemsSize));
	}

	/**
	 * Checks if there are changes that need to be notified and returns the notification.
	 */
	private static Optional<String> buildItemNotification(TrackedItem oldItem, TrackedItem newItem) {
		if (!newItem.isAvailable()) { return Optional.empty(); }

		final String newPrice = newItem.getPrice();

		final boolean couponAdded = !oldItem.isHasCoupon() && newItem.isHasCoupon();
		final String oldPriceToShow = priceLowered(oldItem, newItem);
		final boolean priceLowered = oldPriceToShow != null;

		if (!priceLowered && !couponAdded) { return Optional.empty(); }

		final String history = newItem.getPriceHistory().stream().map(PriceHistory::getPrice).collect(Collectors.joining(" -> "));

		final List<String> headers = new ArrayList<>();
		if (priceLowered) { headers.add("Price lowered"); }
		if (couponAdded) { headers.add("Coupon added"); }
		final String headerString = String.join(" + ", headers) + "!";

		final List<String> prices = new ArrayList<>();
		if (priceLowered) { prices.add(oldPriceToShow); }
		prices.add(newPrice + (couponAdded ? " + coupon" : ""));
		final String priceString = String.join(" ---> ", prices);

		final String message = """
				%s
				%s
				<b>%s</b>
				quantity: %s
				price history: %s
				%s""".formatted(headerString, newItem.getName(), priceString, newItem.getQuantity(), history, newItem.getUrl());

		return Optional.of(message);
	}

	/** Checks if the price lowered and returns the previous price. */
	private static String priceLowered(TrackedItem oldItem, TrackedItem newItem) {
		final String oldPriceString = oldItem.getPrice();
		final String newPriceString = newItem.getPrice();
		final Double oldPrice = Double.valueOf(oldPriceString.replace(",", "."));
		final Double newPrice = Double.valueOf(newPriceString.replace(",", "."));

		final boolean lowered = oldPrice - newPrice > 1;

		// if lower than the previous -> ok
		if (lowered) {
			return oldPriceString;
		}

		// or it can be the same price but back in stock, but has to be lower than the second-last
		// this can happen if the item goes from 120 to 100 while not in stock, and then goes back in stock
		if (Objects.equals(oldPriceString, newPriceString)) {
			final List<PriceHistory> oldHistory = new ArrayList<>(oldItem.getPriceHistory());

			oldHistory.remove(oldHistory.size() - 1);
			if (oldHistory.isEmpty()) { return null; }
			final String firstPreviousString = oldHistory.get(oldHistory.size() - 1).getPrice();
			final Double firstPrevious = Double.valueOf(firstPreviousString.replace(",", "."));

			// in this special case i also check the last notified price, to avoid double notifications
			if (!oldItem.isAvailable() && (firstPrevious - newPrice > 1) && !Objects.equals(newPriceString, oldItem.getBackInStockNotifiedPrice())) {
				newItem.setBackInStockNotifiedPrice(newPriceString);
				return firstPreviousString;
			}
		}

		return null;
	}

}
