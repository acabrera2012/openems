package io.openems.edge.deye.emergencypowermeter;

import org.junit.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyConfigurationAdmin;
import io.openems.edge.deye.sun.hybrid.emergencypowermeter.DeyeEmergencyPowerMeterImpl;

public class DeyeEmergencyPowerMeterTest {

	private static final String MODBUS_ID = "modbus0";

	private static final String METER_ID = "meter2";

	@Test
	public void test() throws Exception {
		new ComponentTest(new DeyeEmergencyPowerMeterImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.activate(MyConfig.create() //
						.setId(METER_ID) //
						.setModbusId(MODBUS_ID) //
						.build());
	}
}
