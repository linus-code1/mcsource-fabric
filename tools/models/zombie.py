"""A shambling zombie scientist in a torn lab coat with a parasite clamped on
its head, with long clawed hands, and idle, walk, attack and death
animations. Half-Life model axes: x forward, y left, z up, 40 units a block.
"""

import math
import sys

import numpy as np

import textures as tx
from modelkit import Ellipsoid, Model, RoundBox, RoundCone, Displace, rot_x, rot_y

m = Model("zombie")
pelvis = m.bone("pelvis", -1, (0, 0, 38))
spine = m.bone("spine", pelvis, (0, 0, 45))
chest = m.bone("chest", spine, (0, 0, 54))
neck = m.bone("neck", chest, (0.5, 0, 63))
head = m.bone("head", neck, (1, 0, 67))
crab = m.bone("parasite", head, (1.5, 0, 74))
SHOULDER_Z = 60.0
arm = {}
# Arms are modelled straight out to the sides (a T pose) so they stay apart
# from the body; the animations swing them down.
for side, y in (("l", 1), ("r", -1)):
    arm[side + "_upper"] = m.bone(side + "_upper", chest, (0, 8.6 * y, SHOULDER_Z))
    arm[side + "_fore"] = m.bone(side + "_fore", arm[side + "_upper"], (0, 20.0 * y, SHOULDER_Z))
    arm[side + "_hand"] = m.bone(side + "_hand", arm[side + "_fore"], (0, 30.0 * y, SHOULDER_Z))
    arm[side + "_thigh"] = m.bone(side + "_thigh", pelvis, (0, 4.4 * y, 36))
    arm[side + "_shin"] = m.bone(side + "_shin", arm[side + "_thigh"], (0.3, 4.6 * y, 19.5))
    arm[side + "_foot"] = m.bone(side + "_foot", arm[side + "_shin"], (0, 4.6 * y, 3.5))

body = [
    # Thin torso under the coat.
    Ellipsoid((0, 0, 55.5), (4.4, 7.8, 6.2), chest, "coat"),
    Ellipsoid((0.4, 0, 47), (3.4, 5.4, 5.6), spine, "coat", blend=2.0),
    Ellipsoid((0, 0, 39), (3.7, 6.0, 4.2), pelvis, "pants", blend=1.2),
    # Short coat hem around the hips.
    RoundCone((-0.2, 0, 42), (-0.4, 0, 36.0), 5.6, 6.2, pelvis, "coat", blend=1.0, squash=(0.66, 1.0, 1.0)),
    # Shoulders.
    Ellipsoid((0, 6.4, 59.5), (3.5, 3.8, 2.8), chest, "coat", blend=1.8),
    Ellipsoid((0, -6.4, 59.5), (3.5, 3.8, 2.8), chest, "coat", blend=1.8),
    # Neck, head, jaw hanging open.
    RoundCone((0.3, 0, 61), (1.0, 0, 67.5), 2.3, 2.1, neck, "skin", blend=1.2),
    Ellipsoid((1.2, 0, 70.4), (4.3, 3.7, 4.6), head, "skin", blend=1.0),
    Ellipsoid((3.0, 0, 66.6), (2.3, 2.7, 1.7), head, "skin", blend=1.0),
    # Parasite: a flat round body clamped over the head with legs hooked down.
    Ellipsoid((1.8, 0, 74.8), (5.8, 5.2, 2.5), crab, "parasite", blend=0.8),
    Ellipsoid((5.6, 0, 73.4), (1.8, 3.3, 1.6), crab, "parasite", blend=0.8),
]
for side, y in (("l", 1), ("r", -1)):
    Y = lambda v: v * y
    body += [
        RoundCone((0, Y(8.6), SHOULDER_Z), (0, Y(20.0), SHOULDER_Z), 2.6, 2.1, arm[side + "_upper"], "coat", blend=1.2),
        RoundCone((0, Y(20.0), SHOULDER_Z), (0, Y(29.5), SHOULDER_Z), 1.8, 1.35, arm[side + "_fore"], "skin", blend=0.8),
        # Torn sleeve end.
        RoundCone((0, Y(19.0), SHOULDER_Z), (0, Y(21.5), SHOULDER_Z), 2.5, 2.35, arm[side + "_fore"], "coat", blend=0.4),
        Ellipsoid((0.0, Y(31.5), SHOULDER_Z), (2.0, 2.2, 1.15), arm[side + "_hand"], "skin", blend=0.7),
        RoundCone((0, Y(4.4), 36), (0.3, Y(4.6), 19.5), 3.2, 2.5, arm[side + "_thigh"], "pants", blend=1.2),
        RoundCone((0.3, Y(4.6), 19.5), (0, Y(4.6), 4.0), 2.4, 1.9, arm[side + "_shin"], "pants", blend=0.8),
        RoundBox((2.0, Y(4.6), 2.0), (4.2, 1.8, 2.0), 1.2, arm[side + "_foot"], "shoes", blend=0.8),
    ]
    # Long fingers ending in claws, pointing out along the arm.
    for spread in (-1.0, 0.0, 1.0):
        base = np.array([spread * 0.85, Y(33.0), SHOULDER_Z])
        tip = base + np.array([spread * 0.5, Y(6.0), -0.4])
        body.append(RoundCone(base, tip, 0.55, 0.36, arm[side + "_hand"], "skin", blend=0.5))
        body.append(RoundCone(tip, tip + np.array([0.0, Y(1.8), -0.9]), 0.36, 0.08, arm[side + "_hand"], "claw", blend=0.2))
    thumb = np.array([1.5, Y(31.0), SHOULDER_Z])
    body.append(RoundCone(thumb, thumb + np.array([2.6, Y(2.8), -0.3]), 0.55, 0.32, arm[side + "_hand"], "skin", blend=0.5))
    # Parasite legs.
    for angle in (0.6, 1.4, 2.3):
        a = angle if y > 0 else -angle
        root = np.array([1.8 + 4.4 * math.cos(a), 4.0 * math.sin(a), 74.3])
        knee = root + np.array([1.6 * math.cos(a), 1.6 * math.sin(a), 0.8])
        foot = knee + np.array([0.4 * math.cos(a), 0.6 * math.sin(a), -5.0])
        body.append(RoundCone(root, knee, 0.7, 0.55, crab, "parasite", blend=0.4))
        body.append(RoundCone(knee, foot, 0.55, 0.2, crab, "claw", blend=0.3))

DOWN = math.pi / 2


def arms(rot, l_pitch, r_pitch, l_out=0.12, r_out=0.12, l_elbow=0.3, r_elbow=0.3, l_twist=0.0, r_twist=0.0):
    """Swings the T-posed arms down, then forward by pitch (negative is forward)."""
    rot["l_upper"] = (-DOWN + l_out, l_pitch, l_twist)
    rot["r_upper"] = (DOWN - r_out, r_pitch, r_twist)
    rot["l_fore"] = (0.0, 0.0, -l_elbow)
    rot["r_fore"] = (0.0, 0.0, r_elbow)
    return rot


# Wrinkles in the coat.
body[0] = Displace(body[0], lambda p: 0.18 * np.sin(p[:, 2] * 1.3 + p[:, 1] * 0.7))
body[3] = Displace(body[3], lambda p: 0.35 * np.sin(np.arctan2(p[:, 1], p[:, 0]) * 7.0))

verts, tris = m.build_mesh(body, (-8, -43, -1), (10, 43, 80), 0.68)
print("zombie:", verts, "vertices", tris, "triangles")

size = 128
m.textures = {
    "coat": tx.stained(size, (196, 196, 184), 21),
    "pants": tx.fabric(size, (62, 66, 78), 22, weave=1, dirt=(50, 40, 30)),
    "shoes": tx.leather(size, (34, 30, 26), 23),
    "skin": tx.skin(size, (128, 140, 112), 24),
    "parasite": tx.skin(size, (186, 160, 118), 25, blotch=(150, 110, 80), veins=(120, 70, 60)),
    "claw": tx.paint(size, (196, 186, 150), 26, chips=(120, 100, 80)),
}


def walk(phase):
    s = math.sin(phase)
    c = math.cos(phase)
    rot = {
        "pelvis": (0.06 * s, 0.0, 0.12 * s),
        "spine": (0.0, 0.18, -0.06 * s),
        "chest": (-0.05 * s, 0.14, -0.08 * s),
        "neck": (0.0, -0.1, 0.0),
        "head": (0.12 + 0.05 * s, -0.12, 0.05 * c),
        "l_thigh": (0.0, -0.45 * s - 0.05, 0.0),
        "r_thigh": (0.0, 0.45 * s - 0.05, 0.0),
        "l_shin": (0.0, 0.5 * max(0.0, c) + 0.1, 0.0),
        "r_shin": (0.0, 0.5 * max(0.0, -c) + 0.1, 0.0),
        "l_foot": (0.0, 0.2 * s, 0.0),
        "r_foot": (0.0, -0.2 * s, 0.0),
    }
    arms(rot, -1.15 + 0.12 * s, -1.25 - 0.12 * s, l_elbow=0.35, r_elbow=0.25)
    return m.pose(rot, {"pelvis": (0, 0, -0.9 + 0.7 * abs(c))})


m.sequence("walk", 24.0, True, [walk(i / 30 * 2 * math.pi) for i in range(30)], distance=46.0)


def idle(t):
    s = math.sin(t)
    rot = {
        "spine": (0.0, 0.22 + 0.03 * s, 0.0),
        "chest": (0.04 * s, 0.16, 0.0),
        "head": (0.25, -0.05 + 0.06 * s, 0.1 * math.sin(t * 0.5)),
        "l_thigh": (0.0, -0.08, 0.0),
        "r_thigh": (0.0, 0.0, 0.0),
        "l_shin": (0.0, 0.16, 0.0),
        "r_shin": (0.0, 0.08, 0.0),
    }
    arms(rot, -0.35 + 0.05 * s, -0.3 - 0.05 * s, l_elbow=0.4, r_elbow=0.45)
    return m.pose(rot, {"pelvis": (0, 0, -0.6 + 0.2 * s)})


m.sequence("idle1", 10.0, True, [idle(i / 30 * 2 * math.pi) for i in range(30)])


def attack(t):
    # Both arms lift high and rake down, the right arm leading.
    lift = math.sin(min(t / 0.45, 1.0) * math.pi / 2)
    strike = max(0.0, (t - 0.45) / 0.55)
    swing = -2.7 * lift + 2.3 * math.sin(strike * math.pi / 2)
    rot = {
        "spine": (0.0, 0.1 + 0.35 * strike - 0.15 * lift, 0.0),
        "chest": (0.0, 0.1 + 0.2 * strike, 0.15 * lift - 0.3 * strike),
        "head": (0.15, -0.2 * lift + 0.2 * strike, 0.0),
        "l_thigh": (0.0, -0.25 * strike, 0.0),
        "r_thigh": (0.0, 0.1, 0.0),
        "l_shin": (0.0, 0.3 * strike, 0.0),
    }
    arms(rot, -2.2 * lift + 1.6 * max(0.0, strike - 0.25), swing, l_elbow=0.6 * lift, r_elbow=0.6 * lift - 0.3 * strike + 0.3)
    return m.pose(rot)


m.sequence("attack1", 20.0, False, [attack(i / 19) for i in range(20)])


def die(t):
    # Knees buckle, then the body falls backwards to the ground.
    k = min(1.0, t / 0.35)
    f = max(0.0, (t - 0.25) / 0.75)
    f = f * f * (3 - 2 * f)
    rot = {
        "pelvis": (0.0, -1.45 * f, 0.0),
        "spine": (0.0, 0.2 * k - 0.1 * f, 0.0),
        "chest": (0.0, 0.1 - 0.15 * f, 0.0),
        "head": (0.3 * f, -0.5 * f, 0.0),
        "l_thigh": (0.0, -0.6 * k + 0.9 * f, 0.15 * f),
        "r_thigh": (0.0, -0.5 * k + 1.0 * f, -0.15 * f),
        "l_shin": (0.0, 1.0 * k - 0.6 * f, 0.0),
        "r_shin": (0.0, 0.9 * k - 0.5 * f, 0.0),
    }
    arms(rot, -0.3 - 0.9 * f, -0.3 - 1.1 * f, l_out=0.12 + 0.6 * f, r_out=0.12 + 0.4 * f, l_elbow=0.6 * f + 0.2, r_elbow=0.3 * f + 0.2)
    return m.pose(rot, {"pelvis": (-6.0 * f, 0, -6.0 * k - 26.0 * f)})


m.sequence("dieforward", 20.0, False, [die(i / 19) for i in range(20)])
print("wrote", m.write(sys.argv[1]), "bytes")
