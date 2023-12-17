package io.openems.edge.deye.sun.hybrid.gridmeter;

import io.openems.common.types.OptionsEnum;

public enum GridMeterStatus implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	NOT_CONNECTED(0, "Unknown (Grid meter not connected)"), //
	ON_GRID(1, "On-Grid mode"), //
	OFF_GRID(2, "Off-Grid mode"); //

	private final int value;
	private final String name;

	private GridMeterStatus(int value, String name) {
		this.value = value;
		this.name = name;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}

	/**
	 * Gets the {@link GridMeterStatus} from an int value.
	 * 
	 * @param value the int value
	 * @return the {@link GridMeterStatus}
	 */
	public static GridMeterStatus fromInt(int value) {
		for (GridMeterStatus status : GridMeterStatus.values()) {
			if (status.getValue() == value) {
				return status;
			}
		}
		return GridMeterStatus.UNDEFINED;
	}
}