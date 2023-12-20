package io.openems.edge.deye.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.openems.edge.deye.sun.hybrid.common.AbstractDeye;
import io.openems.edge.deye.sun.hybrid.common.Deye;
import io.openems.edge.deye.sun.hybrid.common.enums.DeyeType;

public class TestStatic {

	@Test
	public void testGetHardwareTypeFromSerialNr() {
		assertEquals(DeyeType.FENECON_FHI_10_DAH, AbstractDeye.getDeyeTypeFromSerialNr("7010KETU22AW0901"));
		assertNotEquals(DeyeType.FENECON_FHI_10_DAH, AbstractDeye.getDeyeTypeFromSerialNr("70000KETU22AW090"));

		assertEquals(DeyeType.FENECON_FHI_20_DAH, AbstractDeye.getDeyeTypeFromSerialNr("9020KETT22AW0004"));
		assertNotEquals(DeyeType.FENECON_FHI_20_DAH, AbstractDeye.getDeyeTypeFromSerialNr("9010KETT22AW0004"));

		assertEquals(DeyeType.FENECON_FHI_29_9_DAH, AbstractDeye.getDeyeTypeFromSerialNr("9030KETT228W0004"));
		assertNotEquals(DeyeType.FENECON_FHI_29_9_DAH, AbstractDeye.getDeyeTypeFromSerialNr("9020KETT228W0004"));
		assertEquals(DeyeType.FENECON_FHI_29_9_DAH, AbstractDeye.getDeyeTypeFromSerialNr("929K9ETT231W0159"));
		assertNotEquals(DeyeType.FENECON_FHI_29_9_DAH, AbstractDeye.getDeyeTypeFromSerialNr("929KETT231W0159"));
		assertNotEquals(DeyeType.FENECON_FHI_29_9_DAH, AbstractDeye.getDeyeTypeFromSerialNr("928K9ETT231W0159"));
		assertEquals(DeyeType.FENECON_FHI_29_9_DAH, AbstractDeye.getDeyeTypeFromSerialNr("929K9ETT231W0160"));

		assertEquals(DeyeType.UNDEFINED, AbstractDeye.getDeyeTypeFromSerialNr("9040KETT228W0004"));
		assertEquals(DeyeType.UNDEFINED, AbstractDeye.getDeyeTypeFromSerialNr("9000KETT228W0004"));
		assertEquals(DeyeType.UNDEFINED, AbstractDeye.getDeyeTypeFromSerialNr("ET2"));
		assertEquals(DeyeType.UNDEFINED, AbstractDeye.getDeyeTypeFromSerialNr(""));
		assertEquals(DeyeType.UNDEFINED, AbstractDeye.getDeyeTypeFromSerialNr(null));
	}

	@Test
	public void testGetHardwareTypeFromDeyeString() {
		assertEquals(DeyeType.DEYE_10K_BT, AbstractDeye.getDeyeTypeFromStringValue("GW10K-BT"));
		assertEquals(DeyeType.DEYE_10K_ET, AbstractDeye.getDeyeTypeFromStringValue("GW10K-ET"));
		assertEquals(DeyeType.DEYE_5K_BT, AbstractDeye.getDeyeTypeFromStringValue("GW5K-BT"));
		assertEquals(DeyeType.DEYE_5K_ET, AbstractDeye.getDeyeTypeFromStringValue("GW5K-ET"));
		assertEquals(DeyeType.DEYE_8K_BT, AbstractDeye.getDeyeTypeFromStringValue("GW8K-BT"));
		assertEquals(DeyeType.DEYE_8K_ET, AbstractDeye.getDeyeTypeFromStringValue("GW8K-ET"));
		assertEquals(DeyeType.FENECON_FHI_10_DAH, AbstractDeye.getDeyeTypeFromStringValue("FHI-10-DAH"));
		assertEquals(DeyeType.UNDEFINED, AbstractDeye.getDeyeTypeFromStringValue("ET2"));
		assertEquals(DeyeType.UNDEFINED, AbstractDeye.getDeyeTypeFromStringValue(""));
		assertEquals(DeyeType.UNDEFINED, AbstractDeye.getDeyeTypeFromStringValue(null));
	}

	@Test
	public void testDetectActiveDiagStatesH() {
		// 0x00000001 DIAG_STATUS_H_BATTERY_PRECHARGE_RELAY_OFF
		// 0x00000002 DIAG_STATUS_H_BYPASS_RELAY_STICK
		// 0x10000000 DIAG_STATUS_H_METER_VOLTAGE_SAMPLE_FAULT
		// 0x20000000 DIAG_STATUS_H_EXTERNAL_STOP_MODE_ENABLE
		// 0x40000000 DIAG_STATUS_H_BATTERY_OFFGRID_DOD
		// 0x80000000 DIAG_STATUS_H_BATTERY_SOC_ADJUST_ENABLE

		Long value = 0xC0000001L;
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_SOC_ADJUST_ENABLE));
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_OFFGRID_DOD));
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_PRECHARGE_RELAY_OFF));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BYPASS_RELAY_STICK));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_METER_VOLTAGE_SAMPLE_FAULT));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_EXTERNAL_STOP_MODE_ENABLE));

		value = 0xC0005701L;
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_SOC_ADJUST_ENABLE));
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_OFFGRID_DOD));
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_PRECHARGE_RELAY_OFF));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BYPASS_RELAY_STICK));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_METER_VOLTAGE_SAMPLE_FAULT));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_EXTERNAL_STOP_MODE_ENABLE));

		value = 0x90000003L;
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_SOC_ADJUST_ENABLE));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_OFFGRID_DOD));
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_PRECHARGE_RELAY_OFF));
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BYPASS_RELAY_STICK));
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_METER_VOLTAGE_SAMPLE_FAULT));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_EXTERNAL_STOP_MODE_ENABLE));

		value = 3221225473L;
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_SOC_ADJUST_ENABLE));
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_OFFGRID_DOD));
		assertTrue(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_PRECHARGE_RELAY_OFF));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BYPASS_RELAY_STICK));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_METER_VOLTAGE_SAMPLE_FAULT));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_EXTERNAL_STOP_MODE_ENABLE));

		value = null;
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_SOC_ADJUST_ENABLE));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_OFFGRID_DOD));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BATTERY_PRECHARGE_RELAY_OFF));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_BYPASS_RELAY_STICK));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_METER_VOLTAGE_SAMPLE_FAULT));
		assertFalse(AbstractDeye.detectDiagStatesH(value) //
				.get(Deye.ChannelId.DIAG_STATUS_EXTERNAL_STOP_MODE_ENABLE));
	}
}
