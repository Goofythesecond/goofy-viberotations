package com.goofy.viberotations.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Block displays only get their block from NBT in vanilla; the NPC's target blocks set it directly. */
@Mixin(Display.BlockDisplay.class)
public interface BlockDisplayAccessor {
	@Invoker("setBlockState")
	void viberotations$setBlockState(BlockState state);
}
