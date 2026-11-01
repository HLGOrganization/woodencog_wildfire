package net.chauvedev.woodencog.block;

import com.simibubi.create.foundation.data.*;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.TooltipModifier;
import com.tterrag.registrate.util.entry.BlockEntry;
import net.chauvedev.woodencog.WoodenCog;
import net.chauvedev.woodencog.block.generator.WoodenGeneratorBlock;
import net.chauvedev.woodencog.block.hotairchamber.HotAirChamberBlock;
import net.chauvedev.woodencog.block.transformer.CTTransformerBlock;
import net.chauvedev.woodencog.block.tunnelheater.TunnelHeaterBlock;
import net.chauvedev.woodencog.block.tunnelheater.TunnelHeaterItem;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.material.MapColor;

public class WoodencogBlocks {
    private static final CreateRegistrate REGISTRATE = WoodenCog.registrate();

    public static final BlockEntry<CTTransformerBlock> CT_TRANSFORMER = REGISTRATE.block("ct_transformer", CTTransformerBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.noOcclusion().mapColor(MapColor.PODZOL))
            .transform(TagGen.axeOrPickaxe())
            .blockstate(BlockStateGen.directionalBlockProvider(true))
            .addLayer(() -> RenderType::cutoutMipped) //Not sure
            .item()
            .transform(ModelGen.customItemModel())
            .onRegisterAfter(Registries.ITEM, item -> {
                ItemDescription.useKey(item, "block.woodencog.ct_transformer");
                TooltipModifier.REGISTRY.register(item.asItem(), new ItemDescription.Modifier(item.asItem(), FontHelper.Palette.STANDARD_CREATE));
            })
            .register();

    public static final BlockEntry<WoodenGeneratorBlock> WOODEN_GENERATOR = REGISTRATE.block("wooden_generator", WoodenGeneratorBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.noOcclusion().mapColor(MapColor.PODZOL))
            .transform(TagGen.axeOrPickaxe())
            .blockstate(BlockStateGen.directionalBlockProvider(true))
            .addLayer(() -> RenderType::cutoutMipped) //Not sure
            .item()
            .transform(ModelGen.customItemModel())
            .register();

    // The chamber has no blockstate generator on purpose: it is a plain cube whose blockstate,
    // model and item model are shipped by hand (assets/woodencog/blockstates/hot_air_chamber.json),
    // so no data run is needed to give it a model. item().build() returns to the block builder, so
    // the entry stays a BlockEntry.
    public static final BlockEntry<HotAirChamberBlock> HOT_AIR_CHAMBER = REGISTRATE.block("hot_air_chamber", HotAirChamberBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.mapColor(MapColor.PODZOL))
            .transform(TagGen.axeOrPickaxe())
            .item()
            .build()
            .register();

    // Same hand-written resources as the chamber above (blockstates/tunnel_heater.json, the two
    // models and the item model), and noOcclusion() because the block is a 5/16 tall hood rather than
    // a full cube: without it the faces of a neighbouring block are culled where the hood is not.
    //
    // item(TunnelHeaterItem::new) instead of item(): the item is what encases the belt below in
    // INDUSTRIAL_IRON on placement, the same way Create's BeltTunnelItem encases it in brass. See
    // TunnelHeaterItem for why that has to live on the item and not on the block.
    public static final BlockEntry<TunnelHeaterBlock> TUNNEL_HEATER = REGISTRATE.block("tunnel_heater", TunnelHeaterBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.noOcclusion().mapColor(MapColor.PODZOL))
            .transform(TagGen.axeOrPickaxe())
            .item(TunnelHeaterItem::new)
            .build()
            .register();

    /**
     * 刚玉版隧道加热器（2026-10-31）。
     *
     * <p>和上面那个是同一个类、同一个方块实体、同一套行为，只有贴图不同：
     * {@code tunnel_heater_corundum} / {@code tunnel_heater_corundum_on}。
     * 模型文件是照着普通版复制的，UV 一字未改，所以两者外观几何完全一致。
     *
     * <p>因为共用 {@code TunnelHeaterBlockEntity}，两种加热器挨在一起时会算作同一组、
     * 共用一个电池（{@code collectGroup} 只认方块实体类型，不认方块类型）。
     */
    public static final BlockEntry<TunnelHeaterBlock> TUNNEL_HEATER_CORUNDUM = REGISTRATE.block("tunnel_heater_corundum", TunnelHeaterBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.noOcclusion().mapColor(MapColor.PODZOL))
            .transform(TagGen.axeOrPickaxe())
            .item(TunnelHeaterItem::new)
            .build()
            .register();

    public static void register() {}
}
