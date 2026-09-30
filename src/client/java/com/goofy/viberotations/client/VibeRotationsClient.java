package com.goofy.viberotations.client;

import com.goofy.viberotations.VibeRotations;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class VibeRotationsClient implements ClientModInitializer {

	private final PvpRecorder recorder = PvpRecorder.INSTANCE;
	private final TrainingMode training = TrainingMode.INSTANCE;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(VibeRotations.id("main"));
		KeyMapping toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.goofy-viberotations.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (toggleKey.consumeClick()) {
				boolean on = recorder.toggle();
				if (mc.player != null) {
					mc.player.displayClientMessage(Component.literal("Goofy VibeRotations: " + (on ? "ON" : "OFF")), true);
				}
			}
			recorder.tick(mc);
			training.tick(mc);
		});
		NpcView.register();
		training.register();
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			recorder.closeSession();
			training.close();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			recorder.closeSession();
			training.close();
		});
	}
}
