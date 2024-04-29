package runner;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText.EditMessageTextBuilder;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.InlineKeyboardMarkupBuilder;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import pojo.TrackedItem;
import util.LogicUtility;

/**
 * The telegram bot implementation.
 */
public class TelegramBot extends TelegramLongPollingBot {

	private static final String DELETE = "delete/";
	private static final String SHOW_HISTORY = "showhistory/";
	private static final String ADD_ITEM = "addItem/";
	private static final String ADD_USER = "addUser/";
	private static final String DELETE_MESSAGE = "deleteMessage";

	private static final String LINK_MODE = "link_mode";
	private static final String SHOW_HISTORY_MODE = "show_history_mode";
	private static final String DELETE_MODE = "delete_mode";

	private final String botUsername;
	private final LogicUtility utility;

	public TelegramBot(LogicUtility utility) {
		super(utility.getBotToken());
		this.botUsername = utility.getBotUsername();
		this.utility = utility;
	}

	@Override
	public String getBotUsername() {
		return this.botUsername;
	}

	@Override
	public void onUpdateReceived(Update update) {
		try {
			if (update.hasMessage()) {
				final Message message = update.getMessage();
				if (!checkUser(message.getFrom())) { return; }

				helpCommand(message);
				myItemsCommand(message);
				addItem(message);

			} else if (update.hasCallbackQuery()) {
				final CallbackQuery callback = update.getCallbackQuery();
				if (!checkUser(callback.getFrom())) { return; }

				addUserCallback(callback);
				addItemCallback(callback);
				itemsKeyboardChangeModeCallback(callback);
				showHistoryCallback(callback);
				deleteItemCallback(callback);
				deleteMessageCallback(callback);

			}

		} catch (final Exception e) {
			utility.insertErrorLog(e, this);
		}
	}

	private boolean checkUser(User user) throws Exception {
		final Long userId = user.getId();
		if (utility.userExists(userId)) { return true; }

		final Long adminId = utility.getAdminId();
		final boolean isAdmin = Objects.equals(adminId, userId);

		// Check if the bot is public otherwise ask the admin for permission
		if (utility.isBotPublic() || isAdmin) {
			utility.insertLog("Adding new user: %s".formatted(userId));
			utility.createUser(user);
			sendMessage(userId, "Welcome! Take a look at the bottom left menu for the commands");
			if (!isAdmin) {
				sendMessage(adminId, "New user \"%s\" joined".formatted(user.getFirstName()));
			}
		} else {

			if (utility.isUserInApprovalQueue(user)) { return false; }

			utility.insertLog("Uknown user: %s - %s".formatted(user.getId(), user.getFirstName()));

			sendMessage(userId, "Hi! Who are you? Nevermind, just wait for the admin permission...");

			// add the user to the queue
			utility.addNewUserToApprovalQueue(user);

			// Ask the admin to enable the user
			final InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
			final InlineKeyboardButton addUserButton = InlineKeyboardButton.builder()//
					.text("Enable")//
					.callbackData(ADD_USER + userId)//
					.build();
			keyboard.keyboardRow(List.of(addUserButton));

			final String userInfo = Stream.of(user.getId(), user.getUserName(), user.getFirstName(), user.getLastName())//
					.filter(Objects::nonNull)//
					.map(Object::toString)//
					.collect(Collectors.joining("-"));

			final SendMessage addUserMessage = SendMessage.builder()//
					.chatId(utility.getAdminId())//
					.text("New user \"%s\" tried to use the bot. Click below to enable it.".formatted(userInfo))//
					.replyMarkup(keyboard.build())//
					.build();
			exec(addUserMessage);
		}
		return false;
	}

	/** Callback used when the admin enables a new user. */
	private void addUserCallback(CallbackQuery callback) throws Exception {
		final String data = callback.getData();
		if (!data.startsWith(ADD_USER)) { return; }

		final Long userId = Long.valueOf(data.replace(ADD_USER, ""));

		// Add the user
		utility.createUser(userId);

		// Answer the admin
		final AnswerCallbackQuery answer = AnswerCallbackQuery.builder()//
				.callbackQueryId(callback.getId())//
				.text("User added")//
				.showAlert(false).build();
		exec(answer);

		// Delete the message
		final Message message = (Message) callback.getMessage();
		final DeleteMessage deleteMessage = DeleteMessage.builder()//
				.chatId(message.getChatId())//
				.messageId(message.getMessageId())//
				.build();
		exec(deleteMessage);

		// Notify the user
		sendMessage(userId, "You are now enabled!");
	}

	/**
	 * Callback used when a size is chosen and the item has to be added to the list.
	 */
	private void addItemCallback(CallbackQuery callback) throws Exception {
		final String data = callback.getData();
		if (!data.startsWith(ADD_ITEM)) { return; }

		final Long userId = callback.getFrom().getId();
		final String size = data.replace(ADD_ITEM, "");

		final Message message = (Message) callback.getMessage();

		String alertText = null;
		boolean deleteMessages = true;
		if (message == null || message.getReplyToMessage() == null) {
			alertText = "Bad request";
		} else {
			alertText = "Item added!";
			final String userText = message.getReplyToMessage().getText().trim();
			final String name = userText.split("\n")[0].trim();
			final String url = userText.split("\n")[1].trim();

			final TrackedItem toAddTemp = new TrackedItem(UUID.randomUUID().toString(), name, url, size, null, null, false, false);

			final List<TrackedItem> items = utility.getTrackedItems(userId);

			if (items.stream().noneMatch(itm -> Objects.equals(toAddTemp.getUrl(), itm.getUrl()) && Objects.equals(toAddTemp.getSize(), itm.getSize()))) {
				final TrackedItem toAdd = utility.getItemFromUrl(userId, toAddTemp, this).orElse(null);
				if (toAdd != null) {
					items.add(toAdd);
					utility.saveTrackedItems(userId, items);
				}
			} else {
				alertText = "You are already tracking this item!";
				deleteMessages = false;
			}
		}

		final AnswerCallbackQuery alert = AnswerCallbackQuery.builder()//
				.callbackQueryId(callback.getId())//
				.text(alertText)//
				.showAlert(true).build();
		exec(alert);

		if (deleteMessages) {
			final DeleteMessage deleteSizeMessage = DeleteMessage.builder()//
					.chatId(message.getChatId())//
					.messageId(message.getMessageId())//
					.build();
			exec(deleteSizeMessage);

			if (message.getReplyToMessage() != null) {
				exec(DeleteMessage.builder()//
						.chatId(message.getChatId())//
						.messageId(message.getReplyToMessage().getMessageId())//
						.build());
			}
		}

	}

	/** Checks if the user is trying to add a new item and returns the available sizes. */
	private void addItem(Message message) throws Exception {

		// i'm expecting an alias for the item in the first row and the url in the second row
		final String text = message.getText().trim();
		if (!text.contains("\n")) { return; }

		final String[] split = text.split("\n");
		if (split.length != 2) { return; }

		final String url = split[1].trim();

		if (!url.startsWith("https://www.zalando.")) { return; }

		final InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();

		// i proceed to fetch all the sizes from the item and displaying them in buttons
		final List<String> sizes = utility.getSizesFromUrl(url);
		if (sizes.isEmpty()) {
			sendMessage(message.getFrom().getId(), "Hmm... this url is not valid");
			return;
		}

		List<InlineKeyboardButton> list = new ArrayList<>();
		for (final String size : sizes) {
			final InlineKeyboardButton sizeButton = InlineKeyboardButton.builder()//
					.text(size)//
					.callbackData(ADD_ITEM + size).build();

			if (list.size() == 3) { keyboard.keyboardRow(new ArrayList<>(list)); list = new ArrayList<>(); }
			list.add(sizeButton);
		}
		if (!list.isEmpty()) { keyboard.keyboardRow(new ArrayList<>(list)); }

		final SendMessage result = SendMessage.builder().chatId(message.getFrom().getId())//
				.parseMode("HTML")//
				.replyToMessageId(message.getMessageId())//
				.text("Found it! What size would you like to track?")//
				.replyMarkup(keyboard.build())//
				.build();

		exec(result);
	}

	/** Callback used to delete the message with the button. */
	private void deleteMessageCallback(CallbackQuery callback) throws Exception {
		final String data = callback.getData();
		if (!data.startsWith(DELETE_MESSAGE)) { return; }

		final AnswerCallbackQuery answer = AnswerCallbackQuery.builder()//
				.callbackQueryId(callback.getId())//
				.text("")//
				.showAlert(false).build();
		exec(answer);

		final Message message = (Message) callback.getMessage();
		final DeleteMessage deleteMessage = DeleteMessage.builder()//
				.chatId(message.getChatId())//
				.messageId(message.getMessageId())//
				.build();
		exec(deleteMessage);
	}

	/** Callback used to get the price history of an item. */
	private void showHistoryCallback(CallbackQuery callback) throws Exception {
		final Long userId = callback.getFrom().getId();
		final String data = callback.getData();
		if (!data.startsWith(SHOW_HISTORY)) { return; }

		final String uuid = data.replace(SHOW_HISTORY, "");
		final TrackedItem item = utility.getTrackedItems(userId).stream()//
				.filter(itm -> Objects.equals(itm.getUuid(), uuid))//
				.findFirst().orElseThrow();

		// Button to delete the price history message
		final InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
		final InlineKeyboardButton deleteMessageButton = InlineKeyboardButton.builder()//
				.text("\u274C")//
				.callbackData(DELETE_MESSAGE)//
				.build();
		keyboard.keyboardRow(List.of(deleteMessageButton));

		// Send the price history message
		String historyText = item.getPriceHistory().stream().map(o -> o.getDate() + " - " + o.getPrice()).collect(Collectors.joining("\n"));
		historyText = "<b>%s</b>\n".formatted(item.getName()) + historyText;
		final SendMessage historyMessage = SendMessage.builder()//
				.chatId(utility.getAdminId())//
				.parseMode("HTML")//
				.text(historyText)//
				.replyMarkup(keyboard.build())//
				.build();
		exec(historyMessage);

		// Answer the callback
		final AnswerCallbackQuery answer = AnswerCallbackQuery.builder()//
				.callbackQueryId(callback.getId())//
				.text("")//
				.showAlert(false).build();

		exec(answer);
	}

	/** /myitems Command */
	private void myItemsCommand(Message msg) throws Exception {
		if (msg.isCommand() && "/myitems".equals(msg.getText())) {
			final Long userId = msg.getFrom().getId();
			final List<TrackedItem> items = utility.getTrackedItems(userId);

			if (items.isEmpty()) {
				sendMessage(userId, "You are not tracking any item!");
				return;
			} // if

			final SendMessage result = SendMessage.builder().chatId(userId)//
					.parseMode("HTML")//
					.text("These are the item you are tracking.\nmode: Link")//
					.replyMarkup(getItemsKeyboard(userId, items, LINK_MODE))//
					.build();

			exec(result);
		}
	}

	/** Callback used when switching modes in the item keyboard. */
	private void itemsKeyboardChangeModeCallback(CallbackQuery callback) throws Exception {
		final Long userId = callback.getFrom().getId();
		final String data = callback.getData();

		// get the selected mode
		String description = null;
		if (DELETE_MODE.equals(data)) { description = "mode: Delete - watch out!"; }
		if (SHOW_HISTORY_MODE.equals(data)) { description = "mode: Price history"; }
		if (LINK_MODE.equals(data)) { description = "mode: Link"; }
		if (description == null) { return; }

		final List<TrackedItem> items = utility.getTrackedItems(userId);

		final Message msg = (Message) callback.getMessage();

		// prepare the EditMessage with the keyboard
		final EditMessageTextBuilder edit = EditMessageText.builder()//
				.chatId(msg.getChatId())//
				.messageId(msg.getMessageId())//
				.parseMode("HTML");

		if (items.isEmpty()) {
			edit.text("You are not tracking any item!");
		} else {
			final String finalDescription = "These are the items you are tracking.\n%s".formatted(description);
			// if the selected mode is the same i give an alert
			if (Objects.equals(finalDescription, msg.getText())) {
				exec(AnswerCallbackQuery.builder()//
						.callbackQueryId(callback.getId())//
						.cacheTime(5)//
						.text("Already in that mode")//
						.showAlert(true).build());
				return;
			} // if

			edit.text(finalDescription).replyMarkup(getItemsKeyboard(userId, items, data));
		}

		exec(edit.build());
	}

	/**
	 * Returns the items keyboard
	 *
	 * @param userId The user
	 * @param items  The list of items to show
	 * @param mode   Selected mode
	 */
	private InlineKeyboardMarkup getItemsKeyboard(Long userId, List<TrackedItem> items, String mode) throws Exception {

		final InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();

		// The three buttons to switch mode
		final InlineKeyboardButton deleteButton = InlineKeyboardButton.builder()//
				.text("\u274C")//
				.callbackData(DELETE_MODE).build();
		final InlineKeyboardButton showHistoryButton = InlineKeyboardButton.builder()//
				.text("\uD83D\uDCC9")//
				.callbackData(SHOW_HISTORY_MODE).build();
		final InlineKeyboardButton linkButton = InlineKeyboardButton.builder()//
				.text("Link")//
				.callbackData(LINK_MODE).build();

		keyboard.keyboardRow(List.of(deleteButton, showHistoryButton, linkButton));

		// Create a button for each item
		for (final TrackedItem item : items) {
			String link = null;
			String callbackData = null;
			String name = item.getName();
			if (mode.equals(DELETE_MODE)) { name = "\u274C" + name + "\u274C"; callbackData = DELETE + item.getUuid(); }
			if (mode.equals(SHOW_HISTORY_MODE)) { name = "\uD83D\uDCC9" + name; callbackData = SHOW_HISTORY + item.getUuid(); }
			if (mode.equals(LINK_MODE)) { link = item.getUrl(); }

			final InlineKeyboardButton itemButton = InlineKeyboardButton.builder()//
					.text(name)//
					.callbackData(callbackData)//
					.url(link)//
					.build();

			keyboard.keyboardRow(List.of(itemButton));
		}
		return keyboard.build();
	} // getItemsKeyboard

	/** Callback to delete an item. */
	private void deleteItemCallback(CallbackQuery callback) throws Exception {
		final Long userId = callback.getFrom().getId();
		final Long chatId = callback.getMessage().getChatId();
		final String data = callback.getData();
		if (!data.startsWith(DELETE)) { return; }

		final String uuid = data.replace(DELETE, "");
		final List<TrackedItem> items = utility.getTrackedItems(userId);
		final Message msg = (Message) callback.getMessage();

		// Find the item
		final TrackedItem item = items.stream().filter(itm -> Objects.equals(itm.getUuid(), uuid)).findFirst().orElse(null);

		// check that the items keyboard was valid
		if (item == null) {
			exec(EditMessageText.builder()//
					.chatId(chatId)//
					.messageId(msg.getMessageId())//
					.parseMode("HTML")//
					.text("That list was too old!")//
					.build());
			return;
		} // if

		// Delete the item
		final List<TrackedItem> filtered = items.stream().filter(itm -> !Objects.equals(itm.getUuid(), uuid)).toList();
		utility.saveTrackedItems(userId, items);

		// Notify the user
		final SendMessage doneMessage = SendMessage.builder()//
				.chatId(chatId)//
				.text("Deleted:\n%s".formatted(item.getUrl())).build();

		exec(doneMessage);

		// Update the items keyboard
		final EditMessageText edit = EditMessageText.builder()//
				.chatId(chatId)//
				.messageId(msg.getMessageId())//
				.parseMode("HTML")//
				.text(msg.getText())//
				.replyMarkup(getItemsKeyboard(userId, filtered, DELETE_MODE))//
				.build();

		exec(edit);
	}

	/** /help Command */
	private void helpCommand(Message msg) throws Exception {
		if (msg.isCommand() && "/help".equals(msg.getText())) {
			final Long userId = msg.getFrom().getId();

			final String text = """
					Start tracking a new item by sending a message with a nickname that will help you recognize the item.
					Then in the second line of the same message paste the url!

					Example:
					Hilfiger white shoes with stripes
					https://www.zalando.it/tommy-hilfiger-essential-cupsole-sneakers-basse-white-to112o0ib-a11.html
					""";

			sendMessage(userId, text);
		}
	} // myItemsCommand

	public void sendMessage(Long userId, String message) throws Exception {
		sendMessage(userId.toString(), message);
	}

	public void sendMessage(String userId, String message) throws Exception {
		final SendMessage sm = SendMessage.builder()//
				.parseMode("HTML")//
				.chatId(userId)//
				.text(message)//
				.build();
		exec(sm);
	} // sendMessage

	/** Sends an html file with the price history chart. */
	private void sendChart(Long userId, TrackedItem item) throws Exception {
		// TODO
//		List<String> data = item.getPriceHistory().stream()//
//				.map(his -> {
//					final String[] dateParts = his.getDate().split("-");
//					final String date = "%s/%s/%s".formatted(dateParts[0], dateParts[1], dateParts[2].substring(2));
//					final String price = his.getPrice().replace(",", ".");
//					return "['%s', %s]".formatted(date, price);
//				}).toList();
//
//
//		final InputFile file = new InputFile(new ByteArrayInputStream(html.getBytes()), "%s.html".formatted(item.getName()));
//
//		final SendDocument sd = SendDocument.builder()//
//				.chatId(userId)//
//				.document(file)//
//				.build();
//
//		execute(sd);
	}

	private <T extends Serializable, Method extends BotApiMethod<T>> T exec(Method method) throws Exception {
		try {
			return execute(method);
		} catch (final Exception e) {
			// if the error is caused by network problems i wait and retry
			final boolean retry = e.getMessage().contains("Connection timed out") || e.getMessage().contains("Network is unreachable")
					|| (e.getCause() != null && e.getCause().getMessage().contains("Connection timed out"))
					|| (e.getCause() != null && e.getCause().getMessage().contains("Network is unreachable"));

			if (retry) {
				Thread.sleep(6000);
				return execute(method);
			} else {
				throw e;
			}
		}
	}

}
