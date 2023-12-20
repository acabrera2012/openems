package io.openems.edge.deye.sun.hybrid.common;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.INVERT;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.NotImplementedException;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.batteryinverter.api.HybridManagedSymmetricBatteryInverter;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.ChannelMetaInfoReadAndWrite;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.ModbusUtils;
import io.openems.edge.bridge.modbus.api.element.BitsWordElement;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.ModbusElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.EnumReadChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.deye.sun.hybrid.charger.DeyeCharger;
import io.openems.edge.deye.sun.hybrid.charger.twostring.DeyeChargerTwoString;
import io.openems.edge.deye.sun.hybrid.common.enums.BatteryMode;
import io.openems.edge.deye.sun.hybrid.common.enums.DeyeType;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

public abstract class AbstractDeye extends AbstractOpenemsModbusComponent
		implements Deye, OpenemsComponent, TimedataProvider, EventHandler {

	private static final Logger LOG = LoggerFactory.getLogger(AbstractDeye.class);
	private static final Map<Integer, Deye.ChannelId> DIAG_STATUS_H_STATES = Map.of(//
			0x00000001, Deye.ChannelId.DIAG_STATUS_BATTERY_PRECHARGE_RELAY_OFF, //
			0x00000002, Deye.ChannelId.DIAG_STATUS_BYPASS_RELAY_STICK, //
			0x10000000, Deye.ChannelId.DIAG_STATUS_METER_VOLTAGE_SAMPLE_FAULT, //
			0x20000000, Deye.ChannelId.DIAG_STATUS_EXTERNAL_STOP_MODE_ENABLE, //
			0x40000000, Deye.ChannelId.DIAG_STATUS_BATTERY_OFFGRID_DOD, //
			0x80000000, Deye.ChannelId.DIAG_STATUS_BATTERY_SOC_ADJUST_ENABLE);

	private final Logger log = LoggerFactory.getLogger(AbstractDeye.class);

	private final io.openems.edge.common.channel.ChannelId activePowerChannelId;
	private final io.openems.edge.common.channel.ChannelId reactivePowerChannelId;
	private final io.openems.edge.common.channel.ChannelId dcDischargePowerChannelId;
	private final CalculateEnergyFromPower calculateAcChargeEnergy;
	private final CalculateEnergyFromPower calculateAcDischargeEnergy;
	private final CalculateEnergyFromPower calculateDcChargeEnergy;
	private final CalculateEnergyFromPower calculateDcDischargeEnergy;

	protected final Set<DeyeCharger> chargers = new HashSet<>();

	protected AbstractDeye(//
			io.openems.edge.common.channel.ChannelId activePowerChannelId, //
			io.openems.edge.common.channel.ChannelId reactivePowerChannelId, //
			io.openems.edge.common.channel.ChannelId dcDischargePowerChannelId, //
			io.openems.edge.common.channel.ChannelId activeChargeEnergyChannelId, //
			io.openems.edge.common.channel.ChannelId activeDischargeEnergyChannelId, //
			io.openems.edge.common.channel.ChannelId dcChargeEnergyChannelId, //
			io.openems.edge.common.channel.ChannelId dcDischargeEnergyChannelId, //
			io.openems.edge.common.channel.ChannelId[] firstInitialChannelIds, //
			io.openems.edge.common.channel.ChannelId[]... furtherInitialChannelIds) throws OpenemsNamedException {
		super(firstInitialChannelIds, furtherInitialChannelIds);
		this.activePowerChannelId = activePowerChannelId;
		this.reactivePowerChannelId = reactivePowerChannelId;
		this.dcDischargePowerChannelId = dcDischargePowerChannelId;
		this.calculateAcChargeEnergy = new CalculateEnergyFromPower(this, activeChargeEnergyChannelId);
		this.calculateAcDischargeEnergy = new CalculateEnergyFromPower(this, activeDischargeEnergyChannelId);
		this.calculateDcChargeEnergy = new CalculateEnergyFromPower(this, dcChargeEnergyChannelId);
		this.calculateDcDischargeEnergy = new CalculateEnergyFromPower(this, dcDischargeEnergyChannelId);
	}

	@Override
	protected final ModbusProtocol defineModbusProtocol() throws OpenemsException {
		var protocol = new ModbusProtocol(this, //

				new FC3ReadRegistersTask(35001, Priority.LOW, //
						m(SymmetricEss.ChannelId.MAX_APPARENT_POWER, new UnsignedWordElement(35001)), //
						new DummyRegisterElement(35002), //
						m(Deye.ChannelId.SERIAL_NUMBER, new StringWordElement(35003, 8)) //
				),

				new FC3ReadRegistersTask(35016, Priority.LOW, //
						m(Deye.ChannelId.DSP_FM_VERSION_MASTER, new UnsignedWordElement(35016)), //
						m(Deye.ChannelId.DSP_FM_VERSION_SLAVE, new UnsignedWordElement(35017)), //
						m(Deye.ChannelId.DSP_BETA_VERSION, new UnsignedWordElement(35018)), //
						m(Deye.ChannelId.ARM_FM_VERSION, new UnsignedWordElement(35019)), //
						m(Deye.ChannelId.ARM_BETA_VERSION, new UnsignedWordElement(35020)) //
				), //

				new FC3ReadRegistersTask(35111, Priority.LOW, //
						// Registers for PV1 and PV2 (35103 to 35110) are read via DC-Charger
						// implementation
						m(Deye.ChannelId.V_PV3, new UnsignedWordElement(35111), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.I_PV3, new UnsignedWordElement(35112), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.P_PV3, new UnsignedDoublewordElement(35113)), //
						m(Deye.ChannelId.V_PV4, new UnsignedWordElement(35115), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.I_PV4, new UnsignedWordElement(35116), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.P_PV4, new UnsignedDoublewordElement(35117)), //
						m(Deye.ChannelId.PV_MODE, new UnsignedDoublewordElement(35119)), //
						// Registers for Grid Smart-Meter (35121 to 35135) are read via GridMeter
						// implementation
						new DummyRegisterElement(35121, 35135),
						m(SymmetricEss.ChannelId.GRID_MODE, new UnsignedWordElement(35136), //
								new ElementToChannelConverter(value -> {
									Integer intValue = TypeUtils.<Integer>getAsType(OpenemsType.INTEGER, value);
									if (intValue != null) {
										switch (intValue) {
										case 0:
											return GridMode.UNDEFINED;
										case 1:
											return GridMode.ON_GRID;
										case 2:
											return GridMode.OFF_GRID;
										}
									}
									return GridMode.UNDEFINED;
								}))), //

				new FC3ReadRegistersTask(35137, Priority.LOW, //
						m(Deye.ChannelId.TOTAL_INV_POWER, new SignedDoublewordElement(35137)), //
						m(Deye.ChannelId.AC_ACTIVE_POWER, new SignedDoublewordElement(35139), //
								INVERT), //
						m(this.reactivePowerChannelId, new SignedDoublewordElement(35141), //
								INVERT), //
						m(Deye.ChannelId.AC_APPARENT_POWER, new SignedDoublewordElement(35143), //
								INVERT), //
						new DummyRegisterElement(35145, 35147), //
						m(Deye.ChannelId.LOAD_MODE_R, new UnsignedWordElement(35148)), //
						new DummyRegisterElement(35149, 35153), //
						m(Deye.ChannelId.LOAD_MODE_S, new UnsignedWordElement(35154)), //
						new DummyRegisterElement(35155, 35159), //
						m(Deye.ChannelId.LOAD_MODE_T, new UnsignedWordElement(35160)), //
						new DummyRegisterElement(35161, 35162), //
						m(Deye.ChannelId.P_LOAD_R, new SignedDoublewordElement(35163)), //
						m(Deye.ChannelId.P_LOAD_S, new SignedDoublewordElement(35165)), //
						m(Deye.ChannelId.P_LOAD_T, new SignedDoublewordElement(35167)), //
						m(Deye.ChannelId.TOTAL_BACK_UP_LOAD_POWER, new SignedDoublewordElement(35169)), //
						m(Deye.ChannelId.TOTAL_LOAD_POWER, new SignedDoublewordElement(35171)), //
						m(Deye.ChannelId.UPS_LOAD_PERCENT, new UnsignedWordElement(35173), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.AIR_TEMPERATURE, new SignedWordElement(35174), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.MODULE_TEMPERATURE, new SignedWordElement(35175), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.RADIATOR_TEMPERATURE, new SignedWordElement(35176), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.FUNCTION_BIT_VALUE, new UnsignedWordElement(35177)), //
						m(Deye.ChannelId.BUS_VOLTAGE, new UnsignedWordElement(35178), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.NBUS_VOLTAGE, new UnsignedWordElement(35179), SCALE_FACTOR_MINUS_1)), //

				new FC3ReadRegistersTask(35180, Priority.HIGH, //
						m(Deye.ChannelId.V_BATTERY1, new UnsignedWordElement(35180), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.I_BATTERY1, new SignedWordElement(35181), SCALE_FACTOR_MINUS_1), //
						// Required for calculation of ActivePower; wrongly documented in official
						// Modbus protocol v1.9 as being Unsigned.
						m(Deye.ChannelId.P_BATTERY1, new SignedDoublewordElement(35182)),
						m(Deye.ChannelId.BATTERY_MODE, new UnsignedWordElement(35184))), //

				new FC3ReadRegistersTask(35186, Priority.LOW, //
						m(Deye.ChannelId.SAFETY_COUNTRY, new UnsignedWordElement(35186)), //
						m(Deye.ChannelId.WORK_MODE, new UnsignedWordElement(35187)), //
						new DummyRegisterElement(35188), //
						m(new BitsWordElement(35189, this) //
								.bit(0, Deye.ChannelId.STATE_16) //
								.bit(1, Deye.ChannelId.STATE_17) //
								.bit(2, Deye.ChannelId.STATE_18) //
								.bit(3, Deye.ChannelId.STATE_19) //
								.bit(4, Deye.ChannelId.STATE_20) //
								.bit(5, Deye.ChannelId.STATE_21) //
								.bit(6, Deye.ChannelId.STATE_22) //
								.bit(7, Deye.ChannelId.STATE_23) //
								.bit(8, Deye.ChannelId.STATE_24) //
								.bit(9, Deye.ChannelId.STATE_25) //
								.bit(10, Deye.ChannelId.STATE_26) //
								.bit(11, Deye.ChannelId.STATE_27) //
								.bit(12, Deye.ChannelId.STATE_28) //
								.bit(13, Deye.ChannelId.STATE_29) //
								.bit(14, Deye.ChannelId.STATE_30) //
								.bit(15, Deye.ChannelId.STATE_31) //
						), //
						m(new BitsWordElement(35190, this) //
								.bit(0, Deye.ChannelId.STATE_0) //
								.bit(1, Deye.ChannelId.STATE_1) //
								.bit(2, Deye.ChannelId.STATE_2) //
								.bit(3, Deye.ChannelId.STATE_3) //
								.bit(4, Deye.ChannelId.STATE_4) //
								.bit(5, Deye.ChannelId.STATE_5) //
								.bit(6, Deye.ChannelId.STATE_6) //
								.bit(7, Deye.ChannelId.STATE_7) //
								.bit(8, Deye.ChannelId.STATE_8) //
								.bit(9, Deye.ChannelId.STATE_9) //
								.bit(10, Deye.ChannelId.STATE_10) //
								.bit(11, Deye.ChannelId.STATE_11) //
								.bit(12, Deye.ChannelId.STATE_12) //
								.bit(13, Deye.ChannelId.STATE_13)//
								.bit(14, Deye.ChannelId.STATE_14)//
								.bit(15, Deye.ChannelId.STATE_15)//
						), //

						// The total PV production energy from installation
						m(Deye.ChannelId.PV_E_TOTAL, new UnsignedDoublewordElement(35191), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.PV_E_DAY, new UnsignedDoublewordElement(35193), SCALE_FACTOR_MINUS_1), //
						new DummyRegisterElement(35195, 35196), //
						m(Deye.ChannelId.H_TOTAL, new UnsignedDoublewordElement(35197)), //
						m(Deye.ChannelId.E_DAY_SELL, new UnsignedWordElement(35199), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_TOTAL_BUY, new UnsignedDoublewordElement(35200), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_DAY_BUY, new UnsignedWordElement(35202), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_TOTAL_LOAD, new UnsignedDoublewordElement(35203), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_LOAD_DAY, new UnsignedWordElement(35205), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_BATTERY_CHARGE, new UnsignedDoublewordElement(35206), //
								SCALE_FACTOR_2), //
						m(Deye.ChannelId.E_CHARGE_DAY, new UnsignedWordElement(35208), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_BATTERY_DISCHARGE, new UnsignedDoublewordElement(35209), SCALE_FACTOR_2), //
						m(Deye.ChannelId.E_DISCHARGE_DAY, new UnsignedWordElement(35211), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.BATTERY_STRINGS, new UnsignedWordElement(35212)), //
						m(Deye.ChannelId.CPLD_WARNING_CODE, new UnsignedWordElement(35213)), //
						new DummyRegisterElement(35214, 35217), //
						new UnsignedDoublewordElement(35218).onUpdateCallback(code -> {
							detectDiagStatesH(code) //
									.forEach((channel, value) -> this.channel(channel).setNextValue(value));
						}),

						m(new BitsWordElement(35220, this) //
								.bit(0, Deye.ChannelId.DIAG_STATUS_BATTERY_VOLT_LOW)//
								.bit(1, Deye.ChannelId.DIAG_STATUS_BATTERY_SOC_LOW)//
								.bit(2, Deye.ChannelId.DIAG_STATUS_BATTERY_SOC_IN_BACK)//
								.bit(3, Deye.ChannelId.DIAG_STATUS_BMS_DISCHARGE_DISABLE)//
								.bit(4, Deye.ChannelId.DIAG_STATUS_DISCHARGE_TIME_ON)//
								.bit(5, Deye.ChannelId.DIAG_STATUS_CHARGE_TIME_ON)//
								.bit(6, Deye.ChannelId.DIAG_STATUS_DISCHARGE_DRIVE_ON)//
								.bit(7, Deye.ChannelId.DIAG_STATUS_BMS_DISCHG_CURRENT_LOW)//
								.bit(8, Deye.ChannelId.DIAG_STATUS_DISCHARGE_CURRENT_LOW)//
								.bit(9, Deye.ChannelId.DIAG_STATUS_METER_COMM_LOSS)//
								.bit(10, Deye.ChannelId.DIAG_STATUS_METER_CONNECT_REVERSE)//
								.bit(11, Deye.ChannelId.DIAG_STATUS_SELF_USE_LOAD_LIGHT)//
								.bit(12, Deye.ChannelId.DIAG_STATUS_EMS_DISCHARGE_IZERO)//
								.bit(13, Deye.ChannelId.DIAG_STATUS_DISCHARGE_BUS_HIGH)//
								.bit(14, Deye.ChannelId.DIAG_STATUS_BATTERY_DISCONNECT)//
								.bit(15, Deye.ChannelId.DIAG_STATUS_BATTERY_OVERCHARGE)), //

						m(new BitsWordElement(35221, this) //
								.bit(0, Deye.ChannelId.DIAG_STATUS_BMS_OVER_TEMPERATURE)//
								.bit(1, Deye.ChannelId.DIAG_STATUS_BMS_OVERCHARGE)//
								.bit(2, Deye.ChannelId.DIAG_STATUS_BMS_CHARGE_DISABLE)//
								.bit(3, Deye.ChannelId.DIAG_STATUS_SELF_USE_OFF)//
								.bit(4, Deye.ChannelId.DIAG_STATUS_SOC_DELTA_OVER_RANGE)//
								.bit(5, Deye.ChannelId.DIAG_STATUS_BATTERY_SELF_DISCHARGE)//
								.bit(6, Deye.ChannelId.DIAG_STATUS_OFFGRID_SOC_LOW)//
								.bit(7, Deye.ChannelId.DIAG_STATUS_GRID_WAVE_UNSTABLE)//
								.bit(8, Deye.ChannelId.DIAG_STATUS_FEED_POWER_LIMIT)//
								.bit(9, Deye.ChannelId.DIAG_STATUS_PF_VALUE_SET)//
								.bit(10, Deye.ChannelId.DIAG_STATUS_REAL_POWER_LIMIT)//
								.bit(12, Deye.ChannelId.DIAG_STATUS_SOC_PROTECT_OFF)), //
						new DummyRegisterElement(35222, 35224), //
						m(Deye.ChannelId.EH_BATTERY_FUNCTION_ACTIVE, new UnsignedWordElement(35225)), //
						m(Deye.ChannelId.ARC_SELF_CHECK_STATUS, new UnsignedWordElement(35226)) //
				),

				new FC3ReadRegistersTask(35250, Priority.LOW, //
						m(new BitsWordElement(35250, this) //
								.bit(0, Deye.ChannelId.STATE_70) //
								.bit(1, Deye.ChannelId.STATE_71) //
								.bit(2, Deye.ChannelId.STATE_72) //
								.bit(3, Deye.ChannelId.STATE_73) //
								.bit(4, Deye.ChannelId.STATE_74) //
								.bit(5, Deye.ChannelId.STATE_75) //
								.bit(6, Deye.ChannelId.STATE_76) //
								.bit(7, Deye.ChannelId.STATE_77) //
								.bit(8, Deye.ChannelId.STATE_78) //
								.bit(9, Deye.ChannelId.STATE_79) //
								.bit(10, Deye.ChannelId.STATE_80) //
								.bit(11, Deye.ChannelId.STATE_81) //
								.bit(12, Deye.ChannelId.STATE_82) //
								.bit(13, Deye.ChannelId.STATE_83) //
								.bit(14, Deye.ChannelId.STATE_84) //
								.bit(15, Deye.ChannelId.STATE_85)), //
						m(new BitsWordElement(35251, this) //
								.bit(0, Deye.ChannelId.STATE_86) //
								.bit(1, Deye.ChannelId.STATE_87) //
								.bit(2, Deye.ChannelId.STATE_88) //
								.bit(3, Deye.ChannelId.STATE_89) //
								.bit(4, Deye.ChannelId.STATE_90) //
								.bit(5, Deye.ChannelId.STATE_91) //
								.bit(6, Deye.ChannelId.STATE_92) //
								.bit(7, Deye.ChannelId.STATE_93) //
						), //
						new DummyRegisterElement(35252, 35253), //
						m(new BitsWordElement(35254, this) //
								.bit(0, Deye.ChannelId.STATE_94) //
								.bit(1, Deye.ChannelId.STATE_95) //
								.bit(2, Deye.ChannelId.STATE_96) //
								.bit(3, Deye.ChannelId.STATE_97) //
								.bit(4, Deye.ChannelId.STATE_98) //
								.bit(5, Deye.ChannelId.STATE_99) //
								.bit(6, Deye.ChannelId.STATE_100) //
								.bit(7, Deye.ChannelId.STATE_101) //
								.bit(8, Deye.ChannelId.STATE_102) //
								.bit(9, Deye.ChannelId.STATE_103) //
								.bit(10, Deye.ChannelId.STATE_104) //
								.bit(11, Deye.ChannelId.STATE_105) //
								.bit(12, Deye.ChannelId.STATE_106) //
								.bit(13, Deye.ChannelId.STATE_107) //
								.bit(14, Deye.ChannelId.STATE_108) //
								.bit(15, Deye.ChannelId.STATE_109)), //
						m(new BitsWordElement(35255, this) //
								.bit(0, Deye.ChannelId.STATE_110) //
								.bit(1, Deye.ChannelId.STATE_111) //
								.bit(2, Deye.ChannelId.STATE_112) //
								.bit(3, Deye.ChannelId.STATE_113) //
								.bit(4, Deye.ChannelId.STATE_114) //
								.bit(5, Deye.ChannelId.STATE_115) //
								.bit(6, Deye.ChannelId.STATE_116) //
						), //
						new DummyRegisterElement(35256, 35257), //
						m(new BitsWordElement(35258, this) //
								.bit(0, Deye.ChannelId.STATE_117) //
								.bit(1, Deye.ChannelId.STATE_118) //
								.bit(2, Deye.ChannelId.STATE_119) //
								.bit(3, Deye.ChannelId.STATE_120) //
								.bit(4, Deye.ChannelId.STATE_121) //
								.bit(5, Deye.ChannelId.STATE_122) //
								.bit(6, Deye.ChannelId.STATE_123) //
								.bit(7, Deye.ChannelId.STATE_124) //
								.bit(8, Deye.ChannelId.STATE_125) //
								.bit(9, Deye.ChannelId.STATE_126) //
								.bit(10, Deye.ChannelId.STATE_127) //
								.bit(11, Deye.ChannelId.STATE_128) //
								.bit(12, Deye.ChannelId.STATE_129) //
								.bit(13, Deye.ChannelId.STATE_130) //
								.bit(14, Deye.ChannelId.STATE_131) //
								.bit(15, Deye.ChannelId.STATE_132)), //
						m(new BitsWordElement(35259, this) //
								.bit(0, Deye.ChannelId.STATE_133) //
								.bit(1, Deye.ChannelId.STATE_134) //
								.bit(2, Deye.ChannelId.STATE_135) //
								.bit(3, Deye.ChannelId.STATE_136) //
								.bit(4, Deye.ChannelId.STATE_137) //
						), //
						new DummyRegisterElement(35260, 35267), //
						m(Deye.ChannelId.MAX_GRID_FREQ_WITHIN_1_MINUTE, new UnsignedWordElement(35268),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.MIN_GRID_FREQ_WITHIN_1_MINUTE, new UnsignedWordElement(35269),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.MAX_GRID_VOLTAGE_WITHIN_1_MINUTE_R, new UnsignedWordElement(35270),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.MIN_GRID_VOLTAGE_WITHIN_1_MINUTE_R, new UnsignedWordElement(35271),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.MAX_GRID_VOLTAGE_WITHIN_1_MINUTE_S, new UnsignedWordElement(35272),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.MIN_GRID_VOLTAGE_WITHIN_1_MINUTE_S, new UnsignedWordElement(35273),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.MAX_GRID_VOLTAGE_WITHIN_1_MINUTE_T, new UnsignedWordElement(35274),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.MIN_GRID_VOLTAGE_WITHIN_1_MINUTE_T, new UnsignedWordElement(35275),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.MAX_BACKUP_POWER_WITHIN_1_MINUTE_R, new UnsignedDoublewordElement(35276)), //
						m(Deye.ChannelId.MAX_BACKUP_POWER_WITHIN_1_MINUTE_S, new UnsignedDoublewordElement(35278)), //
						m(Deye.ChannelId.MAX_BACKUP_POWER_WITHIN_1_MINUTE_T, new UnsignedDoublewordElement(35280)), //
						m(Deye.ChannelId.MAX_BACKUP_POWER_WITHIN_1_MINUTE_TOTAL,
								new UnsignedDoublewordElement(35282)), //
						m(Deye.ChannelId.GRID_HVRT_EVENT_TIMES, new UnsignedWordElement(35284)), //
						m(Deye.ChannelId.GRID_LVRT_EVENT_TIMES, new UnsignedWordElement(35285)), //
						m(Deye.ChannelId.INV_ERROR_MSG_RECORD_FOR_EMS, new UnsignedDoublewordElement(35286)), //
						m(Deye.ChannelId.INV_WARNING_CODE_RECORD_FOR_EMS, new UnsignedDoublewordElement(35288)), //
						m(Deye.ChannelId.INV_CPLD_WARNING_RECORD_FOR_EMS, new UnsignedDoublewordElement(35290)) //
				),

				// Registers 36066 to 36120 throw "Illegal Data Address"

				new FC3ReadRegistersTask(37000, Priority.LOW, //
						m(new BitsWordElement(37000, this) //
								.bit(0, Deye.ChannelId.DRM0)//
								.bit(1, Deye.ChannelId.DRM1)//
								.bit(2, Deye.ChannelId.DRM2)//
								.bit(3, Deye.ChannelId.DRM3)//
								.bit(4, Deye.ChannelId.DRM4)//
								.bit(5, Deye.ChannelId.DRM5)//
								.bit(6, Deye.ChannelId.DRM6)//
								.bit(7, Deye.ChannelId.DRM7)//
								.bit(8, Deye.ChannelId.DRM8)//
								.bit(15, Deye.ChannelId.DRED_CONNECT)//
						), //
						m(Deye.ChannelId.BATTERY_TYPE_INDEX, new UnsignedWordElement(37001)), //
						m(Deye.ChannelId.BMS_STATUS, new UnsignedWordElement(37002)), //
						m(Deye.ChannelId.BMS_PACK_TEMPERATURE, new UnsignedWordElement(37003), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.BMS_CHARGE_IMAX, new UnsignedWordElement(37004)), //
						m(Deye.ChannelId.BMS_DISCHARGE_IMAX, new UnsignedWordElement(37005)), //
						m(new BitsWordElement(37006, this) //
								.bit(0, Deye.ChannelId.STATE_42)//
								.bit(1, Deye.ChannelId.STATE_43)//
								.bit(2, Deye.ChannelId.STATE_44)//
								.bit(3, Deye.ChannelId.STATE_45)//
								.bit(4, Deye.ChannelId.STATE_46)//
								.bit(5, Deye.ChannelId.STATE_47)//
								.bit(6, Deye.ChannelId.STATE_48)//
								.bit(7, Deye.ChannelId.STATE_49)//
								.bit(8, Deye.ChannelId.STATE_50)//
								.bit(9, Deye.ChannelId.STATE_51)//
								.bit(10, Deye.ChannelId.STATE_52)//
								.bit(11, Deye.ChannelId.STATE_53)//
								.bit(12, Deye.ChannelId.STATE_54)//
								.bit(13, Deye.ChannelId.STATE_55)//
								.bit(14, Deye.ChannelId.STATE_56)//
								.bit(15, Deye.ChannelId.STATE_57)//
						), //
						this.getSocModbusElement(37007), //
						m(Deye.ChannelId.BMS_SOH, new UnsignedWordElement(37008)), //
						m(Deye.ChannelId.BMS_BATTERY_STRINGS, new UnsignedWordElement(37009)), //
						m(new BitsWordElement(37010, this) //
								.bit(0, Deye.ChannelId.STATE_58)//
								.bit(1, Deye.ChannelId.STATE_59)//
								.bit(2, Deye.ChannelId.STATE_60)//
								.bit(3, Deye.ChannelId.STATE_61)//
								.bit(4, Deye.ChannelId.STATE_62)//
								.bit(5, Deye.ChannelId.STATE_63)//
								.bit(6, Deye.ChannelId.STATE_64)//
								.bit(7, Deye.ChannelId.STATE_65)//
								.bit(8, Deye.ChannelId.STATE_66)//
								.bit(9, Deye.ChannelId.STATE_67)//
								.bit(10, Deye.ChannelId.STATE_68)//
								.bit(11, Deye.ChannelId.STATE_69)//
						), //
						m(Deye.ChannelId.BATTERY_PROTOCOL, new UnsignedWordElement(37011)), //
						// TODO BMS_ERROR_CODE_H register 37012 Table 8-7 BMS Alarm Code bits Bit16-31
						// are reserved
						// TODO Same for BMS_WARNING_CODE_H Table 8-8
						new DummyRegisterElement(37012, 37013), //
						m(Deye.ChannelId.BMS_SOFTWARE_VERSION, new UnsignedWordElement(37014)), //
						m(Deye.ChannelId.BATTERY_HARDWARE_VERSION, new UnsignedWordElement(37015)), //
						m(Deye.ChannelId.MAXIMUM_CELL_TEMPERATURE_ID, new UnsignedWordElement(37016)), //
						m(Deye.ChannelId.MINIMUM_CELL_TEMPERATURE_ID, new UnsignedWordElement(37017)), //
						m(Deye.ChannelId.MAXIMUM_CELL_VOLTAGE_ID, new UnsignedWordElement(37018)), //
						m(Deye.ChannelId.MINIMUM_CELL_VOLTAGE_ID, new UnsignedWordElement(37019)), //
						m(Deye.ChannelId.MAXIMUM_CELL_TEMPERATURE, new UnsignedWordElement(37020),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.MINIMUM_CELL_TEMPERATURE, new UnsignedWordElement(37021),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.MAXIMUM_CELL_VOLTAGE, new UnsignedWordElement(37022)), //
						m(Deye.ChannelId.MINIMUM_CELL_VOLTAGE, new UnsignedWordElement(37023)), //
						m(Deye.ChannelId.PASS_INFORMATION_1, new UnsignedWordElement(37024)), //
						m(Deye.ChannelId.PASS_INFORMATION_2, new UnsignedWordElement(37025)), //
						m(Deye.ChannelId.PASS_INFORMATION_3, new UnsignedWordElement(37026)), //
						m(Deye.ChannelId.PASS_INFORMATION_4, new UnsignedWordElement(37027)), //
						m(Deye.ChannelId.PASS_INFORMATION_5, new UnsignedWordElement(37028)), //
						m(Deye.ChannelId.PASS_INFORMATION_6, new UnsignedWordElement(37029)), //
						m(Deye.ChannelId.PASS_INFORMATION_7, new UnsignedWordElement(37030)), //
						m(Deye.ChannelId.PASS_INFORMATION_8, new UnsignedWordElement(37031)), //
						m(Deye.ChannelId.PASS_INFORMATION_9, new UnsignedWordElement(37032)), //
						m(Deye.ChannelId.PASS_INFORMATION_10, new UnsignedWordElement(37033)), //
						m(Deye.ChannelId.PASS_INFORMATION_11, new UnsignedWordElement(37034)), //
						m(Deye.ChannelId.PASS_INFORMATION_12, new UnsignedWordElement(37035)), //
						m(Deye.ChannelId.PASS_INFORMATION_13, new UnsignedWordElement(37036)), //
						m(Deye.ChannelId.PASS_INFORMATION_14, new UnsignedWordElement(37037)), //
						m(Deye.ChannelId.PASS_INFORMATION_15, new UnsignedWordElement(37038)), //
						m(Deye.ChannelId.PASS_INFORMATION_16, new UnsignedWordElement(37039)), //
						m(Deye.ChannelId.PASS_INFORMATION_17, new UnsignedWordElement(37040)), //
						m(Deye.ChannelId.PASS_INFORMATION_18, new UnsignedWordElement(37041)), //
						m(Deye.ChannelId.PASS_INFORMATION_19, new UnsignedWordElement(37042)), //
						m(Deye.ChannelId.PASS_INFORMATION_20, new UnsignedWordElement(37043)), //
						m(Deye.ChannelId.PASS_INFORMATION_21, new UnsignedWordElement(37044)), //
						m(Deye.ChannelId.PASS_INFORMATION_22, new UnsignedWordElement(37045)), //
						m(Deye.ChannelId.PASS_INFORMATION_23, new UnsignedWordElement(37046)), //
						m(Deye.ChannelId.PASS_INFORMATION_24, new UnsignedWordElement(37047)), //
						m(Deye.ChannelId.PASS_INFORMATION_25, new UnsignedWordElement(37048)), //
						m(Deye.ChannelId.PASS_INFORMATION_26, new UnsignedWordElement(37049)), //
						m(Deye.ChannelId.PASS_INFORMATION_27, new UnsignedWordElement(37050)), //
						m(Deye.ChannelId.PASS_INFORMATION_28, new UnsignedWordElement(37051)), //
						m(Deye.ChannelId.PASS_INFORMATION_29, new UnsignedWordElement(37052)), //
						m(Deye.ChannelId.PASS_INFORMATION_30, new UnsignedWordElement(37053)), //
						m(Deye.ChannelId.PASS_INFORMATION_31, new UnsignedWordElement(37054)), //
						m(Deye.ChannelId.PASS_INFORMATION_32, new UnsignedWordElement(37055))), //

				// Registers 40000 to 42011 for BTC and ETC throw "Illegal Data Address"

				// Setting and Controlling Data Registers
				new FC3ReadRegistersTask(45127, Priority.LOW, //
						m(Deye.ChannelId.INVERTER_UNIT_ID, new UnsignedWordElement(45127)), //
						new DummyRegisterElement(45128, 45131), //
						m(Deye.ChannelId.MODBUS_BAUDRATE, new UnsignedDoublewordElement(45132))), //

				new FC3ReadRegistersTask(45222, Priority.LOW, //
						// to read or write the accumulated energy battery discharged, of the day Not
						// from BMS
						m(Deye.ChannelId.PV_E_TOTAL_2, new UnsignedDoublewordElement(45222), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.PV_E_DAY_2, new UnsignedDoublewordElement(45224), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_TOTAL_SELL_2, new UnsignedDoublewordElement(45226), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.H_TOTAL_2, new UnsignedDoublewordElement(45228)), //
						m(Deye.ChannelId.E_DAY_SELL_2, new UnsignedWordElement(45230), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_TOTAL_BUY_2, new UnsignedDoublewordElement(45231), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_DAY_BUY_2, new UnsignedWordElement(45233), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_TOTAL_LOAD_2, new UnsignedDoublewordElement(45234), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_LOAD_DAY_2, new UnsignedWordElement(45236), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_BATTERY_CHARGE_2, new UnsignedDoublewordElement(45237),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_CHARGE_DAY_2, new UnsignedWordElement(45239), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_BATTERY_DISCHARGE_2, new UnsignedDoublewordElement(45240),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_DISCHARGE_DAY_2, new UnsignedWordElement(45242), SCALE_FACTOR_MINUS_1), //
						new DummyRegisterElement(45243), //
						// to set safety code for inverter or read the preset safety code for the
						// inverter
						m(Deye.ChannelId.SAFETY_COUNTRY_CODE, new UnsignedWordElement(45244)), //
						m(Deye.ChannelId.ISO_LIMIT, new UnsignedWordElement(45245)), //
						m(Deye.ChannelId.LVRT_HVRT, new UnsignedWordElement(45246))), //

				new FC3ReadRegistersTask(45250, Priority.LOW, //
						m(Deye.ChannelId.PV_START_VOLTAGE, new UnsignedWordElement(45250), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.MPPT_FOR_SHADOW_ENABLE, new UnsignedWordElement(45251)), //
						m(Deye.ChannelId.BACK_UP_ENABLE, new UnsignedWordElement(45252)), //
						m(Deye.ChannelId.AUTO_START_BACKUP, new UnsignedWordElement(45253)), //
						m(Deye.ChannelId.GRID_WAVE_CHECK_LEVEL, new UnsignedWordElement(45254)), //
						new DummyRegisterElement(45255), //
						m(Deye.ChannelId.BACKUP_START_DLY, new UnsignedWordElement(45256)), //
						m(Deye.ChannelId.UPS_STD_VOLT_TYPE, new UnsignedWordElement(45257)), //
						new DummyRegisterElement(45258, 45262), //
						// Only can set 70, only for German
						m(Deye.ChannelId.DERATE_RATE_VDE, new UnsignedWordElement(45263)), //
						// this function is deactivated as default, set "1" to activate. After
						// activated, All power needs to be turned off and restarted
						m(Deye.ChannelId.THREE_PHASE_UNBALANCED_OUTPUT, new UnsignedWordElement(45264)), //
						new DummyRegisterElement(45265), //
						// For weak grid area
						m(Deye.ChannelId.HIGH_IMP_MODE, new UnsignedWordElement(45266)), //
						new DummyRegisterElement(45267, 45274), //
						// 0:Normal mode 1: cancel ISO test when offgrid to ongrid
						m(Deye.ChannelId.ISO_CHECK_MODE, new UnsignedWordElement(45275)), //
						// The delay time when grid is available
						m(Deye.ChannelId.OFF_GRID_TO_ON_GRID_DELAY, new UnsignedWordElement(45276)), //
						// If set 80%, when offgrid output voltage less than 230*80%=184V, inverter will
						// have the error.
						m(Deye.ChannelId.OFF_GRID_UNDER_VOLTAGE_PROTECT_COEFFICIENT, new UnsignedWordElement(45277)), //
						// When offgrid and the battery SOC is low, PV charge the battery
						m(Deye.ChannelId.BATTERY_MODE_PV_CHARGE_ENABLE, new UnsignedWordElement(45278)), //
						// Default fisresttt.ing is 1
						m(Deye.ChannelId.DCV_CHECK_OFF, new UnsignedWordElement(45279))//

				), //

				// Registers 45333 to 45339 for License throw "Illegal Data Address"

				new FC3ReadRegistersTask(45352, Priority.LOW, //
						m(Deye.ChannelId.BMS_CHARGE_MAX_VOLTAGE, new UnsignedWordElement(45352),
								SCALE_FACTOR_MINUS_1), // [500*N,600*N]
						m(Deye.ChannelId.BMS_CHARGE_MAX_CURRENT, new UnsignedWordElement(45353),
								SCALE_FACTOR_MINUS_1), // [0,1000]
						m(Deye.ChannelId.BMS_DISCHARGE_MIN_VOLTAGE, new UnsignedWordElement(45354),
								SCALE_FACTOR_MINUS_1), // [400*N,480*N]
						m(Deye.ChannelId.BMS_DISCHARGE_MAX_CURRENT, new UnsignedWordElement(45355),
								SCALE_FACTOR_MINUS_1), // [0,1000]
						m(Deye.ChannelId.BMS_SOC_UNDER_MIN, new UnsignedWordElement(45356)), // [0,100]
						m(Deye.ChannelId.BMS_OFFLINE_DISCHARGE_MIN_VOLTAGE, new UnsignedWordElement(45357),
								SCALE_FACTOR_MINUS_1), // ), //
						m(Deye.ChannelId.BMS_OFFLINE_SOC_UNDER_MIN, new UnsignedWordElement(45358))), //

				// Safety
				new FC3ReadRegistersTask(45400, Priority.LOW, //
						m(Deye.ChannelId.GRID_VOLT_HIGH_S1, new UnsignedWordElement(45400), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_HIGH_S1_TIME, new UnsignedWordElement(45401)), //
						m(Deye.ChannelId.GRID_VOLT_LOW_S1, new UnsignedWordElement(45402), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_LOW_S1_TIME, new UnsignedWordElement(45403)), //
						m(Deye.ChannelId.GRID_VOLT_HIGH_S2, new UnsignedWordElement(45404), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_HIGH_S2_TIME, new UnsignedWordElement(45405)), //
						m(Deye.ChannelId.GRID_VOLT_LOW_S2, new UnsignedWordElement(45406), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_LOW_S2_TIME, new UnsignedWordElement(45407)), //
						m(Deye.ChannelId.GRID_VOLT_QUALITY, new UnsignedWordElement(45408), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_FREQ_HIGH_S1, new UnsignedWordElement(45409), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_FREQ_HIGH_S1_TIME, new UnsignedWordElement(45410)), //
						m(Deye.ChannelId.GRID_FREQ_LOW_S1, new UnsignedWordElement(45411), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_FREQ_LOW_S1_TIME, new UnsignedWordElement(45412)), //
						m(Deye.ChannelId.GRID_FREQ_HIGH_S2, new UnsignedWordElement(45413), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_FREQ_HIGH_S2_TIME, new UnsignedWordElement(45414)), //
						m(Deye.ChannelId.GRID_FREQ_LOW_S2, new UnsignedWordElement(45415), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_FREQ_LOW_S2_TIME, new UnsignedWordElement(45416)), //
						m(Deye.ChannelId.GRID_VOLT_HIGH, new UnsignedWordElement(45417), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_LOW, new UnsignedWordElement(45418), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_FREQ_HIGH, new UnsignedWordElement(45419), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_FREQ_LOW, new UnsignedWordElement(45420), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_RECOVER_TIME, new UnsignedWordElement(45421)), //
						m(Deye.ChannelId.GRID_VOLT_RECOVER_HIGH, new UnsignedWordElement(45422),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_RECOVER_LOW, new UnsignedWordElement(45423), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_FREQ_RECOVER_HIGH, new UnsignedWordElement(45424),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_FREQ_RECOVER_LOW, new UnsignedWordElement(45425), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_VOLT_RECOVER_TIME, new UnsignedWordElement(45426)), //
						m(Deye.ChannelId.GRID_FREQ_RECOVER_TIME, new UnsignedWordElement(45427)), //
						m(Deye.ChannelId.POWER_RATE_LIMIT_GENERATE, new UnsignedWordElement(45428)), //
						m(Deye.ChannelId.POWER_RATE_LIMIT_RECONNECT, new UnsignedWordElement(45429)), //
						m(Deye.ChannelId.POWER_RATE_LIMIT_REDUCTION, new UnsignedWordElement(45430)), //
						m(Deye.ChannelId.GRID_PROTECT, new UnsignedWordElement(45431)) //
				), //

				new FC3ReadRegistersTask(45428, Priority.LOW, //
						m(Deye.ChannelId.POWER_RATE_LIMIT_GENERATE, new UnsignedWordElement(45428),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.POWER_RATE_LIMIT_RECONNECT, new UnsignedWordElement(45429),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.POWER_RATE_LIMIT_REDUCTION, new UnsignedWordElement(45430),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_PROTECT, new UnsignedWordElement(45431))), //

				// Cos Phi Curve
				new FC3ReadRegistersTask(45432, Priority.LOW, //
						m(Deye.ChannelId.POWER_SLOPE_ENABLE, new UnsignedWordElement(45432)), //
						m(Deye.ChannelId.ENABLE_CURVE_PU, new UnsignedWordElement(45433)), //
						m(Deye.ChannelId.A_POINT_POWER, new SignedWordElement(45434)), //
						m(Deye.ChannelId.A_POINT_COS_PHI, new SignedWordElement(45435), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.B_POINT_POWER, new SignedWordElement(45436)), //
						m(Deye.ChannelId.B_POINT_COS_PHI, new SignedWordElement(45437), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.C_POINT_POWER, new SignedWordElement(45438)), //
						m(Deye.ChannelId.C_POINT_COS_PHI, new SignedWordElement(45439)),
						m(Deye.ChannelId.LOCK_IN_VOLTAGE, new UnsignedWordElement(45440), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.LOCK_OUT_VOLTAGE, new UnsignedWordElement(45441), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.LOCK_OUT_POWER, new SignedWordElement(45442)), //

						// Power and frequency curve
						m(new BitsWordElement(45443, this)//
								.bit(0, Deye.ChannelId.POWER_FREQUENCY_ENABLED)//
								.bit(1, Deye.ChannelId.POWER_FREQUENCY_RESPONSE_MODE)//
						), //
						m(Deye.ChannelId.FFROZEN_DCH, new UnsignedWordElement(45444), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.FFROZEN_CH, new UnsignedWordElement(45445), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.FSTOP_DCH, new UnsignedWordElement(45446), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.FSTOP_CH, new UnsignedWordElement(45447), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.OF_RECOVERY_WAITING_TIME, new UnsignedWordElement(45448),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.RECOVERY_FREQURNCY1, new UnsignedWordElement(45449), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.RECOVERY_FREQUENCY2, new UnsignedWordElement(45450), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.OF_RECOVERY_SLOPE, new UnsignedWordElement(45451), //
								new ChannelMetaInfoReadAndWrite(45451, 45452)), //
						m(Deye.ChannelId.CFP_SETTINGS, new UnsignedWordElement(45452), //
								new ChannelMetaInfoReadAndWrite(45452, 45451)), //
						m(Deye.ChannelId.CFP_OF_SLOPE_PERCENT, new UnsignedWordElement(45453), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.CFP_UF_SLOPE_PERCENT, new UnsignedWordElement(45454), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.CFP_OF_RECOVER_POWER_PERCENT, new UnsignedWordElement(45455))), //

				// QU Curve
				new FC3ReadRegistersTask(45456, Priority.LOW, //
						m(Deye.ChannelId.QU_CURVE, new UnsignedWordElement(45456)), //
						m(Deye.ChannelId.LOCK_IN_POWER_QU, new SignedWordElement(45457)), //
						m(Deye.ChannelId.LOCK_OUT_POWER_QU, new SignedWordElement(45458)), //
						m(Deye.ChannelId.V1_VOLTAGE, new UnsignedWordElement(45459), SCALE_FACTOR_MINUS_1), // ), //
						m(Deye.ChannelId.V1_VALUE, new SignedWordElement(45460)), //
						m(Deye.ChannelId.V2_VOLTAGE, new UnsignedWordElement(45461), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V2_VALUE, new SignedWordElement(45462)), //
						m(Deye.ChannelId.V3_VOLTAGE, new UnsignedWordElement(45463), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V3_VALUE, new SignedWordElement(45464)), //
						m(Deye.ChannelId.V4_VOLTAGE, new UnsignedWordElement(45465), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V4_VALUE, new SignedWordElement(45466)), //
						m(Deye.ChannelId.K_VALUE, new UnsignedWordElement(45467)), //
						m(Deye.ChannelId.TIME_CONSTANT, new UnsignedWordElement(45468)), //
						m(Deye.ChannelId.MISCELLANEA, new UnsignedWordElement(45469))), //

				// PU Curve
				new FC3ReadRegistersTask(45472, Priority.LOW, //
						m(Deye.ChannelId.PU_CURVE, new UnsignedWordElement(45472)), //
						m(Deye.ChannelId.POWER_CHANGE_RATE, new UnsignedWordElement(45473)), //
						m(Deye.ChannelId.V1_VOLTAGE_PU, new UnsignedWordElement(45474), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V1_VALUE_PU, new SignedWordElement(45475), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V2_VOLTAGE_PU, new UnsignedWordElement(45476), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V2_VALUE_PU, new SignedWordElement(45477), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V3_VOLTAGE_PU, new UnsignedWordElement(45478), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V3_VALUE_PU, new SignedWordElement(45479), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V4_VOLTAGE_PU, new UnsignedWordElement(45480), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V4_VALUE_PU, new SignedWordElement(45481), SCALE_FACTOR_MINUS_1), //
						// 80=Pf 0.8, 20= -0.8Pf
						m(Deye.ChannelId.FIXED_POWER_FACTOR, new UnsignedWordElement(45482), SCALE_FACTOR_MINUS_2), //
						// Set the percentage of rated power of the inverter
						m(Deye.ChannelId.FIXED_REACTIVE_POWER, new SignedWordElement(45483), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.FIXED_ACTIVE_POWER, new UnsignedWordElement(45484), SCALE_FACTOR_MINUS_1)), //

				new FC3ReadRegistersTask(45488, Priority.LOW, //
						m(Deye.ChannelId.AUTO_TEST_ENABLE, new UnsignedWordElement(45488)), //
						m(Deye.ChannelId.AUTO_TEST_STEP, new UnsignedWordElement(45489)), //
						m(Deye.ChannelId.UW_ITALY_FREQ_MODE, new UnsignedWordElement(45490)), //
						// this must be turned off to do Meter test . "1" means Off
						m(Deye.ChannelId.ALL_POWER_CURVE_DISABLE, new UnsignedWordElement(45491)), //
						m(Deye.ChannelId.R_PHASE_FIXED_ACTIVE_POWER, new UnsignedWordElement(45492)), //
						m(Deye.ChannelId.S_PHASE_FIXED_ACTIVE_POWER, new UnsignedWordElement(45493)), //
						m(Deye.ChannelId.T_PHASE_FIXED_ACTIVE_POWER, new UnsignedWordElement(45494)), //
						m(Deye.ChannelId.GRID_VOLT_HIGH_S3, new UnsignedWordElement(45495), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_HIGH_S3_TIME, new UnsignedWordElement(45496)), //
						m(Deye.ChannelId.GRID_VOLT_LOW_S3, new UnsignedWordElement(45497), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_LOW_S3_TIME, new UnsignedWordElement(45498)), //
						m(Deye.ChannelId.ZVRT_CONFIG, new UnsignedWordElement(45499)), //
						m(Deye.ChannelId.LVRT_START_VOLT, new UnsignedWordElement(45500), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.LVRT_END_VOLT, new UnsignedWordElement(45501), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.LVRT_START_TRIP_TIME, new UnsignedWordElement(45502)), //
						m(Deye.ChannelId.LVRT_END_TRIP_TIME, new UnsignedWordElement(45503)), //
						m(Deye.ChannelId.LVRT_TRIP_LIMIT_VOLT, new UnsignedWordElement(45504), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.HVRT_START_VOLT, new UnsignedWordElement(45505), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.HVRT_END_VOLT, new UnsignedWordElement(45506), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.HVRT_START_TRIP_TIME, new UnsignedWordElement(45507)), //
						m(Deye.ChannelId.HVRT_END_TRIP_TIME, new UnsignedWordElement(45508)), //
						m(Deye.ChannelId.HVRT_TRIP_LIMIT_VOLT, new UnsignedWordElement(45509), SCALE_FACTOR_MINUS_1)//
				), //

				// Additional settings for PF/PU/UF
				new FC3ReadRegistersTask(45510, Priority.LOW, //
						m(Deye.ChannelId.PF_TIME_CONSTANT, new UnsignedWordElement(45510)), //
						m(Deye.ChannelId.POWER_FREQ_TIME_CONSTANT, new UnsignedWordElement(45511)), //
						// Additional settings for P(U) Curve
						m(Deye.ChannelId.PU_TIME_CONSTANT, new UnsignedWordElement(45512)), //
						m(Deye.ChannelId.D_POINT_POWER, new SignedWordElement(45513)), //
						m(Deye.ChannelId.D_POINT_COS_PHI, new SignedWordElement(45514)), //
						// Additional settings for UF Curve
						m(Deye.ChannelId.UF_RECOVERY_WAITING_TIME, new UnsignedWordElement(45515),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.UF_RECOVER_SLOPE, new UnsignedWordElement(45516)), //
						m(Deye.ChannelId.CFP_UF_RECOVER_POWER_PERCENT, new UnsignedWordElement(45517)), //
						m(Deye.ChannelId.POWER_CHARGE_LIMIT, new UnsignedWordElement(45518), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.POWER_CHARGE_LIMIT_RECONNECT, new UnsignedWordElement(45519),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.C_EXT_UF_CHARGE_STOP, new UnsignedWordElement(45520), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.C_EXT_OF_DISCHARGE_STOP, new UnsignedWordElement(45521),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.C_EXT_TWOSSTEPF_FLG, new UnsignedWordElement(45522))//
				), //

				new FC3ReadRegistersTask(47500, Priority.LOW, //
						m(Deye.ChannelId.STOP_SOC_PROTECT, new UnsignedWordElement(47500)), //
						m(Deye.ChannelId.BMS_FLOAT_VOLT, new UnsignedWordElement(47501), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.BMS_FLOAT_CURRENT, new UnsignedWordElement(47502)), //
						m(Deye.ChannelId.BMS_FLOAT_TIME, new UnsignedWordElement(47503)), //
						m(Deye.ChannelId.BMS_TYPE_INDEX_ARM, new UnsignedWordElement(47504)), //
						m(Deye.ChannelId.MANUFACTURE_CODE, new UnsignedWordElement(47505)), //
						m(Deye.ChannelId.DC_VOLT_OUTPUT, new UnsignedWordElement(47506)), //
						m(Deye.ChannelId.BMS_AVG_CHG_VOLT, new UnsignedWordElement(47507), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.BMS_AVG_CHG_HOURS, new UnsignedWordElement(47508)), //
						m(Deye.ChannelId.FEED_POWER_ENABLE, new UnsignedWordElement(47509)), //
						m(Deye.ChannelId.FEED_POWER_PARA_SET, new UnsignedWordElement(47510)), //
						m(Deye.ChannelId.EMS_POWER_MODE, new UnsignedWordElement(47511)), //
						m(Deye.ChannelId.EMS_POWER_SET, new UnsignedWordElement(47512)), //
						m(Deye.ChannelId.BMS_CURR_LMT_COFF, new UnsignedWordElement(47513)), //
						m(Deye.ChannelId.BATTERY_PROTOCOL_ARM, new UnsignedWordElement(47514)), //

						m(Deye.ChannelId.WORK_WEEK_1_START_TIME, new UnsignedWordElement(47515)), //
						m(Deye.ChannelId.WORK_WEEK_1_END_TIME, new UnsignedWordElement(47516)), //
						m(Deye.ChannelId.WORK_WEEK_1_BAT_POWER_PERCENT, new UnsignedWordElement(47517)), //
						m(new BitsWordElement(47518, this)//
								.bit(0, Deye.ChannelId.WORK_WEEK_1_SUNDAY)//
								.bit(1, Deye.ChannelId.WORK_WEEK_1_MONDAY)//
								.bit(2, Deye.ChannelId.WORK_WEEK_1_TUESDAY)//
								.bit(3, Deye.ChannelId.WORK_WEEK_1_WEDNESDAY)//
								.bit(4, Deye.ChannelId.WORK_WEEK_1_THURSDAY)//
								.bit(5, Deye.ChannelId.WORK_WEEK_1_FRIDAY)//
								.bit(6, Deye.ChannelId.WORK_WEEK_1_SATURDAY)//
								.bit(7, Deye.ChannelId.WORK_WEEK_1_NA)//
								.bit(8, Deye.ChannelId.WORK_WEEK_1_ENABLED)//
						)), //

				new FC16WriteRegistersTask(45216, //
						// Choose "Warehouse" safety code first and then Set "1" to factory settings
						m(Deye.ChannelId.FACTORY_SETTING, new UnsignedWordElement(45216)), //
						// Reset inverter accumulated data like E-total, E-day, error log running data
						// etc
						m(Deye.ChannelId.CLEAR_DATA, new UnsignedWordElement(45217)), //
						new DummyRegisterElement(45218, 45219), //
						// Inverter will re-check and reconnect to utility again. Inverter does not
						// shutdown
						m(Deye.ChannelId.RESTART, new UnsignedWordElement(45220)), //
						// inverter will total shutdown and wake up again
						m(Deye.ChannelId.RESET_SPS, new UnsignedWordElement(45221)), //
						m(Deye.ChannelId.PV_E_TOTAL_2, new UnsignedDoublewordElement(45222), SCALE_FACTOR_MINUS_1), //
						// to read or write the total PV production energy of the day
						m(Deye.ChannelId.PV_E_DAY_2, new UnsignedDoublewordElement(45224), SCALE_FACTOR_MINUS_1), //
						// to read or write the accumulated exporting energy to grid from the
						// installation date
						m(Deye.ChannelId.E_TOTAL_SELL_2, new UnsignedDoublewordElement(45226), SCALE_FACTOR_MINUS_1), //
						// to read or write the accumulated operation hours from the installation date
						m(Deye.ChannelId.H_TOTAL_2, new UnsignedDoublewordElement(45228)), //
						// to read or write the accumulated exporting energy to grid of the day
						m(Deye.ChannelId.E_DAY_SELL_2, new UnsignedWordElement(45230), SCALE_FACTOR_MINUS_1), //
						// to read or write the accumulated energy imported from grid from the
						// installation date
						m(Deye.ChannelId.E_TOTAL_BUY_2, new UnsignedDoublewordElement(45231), SCALE_FACTOR_MINUS_1), //
						// to read or write the accumulated energy imported from grid of the day
						m(Deye.ChannelId.E_DAY_BUY_2, new UnsignedWordElement(45233), SCALE_FACTOR_MINUS_1), //
						// to read or write the accumulated load consumption energy from the
						// installation date, not include backup load.
						m(Deye.ChannelId.E_TOTAL_LOAD_2, new UnsignedDoublewordElement(45234), SCALE_FACTOR_MINUS_1), //
						// to read or write the accumulated load consumption energy of the day Not
						// include backup loads
						m(Deye.ChannelId.E_LOAD_DAY_2, new UnsignedWordElement(45236), SCALE_FACTOR_MINUS_1), //
						// to read or write the accumulated energy charged to battery from the
						// installation date Not from BMS
						m(Deye.ChannelId.E_BATTERY_CHARGE_2, new UnsignedDoublewordElement(45237),
								SCALE_FACTOR_MINUS_1), //
						// to read or write the accumulated energy charged to battery of the day Not
						// from BMS
						m(Deye.ChannelId.E_CHARGE_DAY_2, new UnsignedWordElement(45239), SCALE_FACTOR_MINUS_1), //
						// to read or write the accumulated energy battery discharged, from the
						// installation date Not from BMS
						m(Deye.ChannelId.E_BATTERY_DISCHARGE_2, new UnsignedDoublewordElement(45240),
								SCALE_FACTOR_MINUS_1), //
						// to read or write the accumulated energy battery discharged, of the day Not
						// from BMS
						m(Deye.ChannelId.E_DISCHARGE_DAY_2, new UnsignedWordElement(45242), SCALE_FACTOR_MINUS_1), //
						new DummyRegisterElement(45243), //
						// to set safety code for inverter or read the preset safety code for the
						// inverter
						m(Deye.ChannelId.SAFETY_COUNTRY_CODE, new UnsignedWordElement(45244)), //
						// default 100 kilo Ohm, to read or set Isolation protection threshold for the
						// inverter
						m(Deye.ChannelId.ISO_LIMIT, new UnsignedWordElement(45245)), //
						// as default is deactivated, set "1" to activate LVRT function, Set "2" to
						// activate HVRT, The same as 45499
						m(Deye.ChannelId.LVRT_HVRT, new UnsignedWordElement(45246))), //
				new FC16WriteRegistersTask(45250, //
						// to write or read the start up PV voltage of the inverter.Please refer to the
						// user manual
						m(Deye.ChannelId.PV_START_VOLTAGE, new UnsignedWordElement(45250), SCALE_FACTOR_MINUS_1), //
						// as default is deactivated, set "1" to activate "Shadow Scan" function
						m(Deye.ChannelId.MPPT_FOR_SHADOW_ENABLE, new UnsignedWordElement(45251)), //
						// as default is deactivated, set "1" to activate "Shadow Scan" function
						m(Deye.ChannelId.BACK_UP_ENABLE, new UnsignedWordElement(45252)), //
						// Off-Grid Auto startup, as default is deactivated, set "1" to activate "Shadow
						// Scan" function
						m(Deye.ChannelId.AUTO_START_BACKUP, new UnsignedWordElement(45253)), //
						// As default is "0"
						m(Deye.ChannelId.GRID_WAVE_CHECK_LEVEL, new UnsignedWordElement(45254)), //
						new DummyRegisterElement(45255), //
						// Default is 1500 (30s)
						m(Deye.ChannelId.BACKUP_START_DLY, new UnsignedWordElement(45256)), //
						m(Deye.ChannelId.UPS_STD_VOLT_TYPE, new UnsignedWordElement(45257)), //
						new DummyRegisterElement(45258, 45262), //
						// Only can set 70, only for German
						m(Deye.ChannelId.DERATE_RATE_VDE, new UnsignedWordElement(45263)), //
						// This function is deactivated as default, set "1" to activate. After
						// activated, All power needs to be turned off and restarted
						m(Deye.ChannelId.THREE_PHASE_UNBALANCED_OUTPUT, new UnsignedWordElement(45264)), //
						new DummyRegisterElement(45265), //
						// For weak grid area
						m(Deye.ChannelId.HIGH_IMP_MODE, new UnsignedWordElement(45266)), //
						new DummyRegisterElement(45267, 45270), //
						// only for inverters with AFCI function
						m(Deye.ChannelId.ARC_SELF_CHECK, new UnsignedWordElement(45271)), //
						// only for inverters with AFCI function
						m(Deye.ChannelId.ARC_FAULT_REMOVE, new UnsignedWordElement(45272)), //
						new DummyRegisterElement(45273, 45274), //
						// 0:Normal mode 1: cancel ISO test when offgrid to ongrid
						m(Deye.ChannelId.ISO_CHECK_MODE, new UnsignedWordElement(45275)), //
						// The delay time when grid is available
						m(Deye.ChannelId.OFF_GRID_TO_ON_GRID_DELAY, new UnsignedWordElement(45276)), //
						// If set 80%, when offgrid output voltage less than 230*80%=184V, inverter will
						// have the error.Default setting is
						m(Deye.ChannelId.OFF_GRID_UNDER_VOLTAGE_PROTECT_COEFFICIENT, new UnsignedWordElement(45277)), //
						// When offgrid and the battery SOC is low, PV charge the battery
						m(Deye.ChannelId.BATTERY_MODE_PV_CHARGE_ENABLE, new UnsignedWordElement(45278)), //
						// Default setting 1, [1,20]
						m(Deye.ChannelId.DCV_CHECK_OFF, new UnsignedWordElement(45279))), //

				// These registers is to set the protection parameters on battery
				// charge/discharge operation ON INVERTER SIDE. The real
				// operation will still follow battery BMS limitations (or registers
				// 47900~47916) if it is not out of the range.Eg. Set BattChargeCurrMax (45353)
				// as 25A, but battery BMS limit the max charge current as 20A, then the battery
				// charge at max 20A. but if battery BMS limit max charge current as 50A,then
				// the real charge current of the battery will exceed 25A.
				new FC16WriteRegistersTask(45352, //
						m(Deye.ChannelId.BMS_CHARGE_MAX_VOLTAGE, new UnsignedWordElement(45352),
								SCALE_FACTOR_MINUS_1), // [500*N,600*N]
						m(Deye.ChannelId.BMS_CHARGE_MAX_CURRENT, new UnsignedWordElement(45353),
								SCALE_FACTOR_MINUS_1), // [0,1000]
						m(Deye.ChannelId.BMS_DISCHARGE_MIN_VOLTAGE, new UnsignedWordElement(45354),
								SCALE_FACTOR_MINUS_1), // [400*N,480*N]
						m(Deye.ChannelId.BMS_DISCHARGE_MAX_CURRENT, new UnsignedWordElement(45355),
								SCALE_FACTOR_MINUS_1), // [0,1000]
						m(Deye.ChannelId.BMS_SOC_UNDER_MIN, new UnsignedWordElement(45356)), // [0,100]
						m(Deye.ChannelId.BMS_OFFLINE_DISCHARGE_MIN_VOLTAGE, new UnsignedWordElement(45357),
								SCALE_FACTOR_MINUS_1), // ), //
						m(Deye.ChannelId.BMS_OFFLINE_SOC_UNDER_MIN, new UnsignedWordElement(45358))), //

				// Safety Parameters
				new FC16WriteRegistersTask(45400, //
						m(Deye.ChannelId.GRID_VOLT_HIGH_S1, new UnsignedWordElement(45400), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_HIGH_S1_TIME, new UnsignedWordElement(45401)), //
						m(Deye.ChannelId.GRID_VOLT_LOW_S1, new UnsignedWordElement(45402), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_LOW_S1_TIME, new UnsignedWordElement(45403)), //
						m(Deye.ChannelId.GRID_VOLT_HIGH_S2, new UnsignedWordElement(45404), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_HIGH_S2_TIME, new UnsignedWordElement(45405)), //
						m(Deye.ChannelId.GRID_VOLT_LOW_S2, new UnsignedWordElement(45406), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_LOW_S2_TIME, new UnsignedWordElement(45407)), //
						m(Deye.ChannelId.GRID_VOLT_QUALITY, new UnsignedWordElement(45408), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_FREQ_HIGH_S1, new UnsignedWordElement(45409), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_FREQ_HIGH_S1_TIME, new UnsignedWordElement(45410)), //
						m(Deye.ChannelId.GRID_FREQ_LOW_S1, new UnsignedWordElement(45411), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_FREQ_LOW_S1_TIME, new UnsignedWordElement(45412)), //
						m(Deye.ChannelId.GRID_FREQ_HIGH_S2, new UnsignedWordElement(45413), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_FREQ_HIGH_S2_TIME, new UnsignedWordElement(45414)), //
						m(Deye.ChannelId.GRID_FREQ_LOW_S2, new UnsignedWordElement(45415), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_FREQ_LOW_S2_TIME, new UnsignedWordElement(45416)), //
						// Connect voltage
						m(Deye.ChannelId.GRID_VOLT_HIGH, new UnsignedWordElement(45417), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_LOW, new UnsignedWordElement(45418), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_FREQ_HIGH, new UnsignedWordElement(45419), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_FREQ_LOW, new UnsignedWordElement(45420), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_RECOVER_TIME, new UnsignedWordElement(45421)), //
						// Reconnect voltage
						m(Deye.ChannelId.GRID_VOLT_RECOVER_HIGH, new UnsignedWordElement(45422),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_RECOVER_LOW, new UnsignedWordElement(45423), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_FREQ_RECOVER_HIGH, new UnsignedWordElement(45424),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_FREQ_RECOVER_LOW, new UnsignedWordElement(45425), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_VOLT_RECOVER_TIME, new UnsignedWordElement(45426)), //
						m(Deye.ChannelId.GRID_FREQ_RECOVER_TIME, new UnsignedWordElement(45427)), //
						// Power rate limit
						m(Deye.ChannelId.POWER_RATE_LIMIT_GENERATE, new UnsignedWordElement(45428),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.POWER_RATE_LIMIT_RECONNECT, new UnsignedWordElement(45429),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.POWER_RATE_LIMIT_REDUCTION, new UnsignedWordElement(45430),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.GRID_PROTECT, new UnsignedWordElement(45431)), //
						m(Deye.ChannelId.POWER_SLOPE_ENABLE, new UnsignedWordElement(45432))), //

				// Cos Phi Curve
				new FC16WriteRegistersTask(45433, //
						m(Deye.ChannelId.ENABLE_CURVE_PU, new UnsignedWordElement(45433)), //
						m(Deye.ChannelId.A_POINT_POWER, new SignedWordElement(45434)), //
						m(Deye.ChannelId.A_POINT_COS_PHI, new SignedWordElement(45435), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.B_POINT_POWER, new SignedWordElement(45436)), //
						m(Deye.ChannelId.B_POINT_COS_PHI, new SignedWordElement(45437), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.C_POINT_POWER, new SignedWordElement(45438)), //
						m(Deye.ChannelId.C_POINT_COS_PHI, new SignedWordElement(45439), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.LOCK_IN_VOLTAGE, new UnsignedWordElement(45440), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.LOCK_OUT_VOLTAGE, new UnsignedWordElement(45441), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.LOCK_OUT_POWER, new SignedWordElement(45442))), //

				// Power and frequency curve
				// m(new BitsWordElement(45443, this)//
				// .bit(0, Deye.ChannelId.STATE_70)//
				// .bit(1, Deye.ChannelId.STATE_71)), //

				// Power and frequency curve
				new FC16WriteRegistersTask(45444, //
						m(Deye.ChannelId.FFROZEN_DCH, new UnsignedWordElement(45444), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.FFROZEN_CH, new UnsignedWordElement(45445), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.FSTOP_DCH, new UnsignedWordElement(45446), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.FSTOP_CH, new UnsignedWordElement(45447), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.RECOVERY_WAITING_TIME, new UnsignedWordElement(45448)), //
						m(Deye.ChannelId.RECOVERY_FREQURNCY1, new UnsignedWordElement(45449), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.RECOVERY_FREQUENCY2, new UnsignedWordElement(45450), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.CFP_SETTINGS, new UnsignedWordElement(45451), //
								new ChannelMetaInfoReadAndWrite(45452, 45451)), //
						m(Deye.ChannelId.OF_RECOVERY_SLOPE, new UnsignedWordElement(45452), //
								new ChannelMetaInfoReadAndWrite(45451, 45452)), //
						m(Deye.ChannelId.CFP_OF_SLOPE_PERCENT, new UnsignedWordElement(45453), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.CFP_UF_SLOPE_PERCENT, new UnsignedWordElement(45454), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.CFP_OF_RECOVER_POWER_PERCENT, new UnsignedWordElement(45455)), //

						// QU Curve
						m(Deye.ChannelId.QU_CURVE, new UnsignedWordElement(45456)), //
						m(Deye.ChannelId.LOCK_IN_POWER_QU, new SignedWordElement(45457)), //
						m(Deye.ChannelId.LOCK_OUT_POWER_QU, new SignedWordElement(45458)), //
						m(Deye.ChannelId.V1_VOLTAGE, new UnsignedWordElement(45459), SCALE_FACTOR_MINUS_1), // ), //
						m(Deye.ChannelId.V1_VALUE, new UnsignedWordElement(45460)), //
						m(Deye.ChannelId.V2_VOLTAGE, new UnsignedWordElement(45461), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V2_VALUE, new UnsignedWordElement(45462)), //
						m(Deye.ChannelId.V3_VOLTAGE, new UnsignedWordElement(45463), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V3_VALUE, new UnsignedWordElement(45464)), //
						m(Deye.ChannelId.V4_VOLTAGE, new UnsignedWordElement(45465), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V4_VALUE, new SignedWordElement(45466)), //
						m(Deye.ChannelId.K_VALUE, new UnsignedWordElement(45467)), //
						m(Deye.ChannelId.TIME_CONSTANT, new UnsignedWordElement(45468)), //
						m(Deye.ChannelId.MISCELLANEA, new UnsignedWordElement(45469))), //

				// PU Curve
				new FC16WriteRegistersTask(45472, //
						m(Deye.ChannelId.PU_CURVE, new UnsignedWordElement(45472)), //
						m(Deye.ChannelId.POWER_CHANGE_RATE, new UnsignedWordElement(45473), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.V1_VOLTAGE_PU, new UnsignedWordElement(45474), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V1_VALUE_PU, new SignedWordElement(45475)), //
						m(Deye.ChannelId.V2_VOLTAGE_PU, new UnsignedWordElement(45476), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V2_VALUE_PU, new SignedWordElement(45477)), //
						m(Deye.ChannelId.V3_VOLTAGE_PU, new UnsignedWordElement(45478), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V3_VALUE_PU, new SignedWordElement(45479)), //
						m(Deye.ChannelId.V4_VOLTAGE_PU, new UnsignedWordElement(45480), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.V4_VALUE_PU, new SignedWordElement(45481)), //
						// 80=Pf 0.8, 20= -0.8Pf
						m(Deye.ChannelId.FIXED_POWER_FACTOR, new UnsignedWordElement(45482)), // [0,20]||[80,100]
						// Set the percentage of rated power of the inverter
						m(Deye.ChannelId.FIXED_REACTIVE_POWER, new SignedWordElement(45483)), // [-600,600]
						m(Deye.ChannelId.FIXED_ACTIVE_POWER, new UnsignedWordElement(45484)), // [0,1000]
						new DummyRegisterElement(45485, 45490), //
						// This must be turned off to do Meter test . "1" means Off
						m(Deye.ChannelId.ALL_POWER_CURVE_DISABLE, new UnsignedWordElement(45491)), //
						// if it is 1-phase inverter, then use only R phase. Unbalance output function
						// must be turned on to set different values for R/S/T phases
						m(Deye.ChannelId.R_PHASE_FIXED_ACTIVE_POWER, new UnsignedWordElement(45492)), //
						m(Deye.ChannelId.S_PHASE_FIXED_ACTIVE_POWER, new UnsignedWordElement(45493)), //
						m(Deye.ChannelId.T_PHASE_FIXED_ACTIVE_POWER, new UnsignedWordElement(45494)), //
						// only for countries where it needs 3-stage grid voltage
						// protection, Eg. Czech Republic
						m(Deye.ChannelId.GRID_VOLT_HIGH_S3, new UnsignedWordElement(45495), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_HIGH_S3_TIME, new UnsignedWordElement(45496)), //
						m(Deye.ChannelId.GRID_VOLT_LOW_S3, new UnsignedWordElement(45497), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.GRID_VOLT_LOW_S3_TIME, new UnsignedWordElement(45498)), //

						// For ZVRT, LVRT, HVRT
						m(Deye.ChannelId.ZVRT_CONFIG, new UnsignedWordElement(45499)), //
						m(Deye.ChannelId.LVRT_START_VOLT, new UnsignedWordElement(45500), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.LVRT_END_VOLT, new UnsignedWordElement(45501), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.LVRT_START_TRIP_TIME, new UnsignedWordElement(45502)), //
						m(Deye.ChannelId.LVRT_END_TRIP_TIME, new UnsignedWordElement(45503)), //
						m(Deye.ChannelId.LVRT_TRIP_LIMIT_VOLT, new UnsignedWordElement(45504), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.HVRT_START_VOLT, new UnsignedWordElement(45505), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.HVRT_END_VOLT, new UnsignedWordElement(45506), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.HVRT_START_TRIP_TIME, new UnsignedWordElement(45507)), //
						m(Deye.ChannelId.HVRT_END_TRIP_TIME, new UnsignedWordElement(45508)), //
						m(Deye.ChannelId.HVRT_TRIP_LIMIT_VOLT, new UnsignedWordElement(45509), SCALE_FACTOR_MINUS_1)//
				), //

				// Additional settings for PF/PU/UF
				new FC16WriteRegistersTask(45510, //
						m(Deye.ChannelId.PF_TIME_CONSTANT, new UnsignedWordElement(45510)), //
						m(Deye.ChannelId.POWER_FREQ_TIME_CONSTANT, new UnsignedWordElement(45511)), //
						// Additional settings for P(U) Curve
						m(Deye.ChannelId.PU_TIME_CONSTANT, new UnsignedWordElement(45512)), //
						m(Deye.ChannelId.D_POINT_POWER, new SignedWordElement(45513)), //
						m(Deye.ChannelId.D_POINT_COS_PHI, new SignedWordElement(45514)), //
						// Additional settings for UF Curve
						m(Deye.ChannelId.UF_RECOVERY_WAITING_TIME, new UnsignedWordElement(45515),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.UF_RECOVER_SLOPE, new UnsignedWordElement(45516)), //
						m(Deye.ChannelId.CFP_UF_RECOVER_POWER_PERCENT, new UnsignedWordElement(45517)), //
						m(Deye.ChannelId.POWER_CHARGE_LIMIT, new UnsignedWordElement(45518), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.POWER_CHARGE_LIMIT_RECONNECT, new UnsignedWordElement(45519),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.C_EXT_UF_CHARGE_STOP, new UnsignedWordElement(45520), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.C_EXT_OF_DISCHARGE_STOP, new UnsignedWordElement(45521),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.C_EXT_TWOSSTEPF_FLG, new UnsignedWordElement(45522))//
				), //

				new FC16WriteRegistersTask(47505, //
						// If using EMS, must set to "2"
						m(Deye.ChannelId.MANUFACTURE_CODE, new UnsignedWordElement(47505)), //
						new DummyRegisterElement(47506, 47508), //
						m(Deye.ChannelId.FEED_POWER_ENABLE, new UnsignedWordElement(47509)), //
						m(Deye.ChannelId.FEED_POWER_PARA_SET, new SignedWordElement(47510)), //
						m(Deye.ChannelId.EMS_POWER_MODE, new UnsignedWordElement(47511)), //
						m(Deye.ChannelId.EMS_POWER_SET, new UnsignedWordElement(47512)), //
						new DummyRegisterElement(47513), //
						m(Deye.ChannelId.BATTERY_PROTOCOL_ARM, new UnsignedWordElement(47514)), //
						m(Deye.ChannelId.WORK_WEEK_1_START_TIME, new UnsignedWordElement(47515)), //
						m(Deye.ChannelId.WORK_WEEK_1_END_TIME, new UnsignedWordElement(47516)), //
						m(Deye.ChannelId.WORK_WEEK_1_BAT_POWER_PERCENT, new SignedWordElement(47517)), //
						m(Deye.ChannelId.WORK_WEEK_1, new UnsignedWordElement(47518)) //
				// TODO .debug()
				), //

				// Real-Time BMS Data for EMS Control (the data directly from BMS. Please refer
				// to the comments on registers 45352~45358)
				new FC16WriteRegistersTask(47900, //
						m(Deye.ChannelId.WBMS_VERSION, new UnsignedWordElement(47900)), //
						m(Deye.ChannelId.WBMS_STRINGS, new UnsignedWordElement(47901)), //
						m(Deye.ChannelId.WBMS_CHARGE_MAX_VOLTAGE, new UnsignedWordElement(47902),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.WBMS_CHARGE_MAX_CURRENT, new UnsignedWordElement(47903),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.WBMS_DISCHARGE_MIN_VOLTAGE, new UnsignedWordElement(47904),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.WBMS_DISCHARGE_MAX_CURRENT, new UnsignedWordElement(47905),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.WBMS_VOLTAGE, new UnsignedWordElement(47906), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.WBMS_CURRENT, new UnsignedWordElement(47907), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.WBMS_SOC, new UnsignedWordElement(47908)), //
						m(Deye.ChannelId.WBMS_SOH, new UnsignedWordElement(47909)), //
						m(Deye.ChannelId.WBMS_TEMPERATURE, new SignedWordElement(47910), SCALE_FACTOR_MINUS_1), //
						/**
						 * Warning Codes (table 8-8).
						 *
						 * <ul>
						 * <li>Bit 12-31 Reserved
						 * <li>Bit 11: System High Temperature
						 * <li>Bit 10: System Low Temperature 2
						 * <li>Bit 09: System Low Temperature 1
						 * <li>Bit 08: Cell Imbalance
						 * <li>Bit 07: System Reboot
						 * <li>Bit 06: Communication Failure
						 * <li>Bit 05: Discharge Over-Current
						 * <li>Bit 04: Charge Over-Current
						 * <li>Bit 03: Cell Low Temperature
						 * <li>Bit 02: Cell High Temperature
						 * <li>Bit 01: Discharge Under-Voltage
						 * <li>Bit 00: Charge Over-Voltage
						 * </ul>
						 */
						m(Deye.ChannelId.WBMS_WARNING_CODE, new UnsignedDoublewordElement(47911)), //
						/**
						 * Alarm Codes (table 8-7).
						 *
						 * <ul>
						 * <li>Bit 16-31 Reserved
						 * <li>Bit 15: Charge Over-Voltage Fault
						 * <li>Bit 14: Discharge Under-Voltage Fault
						 * <li>Bit 13: Cell High Temperature
						 * <li>Bit 12: Communication Fault
						 * <li>Bit 11: Charge Circuit Fault
						 * <li>Bit 10: Discharge Circuit Fault
						 * <li>Bit 09: Battery Lock
						 * <li>Bit 08: Battery Break
						 * <li>Bit 07: DC Bus Fault
						 * <li>Bit 06: Precharge Fault
						 * <li>Bit 05: Discharge Over-Current
						 * <li>Bit 04: Charge Over-Current
						 * <li>Bit 03: Cell Low Temperature
						 * <li>Bit 02: Cell High Temperature
						 * <li>Bit 01: Discharge Under-Voltage
						 * <li>Bit 00: Charge Over-Voltage
						 * </ul>
						 */
						m(Deye.ChannelId.WBMS_ALARM_CODE, new UnsignedDoublewordElement(47913)), //
						/**
						 * BMS Status
						 *
						 * <ul>
						 * <li>Bit 2: Stop Discharge
						 * <li>Bit 1: Stop Charge
						 * <li>Bit 0: Force Charge
						 * </ul>
						 */
						m(Deye.ChannelId.WBMS_STATUS, new UnsignedWordElement(47915)), //
						m(Deye.ChannelId.WBMS_DISABLE_TIMEOUT_DETECTION, new UnsignedWordElement(47916)) //
				),

				new FC3ReadRegistersTask(47900, Priority.LOW, //
						m(Deye.ChannelId.WBMS_VERSION, new UnsignedWordElement(47900)), //
						m(Deye.ChannelId.WBMS_STRINGS, new UnsignedWordElement(47901)), //
						m(Deye.ChannelId.WBMS_CHARGE_MAX_VOLTAGE, new UnsignedWordElement(47902),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.WBMS_CHARGE_MAX_CURRENT, new UnsignedWordElement(47903),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.WBMS_DISCHARGE_MIN_VOLTAGE, new UnsignedWordElement(47904),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.WBMS_DISCHARGE_MAX_CURRENT, new UnsignedWordElement(47905),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.WBMS_VOLTAGE, new UnsignedWordElement(47906), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.WBMS_CURRENT, new UnsignedWordElement(47907)), //
						m(Deye.ChannelId.WBMS_SOC, new UnsignedWordElement(47908)), //
						m(Deye.ChannelId.WBMS_SOH, new UnsignedWordElement(47909)), //
						m(Deye.ChannelId.WBMS_TEMPERATURE, new SignedWordElement(47910), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.WBMS_WARNING_CODE, new UnsignedDoublewordElement(47911)), //
						m(Deye.ChannelId.WBMS_ALARM_CODE, new UnsignedDoublewordElement(47913)), //

						// TODO reset to individual states
						m(Deye.ChannelId.WBMS_STATUS, new UnsignedWordElement(47915)), //
						m(Deye.ChannelId.WBMS_DISABLE_TIMEOUT_DETECTION, new UnsignedWordElement(47916)) //
				) //
		);

		/*
		 * Handles different Deye Types.
		 * 
		 * Register 35011: DeyeType as String (Not supported for Deye 20 & 30)
		 * Register 35003: Serial number as String (Fallback for Deye 20 & 30)
		 */
		ModbusUtils.readELementOnce(protocol, new StringWordElement(35011, 5), true) //
				.thenAccept(value -> {

					/*
					 * Evaluate DeyeType from Deye type register
					 */
					final var resultFromString = getDeyeTypeFromStringValue(
							TypeUtils.<String>getAsType(OpenemsType.STRING, value));

					if (resultFromString != DeyeType.UNDEFINED) {
						this.logInfo(this.log, "Identified " + resultFromString.getName());
						this._setDeyeType(resultFromString);
						return;
					}

					/*
					 * Evaluate DeyeType from serial number
					 */
					try {
						ModbusUtils.readELementOnce(protocol, new StringWordElement(35003, 8), true) //
								.thenAccept(serialNr -> {
									final var hardwareType = getDeyeTypeFromSerialNr(serialNr);
									try {
										this._setDeyeType(hardwareType);
										if (hardwareType == DeyeType.FENECON_FHI_20_DAH
												|| hardwareType == DeyeType.FENECON_FHI_29_9_DAH) {
											this.handleMultipleStringChargers(protocol);
										}

									} catch (OpenemsException e) {
										this.logError(this.log, "Unable to add charger tasks for modbus protocol");
									}
								});
					} catch (OpenemsException e) {
						this.logError(this.log, "Unable to read element for serial number");
						e.printStackTrace();
					}
				});

		// Handles different DSP versions
		ModbusUtils.readELementOnce(protocol, new UnsignedWordElement(35016), true) //
				.thenAccept(dspVersion -> {
					try {

						// Deye 30 has DspFmVersionMaster=0 & DspBetaVersion=80
						if (dspVersion == 0) {
							this.handleDspVersion5(protocol);
							this.handleDspVersion6(protocol);
							this.handleDspVersion7(protocol);
							return;
						}
						if (dspVersion >= 5) {
							this.handleDspVersion5(protocol);
						}
						if (dspVersion >= 6) {
							this.handleDspVersion6(protocol);
						}
						if (dspVersion >= 7) {
							this.handleDspVersion7(protocol);
						}
					} catch (OpenemsException e) {
						this.logError(this.log, "Unable to add task for modbus protocol");
					}
				});

		return protocol;
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE:

			// Set charger information for MPPTs having more than one PV e.g.
			// DeyeChargerTwoString.
			this.setMultipleStringChannels();
		}
	}

	/**
	 * Get Deye type from the Deye string representation.
	 * 
	 * @param stringValue Deye type as String
	 * @return type as {@link DeyeType}
	 */
	public static DeyeType getDeyeTypeFromStringValue(String stringValue) {
		if (stringValue == null || stringValue.isEmpty()) {
			return DeyeType.UNDEFINED;
		}

		return switch (stringValue) {
		case "GW10K-BT" -> DeyeType.DEYE_10K_BT;
		case "GW8K-BT" -> DeyeType.DEYE_8K_BT;
		case "GW5K-BT" -> DeyeType.DEYE_5K_BT;
		case "GW10K-ET" -> DeyeType.DEYE_10K_ET;
		case "GW8K-ET" -> DeyeType.DEYE_8K_ET;
		case "GW5K-ET" -> DeyeType.DEYE_5K_ET;
		case "FHI-10-DAH" -> DeyeType.FENECON_FHI_10_DAH;
		default -> DeyeType.UNDEFINED;
		};
	}

	/**
	 * Get Deye type from serial number.
	 * 
	 * @param serialNr Serial number
	 * @return type as {@link DeyeHardwareType}
	 */
	public static DeyeType getDeyeTypeFromSerialNr(String serialNr) {
		if (serialNr == null || serialNr.isEmpty()) {
			return DeyeType.UNDEFINED;
		}

		// Example serial numbers: default=9010KETT228W0004 float(29.9)=929K9ETT231W0159
		return Stream.of(DeyeType.values()) //
				.filter(t -> {
					try {
						return t.serialNrFilter.apply(serialNr);
					} catch (Exception e) {
						LOG.warn("Unable to parse Deye Serial Number [" + serialNr + "] with [" + t.name() + "]: "
								+ e.getMessage());
						e.printStackTrace();
						return false;
					}
				}) //
				.findFirst() //
				.orElse(DeyeType.UNDEFINED);
	}

	/**
	 * Handle multiple string chargers.
	 * 
	 * <p>
	 * For MPPT connectors e.g. two string on one MPPT the power information is
	 * spread over several registers that should be read as complete blocks.
	 * 
	 * @param protocol current protocol
	 * @throws OpenemsException on error
	 */
	private void handleMultipleStringChargers(ModbusProtocol protocol) throws OpenemsException {
		/*
		 * For two string charger the registers the power information is spread over
		 * several registers that should be read as complete blocks
		 */
		/*
		 * Block 1: PV1 - PV4 voltage & current
		 */
		protocol.addTask(//

				new FC3ReadRegistersTask(35103, Priority.HIGH, //
						m(Deye.ChannelId.TWO_S_PV1_V, new UnsignedWordElement(35103),
								ElementToChannelConverter.SCALE_FACTOR_2),
						m(Deye.ChannelId.TWO_S_PV1_I, new UnsignedWordElement(35104),
								ElementToChannelConverter.SCALE_FACTOR_2),

						// Power having wrong values for two-string charger
						new DummyRegisterElement(35105, 35106),

						m(Deye.ChannelId.TWO_S_PV2_V, new UnsignedWordElement(35107),
								ElementToChannelConverter.SCALE_FACTOR_2),
						m(Deye.ChannelId.TWO_S_PV2_I, new UnsignedWordElement(35108),
								ElementToChannelConverter.SCALE_FACTOR_2),
						new DummyRegisterElement(35109, 35110),
						m(Deye.ChannelId.TWO_S_PV3_V, new UnsignedWordElement(35111),
								ElementToChannelConverter.SCALE_FACTOR_2),
						m(Deye.ChannelId.TWO_S_PV3_I, new UnsignedWordElement(35112),
								ElementToChannelConverter.SCALE_FACTOR_2),
						new DummyRegisterElement(35113, 35114),
						m(Deye.ChannelId.TWO_S_PV4_V, new UnsignedWordElement(35115),
								ElementToChannelConverter.SCALE_FACTOR_2),
						m(Deye.ChannelId.TWO_S_PV4_I, new UnsignedWordElement(35116),
								ElementToChannelConverter.SCALE_FACTOR_2)) //
		);

		/*
		 * Block 2: PV5 - PV6 voltage & current (would continue till PV16) and MPPT
		 * total power and current values
		 */
		protocol.addTask(//
				new FC3ReadRegistersTask(35304, Priority.HIGH, //
						m(Deye.ChannelId.TWO_S_PV5_V, new UnsignedWordElement(35304),
								ElementToChannelConverter.SCALE_FACTOR_2),
						m(Deye.ChannelId.TWO_S_PV5_I, new UnsignedWordElement(35305),
								ElementToChannelConverter.SCALE_FACTOR_2),
						m(Deye.ChannelId.TWO_S_PV6_V, new UnsignedWordElement(35306),
								ElementToChannelConverter.SCALE_FACTOR_2),
						m(Deye.ChannelId.TWO_S_PV6_I, new UnsignedWordElement(35307),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						new DummyRegisterElement(35308, 35336),
						m(Deye.ChannelId.TWO_S_MPPT1_P, new UnsignedWordElement(35337)),
						m(Deye.ChannelId.TWO_S_MPPT2_P, new UnsignedWordElement(35338)),
						m(Deye.ChannelId.TWO_S_MPPT3_P, new UnsignedWordElement(35339)),
						new DummyRegisterElement(35340, 35344), // Power MPPT4 - MPPT8
						m(Deye.ChannelId.TWO_S_MPPT1_I, new UnsignedWordElement(35345), //
								ElementToChannelConverter.SCALE_FACTOR_2),
						m(Deye.ChannelId.TWO_S_MPPT2_I, new UnsignedWordElement(35346), //
								ElementToChannelConverter.SCALE_FACTOR_2),
						m(Deye.ChannelId.TWO_S_MPPT3_I, new UnsignedWordElement(35347), //
								ElementToChannelConverter.SCALE_FACTOR_2)) //
		);
	}

	private void setMultipleStringChannels() {

		this.chargers.stream() //
				.filter(DeyeChargerTwoString.class::isInstance) //
				.map(DeyeChargerTwoString.class::cast) //
				.forEach(charger -> {
					var pvPort = charger.pvPort();

					// Get actual Channels
					IntegerReadChannel totalMpptPowerChannel = this.channel(pvPort.mpptPowerChannelId);
					IntegerReadChannel totalMpptCurrentChannel = this.channel(pvPort.mpptCurrentChannelId);
					IntegerReadChannel stringCurrentChannel = this.channel(pvPort.pvCurrentId);
					IntegerReadChannel stringVoltageChannel = this.channel(pvPort.pvVoltageId);

					// Power value from the total MPPT power and current values
					charger._setActualPower(//
							DeyeChargerTwoString.calculateByRuleOfThree(//
									totalMpptPowerChannel.getNextValue().asOptional(), //
									totalMpptCurrentChannel.getNextValue().asOptional(), //
									stringCurrentChannel.getNextValue().asOptional()) //
									// If at least one value was present, the result should not be null.
									.orElse(0) //
					);

					/*
					 * TODO: Could also be achieved by using listeners for onSetNextValue in
					 * addCharger and removeCharger.
					 */
					charger._setCurrent(stringCurrentChannel.getNextValue().get());
					charger._setVoltage(stringVoltageChannel.getNextValue().get());
				});
	}

	private void handleDspVersion7(ModbusProtocol protocol) throws OpenemsException {
		protocol.addTask(//
				new FC3ReadRegistersTask(47519, Priority.LOW, //
						m(Deye.ChannelId.WORK_WEEK_2_START_TIME, new UnsignedWordElement(47519)), //
						m(Deye.ChannelId.WORK_WEEK_2_END_TIME, new UnsignedWordElement(47520)), //
						m(Deye.ChannelId.WORK_WEEK_2_BAT_POWER_PERCENT, new UnsignedWordElement(47521)), //
						m(new BitsWordElement(47522, this)//
								.bit(0, Deye.ChannelId.WORK_WEEK_2_SUNDAY)//
								.bit(1, Deye.ChannelId.WORK_WEEK_2_MONDAY)//
								.bit(2, Deye.ChannelId.WORK_WEEK_2_TUESDAY)//
								.bit(3, Deye.ChannelId.WORK_WEEK_2_WEDNESDAY)//
								.bit(4, Deye.ChannelId.WORK_WEEK_2_THURSDAY)//
								.bit(5, Deye.ChannelId.WORK_WEEK_2_FRIDAY)//
								.bit(6, Deye.ChannelId.WORK_WEEK_2_SATURDAY)//
								.bit(7, Deye.ChannelId.WORK_WEEK_2_NA)//
								.bit(8, Deye.ChannelId.WORK_WEEK_2_ENABLED)), //
						m(Deye.ChannelId.WORK_WEEK_3_START_TIME, new UnsignedWordElement(47523)), //
						m(Deye.ChannelId.WORK_WEEK_3_END_TIME, new UnsignedWordElement(47524)), //
						m(Deye.ChannelId.WORK_WEEK_3_BAT_POWER_PERCENT, new UnsignedWordElement(47525)), //
						m(new BitsWordElement(47526, this)//
								.bit(0, Deye.ChannelId.WORK_WEEK_3_SUNDAY)//
								.bit(1, Deye.ChannelId.WORK_WEEK_3_MONDAY)//
								.bit(2, Deye.ChannelId.WORK_WEEK_3_TUESDAY)//
								.bit(3, Deye.ChannelId.WORK_WEEK_3_WEDNESDAY)//
								.bit(4, Deye.ChannelId.WORK_WEEK_3_THURSDAY)//
								.bit(5, Deye.ChannelId.WORK_WEEK_3_FRIDAY)//
								.bit(6, Deye.ChannelId.WORK_WEEK_3_SATURDAY)//
								.bit(7, Deye.ChannelId.WORK_WEEK_3_NA)//
								.bit(8, Deye.ChannelId.WORK_WEEK_3_ENABLED)), //
						m(Deye.ChannelId.WORK_WEEK_4_START_TIME, new UnsignedWordElement(47527)), //
						m(Deye.ChannelId.WORK_WEEK_4_END_TIME, new UnsignedWordElement(47528)), //
						m(Deye.ChannelId.WORK_WEEK_4_BMS_POWER_PERCENT, new UnsignedWordElement(47529)), //
						m(new BitsWordElement(47530, this)//
								.bit(0, Deye.ChannelId.WORK_WEEK_4_SUNDAY)//
								.bit(1, Deye.ChannelId.WORK_WEEK_4_MONDAY)//
								.bit(2, Deye.ChannelId.WORK_WEEK_4_TUESDAY)//
								.bit(3, Deye.ChannelId.WORK_WEEK_4_WEDNESDAY)//
								.bit(4, Deye.ChannelId.WORK_WEEK_4_THURSDAY)//
								.bit(5, Deye.ChannelId.WORK_WEEK_4_FRIDAY)//
								.bit(6, Deye.ChannelId.WORK_WEEK_4_SATURDAY)//
								.bit(7, Deye.ChannelId.WORK_WEEK_4_NA)//
								.bit(8, Deye.ChannelId.WORK_WEEK_4_ENABLED)), //
						m(Deye.ChannelId.SOC_START_TO_FORCE_CHARGE, new UnsignedWordElement(47531)), //
						m(Deye.ChannelId.SOC_STOP_TO_FORCE_CHARGE, new UnsignedWordElement(47532)), //
						new DummyRegisterElement(47533, 47541), //
						m(Deye.ChannelId.PEAK_SHAVING_POWER_LIMIT, new UnsignedWordElement(47542)), //
						new DummyRegisterElement(47543), //
						m(Deye.ChannelId.PEAK_SHAVING_SOC, new UnsignedWordElement(47544)), //
						m(Deye.ChannelId.FAST_CHARGE_ENABLE, new UnsignedWordElement(47545)), // [0,1]
						m(Deye.ChannelId.FAST_CHARGE_STOP_SOC, new UnsignedWordElement(47546))) // [0,100]
		);

		protocol.addTask(//
				new FC16WriteRegistersTask(47519, //
						m(Deye.ChannelId.WORK_WEEK_2_START_TIME, new UnsignedWordElement(47519)), //
						m(Deye.ChannelId.WORK_WEEK_2_END_TIME, new UnsignedWordElement(47520)), //
						m(Deye.ChannelId.WORK_WEEK_2_BAT_POWER_PERCENT, new SignedWordElement(47521)), //
						m(Deye.ChannelId.WORK_WEEK_2, new UnsignedWordElement(47522)), //

						m(Deye.ChannelId.WORK_WEEK_3_START_TIME, new UnsignedWordElement(47523)), //
						m(Deye.ChannelId.WORK_WEEK_3_END_TIME, new UnsignedWordElement(47524)), //
						m(Deye.ChannelId.WORK_WEEK_3_BAT_POWER_PERCENT, new SignedWordElement(47525)), //
						m(Deye.ChannelId.WORK_WEEK_3, new UnsignedWordElement(47526)), //

						m(Deye.ChannelId.WORK_WEEK_4_START_TIME, new UnsignedWordElement(47527)), //
						m(Deye.ChannelId.WORK_WEEK_4_END_TIME, new UnsignedWordElement(47528)), //
						m(Deye.ChannelId.WORK_WEEK_4_BMS_POWER_PERCENT, new SignedWordElement(47529)), //
						m(Deye.ChannelId.WORK_WEEK_4, new UnsignedWordElement(47530)), //

						// To set the SOC level to start/stop battery force charge.(this is not the
						// command from BMS, but the protection on inverter side. Eg. StartchgSOC
						// (47531) is set as 5%, but the battery BMS gives a force charge signal at
						// SOC
						// 6%, then battery will start force charge at 6% SOC; if BMS does not send
						// force charge command at 5% SOC, then battery will still start force
						// charge at
						// 5% SOC. ) Note: the default setting is 5% SOC to start and 10% to stop.
						// force
						// charge power is 1000W from PV or Grid as well
						m(Deye.ChannelId.SOC_START_TO_FORCE_CHARGE, new UnsignedWordElement(47531),
								SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.SOC_STOP_TO_FORCE_CHARGE, new UnsignedWordElement(47532),
								SCALE_FACTOR_MINUS_1), //
						// to clear all economical mode settings (47515-47530) enter self Use Mode
						m(Deye.ChannelId.CLEAR_ALL_ECONOMIC_MODE, new UnsignedWordElement(47533)), //
						new DummyRegisterElement(47534, 47538), //
						m(Deye.ChannelId.WIFI_RESET, new UnsignedWordElement(47539)), //
						new DummyRegisterElement(47540), //
						m(Deye.ChannelId.WIFI_RELOAD, new UnsignedWordElement(47541)), //
						// to set the threshold of importing power, where peak-shaving acts. Eg. If
						// set
						// peak-shaving power as 20kW, then battery will only discharge when
						// imported
						// power from grid exceed 20kW to make sure the importing power keeps below
						// 20kW
						m(Deye.ChannelId.PEAK_SHAVING_POWER_LIMIT, new UnsignedDoublewordElement(47542)), //
						m(Deye.ChannelId.PEAK_SHAVING_SOC, new UnsignedWordElement(47544)), //
						// 0: Disable 1:Enable
						m(Deye.ChannelId.FAST_CHARGE_ENABLE, new UnsignedWordElement(47545)), //
						m(Deye.ChannelId.FAST_CHARGE_STOP_SOC, new UnsignedWordElement(47546))) //
		);

		protocol.addTask(//
				// Economic mode setting for ARM version => 18
				new FC3ReadRegistersTask(47547, Priority.LOW, //
						m(Deye.ChannelId.WORK_WEEK_1_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47547)), //
						m(Deye.ChannelId.WORK_WEEK_1_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47548)), //
						m(Deye.ChannelId.WORK_WEEK_1_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47549)), //
						m(Deye.ChannelId.WORK_WEEK_1_PARAMETER1_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47550)), //
						m(Deye.ChannelId.WORK_WEEK_1_PARAMETER1_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47551)), //
						m(Deye.ChannelId.WORK_WEEK_1_PARAMETER1_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47552)), //

						m(Deye.ChannelId.WORK_WEEK_2_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47553)), //
						m(Deye.ChannelId.WORK_WEEK_2_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47554)), //
						m(Deye.ChannelId.WORK_WEEK_2_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47555)), //
						m(Deye.ChannelId.WORK_WEEK_2_PARAMETER2_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47556)), //
						m(Deye.ChannelId.WORK_WEEK_2_PARAMETER2_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47557)), //
						m(Deye.ChannelId.WORK_WEEK_2_PARAMETER2_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47558)), //

						m(Deye.ChannelId.WORK_WEEK_3_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47559)), //
						m(Deye.ChannelId.WORK_WEEK_3_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47560)), //
						m(Deye.ChannelId.WORK_WEEK_3_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47561)), //
						m(Deye.ChannelId.WORK_WEEK_3_PARAMETER3_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47562)), //
						m(Deye.ChannelId.WORK_WEEK_3_PARAMETER3_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47563)), //
						m(Deye.ChannelId.WORK_WEEK_3_PARAMETER3_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47564)), //

						m(Deye.ChannelId.WORK_WEEK_4_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47565)), //
						m(Deye.ChannelId.WORK_WEEK_4_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47566)), //
						m(Deye.ChannelId.WORK_WEEK_4_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47567)), //
						m(Deye.ChannelId.WORK_WEEK_4_PARAMETER4_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47568)), //
						m(Deye.ChannelId.WORK_WEEK_4_PARAMETER4_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47569)), //
						m(Deye.ChannelId.WORK_WEEK_4_PARAMETER4_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47570)), //

						m(Deye.ChannelId.WORK_WEEK_5_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47571)), //
						m(Deye.ChannelId.WORK_WEEK_5_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47572)), //
						m(Deye.ChannelId.WORK_WEEK_5_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47573)), //
						m(Deye.ChannelId.WORK_WEEK_5_PARAMETER5_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47574)), //
						m(Deye.ChannelId.WORK_WEEK_5_PARAMETER5_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47575)), //
						m(Deye.ChannelId.WORK_WEEK_5_PARAMETER5_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47576)), //

						m(Deye.ChannelId.WORK_WEEK_6_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47577)), //
						m(Deye.ChannelId.WORK_WEEK_6_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47578)), //
						m(Deye.ChannelId.WORK_WEEK_6_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47579)), //
						m(Deye.ChannelId.WORK_WEEK_6_PARAMETER6_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47580)), //
						m(Deye.ChannelId.WORK_WEEK_6_PARAMETER6_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47581)), //
						m(Deye.ChannelId.WORK_WEEK_6_PARAMETER6_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47582)), //

						m(Deye.ChannelId.WORK_WEEK_7_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47583)), //
						m(Deye.ChannelId.WORK_WEEK_7_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47584)), //
						m(Deye.ChannelId.WORK_WEEK_7_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47585)), //
						m(Deye.ChannelId.WORK_WEEK_7_PARAMETER7_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47586)), //
						m(Deye.ChannelId.WORK_WEEK_7_PARAMETER7_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47587)), //
						m(Deye.ChannelId.WORK_WEEK_7_PARAMETER7_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47588)), //

						m(Deye.ChannelId.WORK_WEEK_8_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47589)), //
						m(Deye.ChannelId.WORK_WEEK_8_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47590)), //
						m(Deye.ChannelId.WORK_WEEK_8_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47591)), //
						m(Deye.ChannelId.WORK_WEEK_8_PARAMETER8_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47592)), //
						m(Deye.ChannelId.WORK_WEEK_8_PARAMETER8_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47593)), //
						m(Deye.ChannelId.WORK_WEEK_8_PARAMETER8_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47594)), //
						// 0,Disable 1,switching mode 2,Time manage mode
						// Only for inverter with ARM version equal or greater 18 To select Load
						// control
						// mode
						m(Deye.ChannelId.LOAD_REGULATION_INDEX, new UnsignedWordElement(47595)), //
						m(Deye.ChannelId.LOAD_SWITCH_STATUS, new UnsignedWordElement(47596)), //
						// For load control function, if the controlled load on Backup side, use
						// this to
						// switch the load off when battery reaches the SOC set
						m(Deye.ChannelId.BACKUP_SWITCH_SOC_MIN, new UnsignedWordElement(47597)), //
						new DummyRegisterElement(47598), //
						m(Deye.ChannelId.HARDWARE_FEED_POWER, new UnsignedWordElement(47599))) //
		);

		protocol.addTask(//
				new FC16WriteRegistersTask(47547, //
						// Economic mode setting for ARM version => 18
						m(Deye.ChannelId.WORK_WEEK_1_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47547)), //
						m(Deye.ChannelId.WORK_WEEK_1_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47548)), //
						m(Deye.ChannelId.WORK_WEEK_1_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47549)), //
						m(Deye.ChannelId.WORK_WEEK_1_PARAMETER1_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47550)), //
						m(Deye.ChannelId.WORK_WEEK_1_PARAMETER1_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47551)), //
						m(Deye.ChannelId.WORK_WEEK_1_PARAMETER1_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47552)), //

						m(Deye.ChannelId.WORK_WEEK_2_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47553)), //
						m(Deye.ChannelId.WORK_WEEK_2_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47554)), //
						m(Deye.ChannelId.WORK_WEEK_2_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47555)), //
						m(Deye.ChannelId.WORK_WEEK_2_PARAMETER2_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47556)), //
						m(Deye.ChannelId.WORK_WEEK_2_PARAMETER2_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47557)), //
						m(Deye.ChannelId.WORK_WEEK_2_PARAMETER2_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47558)), //

						m(Deye.ChannelId.WORK_WEEK_3_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47559)), //
						m(Deye.ChannelId.WORK_WEEK_3_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47560)), //
						m(Deye.ChannelId.WORK_WEEK_3_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47561)), //
						m(Deye.ChannelId.WORK_WEEK_3_PARAMETER3_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47562)), //
						m(Deye.ChannelId.WORK_WEEK_3_PARAMETER3_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47563)), //
						m(Deye.ChannelId.WORK_WEEK_3_PARAMETER3_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47564)), //

						m(Deye.ChannelId.WORK_WEEK_4_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47565)), //
						m(Deye.ChannelId.WORK_WEEK_4_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47566)), //
						m(Deye.ChannelId.WORK_WEEK_4_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47567)), //
						m(Deye.ChannelId.WORK_WEEK_4_PARAMETER4_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47568)), //
						m(Deye.ChannelId.WORK_WEEK_4_PARAMETER4_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47569)), //
						m(Deye.ChannelId.WORK_WEEK_4_PARAMETER4_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47570)), //

						m(Deye.ChannelId.WORK_WEEK_5_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47571)), //
						m(Deye.ChannelId.WORK_WEEK_5_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47572)), //
						m(Deye.ChannelId.WORK_WEEK_5_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47573)), //
						m(Deye.ChannelId.WORK_WEEK_5_PARAMETER5_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47574)), //
						m(Deye.ChannelId.WORK_WEEK_5_PARAMETER5_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47575)), //
						m(Deye.ChannelId.WORK_WEEK_5_PARAMETER5_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47576)), //

						m(Deye.ChannelId.WORK_WEEK_6_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47577)), //
						m(Deye.ChannelId.WORK_WEEK_6_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47578)), //
						m(Deye.ChannelId.WORK_WEEK_6_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47579)), //
						m(Deye.ChannelId.WORK_WEEK_6_PARAMETER6_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47580)), //
						m(Deye.ChannelId.WORK_WEEK_6_PARAMETER6_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47581)), //
						m(Deye.ChannelId.WORK_WEEK_6_PARAMETER6_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47582)), //

						m(Deye.ChannelId.WORK_WEEK_7_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47583)), //
						m(Deye.ChannelId.WORK_WEEK_7_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47584)), //
						m(Deye.ChannelId.WORK_WEEK_7_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47585)), //
						m(Deye.ChannelId.WORK_WEEK_7_PARAMETER7_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47586)), //
						m(Deye.ChannelId.WORK_WEEK_7_PARAMETER7_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47587)), //
						m(Deye.ChannelId.WORK_WEEK_7_PARAMETER7_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47588)), //

						m(Deye.ChannelId.WORK_WEEK_8_START_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47589)), //
						m(Deye.ChannelId.WORK_WEEK_8_END_TIME_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47590)), //
						m(Deye.ChannelId.WORK_WEEK_8_ECO_MODE_FOR_ARM_18_AND_GREATER, new UnsignedWordElement(47591)), //
						m(Deye.ChannelId.WORK_WEEK_8_PARAMETER8_1_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47592)), //
						m(Deye.ChannelId.WORK_WEEK_8_PARAMETER8_2_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47593)), //
						m(Deye.ChannelId.WORK_WEEK_8_PARAMETER8_3_ECO_MODE_FOR_ARM_18_AND_GREATER,
								new UnsignedWordElement(47594)), //
						// 0,Disable 1,switching mode 2,Time manage mode
						// Only for inverter with ARM version equal or greater 18 To select Load
						// control
						// mode
						m(Deye.ChannelId.LOAD_REGULATION_INDEX, new UnsignedWordElement(47595)), //
						m(Deye.ChannelId.LOAD_SWITCH_STATUS, new UnsignedWordElement(47596)), //
						// For load control function, if the controlled load on Backup side, use
						// this to
						// switch the load off when battery reaches the SOC set
						m(Deye.ChannelId.BACKUP_SWITCH_SOC_MIN, new UnsignedWordElement(47597)), //
						new DummyRegisterElement(47598), //
						m(Deye.ChannelId.HARDWARE_FEED_POWER, new UnsignedWordElement(47599)))//
		);
	}

	private void handleDspVersion6(ModbusProtocol protocol) throws OpenemsException {
		// Registers 36000 for COM_MODE throw "Illegal Data Address"

		protocol.addTask(//
				new FC3ReadRegistersTask(36001, Priority.LOW, //
						// External Communication Data(ARM)
						m(Deye.ChannelId.RSSI, new UnsignedWordElement(36001)), //
						new DummyRegisterElement(36002, 36003), //
						m(Deye.ChannelId.METER_COMMUNICATE_STATUS, new UnsignedWordElement(36004)), //
						// Registers for Grid Smart-Meter (36005 to 36014) are read via GridMeter
						// implementation
						new DummyRegisterElement(36005, 36014),
						m(Deye.ChannelId.E_TOTAL_SELL, new FloatDoublewordElement(36015), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_TOTAL_BUY_F, new FloatDoublewordElement(36017), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.METER_ACTIVE_POWER_R, new SignedDoublewordElement(36019)), //
						m(Deye.ChannelId.METER_ACTIVE_POWER_S, new SignedDoublewordElement(36021)), //
						m(Deye.ChannelId.METER_ACTIVE_POWER_T, new SignedDoublewordElement(36023)), //
						m(Deye.ChannelId.METER_TOTAL_ACTIVE_POWER, new SignedDoublewordElement(36025)), //
						m(Deye.ChannelId.METER_REACTIVE_POWER_R, new SignedDoublewordElement(36027)), //
						m(Deye.ChannelId.METER_REACTIVE_POWER_S, new SignedDoublewordElement(36029)), //
						m(Deye.ChannelId.METER_REACTIVE_POWER_T, new SignedDoublewordElement(36031)), //
						m(Deye.ChannelId.METER_TOTAL_REACTIVE_POWER, new SignedDoublewordElement(36033)), //
						m(Deye.ChannelId.METER_APPARENT_POWER_R, new SignedDoublewordElement(36035)), //
						m(Deye.ChannelId.METER_APPARENT_POWER_S, new SignedDoublewordElement(36037)), //
						m(Deye.ChannelId.METER_APPARENT_POWER_T, new SignedDoublewordElement(36039)), //
						m(Deye.ChannelId.METER_TOTAL_APPARENT_POWER, new SignedDoublewordElement(36041)), //
						// Only for Deye smart meter
						m(Deye.ChannelId.METER_TYPE, new UnsignedWordElement(36043)), //
						m(Deye.ChannelId.METER_SOFTWARE_VERSION, new UnsignedWordElement(36044)), //
						// Only for AC coupled inverter. Detect Pv Meter
						m(Deye.ChannelId.METER_CT2_ACTIVE_POWER, new SignedDoublewordElement(36045)), //
						m(Deye.ChannelId.CT2_E_TOTAL_SELL, new UnsignedDoublewordElement(36047),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.CT2_E_TOTAL_BUY, new UnsignedDoublewordElement(36049), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.METER_CT2_STATUS, new UnsignedWordElement(36051))) //
		);

		protocol.addTask(//
				new FC3ReadRegistersTask(47000, Priority.LOW, //
						m(Deye.ChannelId.SELECT_WORK_MODE, new UnsignedWordElement(47000)), //
						new DummyRegisterElement(47001), //
						m(Deye.ChannelId.WMETER_CONNECT_CHECK_FLAG, new UnsignedWordElement(47002)), //
						new DummyRegisterElement(47003, 47004), //
						m(Deye.ChannelId.LOG_DATA_ENABLE, new UnsignedWordElement(47005)), //
						m(Deye.ChannelId.DATA_SEND_INTERVAL, new UnsignedWordElement(47006)), //
						m(Deye.ChannelId.DRED_CMD, new UnsignedWordElement(47007)), //
						new DummyRegisterElement(47008), //
						m(Deye.ChannelId.WIFI_OR_LAN_SWITCH, new UnsignedWordElement(47009)), //
						m(Deye.ChannelId.RIPPLE_CONTROL_RECEIVER_ENABLE, new UnsignedWordElement(47010)), //
						new DummyRegisterElement(47011), //
						m(Deye.ChannelId.LED_BLINK_TIME, new UnsignedWordElement(47012)), //
						m(Deye.ChannelId.WIFI_LED_STATE, new UnsignedWordElement(47013)), //
						m(Deye.ChannelId.COM_LED_STATE, new UnsignedWordElement(47014)), //
						m(Deye.ChannelId.METER_CT1_REVERSE_ENABLE, new UnsignedWordElement(47015)), //
						m(Deye.ChannelId.ERROR_LOG_READ_PAGE, new UnsignedWordElement(47016)), //
						// 1:on 0:off If not connect to Internet, please set 1
						m(Deye.ChannelId.MODBUS_TCP_WITHOUT_INTERNET, new UnsignedWordElement(47017)), //
						// 1: off, 2: on, 3: flash 1x, 4: flash 2x, 5: flash 4x
						m(Deye.ChannelId.BACKUP_LED, new UnsignedWordElement(47018)), // [1,5]
						m(Deye.ChannelId.GRID_LED, new UnsignedWordElement(47019)), // [1,5]
						m(Deye.ChannelId.SOC_LED_1, new UnsignedWordElement(47020)), // [1,5]
						m(Deye.ChannelId.SOC_LED_2, new UnsignedWordElement(47021)), // [1,5]
						m(Deye.ChannelId.SOC_LED_3, new UnsignedWordElement(47022)), // [1,5]
						m(Deye.ChannelId.SOC_LED_4, new UnsignedWordElement(47023)), // [1,5]
						m(Deye.ChannelId.BATTERY_LED, new UnsignedWordElement(47024)), // [1,5]
						m(Deye.ChannelId.SYSTEM_LED, new UnsignedWordElement(47025)), // [1,5]
						m(Deye.ChannelId.FAULT_LED, new UnsignedWordElement(47026)), // [1,5]
						m(Deye.ChannelId.ENERGY_LED, new UnsignedWordElement(47027)), // [1,5]
						m(Deye.ChannelId.LED_EXTERNAL_CONTROL, new UnsignedWordElement(47028)), // [42343]
						new DummyRegisterElement(47029, 47037), //
						// 1 Enable, After restart the inverter, setting saved
						m(Deye.ChannelId.STOP_MODE_SAVE_ENABLE, new UnsignedWordElement(47038))) //
		);

		protocol.addTask(//
				// The same function as that for Operation Mode on PV Master App
				new FC16WriteRegistersTask(47000, //
						m(Deye.ChannelId.SELECT_WORK_MODE, new UnsignedWordElement(47000)), //
						new DummyRegisterElement(47001), //
						m(Deye.ChannelId.WMETER_CONNECT_CHECK_FLAG, new UnsignedWordElement(47002)), //
						new DummyRegisterElement(47003, 47004), //
						// Breakpoint Resume for Data transferring. Activated as default, time
						// interval 5
						// minutes
						m(Deye.ChannelId.LOG_DATA_ENABLE, new UnsignedWordElement(47005)), //
						// Time interval for data send to cloud or EMS,default is 1 minute
						m(Deye.ChannelId.DATA_SEND_INTERVAL, new UnsignedWordElement(47006)), //
						// Only for Australia, Refer to Table 8-22
						m(Deye.ChannelId.DRED_CMD, new UnsignedWordElement(47007)), //
						new DummyRegisterElement(47008), //
						// For wifi+Lan module, to switch to LAN or WiFi communicaiton
						m(Deye.ChannelId.WIFI_OR_LAN_SWITCH, new UnsignedWordElement(47009)), //
						// Ripple Control Receiver on/off
						m(Deye.ChannelId.RIPPLE_CONTROL_RECEIVER_ENABLE, new UnsignedWordElement(47010)), //
						new DummyRegisterElement(47011), //
						m(Deye.ChannelId.LED_BLINK_TIME, new UnsignedWordElement(47012)), //
						// 1: off, 2: on, 3: flash 1x, 4: flash 2x, 5: flash 4x
						m(Deye.ChannelId.WIFI_LED_STATE, new UnsignedWordElement(47013)), //
						m(Deye.ChannelId.COM_LED_STATE, new UnsignedWordElement(47014)), //
						// 1:on 0:off only for single phase Smart meter
						m(Deye.ChannelId.METER_CT1_REVERSE_ENABLE, new UnsignedWordElement(47015)), //
						m(Deye.ChannelId.ERROR_LOG_READ_PAGE, new UnsignedWordElement(47016)), //
						// 1:on 0:off If not connect to Internet, please set 1
						m(Deye.ChannelId.MODBUS_TCP_WITHOUT_INTERNET, new UnsignedWordElement(47017)), //
						// 1: off, 2: on, 3: flash 1x, 4: flash 2x, 5: flash 4x
						m(Deye.ChannelId.BACKUP_LED, new UnsignedWordElement(47018)), // [1,5]
						m(Deye.ChannelId.GRID_LED, new UnsignedWordElement(47019)), // [1,5]
						m(Deye.ChannelId.SOC_LED_1, new UnsignedWordElement(47020)), // [1,5]
						m(Deye.ChannelId.SOC_LED_2, new UnsignedWordElement(47021)), // [1,5]
						m(Deye.ChannelId.SOC_LED_3, new UnsignedWordElement(47022)), // [1,5]
						m(Deye.ChannelId.SOC_LED_4, new UnsignedWordElement(47023)), // [1,5]
						m(Deye.ChannelId.BATTERY_LED, new UnsignedWordElement(47024)), // [1,5]
						m(Deye.ChannelId.SYSTEM_LED, new UnsignedWordElement(47025)), // [1,5]
						m(Deye.ChannelId.FAULT_LED, new UnsignedWordElement(47026)), // [1,5]
						m(Deye.ChannelId.ENERGY_LED, new UnsignedWordElement(47027)), // [1,5]
						m(Deye.ChannelId.LED_EXTERNAL_CONTROL, new UnsignedWordElement(47028)), // [42343]
						new DummyRegisterElement(47029, 47037), //
						// 1 Enable, After restart the inverter, setting saved
						m(Deye.ChannelId.STOP_MODE_SAVE_ENABLE, new UnsignedWordElement(47038)))//
		);
	}

	private void handleDspVersion5(ModbusProtocol protocol) throws OpenemsException {
		// Registers 36000 for COM_MODE throw "Illegal Data Address"

		protocol.addTask(//
				new FC3ReadRegistersTask(36001, Priority.LOW, //
						m(Deye.ChannelId.RSSI, new UnsignedWordElement(36001)), //
						new DummyRegisterElement(36002, 36003), //
						m(Deye.ChannelId.METER_COMMUNICATE_STATUS, new UnsignedWordElement(36004)), //
						// Registers for Grid Smart-Meter (36005 to 36014) are read via GridMeter
						// implementation
						new DummyRegisterElement(36005, 36014),
						m(Deye.ChannelId.E_TOTAL_SELL, new FloatDoublewordElement(36015), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.E_TOTAL_BUY_F, new FloatDoublewordElement(36017), SCALE_FACTOR_MINUS_1), //
						m(Deye.ChannelId.METER_ACTIVE_POWER_R, new SignedDoublewordElement(36019)), //
						m(Deye.ChannelId.METER_ACTIVE_POWER_S, new SignedDoublewordElement(36021)), //
						m(Deye.ChannelId.METER_ACTIVE_POWER_T, new SignedDoublewordElement(36023)), //
						m(Deye.ChannelId.METER_TOTAL_ACTIVE_POWER, new SignedDoublewordElement(36025)), //
						m(Deye.ChannelId.METER_REACTIVE_POWER_R, new SignedDoublewordElement(36027)), //
						m(Deye.ChannelId.METER_REACTIVE_POWER_S, new SignedDoublewordElement(36029)), //
						m(Deye.ChannelId.METER_REACTIVE_POWER_T, new SignedDoublewordElement(36031)), //
						m(Deye.ChannelId.METER_TOTAL_REACTIVE_POWER, new SignedDoublewordElement(36033)), //
						m(Deye.ChannelId.METER_APPARENT_POWER_R, new SignedDoublewordElement(36035)), //
						m(Deye.ChannelId.METER_APPARENT_POWER_S, new SignedDoublewordElement(36037)), //
						m(Deye.ChannelId.METER_APPARENT_POWER_T, new SignedDoublewordElement(36039)), //
						m(Deye.ChannelId.METER_TOTAL_APPARENT_POWER, new SignedDoublewordElement(36041)), //
						// Only for Deye smart meter
						m(Deye.ChannelId.METER_TYPE, new UnsignedWordElement(36043)), //
						m(Deye.ChannelId.METER_SOFTWARE_VERSION, new UnsignedWordElement(36044)), //
						// Only for AC coupled inverter. Detect Pv Meter
						m(Deye.ChannelId.METER_CT2_ACTIVE_POWER, new SignedDoublewordElement(36045)), //
						m(Deye.ChannelId.CT2_E_TOTAL_SELL, new UnsignedDoublewordElement(36047),
								SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.CT2_E_TOTAL_BUY, new UnsignedDoublewordElement(36049), SCALE_FACTOR_MINUS_2), //
						m(Deye.ChannelId.METER_CT2_STATUS, new UnsignedWordElement(36051))) //
		);
	}

	protected ModbusElement getSocModbusElement(int address) throws NotImplementedException {
		if (this instanceof HybridEss) {
			return m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(address), new ElementToChannelConverter(
					// element -> channel
					value -> {
						// Set SoC to undefined if there is No Battery
						EnumReadChannel batteryModeChannel = this.channel(Deye.ChannelId.BATTERY_MODE);
						BatteryMode batteryMode = batteryModeChannel.value().asEnum();
						if (batteryMode == BatteryMode.NO_BATTERY || batteryMode == BatteryMode.UNDEFINED) {
							return null;
						} else {
							return value;
						}
					},
					// channel -> element
					value -> value));
		}
		if (this instanceof HybridManagedSymmetricBatteryInverter) {
			return new DummyRegisterElement(address);
		} else {
			throw new NotImplementedException("Wrong implementation of AbstractDeye");
		}
	}

	@Override
	public final void addCharger(DeyeCharger charger) {
		this.chargers.add(charger);
	}

	@Override
	public final void removeCharger(DeyeCharger charger) {
		this.chargers.remove(charger);
	}

	/**
	 * Gets the PV production from chargers ACTUAL_POWER. Returns null if the PV
	 * production is not available.
	 *
	 * @return production power
	 */
	protected final Integer calculatePvProduction() {
		Integer productionPower = null;
		for (DeyeCharger charger : this.chargers) {
			productionPower = TypeUtils.sum(productionPower, charger.getActualPower().get());
		}
		return productionPower;
	}

	protected void updatePowerAndEnergyChannels() {
		var productionPower = this.calculatePvProduction();
		final Channel<Integer> pBattery1Channel = this.channel(Deye.ChannelId.P_BATTERY1);
		var dcDischargePower = pBattery1Channel.value().get();
		var acActivePower = TypeUtils.sum(productionPower, dcDischargePower);

		/*
		 * Update AC Active Power
		 */
		IntegerReadChannel activePowerChannel = this.channel(this.activePowerChannelId);
		activePowerChannel.setNextValue(acActivePower);

		/*
		 * Calculate AC Energy
		 */
		if (acActivePower == null) {
			// Not available
			this.calculateAcChargeEnergy.update(null);
			this.calculateAcDischargeEnergy.update(null);
		} else if (acActivePower > 0) {
			// Discharge
			this.calculateAcChargeEnergy.update(0);
			this.calculateAcDischargeEnergy.update(acActivePower);
		} else {
			// Charge
			this.calculateAcChargeEnergy.update(acActivePower * -1);
			this.calculateAcDischargeEnergy.update(0);
		}

		/*
		 * Update DC Discharge Power
		 */
		IntegerReadChannel dcDischargePowerChannel = this.channel(this.dcDischargePowerChannelId);
		dcDischargePowerChannel.setNextValue(dcDischargePower);

		/*
		 * Calculate DC Energy
		 */
		if (dcDischargePower == null) {
			// Not available
			this.calculateDcChargeEnergy.update(null);
			this.calculateDcDischargeEnergy.update(null);
		} else if (dcDischargePower > 0) {
			// Discharge
			this.calculateDcChargeEnergy.update(0);
			this.calculateDcDischargeEnergy.update(dcDischargePower);
		} else {
			// Charge
			this.calculateDcChargeEnergy.update(dcDischargePower * -1);
			this.calculateDcDischargeEnergy.update(0);
		}
	}

	/**
	 * Calculate and store Max-AC-Export and -Import channels.
	 *
	 * @param maxApparentPower the max apparent power
	 */
	protected void calculateMaxAcPower(int maxApparentPower) {
		// Calculate and store Max-AC-Export and -Import for use in
		// getStaticConstraints()
		var maxDcChargePower = /* can be negative for force-discharge */
				TypeUtils.multiply(//
						/* Inverter Charge-Max-Current */ this.getWbmsChargeMaxCurrent().get(), //
						/* Voltage */ this.getWbmsVoltage().orElse(0));
		int pvProduction = TypeUtils.max(0, this.calculatePvProduction());

		// Calculates Max-AC-Import and Max-AC-Export as positive numbers
		var maxAcImport = TypeUtils.subtract(maxDcChargePower,
				TypeUtils.min(maxDcChargePower /* avoid negative number for `subtract` */, pvProduction));
		var maxAcExport = TypeUtils.sum(//
				/* Max DC-Discharge-Power */ TypeUtils.multiply(//
						/* Inverter Discharge-Max-Current */ this.getWbmsDischargeMaxCurrent().get(), //
						/* Voltage */ this.getWbmsVoltage().orElse(0)),
				/* PV Production */ pvProduction);

		// Limit Max-AC-Power to inverter specific limit
		maxAcImport = TypeUtils.min(maxAcImport, maxApparentPower);
		maxAcExport = TypeUtils.min(maxAcExport, maxApparentPower);

		// Set Channels
		this._setMaxAcImport(TypeUtils.multiply(maxAcImport, /* negate */ -1));
		this._setMaxAcExport(maxAcExport);
	}

	/**
	 * Detect the current diagnostic high states.
	 * 
	 * @param value register value
	 * @return DiagnosticStates with the information if it is active or not
	 */
	public static Map<Deye.ChannelId, Boolean> detectDiagStatesH(Long value) {
		return DIAG_STATUS_H_STATES.entrySet().stream().collect(
				Collectors.toMap(Map.Entry::getValue, e -> !(Objects.isNull(value) || (value & e.getKey()) == 0)));
	}

	/**
	 * Gets Surplus Power.
	 *
	 * @return {@link Integer}
	 */
	public abstract Integer getSurplusPower();

}
