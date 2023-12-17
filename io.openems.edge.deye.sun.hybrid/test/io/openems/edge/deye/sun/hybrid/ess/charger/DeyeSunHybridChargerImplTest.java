package io.openems.edge.deye.sun.hybrid.ess.charger;

import org.junit.Test;

import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyConfigurationAdmin;

public class DeyeSunHybridChargerImplTest {

	private static final String CHARGER_ID = "charger0";
	private static final String CORE_ID = "deyeCore0";

	@Test
	public void test() throws Exception {
		new ComponentTest(new DeyeSunHybridChargerImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.activate(MyConfig.create() //
						.setId(CHARGER_ID) //
						.setCoreId(CORE_ID) //
						.build()) //
		;
	}
}
