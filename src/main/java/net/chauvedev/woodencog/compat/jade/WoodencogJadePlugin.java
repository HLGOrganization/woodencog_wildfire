package net.chauvedev.woodencog.compat.jade;

import net.chauvedev.woodencog.block.electricstove.ElectricStoveBlock;
import net.chauvedev.woodencog.block.electricstove.ElectricStoveBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

import java.util.Locale;

@WailaPlugin
public class WoodencogJadePlugin implements IWailaPlugin, IBlockComponentProvider {
    private static final ResourceLocation STOVE_TEMPERATURE = new ResourceLocation("woodencog", "electric_stove_temperature");

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(this, ElectricStoveBlock.class);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (accessor.getBlockEntity() instanceof ElectricStoveBlockEntity stove) {
            String temperature = String.format(Locale.ROOT, "%.0f°C", stove.getCurrentTemperature());
            tooltip.add(Component.literal(temperature).withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public ResourceLocation getUid() {
        return STOVE_TEMPERATURE;
    }
}
