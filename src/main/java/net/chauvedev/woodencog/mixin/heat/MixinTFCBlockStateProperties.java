package net.chauvedev.woodencog.mixin.heat;

import net.dries007.tfc.common.blocks.TFCBlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives TFC's shared charcoal forge heat property a true eighth tier.
 *
 * <p>TFC declares {@code HEAT_LEVEL = IntegerProperty.create("heat_level", 0, 7)} inside the static
 * initialiser of {@link TFCBlockStateProperties}. That class initialiser contains 26 separate
 * {@code IntegerProperty.create} call sites, so the redirect has to discriminate on the runtime
 * property name instead of on the call site. {@code stage} is the only other property that also
 * uses a maximum of 7, hence the exact {@code "heat_level"} match.
 *
 * <p>The extended range alone does <b>not</b> change any behaviour: TFC's own
 * {@code CharcoalForgeBlockEntity.serverTick} still clamps the value it writes to 1..7. Tiers above
 * 7 are written by {@link MixinCharcoalForgeBlockEntity}, which promotes the forge to tier 8 once
 * the device temperature reaches the superheated threshold.
 *
 * <p>The furnace tile itself only ever wrote 0..7, so raising this maximum cannot invalidate an
 * existing world: the extra value simply becomes reachable.
 *
 * <p>This is a native port of the {@code first.wildfires.mixin.tfc.TFCBlockStatePropertiesMixin}
 * patch that used to be injected by the Wildfires mod; that mixin is now disabled, because the
 * property must have exactly one owner to avoid two competing redirects and two writers of the
 * same block state.
 */
@Mixin(value = TFCBlockStateProperties.class, remap = false)
public abstract class MixinTFCBlockStateProperties {

    /**
     * Highest heat level the property is allowed to take.
     */
    private static final int WOODENCOG_EXTENDED_HEAT_LEVEL_MAX = 8;

    @Redirect(
            method = "<clinit>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/properties/IntegerProperty;create(Ljava/lang/String;II)Lnet/minecraft/world/level/block/state/properties/IntegerProperty;"
            ),
            remap = true
    )
    private static IntegerProperty woodencog$extendHeatLevel(String name, int min, int max) {
        return IntegerProperty.create(name, min, "heat_level".equals(name) ? WOODENCOG_EXTENDED_HEAT_LEVEL_MAX : max);
    }
}
