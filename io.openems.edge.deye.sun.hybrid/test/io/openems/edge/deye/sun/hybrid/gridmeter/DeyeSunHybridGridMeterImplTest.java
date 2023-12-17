package io.openems.edge.deye.sun.hybrid.gridmeter;

import org.junit.Test;

import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyConfigurationAdmin;
import io.openems.edge.deye.sun.hybrid.gridmeter.DeyeSunHybridGridMeterImpl;

public class DeyeSunHybridGridMeterImplTest {

	private static final String METER_ID = "meter0";
	private static final String CORE_ID = "deyeCore0";

	@Test
	public void test() throws Exception {
		new ComponentTest(new DeyeSunHybridGridMeterImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.activate(MyConfig.create() //
						.setId(METER_ID) //
						.setCoreId(CORE_ID) //
						.build()) //
		;
	}

}
