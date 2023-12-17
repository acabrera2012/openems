package io.openems.edge.deye.sun.hybrid.core;

import org.junit.Test;

import io.openems.edge.common.test.ComponentTest;

public class DeyeSunHybridCoreImplTest {

	private static final String CORE_ID = "deyeCore0";

	@Test
	public void test() throws Exception {
		new ComponentTest(new DeyeSunHybridCoreImpl()) //
				.activate(MyConfig.create() //
						.setId(CORE_ID) //
						.setIp("192.168.0.1") //
						.setSerialnumber("123456") //
						.setIdentkey("0xabcd") //
						.setUserkey("user") //
						.build()) //
		;
	}
}
