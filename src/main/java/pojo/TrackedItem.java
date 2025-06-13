package pojo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Objects;

public class TrackedItem {

	private final String uuid;
	private final String name;
	private final String url;
	private final String size;
	private String price;
	private final String quantity;
	private final boolean available;
	private final boolean hasCoupon;
	private int notFoundCount = 0;
	private int sizeNotFoundCount = 0;
	private ArrayList<PriceHistory> priceHistory = new ArrayList<>();

	public TrackedItem(String uuid, String name, String url, String size, String price, String quantity, boolean available, boolean hasCoupon) {
		super();
		this.uuid = uuid;
		this.name = name;
		this.url = url;
		this.size = size;
		this.price = price;
		this.quantity = quantity;
		this.available = available;
		this.hasCoupon = hasCoupon;
	}

	public String getUuid() {
		return uuid;
	}

	public String getName() {
		return name;
	}

	public String getPrice() {
		return price;
	}

	public void setPrice(String price) {
		this.price = price;
	}

	public String getQuantity() {
		return quantity;
	}

	public ArrayList<PriceHistory> getPriceHistory() {
		return priceHistory;
	}

	public void setPriceHistory(ArrayList<PriceHistory> priceHistory) {
		this.priceHistory = priceHistory;
	}

	public String getUrl() {
		return url;
	}

	public String getSize() {
		return size;
	}

	public boolean isHasCoupon() {
		return hasCoupon;
	}

	public boolean isAvailable() {
		return available;
	}

	public int getNotFoundCount() {
		return notFoundCount;
	}

	public void incrementNotFoundCount() {
		notFoundCount++;
	}

	public int getSizeNotFoundCount() {
		return sizeNotFoundCount;
	}

	public void incrementSizeNotFoundCount() {
		sizeNotFoundCount++;
	}

	public boolean anyChange(TrackedItem item) {
		return !Objects.equals(price, item.getPrice())//
				|| !Objects.equals(quantity, item.getQuantity())//
				|| !Objects.equals(available, item.isAvailable())//
				|| !Objects.equals(hasCoupon, item.isHasCoupon())//
				|| !Objects.equals(notFoundCount, item.getNotFoundCount()) //
				|| !Objects.equals(sizeNotFoundCount, item.getSizeNotFoundCount());
	}

	@Override
	public String toString() {
		return "TrackedItem [uuid=" + uuid + ", name=" + name + ", url=" + url + ", size=" + size + ", price=" + price + ", quantity=" + quantity + ", available="
				+ available + ", hasCoupon=" + hasCoupon + ", priceHistory=" + priceHistory + "]";
	}

	public static class PriceHistory {
		private final String price;
		private final String date;

		public PriceHistory(String price, String date) {
			super();
			this.price = price;
			this.date = date;
		}

		public String getStringPrice() {
			return price;
		}

		public Double getPrice() {
			return Double.valueOf(price.replace(",", "."));
		}

		public String getDate() {
			return date;
		}

		public LocalDate getLocalDate() {
			final String[] split = date.split("-");
			return LocalDate.of(Integer.valueOf(split[2]), Integer.valueOf(split[1]), Integer.valueOf(split[0]));
		} // getLocalDate

		@Override
		public String toString() {
			return "PriceHistory [price=" + price + ", date=" + date + "]";
		}
	} // PriceHistory

}
