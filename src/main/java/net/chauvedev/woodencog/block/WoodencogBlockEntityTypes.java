package net.chauvedev.woodencog.block;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.OrientedRotatingVisual;
import com.simibubi.create.content.kinetics.transmission.SplitShaftVisual;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.BlockEntityEntry;
import net.chauvedev.woodencog.WoodenCog;
import net.chauvedev.woodencog.block.electricstove.ElectricStoveBlockEntity;
import net.chauvedev.woodencog.block.generator.WoodenGeneratorBlockEntity;
import net.chauvedev.woodencog.block.generator.WoodenGeneratorRenderer;
import net.chauvedev.woodencog.block.hotairchamber.HotAirChamberBlockEntity;
import net.chauvedev.woodencog.block.transformer.CTTransformerBlockEntity;
import net.chauvedev.woodencog.block.transformer.CTTransformerRenderer;
import net.chauvedev.woodencog.block.tunnelheater.TunnelHeaterBlockEntity;

public class WoodencogBlockEntityTypes {

    private static final CreateRegistrate REGISTRATE = WoodenCog.registrate();

    public WoodencogBlockEntityTypes(){}

    public static void register() {}

    public static final BlockEntityEntry<CTTransformerBlockEntity> CT_TRANSFORMER = REGISTRATE
        .blockEntity("ct_transformer", CTTransformerBlockEntity::new)
        .visual(() -> SplitShaftVisual::new, false)
        .validBlocks(WoodencogBlocks.CT_TRANSFORMER)
        .renderer(() -> CTTransformerRenderer::new)
        .register();

    public static final BlockEntityEntry<WoodenGeneratorBlockEntity> WOODEN_GENERATOR = REGISTRATE
        .blockEntity("wooden_generator", WoodenGeneratorBlockEntity::new)
        .visual(() -> OrientedRotatingVisual.of(AllPartialModels.SHAFT_HALF), false)
        .validBlocks(WoodencogBlocks.WOODEN_GENERATOR)
        .renderer(() -> WoodenGeneratorRenderer::new)
        .register();

    // No visual and no renderer: the chamber is a plain cube holding a fluid, nothing moves in it.
    public static final BlockEntityEntry<HotAirChamberBlockEntity> HOT_AIR_CHAMBER = REGISTRATE
        .blockEntity("hot_air_chamber", HotAirChamberBlockEntity::new)
        .validBlocks(WoodencogBlocks.HOT_AIR_CHAMBER)
        .register();

    // No visual and no renderer either, for the same reason. The block entity is a SmartBlockEntity, so
    // IBE#getTicker hands it Create's SmartBlockEntityTicker and tick() runs without anything else here.
    public static final BlockEntityEntry<TunnelHeaterBlockEntity> TUNNEL_HEATER = REGISTRATE
        .blockEntity("tunnel_heater", TunnelHeaterBlockEntity::new)
        // 刚玉版共用这一个方块实体类型：两种加热器是同一个类、同一套行为，只有贴图不同。
        // 副作用是相邻的两种加热器会被算进同一组、共用一个电池（collectGroup 只看方块实体类型）。
        .validBlocks(WoodencogBlocks.TUNNEL_HEATER, WoodencogBlocks.TUNNEL_HEATER_CORUNDUM)
        .register();

    /**
     * 电磁炉。也是 plain SmartBlockEntity，没有 visual 也没有 renderer：
     * 外观走手写的 blockstate + 模型，数值框走 Create 的 ScrollValueBehaviour，两样都不需要 BER。
     */
    public static final BlockEntityEntry<ElectricStoveBlockEntity> ELECTRIC_STOVE = REGISTRATE
        .blockEntity("electric_stove", ElectricStoveBlockEntity::new)
        .validBlocks(WoodencogBlocks.ELECTRIC_STOVE)
        .register();
}
