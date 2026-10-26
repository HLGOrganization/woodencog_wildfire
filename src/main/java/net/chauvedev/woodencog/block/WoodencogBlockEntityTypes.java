package net.chauvedev.woodencog.block;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.OrientedRotatingVisual;
import com.simibubi.create.content.kinetics.transmission.SplitShaftVisual;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.BlockEntityEntry;
import net.chauvedev.woodencog.WoodenCog;
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
        .validBlocks(WoodencogBlocks.TUNNEL_HEATER)
        .register();
}
