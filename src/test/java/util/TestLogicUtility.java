package util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class TestLogicUtility {

	static LogicUtility utility;

	@BeforeAll
	static void setUp() throws Exception {
		utility = new LogicUtility();
	}

	static List<Arguments> providerGetNameAndUrlFromMessage() {
		final List<Arguments> list = new ArrayList<>();

		list.add(Arguments.of("""
				dummy
				""", null, null));

		list.add(Arguments.of("""
				dummy
				https://www.google.com/
				""", null, null));

		list.add(Arguments.of("""
				shirt
				https://www.zalando.fr/yourturn-unisex-sweatshirt-pink-yo121009q-j11.html
				""", "shirt", "https://www.zalando.fr/yourturn-unisex-sweatshirt-pink-yo121009q-j11.html"));

		list.add(Arguments.of("""
				shirt


				https://www.zalando.fr/yourturn-unisex-sweatshirt-pink-yo121009q-j11.html
				""", "shirt", "https://www.zalando.fr/yourturn-unisex-sweatshirt-pink-yo121009q-j11.html"));

		list.add(Arguments.of("""
				https://www.zalando.fr/yourturn-unisex-sweatshirt-pink-yo121009q-j11.html
				""", "yourturn unisex sweatshirt pink", "https://www.zalando.fr/yourturn-unisex-sweatshirt-pink-yo121009q-j11.html"));

		return list;
	}

	@ParameterizedTest
	@MethodSource("providerGetNameAndUrlFromMessage")
	@DisplayName("Test the method that gets the name and url from a message")
	void testGetNameAndUrlFromMessage(String message, String expectedName, String expectedUrl) {

		final Optional<Pair<String, String>> actual = utility.getNameAndUrlFromMessage(message);

		assertEquals(expectedName, actual.map(Pair::getLeft).orElse(null));
		assertEquals(expectedUrl, actual.map(Pair::getRight).orElse(null));
	}

	static List<Arguments> providerIsValidUrl() {
		return Arrays.asList(//
				Arguments.of("dummy", false), //
				Arguments.of("https://www.google.com/", false),
				Arguments.of("https://www.zalando.it/harmont-and-blaine-camicia-blu-scuro-screziato-hr422d0ea-k11.html", true),
				Arguments.of("https://www.zalando.fr/yourturn-unisex-sweatshirt-pink-yo121009q-j11.html", true),
				Arguments.of("https://www.zalando.co.uk/anna-field-boots-black-an611a0le-q11.html", true),
				Arguments.of("https://www.zalando.es/versace-gabardina-black-1ve22t005-q11.html", true));
	}

	@ParameterizedTest
	@MethodSource("providerIsValidUrl")
	@DisplayName("Test url validity")
	void testIsValidUrl(String url, boolean expected) {
		final boolean actual = utility.isValidUrl(url);
		assertEquals(expected, actual);
	}

}
