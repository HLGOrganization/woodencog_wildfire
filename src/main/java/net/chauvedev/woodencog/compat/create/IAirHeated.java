package net.chauvedev.woodencog.compat.create;

/**
 * A block of this mod that a Create fan can pick its heat up from.
 *
 * <p>A Create fan decides what it does by walking the blocks along its air column and remembering the
 * first fan processing type it finds. Every vanilla heat source can be written as a tag - lava is a
 * fluid tag, fire and campfire are block tags, a blaze burner is a block with a heat level - but this
 * mod's sources heat to a temperature that depends on what they are running on at the time - the fluid
 * in a hot air chamber, the fixed temperature of an electric tunnel heater - which no tag can express.
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
 * {@code TunnelHeaterBlockEntity} (the tunnel heater, one Forge Energy battery shared by a connected
 * group of them).
 *
 * <p><b>The amount is in whatever the block burns, not always in millibuckets.</b> A hot air chamber
 * burns fluid and counts millibuckets; a tunnel heater burns Forge Energy and counts FE. A fan never
 * looks inside the number, it just asks the block what one tick costs and hands that back, so the two
 * can be priced apart by their own config keys.
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
     * Takes {@code amount} out of the block's store, called once per tick by the fan running on this
     * block. The unit is whatever {@link #consumptionPerTick()} is priced in - millibuckets for a hot air
     * chamber, Forge Energy for a tunnel heater. A stopped fan, an empty store and a store that cannot
     * pay never get here, so nothing is lost while the block is idle.
     */
    void consume(double amount);

    /**
     * What this block wants taken for one tick of a fan drawing its air through it, in the block's own
     * unit. The rate belongs to the block and not to the fan, because the two kinds of source are meant
     * to be tunable apart: a chamber is priced by {@code fan.hot_air_chamber_consumption} in millibuckets
     * (0.01 out of the box), a tunnel heater by {@code tunnel_heater_energy_per_tick} in Forge Energy
     * (20 out of the box).
     */
    double consumptionPerTick();
}
