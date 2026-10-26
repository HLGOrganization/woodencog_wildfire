package net.chauvedev.woodencog.compat.create;

import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Looks for one of this mod's air heated blocks in the air a fan is moving.
 *
 * <p>Create decides what a fan does by walking the blocks along the fan's air column, back to front,
 * and remembering the first fan processing type it finds ({@code AirCurrent.rebuild}). A block that
 * holds a fluid with a temperature reads as Create's own blasting type there, but the temperature it
 * heats to is not something Create knows about, so it is picked up here instead: {@code MixinAirCurrent}
 * asks this class once per fan tick, parks what it finds in {@link FanContext}, and
 * {@code MixinFanProcessing} hands the source's temperature to {@link FanContext#localCeiling}.
 *
 * <p>Only the first heated block of the column counts. Lava, fire or a lit TFC firepit earlier in the
 * column keeps its own ceiling, and a chamber or tunnel heater behind it is ignored, which is the same
 * "first heat source wins" rule Create applies to the airflow type.
 *
 * <p>Which blocks are sources is asked of {@link IAirHeated} rather than of one class: the airflow
 * heater of this mod and the tunnel heater share one contract, one temperature table shape and one
 * consumption rule, and the fan side does not have to know which of the two it found. The class keeps
 * its name from when the airflow heater was the only source; the {@link Source} it reports has been
 * generalised the same way.
 */
public final class HotAirChamberHeat {

    /** Airflows never usefully reach this far; the scan is bounded so a tick stays cheap. */
    private static final int SCAN_LIMIT = 48;

    private HotAirChamberHeat() {
    }

    /** The heated block a fan is running on, where the airflow picks that heat up, and how hot it is. */
    public record Source(IAirHeated heater, float startOffset, float temperature) {
    }

    /**
     * The heated block that is the heat source of {@code fan}'s airflow, or null when the airflow is
     * not heated, is heated by lava or fire first, or is heated by a block that holds nothing that
     * heats.
     *
     * <p>The column is walked away from the fan, the same way the lava and fire scan in
     * {@code MixinAirCurrent} samples its distances, so a source is reported at the same offset a lava
     * block in its place would be: the block in front of the fan is {@code 0.5}, the next one
     * {@code 1.5}, and so on.
     */
    public static Source find(AirCurrent current, EncasedFanBlockEntity fan) {
        Level level = fan.getLevel();
        if (level == null) {
            return null;
        }
        Direction column = fan.getAirflowOriginSide();
        BlockPos origin = fan.getBlockPos();
        for (int offset = 1; offset <= SCAN_LIMIT; offset++) {
            BlockPos pos = origin.relative(column, offset);
            if (!level.isLoaded(pos)) {
                // The airflow cannot leave a loaded chunk, so there is nothing further to find.
                return null;
            }
            if (level.getBlockEntity(pos) instanceof IAirHeated heater && heater.isHeating()) {
                return new Source(heater, offset - 0.5F, heater.getAirTemperature());
            }
            // Create's own heat source, or a block of this mod that does not heat: either way the air
            // carries whatever is found here rather than one of our temperatures, so this fan is not ours.
            FanProcessingType type = current.getTypeAt(offset - 0.5F);
            if (AllFanProcessingTypes.BLASTING.equals(type) || AllFanProcessingTypes.SMOKING.equals(type)) {
                return null;
            }
        }
        return null;
    }

    /**
     * Takes this tick's fluid out of the block a fan is running on.
     *
     * <p>Called once per tick from {@code AirCurrent.tick}, which a fan only reaches while it is
     * turning, so a stopped fan costs nothing; the block is only reported while it holds a fluid that
     * heats, so an empty tank and a water filled one cost nothing either. The integrated client runs
     * the same tick and must not draw from its own copy of the tank, which is what the level argument
     * is for: it is the fan's level, and the source was found in it, so the side is the source's side.
     *
     * <p>{@code level} was read from the block entity itself before {@link IAirHeated} was extracted.
     * That block entity was reached through {@code level.getBlockEntity(pos)} on the fan's own level,
     * so the two are the same level and the check is unchanged.
     */
    public static void consume(Level level, Source source) {
        if (level == null || level.isClientSide) {
            return;
        }
        IAirHeated heater = source.heater();
        heater.consume(heater.consumptionPerTick());
    }
}
