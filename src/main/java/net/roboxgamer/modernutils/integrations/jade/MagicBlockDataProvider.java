package net.roboxgamer.modernutils.integrations.jade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.roboxgamer.modernutils.block.entity.custom.MagicBlockBlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;

public enum MagicBlockDataProvider implements
    IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
  INSTANCE;
  
  
  @Override
  public void appendTooltip(
      ITooltip iTooltip,
      BlockAccessor blockAccessor,
      IPluginConfig iPluginConfig) {
    int count = blockAccessor.getServerData().getInt("data");
    IElement elem = IElementHelper.get().text(
        Component.translatable("tooltip.modernutils.data", count)
    );
    iTooltip.add(elem);
  }
  
  
  @Override
  public void appendServerData(CompoundTag compoundTag, BlockAccessor blockAccessor) {
    MagicBlockBlockEntity be = (MagicBlockBlockEntity) blockAccessor.getBlockEntity();
    compoundTag.putInt("data", be.getSpeed());
  }
  
  @Override
  public ResourceLocation getUid() {
    return ModJadePlugin.MAGICDATA;
  }
}
