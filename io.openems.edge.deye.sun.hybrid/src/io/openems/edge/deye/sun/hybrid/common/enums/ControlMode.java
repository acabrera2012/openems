package io.openems.edge.deye.sun.hybrid.common.enums;

public enum ControlMode {

	/**
	 * Uses the internal 'AUTO' mode of the Deye inverter. Allows no remote
	 * control of Set-Points. Requires a Deye Smart Meter at the grid junction
	 * point.
	 */
	INTERNAL,
	/**
	 * Uses the internal 'AUTO' mode of the Deye inverter but smartly switches to
	 * other modes if required.Requires a Deye Smart Meter at the grid junction
	 * point.
	 */
	SMART,
	/**
	 * Full control of the Deye inverter by OpenEMS. Slower than internal 'AUTO'
	 * mode, but does not require a Deye Smart Meter at the grid junction point.
	 */
	REMOTE;

}
