package net.chauvedev.woodencog.item;

import net.chauvedev.woodencog.block.electricstove.ElectricStoveBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 电磁炉的「高温升级件」。
 *
 * <p>对着电磁炉右键装上，把温度上限从 {@link ElectricStoveBlockEntity#BASE_MAX_TEMPERATURE}（2000）
 * 抬到 {@link ElectricStoveBlockEntity#UPGRADED_MAX_TEMPERATURE}（2500）。装过一次的电磁炉再点不会
 * 重复消耗——{@link ElectricStoveBlockEntity#setUpgraded} 返回 false 时物品不消失。
 *
 * <p>不是消耗品以外的东西：它真的会被吃掉一个（成功装上时 shrink 1）。
 */
public class ElectricStoveUpgradeItem extends Item {

    public ElectricStoveUpgradeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.getBlockEntity(context.getClickedPos()) instanceof ElectricStoveBlockEntity stove) {
            var player = context.getPlayer();
            if (player == null) {
                return InteractionResult.PASS;
            }
            // ★★ 客户端这一支必须返回 PASS，不能返回 SUCCESS。
            //
            //   2026-11-05 踩过的坑：原来写的是
            //       if (level.isClientSide) return stove.isUpgraded() ? PASS : SUCCESS;
            //   结果装升级件完全没反应 —— 上限永远停在 2000 °C。
            //   原因：客户端返回 SUCCESS 时，Minecraft 认为"这次交互由客户端处理完了"，
            //   <b>不再把这次右键发给服务端</b>，于是服务端分支里的 setUpgraded(true) 从来没跑过。
            //   客户端只能返回 PASS（让服务端处理）或 sidedSuccess（客户端成功 + 通知服务端）。
            //   这里选 PASS，因为升级的真正逻辑全在服务端。
            if (level.isClientSide) {
                return InteractionResult.PASS;
            }
            if (!stove.setUpgraded(true)) {
                // 已经装过了：不消耗物品。
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    sp.displayClientMessage(
                        Component.translatable("woodencog.electric_stove.already_upgraded")
                            .withStyle(ChatFormatting.GRAY),
                        true
                    );
                }
                return InteractionResult.PASS;
            }
            ItemStack stack = context.getItemInHand();
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                sp.displayClientMessage(
                    Component.translatable("woodencog.electric_stove.upgraded",
                            ElectricStoveBlockEntity.UPGRADED_MAX_TEMPERATURE)
                        .withStyle(ChatFormatting.GREEN),
                    true
                );
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("woodencog.electric_stove.upgrade.tip",
                ElectricStoveBlockEntity.BASE_MAX_TEMPERATURE,
                ElectricStoveBlockEntity.UPGRADED_MAX_TEMPERATURE)
            .withStyle(ChatFormatting.GRAY));
    }
}
