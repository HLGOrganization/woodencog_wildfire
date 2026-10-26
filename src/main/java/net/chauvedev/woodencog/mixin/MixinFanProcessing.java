package net.chauvedev.woodencog.mixin;

import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessing;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import net.chauvedev.woodencog.compat.create.BurntFoodConversion;
import net.chauvedev.woodencog.compat.create.FanContext;
import net.chauvedev.woodencog.config.WoodenCogCommonConfigs;
import net.chauvedev.woodencog.datapack.DataPackRegistries;
import net.dries007.tfc.common.capabilities.food.FoodCapability;
import net.dries007.tfc.common.capabilities.food.FoodTraits;
import net.dries007.tfc.common.capabilities.heat.HeatCapability;
import net.dries007.tfc.common.capabilities.heat.IHeat;
import net.dries007.tfc.common.recipes.HeatingRecipe;
import net.dries007.tfc.common.recipes.inventory.ItemStackInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FanProcessing.class, remap = false)
public class MixinFanProcessing {

    @Unique
    private static void applyTemp(ItemStack inputStack, IHeat cap, FanProcessingType type, RegistryAccess registryAccess, BlockPos itemPos) {
        if(!inputStack.getCapability(HeatCapability.CAPABILITY).isPresent()) return;

        // How fast the fan behind this item is turning, 1x when no fan was parked (an addon airflow
        // source, or a call that did not come from a fan tick).
        float speed = FanContext.multiplier();

        if(type.equals(AllFanProcessingTypes.BLASTING)) {
            if(!DataPackRegistries.isInTempBlacklist(inputStack, registryAccess)) {
                // Lava behind the fan, or a hot air chamber, which reads as the same blasting type but
                // brings the temperature of its own fluid with it. The ceiling for lava is
                // configurable; the blasting fan cannot reach the 2300C superheated state of a burner,
                // the two are separate systems.
                // Either way the ceiling is the one at the heat source: airflow further downstream is
                // cooler, which is what localCeiling applies.
                float ceiling = FanContext.heatSourceCeiling(WoodenCogCommonConfigs.fanBlastingTemperature());
                heatTowards(cap, FanContext.localCeiling(ceiling, itemPos), speed);
            }
        } else if (type.equals(AllFanProcessingTypes.SMOKING)) {
            if(!DataPackRegistries.isInTempBlacklist(inputStack, registryAccess)) {
                // Fire, campfire or a lit TFC firepit behind the fan. Same decay as lava.
                heatTowards(cap, FanContext.localCeiling(WoodenCogCommonConfigs.fanSmokingTemperature(), itemPos), speed);
            }
        } else if (type.equals(AllFanProcessingTypes.SPLASHING)) {
            // A faster fan washes faster. Only ever towards 0C, so this cannot heat anything.
            cap.setTemperature(HeatCapability.adjustTempTowards(cap.getTemperature(), 0,
                    WoodenCogCommonConfigs.fanCoolingRate() * speed));
        } else {
            cap.setTemperature(cap.getTemperature() - 2F);
            if(cap.getTemperature() <= 0F) {
                cap.setTemperature(0F);
            }
        }
    }

    /**
     * Heats towards {@code ceiling} at {@code speed} times TFC's own rate.
     *
     * <p>{@code HeatCapability.addTemp} does not mean "add this many degrees". It adds TFC's fixed per
     * tick step divided by the item's heat capacity, clamps the result at the ceiling it is handed as
     * its second argument, and only ever raises a temperature. So one call heats exactly as fast as a
     * TFC device holding that ceiling would - heat capacity included - and the speed bonus is then
     * applied to the increment that call actually produced.
     *
     * <p>An item already at or above its local ceiling is left completely alone: heating must never
     * cool an item down, so walking downstream must not chill something that was hotter upstream.
     */
    @Unique
    private static void heatTowards(IHeat cap, float ceiling, float speed) {
        float before = cap.getTemperature();
        if(before >= ceiling) return;
        HeatCapability.addTemp(cap, ceiling);
        float after = cap.getTemperature();
        if(speed > 1F && after > before && after < ceiling) {
            cap.setTemperatureIfWarmer(Math.min(ceiling, before + (after - before) * speed));
        }
    }

    @Unique
    private static ItemStack applyTFCHeatingRecipe(ItemStack inputStack, IHeat cap){
        HeatingRecipe recipe = HeatingRecipe.getRecipe(inputStack);

        if (recipe!=null){
            if (recipe.isValidTemperature(cap.getTemperature())) {
                ItemStack output = recipe.assemble(new ItemStackInventory(inputStack), null);
                if(output.isEmpty()) return inputStack; //No output for this recipe do not change input
                FluidStack fluidStack = recipe.assembleFluid(new ItemStackInventory(inputStack));
                if(!fluidStack.isEmpty()) {
                    return ItemStack.EMPTY; //Melting recipe input is distorted
                }

                if(FoodCapability.has(output)) FoodCapability.applyTrait(output, FoodTraits.WOOD_GRILLED);

                output.setCount(inputStack.getCount());
                return output;
            }
        }
        return inputStack;
    }

    @Inject(
            method = {"applyProcessing(Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;Lnet/minecraft/world/level/Level;Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)Lcom/simibubi/create/content/kinetics/belt/behaviour/TransportedItemStackHandlerBehaviour$TransportedResult;"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private static void applyProcessing(TransportedItemStack transported, Level world, FanProcessingType type,CallbackInfoReturnable<TransportedItemStackHandlerBehaviour.TransportedResult> cir) {

        ItemStack inputStack = transported.stack;

        if(inputStack.getCapability(HeatCapability.CAPABILITY).isPresent() && WoodenCogCommonConfigs.HANDLE_TEMPERATURE.get()){
            if(inputStack.getCapability(HeatCapability.CAPABILITY).resolve().isEmpty()) {
                cir.setReturnValue(TransportedItemStackHandlerBehaviour.TransportedResult.doNothing());
                return;
            }

            IHeat cap = inputStack.getCapability(HeatCapability.CAPABILITY).resolve().get();

            // A TransportedItemStack carries no world position of its own, so this path has no block to
            // measure the airflow decay against and passes none: the item is treated as sitting at the
            // heat source. Giving this path a position needs the position of the block entity that owns
            // this handler, which this injector is not handed.
            MixinFanProcessing.applyTemp(inputStack, cap, type, world.registryAccess(), null);

            // Food that has been hot for long enough chars into the configured residue, whatever TFC
            // would otherwise do with it. This runs before the heating recipe below so that a burnt
            // food item is never sent through a recipe lookup first, and a result id that resolves to
            // no item at all is reported as "nothing happened", which leaves the food alone.
            ItemStack burnt = BurntFoodConversion.tick(inputStack, cap.getTemperature());
            if (burnt != inputStack) {
                TransportedItemStack newTransportedStack = transported.getSimilar();
                newTransportedStack.stack = burnt;
                cir.setReturnValue(TransportedItemStackHandlerBehaviour.TransportedResult.convertTo(newTransportedStack));
                cir.cancel();
                return;
            }

            ItemStack result = MixinFanProcessing.applyTFCHeatingRecipe(inputStack, cap);

            if(!result.equals(inputStack)) {
                if (result == ItemStack.EMPTY) {
                    cir.setReturnValue(TransportedItemStackHandlerBehaviour.TransportedResult.removeItem());
                } else {
                    TransportedItemStack newTransportedStack = transported.getSimilar();
                    newTransportedStack.stack = result;
                    cir.setReturnValue(TransportedItemStackHandlerBehaviour.TransportedResult.convertTo(newTransportedStack));
                }
                cir.cancel();
                return;
            }

            // TFC has nothing to apply at this temperature.
            // This mirrors the dropped item path: a lava or fire fan stays TFC-only, so Create's own
            // blasting/smoking processing must not run on top of the temperature written above. It used
            // to: a TFC item on a belt or depot therefore got converted by Create's fan recipes while the
            // same item lying on the ground did not. doNothing() is this path's "cancelled, and the item
            // is left exactly as it is".
            if(type.equals(AllFanProcessingTypes.BLASTING) || type.equals(AllFanProcessingTypes.SMOKING)) {
                cir.setReturnValue(TransportedItemStackHandlerBehaviour.TransportedResult.doNothing());
                cir.cancel();
                return;
            }
            // A water fan (or an addon fan type) only cools the item here, so Create's own processing has
            // to keep running for a splashing recipe to work on a belt or a depot.
        }
    }


    @Inject(
            method = {"applyProcessing(Lnet/minecraft/world/entity/item/ItemEntity;Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)Z"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private static void applyProcessing(ItemEntity entity, FanProcessingType type, CallbackInfoReturnable<Boolean> cir) {
        ItemStack inputStack = entity.getItem();

        if(inputStack.getCapability(HeatCapability.CAPABILITY).isPresent() && WoodenCogCommonConfigs.HANDLE_TEMPERATURE.get()){
            if(inputStack.getCapability(HeatCapability.CAPABILITY).resolve().isEmpty()) return;

            IHeat cap = inputStack.getCapability(HeatCapability.CAPABILITY).resolve().get();

            // A dropped item knows where it is, so the airflow decay applies to it in full.
            MixinFanProcessing.applyTemp(inputStack, cap, type, entity.level().registryAccess(), entity.blockPosition());

            // The same rule as on the belt path above, for the same stack in an airflow: food that
            // has been hot for long enough is replaced instead of being sent through TFC's recipes.
            ItemStack burnt = BurntFoodConversion.tick(inputStack, cap.getTemperature());
            if (burnt != inputStack) {
                entity.setItem(burnt);
                cir.setReturnValue(true);
                cir.cancel();
                return;
            }

            ItemStack result = MixinFanProcessing.applyTFCHeatingRecipe(inputStack, cap);

            if(result.equals(inputStack)){
                // TFC has nothing to apply at this temperature.
                // A lava or fire fan stays TFC-only: setReturnValue also cancels the method, so
                // Create's own processing does not run on top of the temperature written above.
                if(type.equals(AllFanProcessingTypes.BLASTING) || type.equals(AllFanProcessingTypes.SMOKING)) {
                    cir.setReturnValue(false);
                    return;
                }
                // A water fan (or an addon fan type) only cools the item here, so Create's own
                // processing has to keep running. Cancelling used to make a dropped item that has
                // both TFC heat data and a Create splashing recipe impossible to wash on the ground,
                // while the belt/depot path washed it fine because that path only cancels when the
                // TFC heating recipe actually changed the stack.
                return;
            }

            if (result == ItemStack.EMPTY){
                entity.kill();
            }else{
                entity.setItem(result);
            }
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    @Inject(
            method = "applyProcessing(Lnet/minecraft/world/entity/item/ItemEntity;Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V"),
            cancellable = true
    )
    private static void cancelDiscard(ItemEntity entity, FanProcessingType type, CallbackInfoReturnable<Boolean> cir){
        cir.cancel();
    }

}
