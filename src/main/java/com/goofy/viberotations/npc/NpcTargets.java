package com.goofy.viberotations.npc;

import com.goofy.viberotations.mixin.BlockDisplayAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The NPC's auto targets as glowing target blocks. They are block display entities: no collision,
 * nothing in the world is replaced, and they are removed when reached or when auto mode stops.
 * The block's centre sits exactly on the target direction. The current target glows; the rest
 * of the burst doesn't until it's their turn.
 */
final class NpcTargets implements NpcAutoRun.Targets {

	/** Marks our displays so leftovers (e.g. after a crash) can be found and removed. */
	static final String TAG = "viberotations_target";

	private final ServerLevel level;
	private final Mannequin npc;
	private final List<Display.BlockDisplay> shown = new ArrayList<>();

	NpcTargets(ServerLevel level, Mannequin npc) {
		this.level = level;
		this.npc = npc;
	}

	@Override
	public void show(double startYaw, double startPitch, List<double[]> waypoints) {
		clear();
		Vec3 eye = npc.getEyePosition();
		double prevYaw = startYaw;
		double prevPitch = startPitch;
		for (int i = 0; i < waypoints.size(); i++) {
			double yaw = waypoints.get(i)[0];
			double pitch = waypoints.get(i)[1];
			// Small steps get a farther (smaller-looking) block so neighbouring targets don't overlap.
			double step = Math.max(1, Math.hypot(yaw - prevYaw, pitch - prevPitch));
			double dist = Math.max(4, Math.min(24, 0.5 / Math.tan(Math.toRadians(step / 3))));
			Vec3 centre = eye.add(Vec3.directionFromRotation((float) pitch, (float) yaw).scale(dist));
			Display.BlockDisplay d = EntityType.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
			if (d == null) {
				// Indices must line up with the waypoints, so show none rather than some.
				clear();
				return;
			}
			((BlockDisplayAccessor) d).viberotations$setBlockState(Blocks.TARGET.defaultBlockState());
			// A block display draws its block from its position to position + 1.
			d.setPos(centre.x - 0.5, centre.y - 0.5, centre.z - 0.5);
			d.addTag(TAG);
			d.setGlowingTag(i == 0);
			level.addFreshEntity(d);
			shown.add(d);
			prevYaw = yaw;
			prevPitch = pitch;
		}
	}

	@Override
	public void reached(int index) {
		if (index < shown.size()) {
			shown.get(index).discard();
			if (index + 1 < shown.size()) {
				shown.get(index + 1).setGlowingTag(true);
			}
		}
	}

	@Override
	public void clear() {
		shown.forEach(Entity::discard);
		shown.clear();
	}

	/** Removes every target display in the level, including ones left behind by a crash. */
	static void removeAll(ServerLevel level) {
		level.getEntities(EntityType.BLOCK_DISPLAY, d -> d.getTags().contains(TAG)).forEach(Entity::discard);
	}
}
