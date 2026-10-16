"""The first-person suit hand: a hazard-suit sleeve with an armour plate, a
ribbed cuff and a gloved hand with a full finger rig, posed in a relaxed curl.

Built in the arm model part's space (1/16 block, y down the arm), for the
right arm; the mod mirrors it for the left.
"""

import math
import sys

import numpy as np

import textures as tx
from modelkit import (Ellipsoid, Model, RoundBox, RoundCone, Displace, rot_x, rot_z)

m = Model("suit_hand")
CX = -1.0
forearm = m.bone("forearm", -1, (CX, -2.0, 0))
hand = m.bone("hand", forearm, (CX, 7.2, 0))

shapes = []
sleeve = RoundCone((CX, -2.6, 0), (CX, 6.0, 0), 2.15, 1.85, forearm, "suit", squash=(1.0, 1.0, 1.06))
shapes.append(Displace(sleeve, lambda p: 0.045 * np.sin(p[:, 1] * 2.3 + np.sin(p[:, 2] * 1.7) * 1.5)))
# Armour plate on the back of the forearm.
shapes.append(RoundBox((CX - 1.95, 2.3, 0), (0.42, 2.7, 1.45), 0.38, forearm, "plate", blend=0.12, rotation=rot_z(0.04)))
cuff = RoundCone((CX, 5.55, 0), (CX, 6.95, 0), 2.02, 1.9, forearm, "cuff", blend=0.05)
shapes.append(Displace(cuff, lambda p: 0.06 * np.sin(p[:, 1] * 13.0)))

# Wrist, strap and palm.
shapes.append(RoundCone((CX, 6.6, 0), (CX, 7.9, 0), 1.6, 1.48, hand, "glove", blend=0.1, squash=(0.82, 1, 1.08)))
shapes.append(RoundCone((CX, 7.0, 0), (CX, 7.45, 0), 1.7, 1.68, hand, "strap", blend=0.05, squash=(0.82, 1, 1.08)))
shapes.append(RoundBox((CX - 0.05, 9.25, 0), (0.66, 1.85, 1.72), 0.6, hand, "glove", blend=0.45))
# Knuckle ridge on the back, thenar and hypothenar pads on the palm side.
shapes.append(Ellipsoid((CX - 0.25, 10.75, 0.05), (0.55, 0.45, 1.8), hand, "glove", blend=0.3))
shapes.append(Ellipsoid((CX + 0.45, 8.6, -1.15), (0.6, 1.35, 0.8), hand, "glove", blend=0.4))
shapes.append(Ellipsoid((CX + 0.38, 9.2, 1.3), (0.5, 1.4, 0.52), hand, "glove", blend=0.35))

FINGERS = [
    # name, z, knuckle y, lengths, base radius
    ("index", -1.27, 10.85, (1.55, 1.05, 0.85), 0.46),
    ("middle", -0.42, 11.05, (1.7, 1.15, 0.92), 0.47),
    ("ring", 0.44, 10.9, (1.6, 1.1, 0.88), 0.45),
    ("pinky", 1.25, 10.5, (1.22, 0.84, 0.72), 0.38),
]
for name, z, y0, lengths, r in FINGERS:
    direction = np.array([0.0, 1.0, z * 0.07])
    direction /= np.linalg.norm(direction)
    start = np.array([CX - 0.05, y0, z])
    parent = hand
    radius = r
    for seg, length in enumerate(lengths):
        bone = m.bone("%s%d" % (name, seg + 1), parent, start)
        end = start + direction * length
        r_end = radius * 0.9
        material = "pad" if seg == 2 else "glove"
        shapes.append(RoundCone(start, end, radius * 1.04, r_end, bone, material, blend=0.12 if seg else 0.25,
                                squash=(0.92, 1, 1)))
        parent = bone
        start = end
        radius = r_end

thumb_points = [(CX + 0.1, 8.0, -1.4), (CX + 0.8, 9.35, -2.2), (CX + 1.3, 10.45, -2.55), (CX + 1.65, 11.3, -2.65)]
thumb_radii = [0.68, 0.56, 0.5, 0.42]
parent = hand
for seg in range(3):
    bone = m.bone("thumb%d" % (seg + 1), parent, thumb_points[seg])
    material = "pad" if seg == 2 else "glove"
    shapes.append(RoundCone(thumb_points[seg], thumb_points[seg + 1], thumb_radii[seg], thumb_radii[seg + 1], bone, material,
                            blend=0.35 if seg == 0 else 0.12))
    parent = bone

# The sleeve is meshed coarser than the hand; the glove's open end is hidden
# inside the cuff.
arm_shapes = shapes[:3]
hand_shapes = shapes[3:]
verts, tris = m.build_parts([
    (arm_shapes, (-3.8, -2.8, -2.6), (1.6, 7.3, 2.6), 0.17),
    (hand_shapes, (-2.9, 6.3, -3.7), (1.4, 14.6, 2.6), 0.09),
])
print("hand:", verts, "vertices", tris, "triangles")

size = 128
m.textures = {
    "suit": tx.fabric(size, (204, 104, 32), 11),
    "plate": tx.paint(size, (222, 122, 38), 12, chips=(80, 70, 60), chip_amount=0.3),
    "cuff": tx.ribbed(size, (92, 92, 94), 13),
    "glove": tx.leather(size, (40, 38, 36), 14),
    "strap": tx.leather(size, (62, 58, 52), 15),
    "pad": tx.rubber(size, (58, 58, 62), 16),
}


def curl(amount):
    rot = {}
    bends = {"index": (0.16, 0.3, 0.22), "middle": (0.2, 0.36, 0.25), "ring": (0.25, 0.4, 0.27), "pinky": (0.3, 0.44, 0.28)}
    for name, (a, b, c) in bends.items():
        for seg, angle in enumerate((a, b, c)):
            rot["%s%d" % (name, seg + 1)] = (0.0, 0.0, -angle * amount)
    rot["thumb1"] = (0.1 * amount, 0.0, -0.12 * amount)
    rot["thumb2"] = (0.0, 0.0, -0.18 * amount)
    rot["thumb3"] = (0.0, 0.0, -0.22 * amount)
    rot["hand"] = (0.0, 0.0, -0.06 * amount)
    return rot


frames = []
for i in range(40):
    breathe = math.sin(i / 40 * 2 * math.pi)
    frames.append(m.pose(curl(1.0 + 0.05 * breathe)))
m.sequence("idle", 10.0, True, frames)
m.sequence("fist", 10.0, False, [m.pose(curl(2.4))])
m.sequence("open", 10.0, False, [m.pose(curl(0.15))])
print("wrote", m.write(sys.argv[1]), "bytes")
