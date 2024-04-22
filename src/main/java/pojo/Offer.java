package pojo;

public class Offer {

	public Price price;
	public Stock stock;
	public boolean isMeaningfulOffer;

	@Override
	public String toString() {
		return "Offer [price=" + price + ", stock=" + stock + "]";
	}

	public static class Price {
		public InnerPrice promotional;
		public InnerPrice original;

		@Override
		public String toString() {
			return "Price [promotional=" + promotional + ", original=" + original + "]";
		}

	}

	public static class InnerPrice {
		public int amount;

		@Override
		public String toString() {
			return "InnerPrice [amount=" + amount + "]";
		}

	}

	public static class Stock {
		public String quantity;

		@Override
		public String toString() {
			return "Stock [quantity=" + quantity + "]";
		}

	}

}
