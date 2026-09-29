package com.goofy.viberotations.client.mixin;

import com.goofy.viberotations.client.PvpRecorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Shadow
	protected int missTime;

	/**
	 * startAttack runs once per attack-key press. Injecting at HEAD records every click,
	 * including ones vanilla drops because of the miss cooldown ({@code missTime > 0}).
	 */
	@Inject(method = "startAttack", at = @At("HEAD"))
	private void rotationLogger$onStartAttack(CallbackInfoReturnable<Boolean> cir) {
		PvpRecorder.INSTANCE.onClick((Minecraft) (Object) this, missTime > 0);
	}
}
