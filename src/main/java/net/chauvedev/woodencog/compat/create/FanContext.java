package net.chauvedev.woodencog.compat.create;

import net.chauvedev.woodencog.config.WoodenCogCommonConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The fan that is currently processing an item, and what the item's position in its airflow means.
 *
 * <p>{@code FanProcessing.applyProcessing} is static and is handed no fan, so {@code MixinAirCurrent}
 * parks the fan's state here at the head of {@code AirCurrent.tick()} and {@code MixinFanProcessing}
 * reads it while it writes temperatures. A {@link ThreadLocal} is needed because an integrated server
 * and its client tick on different threads.
 *
 * <p>Two things come out of here:
 * <ul>
 *   <li>{@link #multiplier()} - how many times the per tick temperature change is worth. One step of
 *       {@code fan_speed_step_rpm} adds {@code fan_speed_step_amount}, so the default 32/0.5 gives 1x
 *       below 32 RPM, 1.5x at 32, and 5x at Create's maximum of 256 RPM.</li>
 *   <li>{@link #localCeiling(float, BlockPos)} - the configured ceiling, decayed by
 *       {@code fan_decay_per_block} for every block the item sits past the point where the airflow
 *       picked up its heat. The fan is an air column: the further downstream, the cooler its ceiling.</li>
 * </ul>
 *
 * <p>{@link #heatSourceCeiling(float)} is where a hot air chamber differs from lava: the chamber
 * carries the temperature of its own fluid, and that temperature - not the lava ceiling - is what the
 * airflow has to be decayed from.
 */
public final class FanContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private FanContext() {
    }

    /** One fan for one tick. Immutable. */
    public static final class State {

        private final float speed;
        private final BlockPos origin;
        private final Direction flow;
        private final float heatStart;
        private final Float chamberTemperature;

        State(float speed, BlockPos origin, Direction flow, float heatStart, Float chamberTemperature) {
            this.speed = speed;
            this.origin = origin;
            this.flow = flow;
            this.heatStart = heatStart;
            this.chamberTemperature = chamberTemperature;
        }

        /** Whole blocks this position sits past the first heated block of the airflow. Never negative. */
        int blocksPastHeat(BlockPos itemPos) {
            BlockPos rel = itemPos.subtract(this.origin);
            int along = rel.getX() * this.flow.getStepX()
                    + rel.getY() * this.flow.getStepY()
                    + rel.getZ() * this.flow.getStepZ();
            return Math.max(0, (int) Math.floor(along - this.heatStart));
        }
    }

    /** Called by {@code MixinAirCurrent} once per fan tick. */
    public static void set(float speed, BlockPos origin, Direction flow, float heatStart) {
        CURRENT.set(new State(speed, origin, flow, heatStart, null));
    }

    /**
     * Called by {@code MixinAirCurrent} once per fan tick when the airflow is heated by one of this
     * mod's hot air chambers instead of by lava or fire. {@code temperature} is what the fluid in that
     * chamber heats the air to, and it replaces the lava or fire ceiling for this fan.
     */
    public static void setFromHotAirChamber(float temperature, float speed, BlockPos origin, Direction flow, float heatStart) {
        CURRENT.set(new State(speed, origin, flow, heatStart, temperature));
    }

    /** Called when the current airflow source is not a fan this mod understands. */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * {@code 1 + fan_speed_step_amount * floor(|speed| / fan_speed_step_rpm)}.
     *
     * <p>The absolute value matters: a fan runs happily in reverse, and the heating should not care.
     * With no fan parked (an addon that drives processing some other way) this is 1x, which leaves the
     * behaviour equal to a plain TFC heating call.
     */
    public static float multiplier() {
        State state = CURRENT.get();
        if (state == null) {
            return 1.0F;
        }
        float step = (float) Math.max(1.0D, WoodenCogCommonConfigs.fanSpeedStepRpm());
        float amount = (float) WoodenCogCommonConfigs.fanSpeedStepAmount();
        return 1.0F + amount * (float) Math.floor(Math.abs(state.speed) / step);
    }

    /**
     * The ceiling an item may reach where it is, or {@code ceiling} unchanged when there is no
     * position to judge (the belt path passes none yet).
     */
    public static float localCeiling(float ceiling, BlockPos itemPos) {
        State state = CURRENT.get();
        if (state == null || itemPos == null) {
            return ceiling;
        }
        int past = state.blocksPastHeat(itemPos);
        float decay = (float) WoodenCogCommonConfigs.fanDecayPerBlock();
        if (past <= 0 || decay <= 0.0D) {
            return ceiling;
        }
        return (float) (ceiling * Math.pow(1.0D - decay, past));
    }

    /**
     * What the heat source of this fan heats the air to: the temperature of the hot air chamber the
     * airflow came out of, or {@code fallback} (the lava or fire ceiling) for every other heat source
     * and for a fan with nothing parked at all.
     *
     * <p>Hand the result to {@link #localCeiling(float, BlockPos)}: the per block decay and the speed
     * bonus then work on the chamber's temperature exactly as they work on lava.
     */
    public static float heatSourceCeiling(float fallback) {
        State state = CURRENT.get();
        if (state == null || state.chamberTemperature == null) {
            return fallback;
        }
        return state.chamberTemperature;
    }
}
