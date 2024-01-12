package io.openems.edge.deye.sun.hybrid;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.BitsWordElement;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.deye.sun.hybrid.pv.DeyeSunHybridPv;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Constraint;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Deye.Sun.Hybrid", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE, //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS //
})
public class DeyeSunHybridImpl extends AbstractOpenemsModbusComponent
		implements DeyeSunHybrid, ManagedSymmetricEss, SymmetricEss, HybridEss, ModbusComponent,
		OpenemsComponent, EventHandler, ModbusSlave, TimedataProvider {

	protected static final int MAX_APPARENT_POWER = 40000;
	protected static final int NET_CAPACITY = 40000;

	private static final int UNIT_ID = 100;
	private static final int MIN_REACTIVE_POWER = -10000;
	private static final int MAX_REACTIVE_POWER = 10000;

	private final Logger log = LoggerFactory.getLogger(DeyeSunHybridImpl.class);

	private final CalculateEnergyFromPower calculateAcChargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateAcDischargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDcChargeEnergy = new CalculateEnergyFromPower(this,
			HybridEss.ChannelId.DC_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDcDischargeEnergy = new CalculateEnergyFromPower(this,
			HybridEss.ChannelId.DC_DISCHARGE_ENERGY);
	private final List<DeyeSunHybridPv> chargers = new ArrayList<>();
	private final SurplusFeedInHandler surplusFeedInHandler = new SurplusFeedInHandler(this);

	@Reference
	private ComponentManager componentManager;

	@Reference
	private Power power;

	@Reference
	private ConfigurationAdmin cm;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	private Config config;

	public DeyeSunHybridImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				HybridEss.ChannelId.values(), //
				DeyeSunHybrid.SystemErrorChannelId.values(), //
				DeyeSunHybrid.InsufficientGridParametersChannelId.values(), //
				DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.values(), //
				DeyeSunHybrid.ChannelId.values() //
		);
		this._setCapacity(NET_CAPACITY);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (super.activate(context, config.id(), config.alias(), config.enabled(), UNIT_ID, this.cm, "Modbus",
				config.modbus_id())) {
			return;
		}
		this.config = config;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		if (this.config.readOnlyMode()) {
			return;
		}

		IntegerWriteChannel setActivePowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_ACTIVE_POWER);
		setActivePowerChannel.setNextWriteValue(activePower);
		IntegerWriteChannel setReactivePowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_REACTIVE_POWER);
		setReactivePowerChannel.setNextWriteValue(reactivePower);
	}

	@Override
	public String getModbusBridgeId() {
		return this.config.modbus_id();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() throws OpenemsException {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(0x0101, Priority.LOW, //
						m(DeyeSunHybrid.ChannelId.SYSTEM_STATE, new UnsignedWordElement(0x0101)),
						m(DeyeSunHybrid.ChannelId.CONTROL_MODE, new UnsignedWordElement(0x0102)),
						new DummyRegisterElement(0x0103), // WorkMode: RemoteDispatch
						m(DeyeSunHybrid.ChannelId.BATTERY_MAINTENANCE_STATE, new UnsignedWordElement(0x0104)),
						m(DeyeSunHybrid.ChannelId.INVERTER_STATE, new UnsignedWordElement(0x0105)),
						m(SymmetricEss.ChannelId.GRID_MODE, new UnsignedWordElement(0x0106), //
								new ElementToChannelConverter(value -> {
									var intValue = TypeUtils.<Integer>getAsType(OpenemsType.INTEGER, value);
									if (intValue != null) {
										switch (intValue) {
										case 1:
											return GridMode.OFF_GRID;
										case 2:
											return GridMode.ON_GRID;
										}
									}
									return GridMode.UNDEFINED;
								})),
						new DummyRegisterElement(0x0107), //
						m(DeyeSunHybrid.ChannelId.PROTOCOL_VERSION, new UnsignedWordElement(0x0108)),
						m(DeyeSunHybrid.ChannelId.SYSTEM_MANUFACTURER, new UnsignedWordElement(0x0109)),
						m(DeyeSunHybrid.ChannelId.SYSTEM_TYPE, new UnsignedWordElement(0x010A)),
						new DummyRegisterElement(0x010B, 0x010F), //
						m(new BitsWordElement(0x0110, this) //
								.bit(2, DeyeSunHybrid.ChannelId.EMERGENCY_STOP_ACTIVATED) //
								.bit(6, DeyeSunHybrid.ChannelId.KEY_MANUAL_ACTIVATED)),
						m(new BitsWordElement(0x0111, this) //
								.bit(3, DeyeSunHybrid.SystemErrorChannelId.STATE_2) //
								.bit(12, DeyeSunHybrid.SystemErrorChannelId.STATE_3)),
						new DummyRegisterElement(0x0112, 0x0124), //
						m(new BitsWordElement(0x0125, this) //
								.bit(0, DeyeSunHybrid.SystemErrorChannelId.STATE_4) //
								.bit(1, DeyeSunHybrid.SystemErrorChannelId.STATE_5) //
								.bit(2, DeyeSunHybrid.SystemErrorChannelId.STATE_6) //
								.bit(4, DeyeSunHybrid.SystemErrorChannelId.STATE_7) //
								.bit(8, DeyeSunHybrid.SystemErrorChannelId.STATE_8) //
								.bit(9, DeyeSunHybrid.SystemErrorChannelId.STATE_9)),
						m(new BitsWordElement(0x0126, this) //
								.bit(3, DeyeSunHybrid.SystemErrorChannelId.STATE_10)), //
						new DummyRegisterElement(0x0127, 0x014F), //
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_SWITCH_STATE,
								new UnsignedWordElement(0x0150))), //
				new FC3ReadRegistersTask(0x0180, Priority.LOW, //
						m(new BitsWordElement(0x0180, this) //
								.bit(0, DeyeSunHybrid.SystemErrorChannelId.STATE_11) //
								.bit(1, DeyeSunHybrid.SystemErrorChannelId.STATE_12) //
								.bit(2, DeyeSunHybrid.SystemErrorChannelId.STATE_13) //
								.bit(3, DeyeSunHybrid.SystemErrorChannelId.STATE_14) //
								.bit(4, DeyeSunHybrid.SystemErrorChannelId.STATE_15) //
								.bit(5, DeyeSunHybrid.SystemErrorChannelId.STATE_16) //
								.bit(6, DeyeSunHybrid.SystemErrorChannelId.STATE_17) //
								.bit(7, DeyeSunHybrid.SystemErrorChannelId.STATE_18) //
								.bit(8, DeyeSunHybrid.SystemErrorChannelId.STATE_19) //
								.bit(9, DeyeSunHybrid.SystemErrorChannelId.STATE_20) //
								.bit(10, DeyeSunHybrid.SystemErrorChannelId.STATE_21) //
								.bit(11, DeyeSunHybrid.SystemErrorChannelId.STATE_22) //
								.bit(12, DeyeSunHybrid.SystemErrorChannelId.STATE_23)),
						new DummyRegisterElement(0x0181), //
						m(new BitsWordElement(0x0182, this) //
								.bit(0, DeyeSunHybrid.SystemErrorChannelId.STATE_24) //
								.bit(1, DeyeSunHybrid.SystemErrorChannelId.STATE_25) //
								.bit(2, DeyeSunHybrid.SystemErrorChannelId.STATE_26) //
								.bit(3, DeyeSunHybrid.ChannelId.BECU_UNIT_DEFECTIVE) //
								.bit(4, DeyeSunHybrid.SystemErrorChannelId.STATE_28) //
								.bit(5, DeyeSunHybrid.SystemErrorChannelId.STATE_29) //
								.bit(6, DeyeSunHybrid.SystemErrorChannelId.STATE_30) //
								.bit(7, DeyeSunHybrid.SystemErrorChannelId.STATE_31) //
								.bit(8, DeyeSunHybrid.SystemErrorChannelId.STATE_32) //
								.bit(9, DeyeSunHybrid.SystemErrorChannelId.STATE_33) //
								.bit(10, DeyeSunHybrid.SystemErrorChannelId.STATE_34) //
								.bit(11, DeyeSunHybrid.SystemErrorChannelId.STATE_35) //
								.bit(12, DeyeSunHybrid.SystemErrorChannelId.STATE_36) //
								.bit(13, DeyeSunHybrid.SystemErrorChannelId.STATE_37) //
								.bit(14, DeyeSunHybrid.SystemErrorChannelId.STATE_38) //
								.bit(15, DeyeSunHybrid.SystemErrorChannelId.STATE_39)),
						m(new BitsWordElement(0x0183, this) //
								.bit(0, DeyeSunHybrid.SystemErrorChannelId.STATE_40) //
								.bit(1, DeyeSunHybrid.SystemErrorChannelId.STATE_41) //
								.bit(2, DeyeSunHybrid.SystemErrorChannelId.STATE_42) //
								.bit(3, DeyeSunHybrid.SystemErrorChannelId.STATE_43) //
								.bit(4, DeyeSunHybrid.SystemErrorChannelId.STATE_44) //
								.bit(5, DeyeSunHybrid.SystemErrorChannelId.STATE_45) //
								.bit(6, DeyeSunHybrid.SystemErrorChannelId.STATE_46) //
								.bit(7, DeyeSunHybrid.SystemErrorChannelId.STATE_47) //
								.bit(8, DeyeSunHybrid.SystemErrorChannelId.STATE_48) //
								.bit(9, DeyeSunHybrid.SystemErrorChannelId.STATE_49) //
								.bit(10, DeyeSunHybrid.SystemErrorChannelId.STATE_50) //
								.bit(11, DeyeSunHybrid.SystemErrorChannelId.STATE_51) //
								.bit(12, DeyeSunHybrid.SystemErrorChannelId.STATE_52) //
								.bit(13, DeyeSunHybrid.SystemErrorChannelId.STATE_53) //
								.bit(14, DeyeSunHybrid.SystemErrorChannelId.STATE_54) //
								.bit(15, DeyeSunHybrid.SystemErrorChannelId.STATE_55)),
						m(new BitsWordElement(0x0184, this) //
								.bit(0, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_56) //
								.bit(1, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_57) //
								.bit(2, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_58) //
								.bit(3, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_59) //
								.bit(4, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_60) //
								.bit(5, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_61) //
								.bit(6, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_62) //
								.bit(7, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_63) //
								.bit(8, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_64) //
								.bit(9, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_65) //
								.bit(10, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_66) //
								.bit(11, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_67) //
								.bit(12, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_68) //
								.bit(13, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_69) //
								.bit(14, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_70) //
								.bit(15, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_71)),
						m(new BitsWordElement(0x0185, this) //
								.bit(0, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_72) //
								.bit(1, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_73) //
								.bit(2, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_74) //
								.bit(3, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_75) //
								.bit(4, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_76) //
								.bit(5, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_77) //
								.bit(6, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_78) //
								.bit(7, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_79) //
								.bit(8, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_80) //
								.bit(9, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_81) //
								.bit(10, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_82) //
								.bit(11, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_83) //
								.bit(12, DeyeSunHybrid.InsufficientGridParametersChannelId.STATE_84) //
								.bit(13, DeyeSunHybrid.SystemErrorChannelId.STATE_85)),
						m(new BitsWordElement(0x0186, this) //
								.bit(0, DeyeSunHybrid.SystemErrorChannelId.STATE_86) //
								.bit(1, DeyeSunHybrid.SystemErrorChannelId.STATE_87) //
								.bit(2, DeyeSunHybrid.SystemErrorChannelId.STATE_88) //
								.bit(3, DeyeSunHybrid.SystemErrorChannelId.STATE_89) //
								.bit(4, DeyeSunHybrid.SystemErrorChannelId.STATE_90) //
								.bit(5, DeyeSunHybrid.SystemErrorChannelId.STATE_91) //
								.bit(6, DeyeSunHybrid.SystemErrorChannelId.STATE_92) //
								.bit(7, DeyeSunHybrid.SystemErrorChannelId.STATE_93) //
								.bit(8, DeyeSunHybrid.SystemErrorChannelId.STATE_94) //
								.bit(9, DeyeSunHybrid.SystemErrorChannelId.STATE_95) //
								.bit(10, DeyeSunHybrid.SystemErrorChannelId.STATE_96) //
								.bit(11, DeyeSunHybrid.SystemErrorChannelId.STATE_97) //
								.bit(12, DeyeSunHybrid.SystemErrorChannelId.STATE_98) //
								.bit(13, DeyeSunHybrid.SystemErrorChannelId.STATE_99) //
								.bit(14, DeyeSunHybrid.SystemErrorChannelId.STATE_100) //
								.bit(15, DeyeSunHybrid.SystemErrorChannelId.STATE_101)),
						m(new BitsWordElement(0x0187, this) //
								.bit(0, DeyeSunHybrid.SystemErrorChannelId.STATE_102) //
								.bit(1, DeyeSunHybrid.SystemErrorChannelId.STATE_103) //
								.bit(2, DeyeSunHybrid.SystemErrorChannelId.STATE_104) //
								.bit(3, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_105) //
								.bit(4, DeyeSunHybrid.SystemErrorChannelId.STATE_106) //
								.bit(5, DeyeSunHybrid.SystemErrorChannelId.STATE_107) //
								.bit(6, DeyeSunHybrid.SystemErrorChannelId.STATE_108) //
								.bit(7, DeyeSunHybrid.SystemErrorChannelId.STATE_109) //
								.bit(8, DeyeSunHybrid.SystemErrorChannelId.STATE_110) //
								.bit(9, DeyeSunHybrid.SystemErrorChannelId.STATE_111) //
								.bit(10, DeyeSunHybrid.SystemErrorChannelId.STATE_112) //
								.bit(11, DeyeSunHybrid.SystemErrorChannelId.STATE_113) //
								.bit(12, DeyeSunHybrid.SystemErrorChannelId.STATE_114) //
								.bit(13, DeyeSunHybrid.SystemErrorChannelId.STATE_115) //
								.bit(14, DeyeSunHybrid.SystemErrorChannelId.STATE_116)),
						m(new BitsWordElement(0x0188, this) //
								.bit(0, DeyeSunHybrid.SystemErrorChannelId.STATE_117) //
								.bit(1, DeyeSunHybrid.SystemErrorChannelId.STATE_118) //
								.bit(2, DeyeSunHybrid.SystemErrorChannelId.STATE_119) //
								.bit(3, DeyeSunHybrid.SystemErrorChannelId.STATE_120) //
								.bit(4, DeyeSunHybrid.SystemErrorChannelId.STATE_121) //
								.bit(5, DeyeSunHybrid.SystemErrorChannelId.STATE_122) //
								.bit(6, DeyeSunHybrid.SystemErrorChannelId.STATE_123) //
								.bit(14, DeyeSunHybrid.SystemErrorChannelId.STATE_124))),
				new FC3ReadRegistersTask(0x0200, Priority.HIGH, //
						m(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE, new SignedWordElement(0x0200),
								SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.BATTERY_CURRENT, new SignedWordElement(0x0201),
								SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.BATTERY_POWER, new SignedWordElement(0x0202),
								SCALE_FACTOR_2), //
						new DummyRegisterElement(0x0203, 0x0207),
						m(DeyeSunHybrid.ChannelId.ORIGINAL_ACTIVE_CHARGE_ENERGY,
								new UnsignedDoublewordElement(0x0208).wordOrder(WordOrder.LSWMSW), SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.ORIGINAL_ACTIVE_DISCHARGE_ENERGY,
								new UnsignedDoublewordElement(0x020A).wordOrder(WordOrder.LSWMSW), SCALE_FACTOR_2), //
						new DummyRegisterElement(0x020C, 0x020F), //
						m(DeyeSunHybrid.ChannelId.GRID_ACTIVE_POWER, new SignedWordElement(0x0210),
								SCALE_FACTOR_2), //
						m(SymmetricEss.ChannelId.REACTIVE_POWER, new SignedWordElement(0x0211), SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.APPARENT_POWER, new UnsignedWordElement(0x0212),
								SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.CURRENT_L1, new SignedWordElement(0x0213), SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.CURRENT_L2, new SignedWordElement(0x0214), SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.CURRENT_L3, new SignedWordElement(0x0215), SCALE_FACTOR_2), //
						new DummyRegisterElement(0x0216, 0x218), //
						m(DeyeSunHybrid.ChannelId.VOLTAGE_L1, new UnsignedWordElement(0x0219), SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.VOLTAGE_L2, new UnsignedWordElement(0x021A), SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.VOLTAGE_L3, new UnsignedWordElement(0x021B), SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.FREQUENCY, new UnsignedWordElement(0x021C)), //
						new DummyRegisterElement(0x021D, 0x0221), //
						m(DeyeSunHybrid.ChannelId.INVERTER_VOLTAGE_L1, new UnsignedWordElement(0x0222),
								SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.INVERTER_VOLTAGE_L2, new UnsignedWordElement(0x0223),
								SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.INVERTER_VOLTAGE_L3, new UnsignedWordElement(0x0224),
								SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.INVERTER_CURRENT_L1, new SignedWordElement(0x0225),
								SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.INVERTER_CURRENT_L2, new SignedWordElement(0x0226),
								SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.INVERTER_CURRENT_L3, new SignedWordElement(0x0227),
								SCALE_FACTOR_2), //
						m(SymmetricEss.ChannelId.ACTIVE_POWER, new SignedWordElement(0x0228), SCALE_FACTOR_2), //
						new DummyRegisterElement(0x0229, 0x022F), //
						m(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER, new SignedWordElement(0x0230),
								SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER,
								new UnsignedWordElement(0x0231), SCALE_FACTOR_2), //
						m(SymmetricEss.ChannelId.MAX_APPARENT_POWER, new UnsignedWordElement(0x0232), SCALE_FACTOR_2), //
						new DummyRegisterElement(0x0233, 0x23F),
						m(DeyeSunHybrid.ChannelId.IPM_TEMPERATURE_L1, new SignedWordElement(0x0240)), //
						m(DeyeSunHybrid.ChannelId.IPM_TEMPERATURE_L2, new SignedWordElement(0x0241)), //
						m(DeyeSunHybrid.ChannelId.IPM_TEMPERATURE_L3, new SignedWordElement(0x0242)), //
						new DummyRegisterElement(0x0243, 0x0248), //
						m(DeyeSunHybrid.ChannelId.TRANSFORMER_TEMPERATURE_L2, new SignedWordElement(0x0249))), //
				new FC16WriteRegistersTask(0x0500, //
						m(DeyeSunHybrid.ChannelId.SET_WORK_STATE, new UnsignedWordElement(0x0500))), //
				new FC16WriteRegistersTask(0x0501, //
						m(DeyeSunHybrid.ChannelId.SET_ACTIVE_POWER, new SignedWordElement(0x0501),
								SCALE_FACTOR_2), //
						m(DeyeSunHybrid.ChannelId.SET_REACTIVE_POWER, new SignedWordElement(0x0502),
								SCALE_FACTOR_2)), //
				new FC3ReadRegistersTask(0xA000, Priority.LOW, //
						m(DeyeSunHybrid.ChannelId.BMS_DCDC_WORK_STATE, new UnsignedWordElement(0xA000)), //
						m(DeyeSunHybrid.ChannelId.BMS_DCDC_WORK_MODE, new UnsignedWordElement(0xA001))), //
				new FC3ReadRegistersTask(0xA100, Priority.LOW, //
						m(new BitsWordElement(0xA100, this) //
								.bit(0, DeyeSunHybrid.SystemErrorChannelId.STATE_125) //
								.bit(1, DeyeSunHybrid.SystemErrorChannelId.STATE_126) //
								.bit(6, DeyeSunHybrid.SystemErrorChannelId.STATE_127) //
								.bit(7, DeyeSunHybrid.SystemErrorChannelId.STATE_128) //
								.bit(8, DeyeSunHybrid.SystemErrorChannelId.STATE_129) //
								.bit(9, DeyeSunHybrid.SystemErrorChannelId.STATE_130)),
						m(new BitsWordElement(0xA101, this) //
								.bit(0, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_131) //
								.bit(1, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_132) //
								.bit(2, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_133) //
								.bit(3, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_134) //
								.bit(4, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_135) //
								.bit(5, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_136) //
								.bit(6, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_137) //
								.bit(7, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_138) //
								.bit(8, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_139) //
								.bit(9, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_140) //
								.bit(10, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_141) //
								.bit(11, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_142) //
								.bit(12, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_143) //
								.bit(13, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_144) //
								.bit(14, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_145) //
								.bit(15, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.STATE_146)),
						m(new BitsWordElement(0xA102, this) //
								.bit(0, DeyeSunHybrid.SystemErrorChannelId.STATE_147) //
								.bit(1, DeyeSunHybrid.SystemErrorChannelId.STATE_148) //
								.bit(2, DeyeSunHybrid.SystemErrorChannelId.STATE_149))),
				new FC3ReadRegistersTask(0x1500, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.CELL_1_VOLTAGE, new UnsignedWordElement(0x1500)),
						m(DeyeSunHybrid.ChannelId.CELL_2_VOLTAGE, new UnsignedWordElement(0x1501)),
						m(DeyeSunHybrid.ChannelId.CELL_3_VOLTAGE, new UnsignedWordElement(0x1502)),
						m(DeyeSunHybrid.ChannelId.CELL_4_VOLTAGE, new UnsignedWordElement(0x1503)),
						m(DeyeSunHybrid.ChannelId.CELL_5_VOLTAGE, new UnsignedWordElement(0x1504)),
						m(DeyeSunHybrid.ChannelId.CELL_6_VOLTAGE, new UnsignedWordElement(0x1505)),
						m(DeyeSunHybrid.ChannelId.CELL_7_VOLTAGE, new UnsignedWordElement(0x1506)),
						m(DeyeSunHybrid.ChannelId.CELL_8_VOLTAGE, new UnsignedWordElement(0x1507)),
						m(DeyeSunHybrid.ChannelId.CELL_9_VOLTAGE, new UnsignedWordElement(0x1508)),
						m(DeyeSunHybrid.ChannelId.CELL_10_VOLTAGE, new UnsignedWordElement(0x1509)),
						m(DeyeSunHybrid.ChannelId.CELL_11_VOLTAGE, new UnsignedWordElement(0x150A)),
						m(DeyeSunHybrid.ChannelId.CELL_12_VOLTAGE, new UnsignedWordElement(0x150B)),
						m(DeyeSunHybrid.ChannelId.CELL_13_VOLTAGE, new UnsignedWordElement(0x150C)),
						m(DeyeSunHybrid.ChannelId.CELL_14_VOLTAGE, new UnsignedWordElement(0x150D)),
						m(DeyeSunHybrid.ChannelId.CELL_15_VOLTAGE, new UnsignedWordElement(0x150E)),
						m(DeyeSunHybrid.ChannelId.CELL_16_VOLTAGE, new UnsignedWordElement(0x150F)),
						m(DeyeSunHybrid.ChannelId.CELL_17_VOLTAGE, new UnsignedWordElement(0x1510)),
						m(DeyeSunHybrid.ChannelId.CELL_18_VOLTAGE, new UnsignedWordElement(0x1511)),
						m(DeyeSunHybrid.ChannelId.CELL_19_VOLTAGE, new UnsignedWordElement(0x1512)),
						m(DeyeSunHybrid.ChannelId.CELL_20_VOLTAGE, new UnsignedWordElement(0x1513)),
						m(DeyeSunHybrid.ChannelId.CELL_21_VOLTAGE, new UnsignedWordElement(0x1514)),
						m(DeyeSunHybrid.ChannelId.CELL_22_VOLTAGE, new UnsignedWordElement(0x1515)),
						m(DeyeSunHybrid.ChannelId.CELL_23_VOLTAGE, new UnsignedWordElement(0x1516)),
						m(DeyeSunHybrid.ChannelId.CELL_24_VOLTAGE, new UnsignedWordElement(0x1517)),
						m(DeyeSunHybrid.ChannelId.CELL_25_VOLTAGE, new UnsignedWordElement(0x1518)),
						m(DeyeSunHybrid.ChannelId.CELL_26_VOLTAGE, new UnsignedWordElement(0x1519)),
						m(DeyeSunHybrid.ChannelId.CELL_27_VOLTAGE, new UnsignedWordElement(0x151A)),
						m(DeyeSunHybrid.ChannelId.CELL_28_VOLTAGE, new UnsignedWordElement(0x151B)),
						m(DeyeSunHybrid.ChannelId.CELL_29_VOLTAGE, new UnsignedWordElement(0x151C)),
						m(DeyeSunHybrid.ChannelId.CELL_30_VOLTAGE, new UnsignedWordElement(0x151D)),
						m(DeyeSunHybrid.ChannelId.CELL_31_VOLTAGE, new UnsignedWordElement(0x151E)),
						m(DeyeSunHybrid.ChannelId.CELL_32_VOLTAGE, new UnsignedWordElement(0x151F)),
						m(DeyeSunHybrid.ChannelId.CELL_33_VOLTAGE, new UnsignedWordElement(0x1520)),
						m(DeyeSunHybrid.ChannelId.CELL_34_VOLTAGE, new UnsignedWordElement(0x1521)),
						m(DeyeSunHybrid.ChannelId.CELL_35_VOLTAGE, new UnsignedWordElement(0x1522)),
						m(DeyeSunHybrid.ChannelId.CELL_36_VOLTAGE, new UnsignedWordElement(0x1523)),
						m(DeyeSunHybrid.ChannelId.CELL_37_VOLTAGE, new UnsignedWordElement(0x1524)),
						m(DeyeSunHybrid.ChannelId.CELL_38_VOLTAGE, new UnsignedWordElement(0x1525)),
						m(DeyeSunHybrid.ChannelId.CELL_39_VOLTAGE, new UnsignedWordElement(0x1526)),
						m(DeyeSunHybrid.ChannelId.CELL_40_VOLTAGE, new UnsignedWordElement(0x1527)),
						m(DeyeSunHybrid.ChannelId.CELL_41_VOLTAGE, new UnsignedWordElement(0x1528)),
						m(DeyeSunHybrid.ChannelId.CELL_42_VOLTAGE, new UnsignedWordElement(0x1529)),
						m(DeyeSunHybrid.ChannelId.CELL_43_VOLTAGE, new UnsignedWordElement(0x152A)),
						m(DeyeSunHybrid.ChannelId.CELL_44_VOLTAGE, new UnsignedWordElement(0x152B)),
						m(DeyeSunHybrid.ChannelId.CELL_45_VOLTAGE, new UnsignedWordElement(0x152C)),
						m(DeyeSunHybrid.ChannelId.CELL_46_VOLTAGE, new UnsignedWordElement(0x152D)),
						m(DeyeSunHybrid.ChannelId.CELL_47_VOLTAGE, new UnsignedWordElement(0x152E)),
						m(DeyeSunHybrid.ChannelId.CELL_48_VOLTAGE, new UnsignedWordElement(0x152F)),
						m(DeyeSunHybrid.ChannelId.CELL_49_VOLTAGE, new UnsignedWordElement(0x1530)),
						m(DeyeSunHybrid.ChannelId.CELL_50_VOLTAGE, new UnsignedWordElement(0x1531)),
						m(DeyeSunHybrid.ChannelId.CELL_51_VOLTAGE, new UnsignedWordElement(0x1532)),
						m(DeyeSunHybrid.ChannelId.CELL_52_VOLTAGE, new UnsignedWordElement(0x1533)),
						m(DeyeSunHybrid.ChannelId.CELL_53_VOLTAGE, new UnsignedWordElement(0x1534)),
						m(DeyeSunHybrid.ChannelId.CELL_54_VOLTAGE, new UnsignedWordElement(0x1535)),
						m(DeyeSunHybrid.ChannelId.CELL_55_VOLTAGE, new UnsignedWordElement(0x1536)),
						m(DeyeSunHybrid.ChannelId.CELL_56_VOLTAGE, new UnsignedWordElement(0x1537)),
						m(DeyeSunHybrid.ChannelId.CELL_57_VOLTAGE, new UnsignedWordElement(0x1538)),
						m(DeyeSunHybrid.ChannelId.CELL_58_VOLTAGE, new UnsignedWordElement(0x1539)),
						m(DeyeSunHybrid.ChannelId.CELL_59_VOLTAGE, new UnsignedWordElement(0x153A)),
						m(DeyeSunHybrid.ChannelId.CELL_60_VOLTAGE, new UnsignedWordElement(0x153B)),
						m(DeyeSunHybrid.ChannelId.CELL_61_VOLTAGE, new UnsignedWordElement(0x153C)),
						m(DeyeSunHybrid.ChannelId.CELL_62_VOLTAGE, new UnsignedWordElement(0x153D)),
						m(DeyeSunHybrid.ChannelId.CELL_63_VOLTAGE, new UnsignedWordElement(0x153E)),
						m(DeyeSunHybrid.ChannelId.CELL_64_VOLTAGE, new UnsignedWordElement(0x153F)),
						m(DeyeSunHybrid.ChannelId.CELL_65_VOLTAGE, new UnsignedWordElement(0x1540)),
						m(DeyeSunHybrid.ChannelId.CELL_66_VOLTAGE, new UnsignedWordElement(0x1541)),
						m(DeyeSunHybrid.ChannelId.CELL_67_VOLTAGE, new UnsignedWordElement(0x1542)),
						m(DeyeSunHybrid.ChannelId.CELL_68_VOLTAGE, new UnsignedWordElement(0x1543)),
						m(DeyeSunHybrid.ChannelId.CELL_69_VOLTAGE, new UnsignedWordElement(0x1544)),
						m(DeyeSunHybrid.ChannelId.CELL_70_VOLTAGE, new UnsignedWordElement(0x1545)),
						m(DeyeSunHybrid.ChannelId.CELL_71_VOLTAGE, new UnsignedWordElement(0x1546)),
						m(DeyeSunHybrid.ChannelId.CELL_72_VOLTAGE, new UnsignedWordElement(0x1547)),
						m(DeyeSunHybrid.ChannelId.CELL_73_VOLTAGE, new UnsignedWordElement(0x1548)),
						m(DeyeSunHybrid.ChannelId.CELL_74_VOLTAGE, new UnsignedWordElement(0x1549)),
						m(DeyeSunHybrid.ChannelId.CELL_75_VOLTAGE, new UnsignedWordElement(0x154A)),
						m(DeyeSunHybrid.ChannelId.CELL_76_VOLTAGE, new UnsignedWordElement(0x154B)),
						m(DeyeSunHybrid.ChannelId.CELL_77_VOLTAGE, new UnsignedWordElement(0x154C)),
						m(DeyeSunHybrid.ChannelId.CELL_78_VOLTAGE, new UnsignedWordElement(0x154D)),
						m(DeyeSunHybrid.ChannelId.CELL_79_VOLTAGE, new UnsignedWordElement(0x154E)),
						m(DeyeSunHybrid.ChannelId.CELL_80_VOLTAGE, new UnsignedWordElement(0x154F))),
				new FC3ReadRegistersTask(0x1550, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.CELL_81_VOLTAGE, new UnsignedWordElement(0x1550)),
						m(DeyeSunHybrid.ChannelId.CELL_82_VOLTAGE, new UnsignedWordElement(0x1551)),
						m(DeyeSunHybrid.ChannelId.CELL_83_VOLTAGE, new UnsignedWordElement(0x1552)),
						m(DeyeSunHybrid.ChannelId.CELL_84_VOLTAGE, new UnsignedWordElement(0x1553)),
						m(DeyeSunHybrid.ChannelId.CELL_85_VOLTAGE, new UnsignedWordElement(0x1554)),
						m(DeyeSunHybrid.ChannelId.CELL_86_VOLTAGE, new UnsignedWordElement(0x1555)),
						m(DeyeSunHybrid.ChannelId.CELL_87_VOLTAGE, new UnsignedWordElement(0x1556)),
						m(DeyeSunHybrid.ChannelId.CELL_88_VOLTAGE, new UnsignedWordElement(0x1557)),
						m(DeyeSunHybrid.ChannelId.CELL_89_VOLTAGE, new UnsignedWordElement(0x1558)),
						m(DeyeSunHybrid.ChannelId.CELL_90_VOLTAGE, new UnsignedWordElement(0x1559)),
						m(DeyeSunHybrid.ChannelId.CELL_91_VOLTAGE, new UnsignedWordElement(0x155A)),
						m(DeyeSunHybrid.ChannelId.CELL_92_VOLTAGE, new UnsignedWordElement(0x155B)),
						m(DeyeSunHybrid.ChannelId.CELL_93_VOLTAGE, new UnsignedWordElement(0x155C)),
						m(DeyeSunHybrid.ChannelId.CELL_94_VOLTAGE, new UnsignedWordElement(0x155D)),
						m(DeyeSunHybrid.ChannelId.CELL_95_VOLTAGE, new UnsignedWordElement(0x155E)),
						m(DeyeSunHybrid.ChannelId.CELL_96_VOLTAGE, new UnsignedWordElement(0x155F)),
						m(DeyeSunHybrid.ChannelId.CELL_97_VOLTAGE, new UnsignedWordElement(0x1560)),
						m(DeyeSunHybrid.ChannelId.CELL_98_VOLTAGE, new UnsignedWordElement(0x1561)),
						m(DeyeSunHybrid.ChannelId.CELL_99_VOLTAGE, new UnsignedWordElement(0x1562)),
						m(DeyeSunHybrid.ChannelId.CELL_100_VOLTAGE, new UnsignedWordElement(0x1563)),
						m(DeyeSunHybrid.ChannelId.CELL_101_VOLTAGE, new UnsignedWordElement(0x1564)),
						m(DeyeSunHybrid.ChannelId.CELL_102_VOLTAGE, new UnsignedWordElement(0x1565)),
						m(DeyeSunHybrid.ChannelId.CELL_103_VOLTAGE, new UnsignedWordElement(0x1566)),
						m(DeyeSunHybrid.ChannelId.CELL_104_VOLTAGE, new UnsignedWordElement(0x1567)),
						m(DeyeSunHybrid.ChannelId.CELL_105_VOLTAGE, new UnsignedWordElement(0x1568)),
						m(DeyeSunHybrid.ChannelId.CELL_106_VOLTAGE, new UnsignedWordElement(0x1569)),
						m(DeyeSunHybrid.ChannelId.CELL_107_VOLTAGE, new UnsignedWordElement(0x156A)),
						m(DeyeSunHybrid.ChannelId.CELL_108_VOLTAGE, new UnsignedWordElement(0x156B)),
						m(DeyeSunHybrid.ChannelId.CELL_109_VOLTAGE, new UnsignedWordElement(0x156C)),
						m(DeyeSunHybrid.ChannelId.CELL_110_VOLTAGE, new UnsignedWordElement(0x156D)),
						m(DeyeSunHybrid.ChannelId.CELL_111_VOLTAGE, new UnsignedWordElement(0x156E)),
						m(DeyeSunHybrid.ChannelId.CELL_112_VOLTAGE, new UnsignedWordElement(0x156F)),
						m(DeyeSunHybrid.ChannelId.CELL_113_VOLTAGE, new UnsignedWordElement(0x1570)),
						m(DeyeSunHybrid.ChannelId.CELL_114_VOLTAGE, new UnsignedWordElement(0x1571)),
						m(DeyeSunHybrid.ChannelId.CELL_115_VOLTAGE, new UnsignedWordElement(0x1572)),
						m(DeyeSunHybrid.ChannelId.CELL_116_VOLTAGE, new UnsignedWordElement(0x1573)),
						m(DeyeSunHybrid.ChannelId.CELL_117_VOLTAGE, new UnsignedWordElement(0x1574)),
						m(DeyeSunHybrid.ChannelId.CELL_118_VOLTAGE, new UnsignedWordElement(0x1575)),
						m(DeyeSunHybrid.ChannelId.CELL_119_VOLTAGE, new UnsignedWordElement(0x1576)),
						m(DeyeSunHybrid.ChannelId.CELL_120_VOLTAGE, new UnsignedWordElement(0x1577)),
						m(DeyeSunHybrid.ChannelId.CELL_121_VOLTAGE, new UnsignedWordElement(0x1578)),
						m(DeyeSunHybrid.ChannelId.CELL_122_VOLTAGE, new UnsignedWordElement(0x1579)),
						m(DeyeSunHybrid.ChannelId.CELL_123_VOLTAGE, new UnsignedWordElement(0x157A)),
						m(DeyeSunHybrid.ChannelId.CELL_124_VOLTAGE, new UnsignedWordElement(0x157B)),
						m(DeyeSunHybrid.ChannelId.CELL_125_VOLTAGE, new UnsignedWordElement(0x157C)),
						m(DeyeSunHybrid.ChannelId.CELL_126_VOLTAGE, new UnsignedWordElement(0x157D)),
						m(DeyeSunHybrid.ChannelId.CELL_127_VOLTAGE, new UnsignedWordElement(0x157E)),
						m(DeyeSunHybrid.ChannelId.CELL_128_VOLTAGE, new UnsignedWordElement(0x157F)),
						m(DeyeSunHybrid.ChannelId.CELL_129_VOLTAGE, new UnsignedWordElement(0x1580)),
						m(DeyeSunHybrid.ChannelId.CELL_130_VOLTAGE, new UnsignedWordElement(0x1581)),
						m(DeyeSunHybrid.ChannelId.CELL_131_VOLTAGE, new UnsignedWordElement(0x1582)),
						m(DeyeSunHybrid.ChannelId.CELL_132_VOLTAGE, new UnsignedWordElement(0x1583)),
						m(DeyeSunHybrid.ChannelId.CELL_133_VOLTAGE, new UnsignedWordElement(0x1584)),
						m(DeyeSunHybrid.ChannelId.CELL_134_VOLTAGE, new UnsignedWordElement(0x1585)),
						m(DeyeSunHybrid.ChannelId.CELL_135_VOLTAGE, new UnsignedWordElement(0x1586)),
						m(DeyeSunHybrid.ChannelId.CELL_136_VOLTAGE, new UnsignedWordElement(0x1587)),
						m(DeyeSunHybrid.ChannelId.CELL_137_VOLTAGE, new UnsignedWordElement(0x1588)),
						m(DeyeSunHybrid.ChannelId.CELL_138_VOLTAGE, new UnsignedWordElement(0x1589)),
						m(DeyeSunHybrid.ChannelId.CELL_139_VOLTAGE, new UnsignedWordElement(0x158A)),
						m(DeyeSunHybrid.ChannelId.CELL_140_VOLTAGE, new UnsignedWordElement(0x158B)),
						m(DeyeSunHybrid.ChannelId.CELL_141_VOLTAGE, new UnsignedWordElement(0x158C)),
						m(DeyeSunHybrid.ChannelId.CELL_142_VOLTAGE, new UnsignedWordElement(0x158D)),
						m(DeyeSunHybrid.ChannelId.CELL_143_VOLTAGE, new UnsignedWordElement(0x158E)),
						m(DeyeSunHybrid.ChannelId.CELL_144_VOLTAGE, new UnsignedWordElement(0x158F)),
						m(DeyeSunHybrid.ChannelId.CELL_145_VOLTAGE, new UnsignedWordElement(0x1590)),
						m(DeyeSunHybrid.ChannelId.CELL_146_VOLTAGE, new UnsignedWordElement(0x1591)),
						m(DeyeSunHybrid.ChannelId.CELL_147_VOLTAGE, new UnsignedWordElement(0x1592)),
						m(DeyeSunHybrid.ChannelId.CELL_148_VOLTAGE, new UnsignedWordElement(0x1593)),
						m(DeyeSunHybrid.ChannelId.CELL_149_VOLTAGE, new UnsignedWordElement(0x1594)),
						m(DeyeSunHybrid.ChannelId.CELL_150_VOLTAGE, new UnsignedWordElement(0x1595)),
						m(DeyeSunHybrid.ChannelId.CELL_151_VOLTAGE, new UnsignedWordElement(0x1596)),
						m(DeyeSunHybrid.ChannelId.CELL_152_VOLTAGE, new UnsignedWordElement(0x1597)),
						m(DeyeSunHybrid.ChannelId.CELL_153_VOLTAGE, new UnsignedWordElement(0x1598)),
						m(DeyeSunHybrid.ChannelId.CELL_154_VOLTAGE, new UnsignedWordElement(0x1599)),
						m(DeyeSunHybrid.ChannelId.CELL_155_VOLTAGE, new UnsignedWordElement(0x159A)),
						m(DeyeSunHybrid.ChannelId.CELL_156_VOLTAGE, new UnsignedWordElement(0x159B)),
						m(DeyeSunHybrid.ChannelId.CELL_157_VOLTAGE, new UnsignedWordElement(0x159C)),
						m(DeyeSunHybrid.ChannelId.CELL_158_VOLTAGE, new UnsignedWordElement(0x159D)),
						m(DeyeSunHybrid.ChannelId.CELL_159_VOLTAGE, new UnsignedWordElement(0x159E)),
						m(DeyeSunHybrid.ChannelId.CELL_160_VOLTAGE, new UnsignedWordElement(0x159F))),
				new FC3ReadRegistersTask(0x15A0, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.CELL_161_VOLTAGE, new UnsignedWordElement(0x15A0)),
						m(DeyeSunHybrid.ChannelId.CELL_162_VOLTAGE, new UnsignedWordElement(0x15A1)),
						m(DeyeSunHybrid.ChannelId.CELL_163_VOLTAGE, new UnsignedWordElement(0x15A2)),
						m(DeyeSunHybrid.ChannelId.CELL_164_VOLTAGE, new UnsignedWordElement(0x15A3)),
						m(DeyeSunHybrid.ChannelId.CELL_165_VOLTAGE, new UnsignedWordElement(0x15A4)),
						m(DeyeSunHybrid.ChannelId.CELL_166_VOLTAGE, new UnsignedWordElement(0x15A5)),
						m(DeyeSunHybrid.ChannelId.CELL_167_VOLTAGE, new UnsignedWordElement(0x15A6)),
						m(DeyeSunHybrid.ChannelId.CELL_168_VOLTAGE, new UnsignedWordElement(0x15A7)),
						m(DeyeSunHybrid.ChannelId.CELL_169_VOLTAGE, new UnsignedWordElement(0x15A8)),
						m(DeyeSunHybrid.ChannelId.CELL_170_VOLTAGE, new UnsignedWordElement(0x15A9)),
						m(DeyeSunHybrid.ChannelId.CELL_171_VOLTAGE, new UnsignedWordElement(0x15AA)),
						m(DeyeSunHybrid.ChannelId.CELL_172_VOLTAGE, new UnsignedWordElement(0x15AB)),
						m(DeyeSunHybrid.ChannelId.CELL_173_VOLTAGE, new UnsignedWordElement(0x15AC)),
						m(DeyeSunHybrid.ChannelId.CELL_174_VOLTAGE, new UnsignedWordElement(0x15AD)),
						m(DeyeSunHybrid.ChannelId.CELL_175_VOLTAGE, new UnsignedWordElement(0x15AE)),
						m(DeyeSunHybrid.ChannelId.CELL_176_VOLTAGE, new UnsignedWordElement(0x15AF)),
						m(DeyeSunHybrid.ChannelId.CELL_177_VOLTAGE, new UnsignedWordElement(0x15B0)),
						m(DeyeSunHybrid.ChannelId.CELL_178_VOLTAGE, new UnsignedWordElement(0x15B1)),
						m(DeyeSunHybrid.ChannelId.CELL_179_VOLTAGE, new UnsignedWordElement(0x15B2)),
						m(DeyeSunHybrid.ChannelId.CELL_180_VOLTAGE, new UnsignedWordElement(0x15B3)),
						m(DeyeSunHybrid.ChannelId.CELL_181_VOLTAGE, new UnsignedWordElement(0x15B4)),
						m(DeyeSunHybrid.ChannelId.CELL_182_VOLTAGE, new UnsignedWordElement(0x15B5)),
						m(DeyeSunHybrid.ChannelId.CELL_183_VOLTAGE, new UnsignedWordElement(0x15B6)),
						m(DeyeSunHybrid.ChannelId.CELL_184_VOLTAGE, new UnsignedWordElement(0x15B7)),
						m(DeyeSunHybrid.ChannelId.CELL_185_VOLTAGE, new UnsignedWordElement(0x15B8)),
						m(DeyeSunHybrid.ChannelId.CELL_186_VOLTAGE, new UnsignedWordElement(0x15B9)),
						m(DeyeSunHybrid.ChannelId.CELL_187_VOLTAGE, new UnsignedWordElement(0x15BA)),
						m(DeyeSunHybrid.ChannelId.CELL_188_VOLTAGE, new UnsignedWordElement(0x15BB)),
						m(DeyeSunHybrid.ChannelId.CELL_189_VOLTAGE, new UnsignedWordElement(0x15BC)),
						m(DeyeSunHybrid.ChannelId.CELL_190_VOLTAGE, new UnsignedWordElement(0x15BD)),
						m(DeyeSunHybrid.ChannelId.CELL_191_VOLTAGE, new UnsignedWordElement(0x15BE)),
						m(DeyeSunHybrid.ChannelId.CELL_192_VOLTAGE, new UnsignedWordElement(0x15BF)),
						m(DeyeSunHybrid.ChannelId.CELL_193_VOLTAGE, new UnsignedWordElement(0x15C0)),
						m(DeyeSunHybrid.ChannelId.CELL_194_VOLTAGE, new UnsignedWordElement(0x15C1)),
						m(DeyeSunHybrid.ChannelId.CELL_195_VOLTAGE, new UnsignedWordElement(0x15C2)),
						m(DeyeSunHybrid.ChannelId.CELL_196_VOLTAGE, new UnsignedWordElement(0x15C3)),
						m(DeyeSunHybrid.ChannelId.CELL_197_VOLTAGE, new UnsignedWordElement(0x15C4)),
						m(DeyeSunHybrid.ChannelId.CELL_198_VOLTAGE, new UnsignedWordElement(0x15C5)),
						m(DeyeSunHybrid.ChannelId.CELL_199_VOLTAGE, new UnsignedWordElement(0x15C6)),
						m(DeyeSunHybrid.ChannelId.CELL_200_VOLTAGE, new UnsignedWordElement(0x15C7)),
						m(DeyeSunHybrid.ChannelId.CELL_201_VOLTAGE, new UnsignedWordElement(0x15C8)),
						m(DeyeSunHybrid.ChannelId.CELL_202_VOLTAGE, new UnsignedWordElement(0x15C9)),
						m(DeyeSunHybrid.ChannelId.CELL_203_VOLTAGE, new UnsignedWordElement(0x15CA)),
						m(DeyeSunHybrid.ChannelId.CELL_204_VOLTAGE, new UnsignedWordElement(0x15CB)),
						m(DeyeSunHybrid.ChannelId.CELL_205_VOLTAGE, new UnsignedWordElement(0x15CC)),
						m(DeyeSunHybrid.ChannelId.CELL_206_VOLTAGE, new UnsignedWordElement(0x15CD)),
						m(DeyeSunHybrid.ChannelId.CELL_207_VOLTAGE, new UnsignedWordElement(0x15CE)),
						m(DeyeSunHybrid.ChannelId.CELL_208_VOLTAGE, new UnsignedWordElement(0x15CF)),
						m(DeyeSunHybrid.ChannelId.CELL_209_VOLTAGE, new UnsignedWordElement(0x15D0)),
						m(DeyeSunHybrid.ChannelId.CELL_210_VOLTAGE, new UnsignedWordElement(0x15D1)),
						m(DeyeSunHybrid.ChannelId.CELL_211_VOLTAGE, new UnsignedWordElement(0x15D2)),
						m(DeyeSunHybrid.ChannelId.CELL_212_VOLTAGE, new UnsignedWordElement(0x15D3)),
						m(DeyeSunHybrid.ChannelId.CELL_213_VOLTAGE, new UnsignedWordElement(0x15D4)),
						m(DeyeSunHybrid.ChannelId.CELL_214_VOLTAGE, new UnsignedWordElement(0x15D5)),
						m(DeyeSunHybrid.ChannelId.CELL_215_VOLTAGE, new UnsignedWordElement(0x15D6)),
						m(DeyeSunHybrid.ChannelId.CELL_216_VOLTAGE, new UnsignedWordElement(0x15D7)),
						m(DeyeSunHybrid.ChannelId.CELL_217_VOLTAGE, new UnsignedWordElement(0x15D8)),
						m(DeyeSunHybrid.ChannelId.CELL_218_VOLTAGE, new UnsignedWordElement(0x15D9)),
						m(DeyeSunHybrid.ChannelId.CELL_219_VOLTAGE, new UnsignedWordElement(0x15DA)),
						m(DeyeSunHybrid.ChannelId.CELL_220_VOLTAGE, new UnsignedWordElement(0x15DB)),
						m(DeyeSunHybrid.ChannelId.CELL_221_VOLTAGE, new UnsignedWordElement(0x15DC)),
						m(DeyeSunHybrid.ChannelId.CELL_222_VOLTAGE, new UnsignedWordElement(0x15DD)),
						m(DeyeSunHybrid.ChannelId.CELL_223_VOLTAGE, new UnsignedWordElement(0x15DE)),
						m(DeyeSunHybrid.ChannelId.CELL_224_VOLTAGE, new UnsignedWordElement(0x15DF))),
				new FC3ReadRegistersTask(0x1400, Priority.LOW, //
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_TOTAL_VOLTAGE,
								new UnsignedWordElement(0x1400), SCALE_FACTOR_2),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_TOTAL_CURRENT, new SignedWordElement(0x1401),
								SCALE_FACTOR_2),
						m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(0x1402)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_SOH, new UnsignedWordElement(0x1403)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_AVG_TEMPERATURE,
								new SignedWordElement(0x1404)),
						new DummyRegisterElement(0x1405),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_CHARGE_CURRENT_LIMIT,
								new UnsignedWordElement(0x1406), SCALE_FACTOR_2),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_DISCHARGE_CURRENT_LIMIT,
								new UnsignedWordElement(0x1407), SCALE_FACTOR_2),
						new DummyRegisterElement(0x1408, 0x1409),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_CYCLES,
								new UnsignedDoublewordElement(0x140A).wordOrder(WordOrder.LSWMSW)),
						new DummyRegisterElement(0x140C, 0x1417),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_CHARGE_ENERGY,
								new UnsignedDoublewordElement(0x1418).wordOrder(WordOrder.LSWMSW)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_DISCHARGE_ENERGY,
								new UnsignedDoublewordElement(0x141A).wordOrder(WordOrder.LSWMSW)),
						new DummyRegisterElement(0x141C, 0x141F),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_POWER, new SignedWordElement(0x1420),
								SCALE_FACTOR_2),
						new DummyRegisterElement(0x1421, 0x142F),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_MAX_CELL_VOLTAGE_NO,
								new UnsignedWordElement(0x1430)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_MAX_CELL_VOLTAGE,
								new UnsignedWordElement(0x1431)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_MAX_CELL_VOLTAGE_TEMPERATURE,
								new SignedWordElement(0x1432)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_MIN_CELL_VOLTAGE_NO,
								new UnsignedWordElement(0x1433)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_MIN_CELL_VOLTAGE,
								new UnsignedWordElement(0x1434)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_MIN_CELL_VOLTAGE_TEMPERATURE,
								new SignedWordElement(0x1435)),
						new DummyRegisterElement(0x1436, 0x1439),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_MAX_CELL_TEMPERATURE_NO,
								new UnsignedWordElement(0x143A)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_MAX_CELL_TEMPERATURE,
								new SignedWordElement(0x143B)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_MAX_CELL_TEMPERATURE_VOLTAGE,
								new SignedWordElement(0x143C)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_MIN_CELL_TEMPERATURE_NO,
								new UnsignedWordElement(0x143D)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_MIN_CELL_TEMPERATURE,
								new SignedWordElement(0x143E)),
						m(DeyeSunHybrid.ChannelId.BATTERY_STRING_MIN_CELL_TEMPERATURE_VOLTAGE,
								new SignedWordElement(0x143F))));
	}

	@Override
	public String debugLog() {
		return "SoC:" + this.getSoc().asString() //
				+ "|L:" + this.getActivePower().asString() //
				+ "|Allowed:"
				+ this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER).value().asStringWithoutUnit() + ";"
				+ this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER).value().asString() //
				+ "|" + this.getGridModeChannel().value().asOptionString() //
				+ "|Feed-In:"
				+ this.channel(DeyeSunHybrid.ChannelId.SURPLUS_FEED_IN_STATE_MACHINE).value().asOptionString();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			this.applyPowerLimitOnPowerDecreaseCausedByOvertemperatureError();
			this.calculateEnergy();
			break;
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS:
			this.defineWorkState();
			break;
		}
	}

	private LocalDateTime lastDefineWorkState = null;

	private void defineWorkState() {
		/*
		 * Set ESS in running mode
		 */
		// TODO this should be smarter: set in energy saving mode if there was no output
		// power for a while and we don't need emergency power.
		var now = LocalDateTime.now();
		if (this.lastDefineWorkState == null || now.minusMinutes(1).isAfter(this.lastDefineWorkState)) {
			this.lastDefineWorkState = now;
			EnumWriteChannel setWorkStateChannel = this.channel(DeyeSunHybrid.ChannelId.SET_WORK_STATE);
			try {
				setWorkStateChannel.setNextWriteValue(SetWorkState.START);
			} catch (OpenemsNamedException e) {
				this.logError(this.log, "Unable to start: " + e.getMessage());
			}
		}
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public boolean isManaged() {
		return !this.config.readOnlyMode();
	}

	@Override
	public int getPowerPrecision() {
		return 100; // the modbus field for SetActivePower has the unit 0.1 kW
	}

	@Override
	public Constraint[] getStaticConstraints() throws OpenemsNamedException {
		// Read-Only-Mode
		if (this.config.readOnlyMode()) {
			return new Constraint[] { //
					this.createPowerConstraint("Read-Only-Mode", Phase.ALL, Pwr.ACTIVE, Relationship.EQUALS, 0), //
					this.createPowerConstraint("Read-Only-Mode", Phase.ALL, Pwr.REACTIVE, Relationship.EQUALS, 0) //
			};
		}

		// Reactive Power constraints
		return new Constraint[] { //
				this.createPowerConstraint("Commercial40 Min Reactive Power", Phase.ALL, Pwr.REACTIVE,
						Relationship.GREATER_OR_EQUALS, MIN_REACTIVE_POWER), //
				this.createPowerConstraint("Commercial40 Max Reactive Power", Phase.ALL, Pwr.REACTIVE,
						Relationship.LESS_OR_EQUALS, MAX_REACTIVE_POWER) };
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				SymmetricEss.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricEss.getModbusSlaveNatureTable(accessMode) //
		);
	}

	@Override
	public void addCharger(DeyeSunHybridPv charger) {
		this.chargers.add(charger);
	}

	@Override
	public void removeCharger(DeyeSunHybridPv charger) {
		this.chargers.remove(charger);
	}

	@Override
	protected void logInfo(Logger log, String message) {
		super.logInfo(log, message);
	}

	private void applyPowerLimitOnPowerDecreaseCausedByOvertemperatureError() {
		if (this.config.powerLimitOnPowerDecreaseCausedByOvertemperatureChannel() != 0) {
			StateChannel powerDecreaseCausedByOvertemperatureChannel = this
					.channel(DeyeSunHybrid.ChannelId.POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE);
			if (powerDecreaseCausedByOvertemperatureChannel.value().orElse(false)) {
				/*
				 * Apply limit on ESS charge/discharge power
				 */
				try {
					this.power.addConstraintAndValidate(
							this.createPowerConstraint("Limit On PowerDecreaseCausedByOvertemperature Error", Phase.ALL,
									Pwr.ACTIVE, Relationship.GREATER_OR_EQUALS,
									this.config.powerLimitOnPowerDecreaseCausedByOvertemperatureChannel() * -1));
					this.power.addConstraintAndValidate(
							this.createPowerConstraint("Limit On PowerDecreaseCausedByOvertemperature Error", Phase.ALL,
									Pwr.ACTIVE, Relationship.LESS_OR_EQUALS,
									this.config.powerLimitOnPowerDecreaseCausedByOvertemperatureChannel()));
				} catch (OpenemsException e) {
					this.logError(this.log, e.getMessage());
				}
				/*
				 * Apply limit on Charger
				 */
				if (this.chargers.size() > 0) {
					IntegerWriteChannel setPvPowerLimit = this.chargers.get(0)
							.channel(DeyeSunHybridPv.ChannelId.SET_PV_POWER_LIMIT);
					try {
						setPvPowerLimit.setNextWriteValue(
								this.config.powerLimitOnPowerDecreaseCausedByOvertemperatureChannel());
					} catch (OpenemsNamedException e) {
						this.logError(this.log, e.getMessage());
					}
				}

			}
		}
	}

	@Override
	public Integer getSurplusPower() {
		return this.surplusFeedInHandler.run(this.chargers, this.config, this.componentManager);
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	private void calculateEnergy() {
		/*
		 * Calculate AC Energy
		 */
		var acActivePower = this.getActivePowerChannel().getNextValue().get();
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
		 * Calculate DC Power and Energy
		 */
		var dcDischargePower = acActivePower;
		for (DeyeSunHybridPv charger : this.chargers) {
			dcDischargePower = TypeUtils.subtract(dcDischargePower,
					charger.getActualPowerChannel().getNextValue().get());
		}
		this._setDcDischargePower(dcDischargePower);

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

}
