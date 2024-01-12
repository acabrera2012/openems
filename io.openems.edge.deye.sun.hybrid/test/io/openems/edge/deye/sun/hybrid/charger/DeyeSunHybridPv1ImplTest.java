package io.openems.edge.deye.sun.hybrid.charger;

import org.junit.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyConfigurationAdmin;
import io.openems.edge.deye.sun.hybrid.DeyeSunHybridImpl;
import io.openems.edge.deye.sun.hybrid.pv.DeyeSunHybridPv1Impl;
import io.openems.edge.ess.test.ManagedSymmetricEssTest;

public class DeyeSunHybridPv1ImplTest {

	private static final String CHARGER_ID = "charger0";
	private static final String ESS_ID = "ess0";
	private static final String MODBUS_ID = "modbus0";

	@Test
	public void test() throws Exception {
		var ess = new DeyeSunHybridImpl();
		new ManagedSymmetricEssTest(ess) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.activate(io.openems.edge.deye.sun.hybrid.MyConfig.create() //
						.setId(ESS_ID) //
						.setModbusId(MODBUS_ID) //
						.setSurplusFeedInSocLimit(90) //
						.setSurplusFeedInAllowedChargePowerLimit(-8000) //
						.setSurplusFeedInIncreasePowerFactor(1.1) //
						.setSurplusFeedInMaxIncreasePowerFactor(2000) //
						.setSurplusFeedInPvLimitOnPowerDecreaseCausedByOvertemperature(5000) //
						.setSurplusFeedInOffTime("17:00:00") //
						.build());

		new ComponentTest(new DeyeSunHybridPv1Impl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", ess) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.activate(MyConfigPv1.create() //
						.setId(CHARGER_ID) //
						.setModbusId(MODBUS_ID) //
						.setEssId(ESS_ID) //
						.setMaxActualPower(0) //
						.build()) //
		;
	}
}
