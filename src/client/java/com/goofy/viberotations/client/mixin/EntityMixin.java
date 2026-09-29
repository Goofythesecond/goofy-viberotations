package com.goofy.viberotations.client.mixin;

import com.goofy.viberotations.client.PvpRecorder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityMixin {
	/** turn() is how mouse movement reaches the player, once per rendered frame. */
	@Inject(method = "turn", at = @At("TAIL"))
	private void rotationLogger$onTurn(double yRot, double xRot, CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer player) {
			PvpRecorder.INSTANCE.onTurn(player, yRot, xRot);
		}
	}
}
