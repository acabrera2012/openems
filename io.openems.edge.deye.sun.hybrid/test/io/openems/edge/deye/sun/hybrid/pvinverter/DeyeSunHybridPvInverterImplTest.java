package io.openems.edge.deye.sun.hybrid.pvinverter;

import org.junit.Test;

import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyConfigurationAdmin;

public class DeyeSunHybridPvInverterImplTest {

	private static final String PV_INVERTER_ID = "pvInverter0";
	private static final String CORE_ID = "deyeCore0";

	@Test
	public void test() throws Exception {
		new ComponentTest(new DeyeSunHybridPvInverterImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.activate(MyConfig.create() //
						.setId(PV_INVERTER_ID) //
						.setCoreId(CORE_ID) //
						.build()) //
		;
	}
}
