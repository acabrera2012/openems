package io.openems.edge.deye.sun.hybrid.ess;

import io.openems.edge.battery.api.Battery;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.ClockProvider;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.deye.sun.hybrid.common.Deye;
import io.openems.edge.ess.generic.common.AbstractAllowedChargeDischargeHandler;

public class AllowedChargeDischargeHandler extends AbstractAllowedChargeDischargeHandler<DeyeEssImpl> {

	public AllowedChargeDischargeHandler(DeyeEssImpl parent) {
		super(parent);
	}

	@Override
	public void accept(ClockProvider clockProvider, Battery battery) {
		this.accept(clockProvider);
	}

	/**
	 * Calculates AllowedChargePower and AllowedDischargePower and sets the
	 * Channels.
	 *
	 * @param clockProvider a {@link ClockProvider}
	 */
	public void accept(ClockProvider clockProvider) {
		IntegerReadChannel bmsChargeImaxChannel = parent.channel(Deye.ChannelId.BMS_CHARGE_IMAX);
		var bmsChargeImax = bmsChargeImaxChannel.value().get();
		IntegerReadChannel bmsDischargeImaxChannel = parent.channel(Deye.ChannelId.BMS_DISCHARGE_IMAX);
		var bmsDischargeImax = bmsDischargeImaxChannel.value().get();
		IntegerReadChannel wbmsVoltageChannel = parent.channel(Deye.ChannelId.WBMS_VOLTAGE);
		var wbmsVoltage = wbmsVoltageChannel.value().get();
		this.calculateAllowedChargeDischargePower(clockProvider, true, bmsChargeImax, bmsDischargeImax, wbmsVoltage);

		// Battery limits
		var batteryAllowedChargePower = Math.round(this.lastBatteryAllowedChargePower);
		var batteryAllowedDischargePower = Math.round(this.lastBatteryAllowedDischargePower);

		// PV-Production
		var pvProduction = Math.max(//
				TypeUtils.orElse(//
						TypeUtils.subtract(this.parent.getActivePower().get(), this.parent.getDcDischargePower().get()), //
						0),
				0);

		// Apply AllowedChargePower and AllowedDischargePower
		this.parent._setAllowedChargePower(batteryAllowedChargePower * -1 /* invert charge power */);
		this.parent._setAllowedDischargePower(batteryAllowedDischargePower + pvProduction);
	}

}
