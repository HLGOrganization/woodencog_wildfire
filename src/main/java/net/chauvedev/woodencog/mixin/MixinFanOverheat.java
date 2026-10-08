package net.chauvedev.woodencog.mixin;

import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessing;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import net.chauvedev.woodencog.compat.create.FanOverheatGuard;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the missing destruction ceiling to a blasting fan, for a dropped item and for one carried on
 * a belt.
 *
 * <p>The injection runs before {@link MixinFanProcessing}, which is registered at the default
 * priority of 1000 and cancels the method for every item it heats. Running first is what lets this
 * mixin see such an item at all; the check only reads state, so seeing last tick's temperature
 * simply means the item is destroyed on the tick after it crosses the ceiling.
 *
 * <p>Neither injection cancels when the item is fine, so the normal heating, cooking and melting
 * paths are untouched. On the belt path the two are mutually exclusive: cancelling with
 * {@code removeItem()} stops the heating injector from running for that item.
 *
 * <p>Moved here from Wildfires ({@code first.wildfires.mixin.create.FanOverheatMixin}) together with
 * {@link FanOverheatGuard}. Its priority is kept at 900 so that the ordering against the priority
 * 1000 heating injector survives the move; the two are now the only owners of this call site.
 */
@Mixin(value = FanProcessing.class, priority = 900, remap = false)
public abstract class MixinFanOverheat {

    @Inject(
            method = "applyProcessing(Lnet/minecraft/world/entity/item/ItemEntity;"
                    + "Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)Z",
            at = @At("HEAD"),
            remap = false
    )
    private static void woodencog$discardOverheatedItem(
            ItemEntity itemEntity,
            FanProcessingType type,
            CallbackInfoReturnable<Boolean> callback
    ) {
        FanOverheatGuard.discardIfOverheated(itemEntity, type);
    }

    @Inject(
            method = "applyProcessing(Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;"
                    + "Lnet/minecraft/world/level/Level;"
                    + "Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)"
                    + "Lcom/simibubi/create/content/kinetics/belt/behaviour/"
                    + "TransportedItemStackHandlerBehaviour$TransportedResult;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void woodencog$discardOverheatedBeltItem(
            TransportedItemStack transportedItem,
            Level level,
            FanProcessingType type,
            CallbackInfoReturnable<TransportedResult> callback
    ) {
        if (FanOverheatGuard.isOverheated(transportedItem.stack, type)) {
            // removeItem is Create's own "the fan destroyed this" answer for a belt.
            callback.setReturnValue(TransportedResult.removeItem());
        }
    }
}
