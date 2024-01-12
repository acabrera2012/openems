package io.openems.edge.deye.batteryinverter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import io.openems.edge.common.channel.value.Value;
import io.openems.edge.deye.sun.hybrid.batteryinverter.DeyeBatteryInverterImpl;
import io.openems.edge.deye.sun.hybrid.batteryinverter.DeyeBatteryInverterImpl.BatteryData;

public class TestStatic {

	private static final int MAX_DC_CURRENT = 25;

	@Test
	public void testCalculateSurplusPower() throws Exception {
		// Battery Current is unknown -> null
		assertNull(DeyeBatteryInverterImpl.calculateSurplusPower(new BatteryData(null, null), 5000, MAX_DC_CURRENT));

		// Battery Current is > Max BatteryInverter DC Current -> null
		assertNull(DeyeBatteryInverterImpl.calculateSurplusPower(new BatteryData(MAX_DC_CURRENT + 1, null), 5000,
				MAX_DC_CURRENT));

		// Production Power is unknown or negative > null
		assertNull(DeyeBatteryInverterImpl.calculateSurplusPower(new BatteryData(MAX_DC_CURRENT - 1, null), null,
				MAX_DC_CURRENT));
		assertNull(DeyeBatteryInverterImpl.calculateSurplusPower(new BatteryData(MAX_DC_CURRENT - 1, null), -1,
				MAX_DC_CURRENT));

		// Max Charge Power exceeds Production Power
		assertNull(DeyeBatteryInverterImpl.calculateSurplusPower(new BatteryData(20, 466) /* 9320 */, 5000,
				MAX_DC_CURRENT));

		// Surplus Power is Production Power minus Max Charge Power
		assertEquals(5680, (int) DeyeBatteryInverterImpl.calculateSurplusPower(new BatteryData(20, 466) /* 9320 */,
				15000, MAX_DC_CURRENT));
	}

	@Test
	public void testPreprocessAmpereValue47900() {

		assertEquals(MAX_DC_CURRENT,
				DeyeBatteryInverterImpl.preprocessAmpereValue47900(new Value<Integer>(null, 1234), MAX_DC_CURRENT));

		assertEquals(0,
				DeyeBatteryInverterImpl.preprocessAmpereValue47900(new Value<Integer>(null, -25), MAX_DC_CURRENT));

		assertEquals(12,
				DeyeBatteryInverterImpl.preprocessAmpereValue47900(new Value<Integer>(null, 12), MAX_DC_CURRENT));

		assertEquals(0,
				DeyeBatteryInverterImpl.preprocessAmpereValue47900(new Value<Integer>(null, null), MAX_DC_CURRENT));
	}
}
