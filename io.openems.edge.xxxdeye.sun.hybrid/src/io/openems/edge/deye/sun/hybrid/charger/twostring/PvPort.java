package io.openems.edge.deye.sun.hybrid.charger.twostring;

import io.openems.edge.deye.sun.hybrid.common.Deye;

/**
 * Defines the PV-Port of a Deye Charger Two-String.
 */
public enum PvPort {

	PV_1(Deye.ChannelId.TWO_S_MPPT1_P, Deye.ChannelId.TWO_S_MPPT1_I, Deye.ChannelId.TWO_S_PV1_I,
			Deye.ChannelId.TWO_S_PV2_I, Deye.ChannelId.TWO_S_PV1_V),
	PV_2(Deye.ChannelId.TWO_S_MPPT1_P, Deye.ChannelId.TWO_S_MPPT1_I, Deye.ChannelId.TWO_S_PV2_I,
			Deye.ChannelId.TWO_S_PV1_I, Deye.ChannelId.TWO_S_PV2_V), //
	PV_3(Deye.ChannelId.TWO_S_MPPT2_P, Deye.ChannelId.TWO_S_MPPT2_I, Deye.ChannelId.TWO_S_PV3_I,
			Deye.ChannelId.TWO_S_PV4_I, Deye.ChannelId.TWO_S_PV3_V), //
	PV_4(Deye.ChannelId.TWO_S_MPPT2_P, Deye.ChannelId.TWO_S_MPPT2_I, Deye.ChannelId.TWO_S_PV4_I,
			Deye.ChannelId.TWO_S_PV5_I, Deye.ChannelId.TWO_S_PV4_V), //
	PV_5(Deye.ChannelId.TWO_S_MPPT3_P, Deye.ChannelId.TWO_S_MPPT3_I, Deye.ChannelId.TWO_S_PV5_I,
			Deye.ChannelId.TWO_S_PV6_I, Deye.ChannelId.TWO_S_PV5_V), //
	PV_6(Deye.ChannelId.TWO_S_MPPT3_P, Deye.ChannelId.TWO_S_MPPT3_I, Deye.ChannelId.TWO_S_PV6_I,
			Deye.ChannelId.TWO_S_PV5_I, Deye.ChannelId.TWO_S_PV6_V); //

	public final Deye.ChannelId mpptPowerChannelId;
	public final Deye.ChannelId mpptCurrentChannelId;
	public final Deye.ChannelId pvCurrentId;
	public final Deye.ChannelId relatedPvCurrent;
	public final Deye.ChannelId pvVoltageId;

	private PvPort(Deye.ChannelId mpptPowerChannelId, Deye.ChannelId mpptCurrentChannelId,
			Deye.ChannelId pvCurrentId, Deye.ChannelId relatedPvCurrent, Deye.ChannelId pvVoltageId) {
		this.mpptPowerChannelId = mpptPowerChannelId;
		this.mpptCurrentChannelId = mpptCurrentChannelId;
		this.pvCurrentId = pvCurrentId;
		this.relatedPvCurrent = relatedPvCurrent;
		this.pvVoltageId = pvVoltageId;
	}
}
