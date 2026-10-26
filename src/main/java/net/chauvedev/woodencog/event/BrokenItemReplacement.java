package net.chauvedev.woodencog.event;

import net.chauvedev.woodencog.WoodenCog;
import net.chauvedev.woodencog.config.WoodenCogCommonConfigs;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Puts the configured replacement into the player's hand when an item of theirs is destroyed by
 * running out of durability, instead of leaving the hand empty.
 *
 * <p>This is the answer to a tool that has a "worn out" form: a modpack registers both the tool and a
 * used up version of it, and wants the used up version to appear where the tool was, which vanilla
 * has no rule for. The pairs live in {@code woodencog.broken_items.replacements} in the common config,
 * keyed by item id, so a modpack can add its own tools without this mod being rebuilt.
 *
 * <p>{@code PlayerDestroyItemEvent} is Forge's notification that a player lost an item this way. It
 * carries the stack as it was <em>before</em> it was destroyed - which is what identifies the item,
 * since the stack in the hand is empty by then - and the hand it came from. The hand is nullable, and
 * is null for a destroyed crafting result, so a handler with nothing to write into does nothing.
 *
 * <p>Only {@code woodencog.broken_items.replacements} decides what is replaced. An item that is not
 * in the table is untouched, and an entry that maps an item onto itself is ignored: handing the player
 * a brand new copy of the item that just broke would make breaking it pointless.
 *
 * <p>What this cannot see is worth knowing before trusting it:
 * <ul>
 *   <li>The melee attack path and the "interact with an entity" path in {@code Player} post the event
 *       and then write an empty stack into the hand themselves, so a replacement written from here is
 *       overwritten in the same tick. Those two breaks still end with an empty hand.</li>
 *   <li>Only the vanilla call sites post the event. An item destroyed by a script that writes its
 *       damage value directly, or by any {@code ItemStack.hurtAndBreak} call made outside those sites
 *       (a machine, a deployer, a crafting handler), never reaches this class at all. The TerraFirmaCraft
 *       Wild Fire modpack's own tong wear is one of those: its {@code damageItem} helper sets the
 *       damage value and shrinks the stack by hand.</li>
 *   <li>The event also fires on the client, where the hand is not authoritative; the client is skipped
 *       so that a prediction here cannot fight the slot the server sends.</li>
 * </ul>
 * A block break with a tool, the one path that posts the event and then leaves the hand alone, does
 * get the replacement.
 */
@Mod.EventBusSubscriber(modid = WoodenCog.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BrokenItemReplacement {

    /** The replacement id already reported as unregistered, so a break does not log every time. */
    private static String unresolvableReplacementId;

    private BrokenItemReplacement() {
    }

    @SubscribeEvent
    public static void onPlayerDestroyItem(PlayerDestroyItemEvent event) {
        Player player = event.getEntity();
        InteractionHand hand = event.getHand();
        ItemStack original = event.getOriginal();
        if (player == null || hand == null || original.isEmpty()) {
            // Nothing to put anywhere: a crafting result has no hand, and an already empty stack says
            // nothing about which item the player lost.
            return;
        }
        if (player.level().isClientSide) {
            // The server owns the inventory. Writing the replacement on the client as well would
            // fight the slot the server sends for the same break.
            return;
        }

        ResourceLocation brokenId = BuiltInRegistries.ITEM.getKey(original.getItem());
        if (brokenId == null) {
            return;
        }
        ResourceLocation replacementId = WoodenCogCommonConfigs.brokenItemReplacement(brokenId);
        if (replacementId == null) {
            // Not one of ours, so this break keeps the behaviour it had before this class existed.
            return;
        }

        Item replacement = replacement(replacementId);
        if (replacement == null) {
            // Nothing is registered under the configured id. The item is not taken away early and the
            // stack is not touched; the miss is reported once per id instead of once per break.
            return;
        }
        if (replacement == original.getItem()) {
            // A mapping onto the item that just broke would hand the player a brand new one.
            return;
        }

        player.setItemInHand(hand, new ItemStack(replacement));
    }

    /**
     * The item registered under {@code id}, or null while there is none. The item registry answers an
     * unknown id with air rather than with null, which means the same thing here.
     *
     * <p>The lookup is done on the break and not cached: a break is rare, and a cached item would keep
     * answering with the item of a world that has since been left and rejoined.
     */
    private static Item replacement(ResourceLocation id) {
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == Items.AIR) {
            if (!id.toString().equals(unresolvableReplacementId)) {
                unresolvableReplacementId = id.toString();
                WoodenCog.LOGGER.warn("Broken item replacement '{}' is not a registered item, no item will be replaced with it", id);
            }
            return null;
        }
        return item;
    }
}
