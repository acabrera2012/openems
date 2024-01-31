package io.openems.edge.deye.sun.hybrid.ess;

import io.openems.common.types.OptionsEnum;

public enum InverterRunState implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	STANDBY(0, "Standby"), //
	SELF_CHECK(1, "Self-check"), //
	NORMAL(2, "Normal"), //
	ALARM(3, "Alarm"), //
	FAULT(4, "Fault"), //
	ACTIVATING(5, "Activating");

	private final int value;
	private final String name;

	private InverterRunState(int value, String name) {
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