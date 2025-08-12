package main;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import pojo.TrackedItem;

public class TestMain {

	static List<Arguments> providerPriceLowered() {
		return Arrays.asList(//
				Arguments.of("49,99", "49,99", false), //
				Arguments.of("49,99", "59,99", false), //
				Arguments.of("49,99", "49,00", false), //
				Arguments.of("49,99", "39,99", true));
	}

	@ParameterizedTest
	@MethodSource("providerPriceLowered")
	@DisplayName("Test the method that checks if the price is lowered")
	void testPriceLowered(String oldPrice, String newPrice, boolean expected) {

		final TrackedItem oldItem = new TrackedItem("uuid", "name", "url", "size", oldPrice, "qty", true, false);
		final TrackedItem newItem = new TrackedItem("uuid", "name", "url", "size", newPrice, "qty", true, false);

		final boolean actual = Main.priceLowered(oldItem, newItem);
		assertEquals(expected, actual);
	}

}
