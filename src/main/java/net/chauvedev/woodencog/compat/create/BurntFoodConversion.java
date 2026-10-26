package net.chauvedev.woodencog.compat.create;

import net.chauvedev.woodencog.WoodenCog;
import net.chauvedev.woodencog.config.WoodenCogCommonConfigs;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Burns food that a fan has held at or above a configured temperature for long enough into a
 * configured residue item.
 *
 * <p>Where TFC itself stops: a TFC heat definition carries a heat capacity, a forging temperature and
 * a welding temperature, and no notion of food being ruined by heat. TFC's own two "burn the food"
 * recipes are {@code destroy_bread} at 700 degrees (for {@code tfc:foods/breads}) and
 * {@code destroy_cooked_meat} at 900 (for {@code tfc:foods/cooked_meats}), and both sit above the
 * blasting ceiling this mod gives a plain lava fan (500 by default), so on a lava fan nothing at all
 * happens to food. This class is that missing outcome: leave a food item at or above
 * {@code fan.burnt_food_temperature} for {@code fan.burnt_food_duration} ticks in a row and it turns
 * into {@code fan.burnt_food_result}.
 *
 * <p>Three things are deliberately looked up rather than compiled in:
 * <ul>
 *   <li>the result item, by {@link ResourceLocation}. The shipped default is registered by KubeJS in
 *       the modpack and does not exist when this mod is built, and it may also be absent at runtime.
 *       While no item answers to the id the food simply does not burn, and the miss is reported once
 *       per configured id rather than once per tick.</li>
 *   <li>the food tag, by id, so a modpack that keeps its own list of food can point this at it.</li>
 *   <li>both of those are read from the config on every tick, but re-resolved only when the
 *       configured id actually changed, which is the same shape {@code hotAirChamberTemperature}
 *       uses for its fluid table.</li>
 * </ul>
 *
 * <p>The count of ticks spent above the temperature lives in the stack's own root NBT, under
 * {@link #BURNT_TICKS_KEY}, and nowhere else. That is what makes it survive a move between a belt, a
 * depot, a hopper and a chest, and what makes it survive saving and loading the world. It is written
 * only when it changes, and it is removed outright once the item drops back below the temperature, so
 * an item that was hot once and cooled down again carries no leftover of this feature. It is not a
 * Forge capability on purpose: the capability tag the stack already carries is where TFC keeps the
 * heat and the food data.
 *
 * <p>Called from {@code MixinFanProcessing}, once per item per fan tick, for a dropped item in an
 * airflow and for a stack riding a belt or a depot. Both of those paths feed a TFC temperature in,
 * which is the only input here besides the stack: the fan type, the heat source and the distance to
 * it are already folded into that temperature by the time this is called, so a lava fan, a fire fan
 * and a hot air chamber all reach this rule through the one value they agree on.
 *
 * <p>The trigger is a temperature and not a fan: an item that is hot counts, whatever heated it. The
 * intended reading of "lava converts food" is therefore expressed as "the temperature a lava fan
 * reaches converts food", and a fire fan (600 degrees by default) or a hot air chamber passes that
 * value too because it is hotter still.
 */
public final class BurntFoodConversion {

    /**
     * NBT key the count of ticks spent above the temperature lives under. Namespaced, and the colon
     * in the key is intentional: it is this mod's own key in a compound that TFC also writes into,
     * so it should read as one.
     */
    public static final String BURNT_TICKS_KEY = "woodencog:BurntTicks";

    /** The configured result id the cached item below was resolved from. */
    private static String resultIdFrom;
    /** What that id resolved to in the item registry, or null while nothing answers to it. */
    private static Item resultItem;
    /** Whether the current id has already been reported as unregistered. */
    private static boolean missingResultReported;

    /** The configured food tag id the cached tag below was built from. */
    private static String tagIdFrom;
    /** The tag that id built, or null while the id is not a valid resource location. */
    private static TagKey<Item> foodTag;

    private BurntFoodConversion() {
    }

    /**
     * Counts one tick of a food item being hot, and converts it once it has been hot for long enough.
     *
     * <p>Called after the fan has written this tick's temperature into the stack, so the temperature
     * handed in is the one the item has right now, this tick's heating included.
     *
     * <p>A stack that is not food, a stack that is below the temperature, a stack that is still
     * counting and a stack whose result item does not exist are all returned as the very same
     * instance, which is what lets a caller use a reference comparison to tell "nothing happened"
     * from "this stack is now something else". An empty stack is never returned: there is no outcome
     * here that destroys an item without replacing it, and the empty branch the callers keep belongs
     * to the TFC melting path that shares the same call site.
     *
     * @param stack       the item to count, which is written to in place
     * @param temperature the stack's TFC temperature in degrees, this tick's heating included
     * @return the same stack when nothing happened, a fresh residue stack when the food has burnt
     */
    public static ItemStack tick(ItemStack stack, float temperature) {
        if (stack.isEmpty() || !WoodenCogCommonConfigs.fanBurntFoodEnabled()) {
            return stack;
        }

        TagKey<Item> food = foodTag();
        if (food == null || !stack.is(food)) {
            // Not food, so this rule has nothing to say about the stack at all. A count left on a
            // stack that is no longer in the tag is not touched either: it can only have been written
            // below, back when the tag still matched, and the tag itself is a datapack decision.
            return stack;
        }

        int burntTicks = burntTicks(stack);
        if (temperature < WoodenCogCommonConfigs.fanBurntFoodTemperature()) {
            // The exposure has to be continuous, so one tick below the temperature starts the count
            // over. This is also what keeps the tag from sitting on an item forever: an item that
            // cooled down is made to look like the item it was before it was ever hot.
            if (burntTicks != 0) {
                setBurntTicks(stack, 0);
            }
            return stack;
        }

        Item residue = resultItem();
        if (residue == null) {
            // There is nothing to convert the food into, so the food is left exactly as it is and no
            // count is started either - a modpack without the residue item sees no change at all.
            // This is the "KubeJS is not installed, or the id is a typo" case, and it must neither
            // eat the item nor complain once per tick; resultItem reports it once per configured id.
            return stack;
        }

        if (burntTicks + 1 < WoodenCogCommonConfigs.fanBurntFoodDuration()) {
            setBurntTicks(stack, burntTicks + 1);
            return stack;
        }

        // Count 1 and a brand new stack on purpose: the residue is asked to carry none of the food's
        // own data, so it gets neither the TFC heat nor the TFC food capability nor the count above.
        return new ItemStack(residue, 1);
    }

    /** The count stored on the stack, or 0 when the stack has never been hot enough. */
    private static int burntTicks(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? 0 : tag.getInt(BURNT_TICKS_KEY);
    }

    /**
     * Writes the count, or removes the key again at zero. Leaving a zero behind would keep the stack
     * from stacking with the very same item that was never hot, because a stack is compared by its
     * whole tag, so a reset takes the key with it - and the empty compound, if that was all that was
     * in the tag, goes too.
     */
    private static void setBurntTicks(ItemStack stack, int ticks) {
        if (ticks > 0) {
            stack.getOrCreateTag().putInt(BURNT_TICKS_KEY, ticks);
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        tag.remove(BURNT_TICKS_KEY);
        if (tag.isEmpty()) {
            stack.setTag(null);
        }
    }

    /**
     * The tag that marks an item as food, rebuilt only when the configured id changes.
     *
     * <p>The shipped default is TFC's own {@code tfc:foods}, which is the file
     * {@code data/tfc/tags/items/foods.json} in TFC and the field
     * {@code net.dries007.tfc.common.TFCTags.Items.FOODS} in its code. There is no singular
     * {@code tfc:food} tag in TFC at all, so an id of that name would match nothing.
     *
     * @return the tag, or null when the configured id is not a valid resource location
     */
    private static TagKey<Item> foodTag() {
        String id = WoodenCogCommonConfigs.fanBurntFoodTag();
        if (!id.equals(tagIdFrom)) {
            tagIdFrom = id;
            ResourceLocation location = ResourceLocation.tryParse(id);
            foodTag = location == null ? null : TagKey.create(Registries.ITEM, location);
        }
        return foodTag;
    }

    /**
     * The item a burnt food item turns into, resolved from the configured id and cached until that id
     * changes.
     *
     * <p>This is the reason nothing in this class names a KubeJS type: the id is a string in the
     * config, looked up in the item registry when it is first needed, which is long after every mod
     * has registered its items. An id nobody answers to reads back as air, which is treated as "there
     * is no result" and reported once, at warn, rather than on every tick.
     *
     * @return the item, or null while no item is registered under the configured id
     */
    private static Item resultItem() {
        String id = WoodenCogCommonConfigs.fanBurntFoodResult();
        if (!id.equals(resultIdFrom)) {
            resultIdFrom = id;
            missingResultReported = false;
            ResourceLocation location = ResourceLocation.tryParse(id);
            Item item = location == null ? null : ForgeRegistries.ITEMS.getValue(location);
            resultItem = item == null || item == Items.AIR ? null : item;
        }
        if (resultItem == null && !missingResultReported) {
            missingResultReported = true;
            WoodenCog.LOGGER.warn("Burnt food result '{}' is not a registered item, food will not burn", id);
        }
        return resultItem;
    }
}
