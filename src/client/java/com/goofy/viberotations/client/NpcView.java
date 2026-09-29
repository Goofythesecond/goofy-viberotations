package com.goofy.viberotations.client;

import com.goofy.viberotations.npc.NpcController;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;

/**
 * {@code /npcview} — look through the NPC's eyes (singleplayer only). Your own player is not
 * moved or rotated; the camera just follows the NPC's head.
 *
 * <p>The client copy of the NPC only gets rotation updates a few times a second, rounded to
 * 1.4°. So while viewing, the exact rotation is copied from the singleplayer server each tick,
 * and the camera interpolates between ticks as usual.</p>
 */
public final class NpcView {

	private static boolean viewing;
	private static float lastYaw;
	private static float lastPitch;

	private NpcView() {}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
				dispatcher.register(ClientCommandManager.literal("npcview").executes(ctx -> {
					Minecraft mc = Minecraft.getInstance();
					if (viewing) {
						stop(mc);
						ctx.getSource().sendFeedback(Component.literal("Back to your own view"));
						return 1;
					}
					if (!mc.hasSingleplayerServer()) {
						ctx.getSource().sendError(Component.literal("/npcview only works in singleplayer"));
						return 0;
					}
					Entity npc = clientNpc(mc);
					if (npc == null) {
						ctx.getSource().sendError(Component.literal("No NPC nearby. Use /npc spawn first."));
						return 0;
					}
					Mannequin server = NpcController.npc();
					lastYaw = server.getYHeadRot();
					lastPitch = server.getXRot();
					mc.setCameraEntity(npc);
					viewing = true;
					ctx.getSource().sendFeedback(Component.literal("Viewing through the NPC. Run /npcview again to return."));
					return 1;
				})));
		ClientTickEvents.END_CLIENT_TICK.register(NpcView::tick);
	}

	private static void tick(Minecraft mc) {
		if (!viewing) {
			return;
		}
		Mannequin server = NpcController.npc();
		Entity client = clientNpc(mc);
		if (server == null || client == null || !mc.hasSingleplayerServer()) {
			stop(mc);
			return;
		}
		if (mc.getCameraEntity() != client) {
			mc.setCameraEntity(client);
		}
		float yaw = server.getYHeadRot();
		float pitch = server.getXRot();
		if (client instanceof Mannequin m) {
			m.yHeadRotO = lastYaw;
			m.yHeadRot = yaw;
			m.yBodyRotO = m.yBodyRot;
		}
		client.xRotO = lastPitch;
		client.setXRot(pitch);
		lastYaw = yaw;
		lastPitch = pitch;
	}

	private static Entity clientNpc(Minecraft mc) {
		Mannequin server = NpcController.npc();
		if (server == null || mc.level == null) {
			return null;
		}
		return mc.level.getEntity(server.getId());
	}

	private static void stop(Minecraft mc) {
		viewing = false;
		if (mc.player != null) {
			mc.setCameraEntity(mc.player);
		}
	}
}
