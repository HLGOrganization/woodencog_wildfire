package net.chauvedev.woodencog.compat.create;

/**
 * A block of this mod that a Create fan can pick its heat up from.
 *
 * <p>A Create fan decides what it does by walking the blocks along its air column and remembering the
 * first fan processing type it finds. Every vanilla heat source can be written as a tag - lava is a
 * fluid tag, fire and campfire are block tags, a blaze burner is a block with a heat level - but this
 * mod's sources heat to the temperature of the fluid they hold at the time, which no tag can express.
 * They are therefore recognised in code, and this interface is the one thing that code asks them:
 * heat or not, how hot, and what the fan owes them for the air it took.
 *
 * <p>Three places use it, and they used to name one concrete class instead:
 * <ul>
 *   <li>{@link HotAirChamberHeat#find} - walks the air column of a running fan looking for a source it
 *       can report to {@link FanContext}.</li>
 *   <li>{@code MixinBlastingType} - makes the source count as Create's own blasting type while it is
 *       heating, so the airflow carries heat at all.</li>
 *   <li>{@link HotAirChamberHeat#consume} - takes this tick's fluid out of the source the fan runs
 *       on, on the server only.</li>
 * </ul>
 *
 * <p>Implementations: {@code HotAirChamberBlockEntity} (the airflow heater, one 2000 mB tank) and
 * {@code TunnelHeaterBlockEntity} (the tunnel heater, one tank shared by a connected group of them).
 */
public interface IAirHeated {

    /**
     * Whether a fan drawing its air through this block would pick up any heat at all. False for an
     * empty tank, a fluid the config lists at 0 such as water, and a block that a redstone signal has
     * switched off.
     */
    boolean isHeating();

    /**
     * What the fluid in this block's tank heats a fan's air to, or 0 when it heats nothing and for a
     * block that is switched off.
     */
    float getAirTemperature();

    /**
     * Takes {@code millibuckets} out of the tank, called once per tick by the fan running on this
     * block. A stopped fan, an empty tank and a tank whose fluid does not heat never get here, so
     * nothing is lost while the block is idle.
     */
    void consume(double millibuckets);

    /**
     * Millibuckets this block wants taken for one tick of a fan drawing its air through it. The rate
     * belongs to the block and not to the fan, because the two kinds of source are meant to be
     * tunable apart: a chamber is priced by {@code fan.hot_air_chamber_consumption}, a tunnel heater
     * by {@code fan.tunnel_heater_consumption}, and both default to 0.01 mB per tick.
     */
    double consumptionPerTick();
}
