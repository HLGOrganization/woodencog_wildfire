package net.chauvedev.woodencog.mixin;

import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import net.chauvedev.woodencog.compat.create.FanContext;
import net.chauvedev.woodencog.compat.create.HotAirChamberHeat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Parks the fan behind the airflow in {@link FanContext} for the duration of its tick, so that the
 * static {@code FanProcessing.applyProcessing} can tell how fast the fan is turning and how far the
 * item sits from the heat source.
 *
 * <p>{@code AirCurrent.tick()} is the right place: {@code applyProcessing} is only ever reached from
 * {@code tickAffectedEntities} and {@code tickAffectedHandlers}, both of which run inside it, so a
 * value parked at the head of the tick is the value in force while that fan's items are processed.
 *
 * <p>The distance to the heat is read from {@code AirCurrent.getTypeAt(float)}, which already answers
 * "what is the airflow like this many blocks out": scanning for the first BLASTING or SMOKING sample
 * is exactly the point where the column picked up its heat, and everything downstream of it is
 * decayed. The scan stops at {@link #HEAT_SCAN_LIMIT} blocks, which is far past any usable airflow.
 * That is the lava, campfire and firepit story.
 *
 * <p>A block of this mod that heats its own air is not answered that way: it heats to the temperature
 * of its own fluid, which is not a type Create knows. {@code HotAirChamberHeat} finds such a block in
 * the column by hand and the fan is parked with its temperature instead, which is the only thing that
 * changes here - the fan speed, the flow direction and the decay are the same either way. Which blocks
 * those are is asked of {@code IAirHeated}, so this mixin does not care whether it was an airflow
 * heater or a tunnel heater that the airflow ran through.
 */
@Mixin(value = AirCurrent.class, remap = false)
public abstract class MixinAirCurrent {

    /** Airflows never usefully reach this far; the scan is bounded so a tick stays cheap. */
    private static final float HEAT_SCAN_LIMIT = 48.0F;

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void woodencog$parkFanState(CallbackInfo callback) {
        AirCurrent self = (AirCurrent) (Object) this;
        if (!(self.source instanceof EncasedFanBlockEntity fan)) {
            // An addon's airflow source: leave the context empty, which reads as 1x and no decay.
            FanContext.clear();
            return;
        }

        // A block of this mod that heats its own air first. It is heated to the temperature of its own
        // fluid, which the fan has to heat towards instead of the lava ceiling, so the fan is parked
        // with that temperature. The lookup answers null whenever the airflow takes its heat from lava
        // or fire first, and the vanilla path below then runs exactly as it did before.
        HotAirChamberHeat.Source source = HotAirChamberHeat.find(self, fan);
        if (source != null) {
            HotAirChamberHeat.consume(fan.getLevel(), source);
            FanContext.setFromHotAirChamber(source.temperature(), fan.getSpeed(), fan.getBlockPos(),
                    fan.getAirFlowDirection(), source.startOffset());
            return;
        }

        FanContext.set(fan.getSpeed(), fan.getBlockPos(), fan.getAirFlowDirection(), heatStart(self));
    }

    /**
     * Blocks from the fan to the first sample of airflow that carries heat, or 0 when this airflow is
     * not hot at all (in which case the ceiling is never consulted).
     */
    private static float heatStart(AirCurrent current) {
        for (float distance = 0.5F; distance <= HEAT_SCAN_LIMIT; distance += 1.0F) {
            FanProcessingType type = current.getTypeAt(distance);
            if (AllFanProcessingTypes.BLASTING.equals(type) || AllFanProcessingTypes.SMOKING.equals(type)) {
                return distance;
            }
        }
        return 0.0F;
    }
}
