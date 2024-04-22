package pojo;

import java.util.ArrayList;
import java.util.List;

public class TrackedItems {

	private List<TrackedItem> trackedItems = new ArrayList<>();

	public TrackedItems(List<TrackedItem> trackedItems) {
		super();
		this.trackedItems = trackedItems;
	}

	public List<TrackedItem> getTrackedItems() {
		return trackedItems;
	}

	@Override
	public String toString() {
		return "TrackedItems [trackedItems=" + trackedItems + "]";
	}

}
