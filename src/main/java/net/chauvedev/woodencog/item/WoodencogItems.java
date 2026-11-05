package net.chauvedev.woodencog.item;

import net.chauvedev.woodencog.WoodenCog;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class WoodencogItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, WoodenCog.MOD_ID);

    /**
     * 电磁炉的高温升级件。对着电磁炉右键装上，温度上限 2000 → 2500。
     *
     * <p>2026-11-03 和 {@code electric_stove} 一起加的，见 {@link ElectricStoveUpgradeItem}。
     */
    public static final RegistryObject<Item> ELECTRIC_STOVE_UPGRADE = ITEMS.register(
        "electric_stove_upgrade",
        () -> new ElectricStoveUpgradeItem(new Item.Properties().stacksTo(16))
    );

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);

        // Upstream used to register an "unfinished" twin of every TFC metal item here, one per
        // entry of TFCItems.METAL_ITEMS - roughly 477 items named <tfc path>/unfinished.
        //
        // Nothing ever referred to them: no recipe, tag or loot table in this mod's data, no
        // creative tab and no other mod in the pack. They also had no translation entries, so
        // they surfaced as raw keys such as
        //   item.woodencog.metal.knife_blade.black_bronze.unfinished
        // and 240 of them had no model either. The registration was removed in this build so
        // they stop cluttering the JEI item list. See NOTICE.md for the full rationale.
        //
        // Restore the loop from upstream 1.2.17 (commit a18dd48, "New Test Block, Item") if a
        // datapack is ever written that needs these items.
    }
}
