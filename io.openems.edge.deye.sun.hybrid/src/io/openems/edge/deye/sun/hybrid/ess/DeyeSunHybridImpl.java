package io.openems.edge.deye.sun.hybrid.ess;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;

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
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
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
import io.openems.edge.deye.sun.hybrid.ess.pv.DeyeSunHybridPv;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Constraint;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;
//import io.openems.edge.goodwe.common.GoodWe;
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
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.unit_id(), this.cm, "Modbus",
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

		// AC 1/28/2024
		IntegerWriteChannel setGridLoadOffPowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_GRID_LOAD_OFF_POWER);
		setGridLoadOffPowerChannel.setNextWriteValue(93);
	}

	@Override
	public String getModbusBridgeId() {
		return this.config.modbus_id();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() throws OpenemsException {
		return new ModbusProtocol(this, //
				
				new FC3ReadRegistersTask(40001, Priority.LOW,
						m(SymmetricEss.ChannelId.MAX_APPARENT_POWER, new UnsignedWordElement(40001)),
						new DummyRegisterElement(40002),
						m(DeyeSunHybrid.ChannelId.SERIAL_NUMBER, new StringWordElement(40003, 5))),
				
				new FC16WriteRegistersTask(40077,
						m(DeyeSunHybrid.ChannelId.SET_ACTIVE_POWER, new SignedWordElement(40077)),
						m(DeyeSunHybrid.ChannelId.SET_REACTIVE_POWER, new SignedWordElement(40078))),

				new FC3ReadRegistersTask(40098, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.BATTERY_CHARGING_TYPE, new SignedWordElement(40098)),
						new DummyRegisterElement(40099, 40101),
						m(DeyeSunHybrid.ChannelId.BATTERY_CAPACITY, new SignedWordElement(40102)),
						new DummyRegisterElement(40103),
						m(DeyeSunHybrid.ChannelId.ZERO_EXPORT_POWER, new SignedWordElement(40104))),

				new FC3ReadRegistersTask(40108, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.MAX_A_BATTERY_CHARGE_CURRENT, new SignedWordElement(40108)),
						m(DeyeSunHybrid.ChannelId.MAX_A_BATTERY_DISCHARGE_CURRENT, new SignedWordElement(40109)),
						m(DeyeSunHybrid.ChannelId.PARALLEL_BAT_1_AND_BAT_2, new SignedWordElement(40110))),

				new FC3ReadRegistersTask(40115, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.BATTERY_CAPACITY_SHUTDOWN, new SignedWordElement(40115)),
						m(DeyeSunHybrid.ChannelId.BATTERY_CAPACITY_RESTART, new SignedWordElement(40116)),
						m(DeyeSunHybrid.ChannelId.BATTERY_CAPACITY_LOW_BATTERY, new SignedWordElement(40117))),

				new FC3ReadRegistersTask(40121, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.GEN_MAX_RUN_TIME, new SignedWordElement(40121), SCALE_FACTOR_MINUS_1),
						m(DeyeSunHybrid.ChannelId.GEN_COOLING_TIME, new SignedWordElement(40122), SCALE_FACTOR_MINUS_1),
						new DummyRegisterElement(40123),
						m(DeyeSunHybrid.ChannelId.GEN_CHARGING_STARTING_CAPACITY_POINT, new SignedWordElement(40124)),
						m(DeyeSunHybrid.ChannelId.GEN_CHARGING_CURRENT_TO_BATTERY, new SignedWordElement(40125)),
						new DummyRegisterElement(40126, 40128),
						m(DeyeSunHybrid.ChannelId.GEN_CHARGE_ENABLED, new SignedWordElement(40129)),
						m(DeyeSunHybrid.ChannelId.GRID_CHARGE_ENABLED, new SignedWordElement(40130)),
						m(DeyeSunHybrid.ChannelId.AC_COUPLE_FREQUENCY_CAP, new SignedWordElement(40131), SCALE_FACTOR_MINUS_2),
						new DummyRegisterElement(40132, 40134),
						m(DeyeSunHybrid.ChannelId.GEN_LOAD_OFF_POWER, new SignedWordElement(40135)),
						new DummyRegisterElement(40136),
						m(DeyeSunHybrid.ChannelId.GEN_LOAD_ON_POWER, new SignedWordElement(40137)),
						new DummyRegisterElement(40138, 40139),
						m(DeyeSunHybrid.ChannelId.GEN_GRID_SIGNAL, new SignedWordElement(40140))),

				new FC3ReadRegistersTask(40145, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.SOLAR_SELL, new SignedWordElement(40145)),
						new DummyRegisterElement(40146, 40147),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_TIME_POINT_1, new SignedWordElement(40148)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_TIME_POINT_2, new SignedWordElement(40149)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_TIME_POINT_3, new SignedWordElement(40150)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_TIME_POINT_4, new SignedWordElement(40151)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_TIME_POINT_5, new SignedWordElement(40152)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_TIME_POINT_6, new SignedWordElement(40153)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_TIME_POINT_1_POWER, new SignedWordElement(40154)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_TIME_POINT_2_POWER, new SignedWordElement(40155)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_TIME_POINT_3_POWER, new SignedWordElement(40156)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_TIME_POINT_4_POWER, new SignedWordElement(40157)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_TIME_POINT_5_POWER, new SignedWordElement(40158)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_TIME_POINT_6_POWER, new SignedWordElement(40159)),
						new DummyRegisterElement(40160, 40165),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_CAPACITY_1, new SignedWordElement(40166)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_CAPACITY_2, new SignedWordElement(40167)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_CAPACITY_3, new SignedWordElement(40168)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_CAPACITY_4, new SignedWordElement(40169)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_CAPACITY_5, new SignedWordElement(40170)),
						m(DeyeSunHybrid.ChannelId.SELL_MODE_CAPACITY_6, new SignedWordElement(40171)),
						m(DeyeSunHybrid.ChannelId.TIME_POINT_1_GRID_GEN_CHARGE_ENABLE, new SignedWordElement(40172)),
						m(DeyeSunHybrid.ChannelId.TIME_POINT_2_GRID_GEN_CHARGE_ENABLE, new SignedWordElement(40173)),
						m(DeyeSunHybrid.ChannelId.TIME_POINT_3_GRID_GEN_CHARGE_ENABLE, new SignedWordElement(40174)),
						m(DeyeSunHybrid.ChannelId.TIME_POINT_4_GRID_GEN_CHARGE_ENABLE, new SignedWordElement(40175)),
						m(DeyeSunHybrid.ChannelId.TIME_POINT_5_GRID_GEN_CHARGE_ENABLE, new SignedWordElement(40176)),
						m(DeyeSunHybrid.ChannelId.TIME_POINT_6_GRID_GEN_CHARGE_ENABLE, new SignedWordElement(40177)),
						m(DeyeSunHybrid.ChannelId.MICROINVERTER_EXPORT_TO_GRID_CUTOFF, new SignedWordElement(40178)),
						new DummyRegisterElement(40179, 40180),
						m(DeyeSunHybrid.ChannelId.SOLAR_ARC_FAULT_ON, new SignedWordElement(40181))
						),

				new FC3ReadRegistersTask(40189, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.GEN_CONNECTED_TO_GRID_INPUT, new UnsignedWordElement(40189)),
						m(DeyeSunHybrid.ChannelId.GEN_PEAK_SHAVING_POWER, new UnsignedWordElement(40190)),
						m(DeyeSunHybrid.ChannelId.GRID_PEAK_SHAVING_POWER, new UnsignedWordElement(40191))
						),

				new FC16WriteRegistersTask(40190,
						m(DeyeSunHybrid.ChannelId.SET_GEN_PEAK_SHAVING_POWER, new SignedWordElement(40190)),
						m(DeyeSunHybrid.ChannelId.SET_GRID_PEAK_SHAVING_POWER, new SignedWordElement(40191))),

				new FC3ReadRegistersTask(40209, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.UPS_BACKUP_DELAY_TIME, new UnsignedWordElement(40209))),

				new FC3ReadRegistersTask(40214, Priority.LOW,
						m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(40214))),

				new FC3ReadRegistersTask(40340, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.MAX_SOLAR_SELL_POWER, new UnsignedWordElement(40340), SCALE_FACTOR_1)),

				new FC3ReadRegistersTask(40347, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.CT_RATIO, new UnsignedWordElement(40347))),

				new FC3ReadRegistersTask(40500, Priority.LOW,
						m(DeyeSunHybrid.ChannelId.INVERTER_RUN_STATE, new UnsignedWordElement(40500))),
				
				new FC3ReadRegistersTask(40607, Priority.LOW,
						m(SymmetricEss.ChannelId.ACTIVE_POWER, new SignedWordElement(40607)),
						m(SymmetricEss.ChannelId.REACTIVE_POWER, new SignedWordElement(40608))),

				new FC3ReadRegistersTask(40620, Priority.LOW, //
						m(DeyeSunHybrid.ChannelId.APPARENT_POWER, new UnsignedWordElement(40620)))

				);
	}

	@Override
	public String debugLog() {
		return "SoC:" + this.getSoc().asString() //
				+ "|Type:"
				+ this.channel(DeyeSunHybrid.ChannelId.BATTERY_CHARGING_TYPE).value().asOptionString()
/*				+ "|L:" + this.getActivePower().asString() //
				+ "|Active Power:"
				+ this.channel(SymmetricEss.ChannelId.ACTIVE_POWER).value().asStringWithoutUnit() + ";"
				+ "|Allowed:"
				+ this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER).value().asStringWithoutUnit() + ";"
				+ this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER).value().asString() //
				+ "|Run State:"
				+ this.channel(DeyeSunHybrid.ChannelId.INVERTER_RUN_STATE).value().asOptionString()
				*/
				;
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
