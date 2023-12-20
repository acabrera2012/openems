package io.openems.edge.deye.ess;

import org.junit.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyConfigurationAdmin;
import io.openems.edge.deye.sun.hybrid.DeyeConstants;
import io.openems.edge.deye.sun.hybrid.charger.singlestring.DeyeChargerPv1;
import io.openems.edge.deye.sun.hybrid.common.enums.ControlMode;
import io.openems.edge.deye.sun.hybrid.ess.DeyeEssImpl;
import io.openems.edge.ess.test.DummyPower;
import io.openems.edge.ess.test.ManagedSymmetricEssTest;

public class DeyeEssImplTest {

	private static final String ESS_ID = "ess0";
	private static final String MODBUS_ID = "modbus0";
	private static final String CHARGER_ID = "charger0";

	@Test
	public void testEt() throws Exception {
		var charger = new DeyeChargerPv1();
		new ComponentTest(charger) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.activate(io.openems.edge.deye.charger.singlestring.MyConfig.create() //
						.setId(CHARGER_ID) //
						.setBatteryInverterId(ESS_ID) //
						.setModbusId(MODBUS_ID) //
						.setModbusUnitId(DeyeConstants.DEFAULT_UNIT_ID) //
						.build());

		var ess = new DeyeEssImpl();
		ess.addCharger(charger);
		new ManagedSymmetricEssTest(ess) //
				.addReference("power", new DummyPower()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.addComponent(charger) //
				.activate(MyConfig.create() //
						.setId(ESS_ID) //
						.setModbusId(MODBUS_ID) //
						.setModbusUnitId(DeyeConstants.DEFAULT_UNIT_ID) //
						.setCapacity(9_000) //
						.setMaxBatteryPower(5_200) //
						.setControlMode(ControlMode.SMART) //
						.build()) //
		;
	}

	@Test
	public void testBt() throws Exception {
		var ess = new DeyeEssImpl();
		new ManagedSymmetricEssTest(ess) //
				.addReference("power", new DummyPower()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.activate(MyConfig.create() //
						.setId(ESS_ID) //
						.setModbusId(MODBUS_ID) //
						.setModbusUnitId(DeyeConstants.DEFAULT_UNIT_ID) //
						.setCapacity(9_000) //
						.setMaxBatteryPower(5_200) //
						.setControlMode(ControlMode.SMART) //
						.build()) //
		;
	}

}
