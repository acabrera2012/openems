package io.openems.edge.deye.sun.hybrid;

import org.junit.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.DummyConfigurationAdmin;
import io.openems.edge.deye.sun.hybrid.ess.DeyeSunHybridImpl;
import io.openems.edge.ess.test.ManagedSymmetricEssTest;

public class DeyeSunHybridImplTest {

	private static final String ESS_ID = "ess0";
	private static final String MODBUS_ID = "modbus0";

	@Test
	public void test() throws Exception {
		new ManagedSymmetricEssTest(new DeyeSunHybridImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.activate(MyConfig.create() //
						.setId(ESS_ID) //
						.setModbusId(MODBUS_ID) //
						.setSurplusFeedInSocLimit(90) //
						.setSurplusFeedInAllowedChargePowerLimit(-8000) //
						.setSurplusFeedInIncreasePowerFactor(1.1) //
						.setSurplusFeedInMaxIncreasePowerFactor(2000) //
						.setSurplusFeedInPvLimitOnPowerDecreaseCausedByOvertemperature(5000) //
						.setSurplusFeedInOffTime("17:00:00") //
						.build()) //
		;
	}
}
