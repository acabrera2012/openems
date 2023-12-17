package io.openems.edge.deye.sun.hybrid.ess;

import org.junit.Test;

import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyConfigurationAdmin;
import io.openems.edge.timedata.test.DummyTimedata;

public class DeyeSunHybridEssImplTest {

	private static final String ESS_ID = "ess0";
	private static final String CORE_ID = "deyeCore0";
	private static final String TIMEDATA_ID = "timedata0";

	@Test
	public void test() throws Exception {
		new ComponentTest(new DeyeSunHybridEssImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("timedata", new DummyTimedata(TIMEDATA_ID)) //
				.activate(MyConfig.create() //
						.setId(ESS_ID) //
						.setCoreId(CORE_ID) //
						.build()) //
		;
	}
}
