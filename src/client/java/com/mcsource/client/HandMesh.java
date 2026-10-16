package com.mcsource.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;

/**
 * A modelled first-person hand in a hazard suit, in place of Minecraft's
 * blocky arm: a tapered sleeve, a ribbed cuff, a gloved wrist and palm, and
 * jointed fingers and thumb resting in a relaxed curl. The shapes are built
 * from smooth-shaded tubes here in code; no Valve models are used.
 *
 * <p>Coordinates are in the arm model part's space (1/16 block, y pointing
 * down the arm from the shoulder), for the right arm. The left arm is the
 * mirror image.
 */
public final class HandMesh {
	/** Texture regions in suit_hand.png (u0, v0, u1, v1). */
	private static final float[] SUIT = {0.0F, 0.0F, 0.5F, 0.5F};
	private static final float[] GLOVE = {0.5F, 0.0F, 1.0F, 0.5F};
	private static final float[] CUFF = {0.0F, 0.5F, 0.5F, 1.0F};
	private static final float[] PAD = {0.5F, 0.5F, 1.0F, 1.0F};

	private static final int SIDES = 12;
	private static final int FINGER_SIDES = 8;

	private static float[] right;
	private static float[] left;

	/** Vertex data, 12 floats each: x, y, z, nx, ny, nz, u, v, and padding. */
	private final List<float[]> vertices = new ArrayList<>();

	private HandMesh() {
	}

	public static void emit(PoseStack.Pose pose, VertexConsumer consumer, int light, boolean leftArm) {
		float[] data = mesh(leftArm);
		for (int i = 0; i < data.length; i += 8) {
			consumer.addVertex(pose, data[i] / 16.0F, data[i + 1] / 16.0F, data[i + 2] / 16.0F)
					.setColor(-1)
					.setUv(data[i + 6], data[i + 7])
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(light)
					.setNormal(pose, data[i + 3], data[i + 4], data[i + 5]);
		}
	}

	private static synchronized float[] mesh(boolean leftArm) {
		if (right == null) {
			HandMesh builder = new HandMesh();
			builder.build();
			right = builder.flatten(false);
			left = builder.flatten(true);
		}
		return leftArm ? left : right;
	}

	private float[] flatten(boolean mirror) {
		float[] out = new float[vertices.size() * 8];
		int quads = vertices.size() / 4;
		for (int q = 0; q < quads; q++) {
			for (int k = 0; k < 4; k++) {
				// Mirroring flips the winding, so reverse each quad's vertex order.
				float[] v = vertices.get(q * 4 + (mirror ? 3 - k : k));
				int o = (q * 4 + k) * 8;
				// The right arm is centred at x = -1, the left at x = +1.
				out[o] = mirror ? -v[0] : v[0];
				out[o + 1] = v[1];
				out[o + 2] = v[2];
				out[o + 3] = mirror ? -v[3] : v[3];
				out[o + 4] = v[4];
				out[o + 5] = v[5];
				out[o + 6] = v[6];
				out[o + 7] = v[7];
			}
		}
		return out;
	}

	private void build() {
		float cx = -1.0F;
		Vector3f across = new Vector3f(1, 0, 0);
		// Sleeve, slightly tapered towards the wrist.
		tube(new Vector3f(cx, -2.0F, 0), new Vector3f(cx, 6.2F, 0), 2.15F, 2.1F, 1.85F, 1.8F, across, SIDES, SUIT, false, false);
		// Ribbed cuff where the glove meets the sleeve.
		tube(new Vector3f(cx, 5.6F, 0), new Vector3f(cx, 6.9F, 0), 2.0F, 1.95F, 1.9F, 1.85F, across, SIDES, CUFF, true, true);
		// Wrist.
		tube(new Vector3f(cx, 6.8F, 0), new Vector3f(cx, 7.8F, 0), 1.35F, 1.45F, 1.3F, 1.55F, across, SIDES, GLOVE, false, false);
		// Palm: flat across x (thickness), wide along z, palm facing +x.
		tube(new Vector3f(cx, 7.6F, 0), new Vector3f(cx + 0.15F, 10.3F, 0), 1.0F, 1.7F, 0.85F, 2.0F, across, SIDES, GLOVE, true, true);

		// Fingers along the end of the palm, index at -z beside the thumb.
		float[] zs = {-1.4F, -0.48F, 0.45F, 1.35F};
		float[] lengths = {1.0F, 1.08F, 1.0F, 0.8F};
		for (int f = 0; f < 4; f++) {
			finger(new Vector3f(cx + 0.15F, 10.1F, zs[f]), lengths[f], f == 3 ? 0.3F : 0.36F);
		}
		// Thumb from the side of the palm, angled forward and in.
		Vector3f base = new Vector3f(cx + 0.45F, 8.2F, -1.55F);
		Vector3f dir = new Vector3f(0.35F, 0.75F, -0.55F).normalize();
		Vector3f mid = new Vector3f(dir).mul(1.5F).add(base);
		tube(base, mid, 0.52F, 0.5F, 0.45F, 0.43F, across, FINGER_SIDES, GLOVE, true, false);
		Vector3f dir2 = new Vector3f(0.55F, 0.8F, -0.2F).normalize();
		Vector3f tip = new Vector3f(dir2).mul(1.15F).add(mid);
		tube(mid, tip, 0.45F, 0.43F, 0.36F, 0.34F, across, FINGER_SIDES, PAD, true, true);
	}

	/** Three segments, each bent a little further towards the palm (+x). */
	private void finger(Vector3f start, float scale, float radius) {
		float[] segments = {1.5F, 1.0F, 0.75F};
		float[] bend = {12.0F, 30.0F, 28.0F};
		float angle = 0.0F;
		Vector3f from = new Vector3f(start);
		float r = radius;
		Vector3f across = new Vector3f(0, 0, 1);
		for (int s = 0; s < 3; s++) {
			angle += bend[s];
			float rad = angle * Mth.DEG_TO_RAD;
			Vector3f dir = new Vector3f(Mth.sin(rad), Mth.cos(rad), 0);
			Vector3f to = new Vector3f(dir).mul(segments[s] * scale).add(from);
			float rEnd = r * 0.9F;
			tube(from, to, r, r, rEnd, rEnd, across, FINGER_SIDES, s == 2 ? PAD : GLOVE, true, true);
			from = to;
			r = rEnd;
		}
	}

	/**
	 * An elliptical tube from {@code a} to {@code b} with radii (ra, rb) at each
	 * end measured along {@code hint} and across it, smooth-shaded, with
	 * optional end caps.
	 */
	private void tube(Vector3f a, Vector3f b, float ra1, float rb1, float ra2, float rb2, Vector3f hint, int sides, float[] region,
			boolean capStart, boolean capEnd) {
		Vector3f axis = new Vector3f(b).sub(a).normalize();
		Vector3f u = new Vector3f(hint).sub(new Vector3f(axis).mul(hint.dot(axis)));
		if (u.lengthSquared() < 1.0E-6F) {
			u.set(0, 0, 1).sub(new Vector3f(axis).mul(axis.z));
		}
		u.normalize();
		Vector3f v = new Vector3f(axis).cross(u).normalize();
		for (int i = 0; i < sides; i++) {
			float t0 = (float) i / sides;
			float t1 = (float) (i + 1) / sides;
			ring(a, b, u, v, ra1, rb1, ra2, rb2, t0, t1, region);
		}
		if (capStart) {
			cap(a, u, v, ra1, rb1, new Vector3f(axis).negate(), sides, region, true);
		}
		if (capEnd) {
			cap(b, u, v, ra2, rb2, axis, sides, region, false);
		}
	}

	private void ring(Vector3f a, Vector3f b, Vector3f u, Vector3f v, float ra1, float rb1, float ra2, float rb2, float t0, float t1,
			float[] region) {
		// Wound so the outside faces out (counter-clockwise seen from outside).
		float[][] corners = {{t0, 0}, {t1, 0}, {t1, 1}, {t0, 1}};
		for (float[] c : corners) {
			float theta = c[0] * Mth.TWO_PI;
			float cos = Mth.cos(theta);
			float sin = Mth.sin(theta);
			boolean end = c[1] > 0.5F;
			float ra = end ? ra2 : ra1;
			float rb = end ? rb2 : rb1;
			Vector3f centre = end ? b : a;
			Vector3f p = new Vector3f(centre).add(new Vector3f(u).mul(cos * ra)).add(new Vector3f(v).mul(sin * rb));
			Vector3f n = new Vector3f(u).mul(cos / ra).add(new Vector3f(v).mul(sin / rb)).normalize();
			vertex(p, n, region, c[0], c[1]);
		}
	}

	private void cap(Vector3f centre, Vector3f u, Vector3f v, float ra, float rb, Vector3f normal, int sides, float[] region, boolean start) {
		// A slightly domed cap: rim on the tube, tip pushed out along the axis.
		Vector3f tip = new Vector3f(normal).mul(Math.min(ra, rb) * 0.6F).add(centre);
		for (int i = 0; i < sides; i++) {
			float th0 = (float) i / sides * Mth.TWO_PI;
			float th1 = (float) (i + 1) / sides * Mth.TWO_PI;
			Vector3f p0 = new Vector3f(centre).add(new Vector3f(u).mul(Mth.cos(th0) * ra)).add(new Vector3f(v).mul(Mth.sin(th0) * rb));
			Vector3f p1 = new Vector3f(centre).add(new Vector3f(u).mul(Mth.cos(th1) * ra)).add(new Vector3f(v).mul(Mth.sin(th1) * rb));
			Vector3f n0 = new Vector3f(p0).sub(centre).normalize().add(normal).normalize();
			Vector3f n1 = new Vector3f(p1).sub(centre).normalize().add(normal).normalize();
			float t0 = (float) i / sides;
			float t1 = (float) (i + 1) / sides;
			// Quad with the tip repeated; wound to face outwards.
			if (start) {
				vertex(tip, normal, region, (t0 + t1) / 2, 0.5F);
				vertex(tip, normal, region, (t0 + t1) / 2, 0.5F);
				vertex(p1, n1, region, t1, 0);
				vertex(p0, n0, region, t0, 0);
			} else {
				vertex(p0, n0, region, t0, 1);
				vertex(p1, n1, region, t1, 1);
				vertex(tip, normal, region, (t0 + t1) / 2, 0.5F);
				vertex(tip, normal, region, (t0 + t1) / 2, 0.5F);
			}
		}
	}

	private void vertex(Vector3f p, Vector3f n, float[] region, float s, float t) {
		float uu = region[0] + (region[2] - region[0]) * s;
		float vv = region[1] + (region[3] - region[1]) * t;
		vertices.add(new float[] {p.x, p.y, p.z, n.x, n.y, n.z, uu, vv});
	}
}
