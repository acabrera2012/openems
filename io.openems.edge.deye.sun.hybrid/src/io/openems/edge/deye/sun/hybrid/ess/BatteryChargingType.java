package io.openems.edge.deye.sun.hybrid.ess;

import io.openems.common.types.OptionsEnum;

public enum BatteryChargingType implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	LEAD(0, "Lead"), //
	LITHIUM(1, "Lithium");

	private final int value;
	private final String name;

	private BatteryChargingType(int value, String name) {
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
}