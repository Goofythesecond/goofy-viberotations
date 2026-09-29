package com.goofy.viberotations.client;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Geometry helpers for aim and reach. Angles are in degrees, using Minecraft's yaw/pitch convention. */
final class AimMath {

	private AimMath() {}

	/** Distance from the eye to the nearest point of the hitbox — what vanilla reach checks. */
	static double reach(Vec3 eye, AABB box) {
		return eye.distanceTo(clamp(eye, box));
	}

	static float yawTo(Vec3 from, Vec3 to) {
		return Mth.wrapDegrees((float) (Math.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90f);
	}

	static float pitchTo(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		return (float) (-Math.atan2(to.y - from.y, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
	}

	/** Angle in degrees between two direction vectors. */
	static double angle(Vec3 a, Vec3 b) {
		double len = a.length() * b.length();
		if (len < 1e-9) {
			return 0;
		}
		return Math.acos(Mth.clamp(a.dot(b) / len, -1.0, 1.0)) * Mth.RAD_TO_DEG;
	}

	/** Whether the look ray passes through the hitbox within {@code range} blocks. */
	static boolean rayHits(Vec3 eye, Vec3 look, AABB box, double range) {
		return box.contains(eye) || box.clip(eye, eye.add(look.scale(range))).isPresent();
	}

	/**
	 * The point on the hitbox closest to the look ray. Two projection passes are
	 * accurate to a small fraction of a degree for player-sized boxes.
	 */
	static Vec3 aimPoint(Vec3 eye, Vec3 look, AABB box) {
		Vec3 q = box.getCenter();
		for (int i = 0; i < 2; i++) {
			double t = Math.max(0, q.subtract(eye).dot(look));
			q = clamp(eye.add(look.scale(t)), box);
		}
		return q;
	}

	/** Degrees the crosshair must move to touch the hitbox. 0 when already on it. */
	static double boxAimError(Vec3 eye, Vec3 look, AABB box) {
		if (rayHits(eye, look, box, 64)) {
			return 0;
		}
		return angle(look, aimPoint(eye, look, box).subtract(eye));
	}

	private static Vec3 clamp(Vec3 p, AABB box) {
		return new Vec3(
				Mth.clamp(p.x, box.minX, box.maxX),
				Mth.clamp(p.y, box.minY, box.maxY),
				Mth.clamp(p.z, box.minZ, box.maxZ));
	}
}
