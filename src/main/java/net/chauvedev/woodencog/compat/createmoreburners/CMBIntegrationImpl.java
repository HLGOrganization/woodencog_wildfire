package net.chauvedev.woodencog.compat.createmoreburners;

import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import net.chauvedev.woodencog.config.WoodenCogCommonConfigs;
import net.dries007.tfc.common.capabilities.heat.HeatCapability;
import net.dragonegg.moreburners.content.block.entity.BaseBurnerBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Supplies a TFC temperature for Create More Burners' blocks.
 *
 * <h2>Electric burner</h2>
 *
 * <p>The electric burner is driven by Create heat levels rather than by a fuel value, so a linear
 * mapping of its raw {@code heat} field is the wrong scale: it spreads the whole range evenly and
 * lands between the thresholds Wooden Cog actually compares against. The burner is therefore mapped
 * through the same heat-level thresholds the rest of this mod uses, and the temperature is approached
 * gradually with TFC's device-temperature adjustment, matching how a charcoal forge without bellows
 * warms up instead of jumping straight to its target.
 *
 * <p>This logic used to live in the Wildfires mod as a mixin onto this class, because this class
 * could not be edited there. It is native code now, so that mixin has been disabled.
 *
 * <h2>Every other burner</h2>
 *
 * <p>Other More Burners blocks keep the original linear formula untouched.
 */
public class CMBIntegrationImpl implements CMBIntegration{

    /** The only More Burners block that follows Create heat levels instead of a fuel value. */
    private static final ResourceLocation ELECTRIC_BURNER_ID =
            new ResourceLocation("moreburners", "electric_burner");

    /**
     * Remembers how hot each burner has become, so it ramps up over time.
     *
     * <p>Weak keys: an entry disappears together with its block entity, so unloaded chunks cannot
     * accumulate stale temperatures.
     */
    private static final Map<BlockEntity, Float> CURRENT_TEMPERATURES = new WeakHashMap<>();

    /**
     * Get a tfc temperature value for a given blockEntity
     *
     * @param be BlockEntity of other mods
     */
    @Override
    public float getTFCTemperatureOf(BlockEntity be) {
        if (!(be instanceof BaseBurnerBlockEntity burner)) {
            return 0;
        }

        if (isElectricBurner(be)) {
            BlazeBurnerBlock.HeatLevel heatLevel = BlazeBurnerBlock.getHeatLevelOf(be.getBlockState());
            return graduallyApproach(be, targetForHeatLevel(heatLevel));
        }

        return (float) ((burner.heat / 300.0) * 1350.0);
    }

    private static boolean isElectricBurner(BlockEntity be) {
        return ELECTRIC_BURNER_ID.equals(BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()));
    }

    /**
     * The TFC temperature a given Create heat level stands for.
     *
     * <p>Lower Create states are not active heating modes for these burners, so they map to the
     * configured "none" temperature instead of their own thresholds.
     */
    private static float targetForHeatLevel(BlazeBurnerBlock.HeatLevel heatLevel) {
        return switch (heatLevel) {
            case KINDLED -> WoodenCogCommonConfigs.BLAZE_BURNER_KINDLED.get().floatValue();
            case SEETHING -> WoodenCogCommonConfigs.BLAZE_BURNER_SEETHING.get().floatValue();
            case NONE, SMOULDERING, FADING -> WoodenCogCommonConfigs.BLAZE_BURNER_NONE.get().floatValue();
        };
    }

    /**
     * Advances a burner towards its target temperature using the same device-temperature adjustment
     * a TFC charcoal forge without bellows uses, so the burner heats up at a believable rate.
     */
    private static synchronized float graduallyApproach(BlockEntity blockEntity, float targetTemperature) {
        float currentTemperature = CURRENT_TEMPERATURES.getOrDefault(blockEntity, 0.0F);
        float nextTemperature = HeatCapability.adjustDeviceTemp(
                currentTemperature,
                targetTemperature,
                0,
                false
        );
        if (nextTemperature <= 0.0F) {
            CURRENT_TEMPERATURES.remove(blockEntity);
            return 0.0F;
        }
        CURRENT_TEMPERATURES.put(blockEntity, nextTemperature);
        return nextTemperature;
    }
}
