"""A go-kart: a low red body with side pods, a seat, a steering wheel, an
engine with exhaust pipes and a rear wing, on four wheels that turn as it
drives. Half-Life model axes: x forward, y left, z up, 40 units a block.
"""

import math
import sys

import numpy as np

import textures as tx
from modelkit import Cylinder, Model, RoundBox, RoundCone, Torus, rot_y

m = Model("kart")
body = m.bone("body", -1, (0, 0, 8))
FRONT_R = 6.0
REAR_R = 7.5
wheels = {
    "wheel_fl": ((19.0, 17.5, FRONT_R), FRONT_R, 2.6),
    "wheel_fr": ((19.0, -17.5, FRONT_R), FRONT_R, 2.6),
    "wheel_rl": ((-19.0, 18.5, REAR_R), REAR_R, 3.6),
    "wheel_rr": ((-19.0, -18.5, REAR_R), REAR_R, 3.6),
}
for name, (pos, r, half) in wheels.items():
    m.bone(name, body, pos)
steer = m.bone("steer", body, (12.0, 0, 18.0))

shapes = [
    RoundBox((0, 0, 4.6), (27, 12.5, 1.5), 1.0, body, "frame"),
    RoundBox((1, 0, 8.4), (20, 9.0, 3.4), 2.6, body, "paint", blend=1.5),
    RoundCone((14, 0, 8.6), (31, 0, 6.8), 6.0, 2.6, body, "paint", blend=2.0, squash=(1.0, 1.6, 0.6)),
    RoundCone((32, -13.5, 5.5), (32, 13.5, 5.5), 1.5, 1.5, body, "plastic", blend=1.0),
    RoundBox((-1, 12.0, 7.0), (9.5, 2.8, 3.0), 2.0, body, "paint", blend=1.5),
    RoundBox((-1, -12.0, 7.0), (9.5, 2.8, 3.0), 2.0, body, "paint", blend=1.5),
    # Seat.
    RoundBox((-5.5, 0, 9.8), (5.0, 6.2, 1.5), 1.2, body, "seat", blend=1.0),
    RoundBox((-10.5, 0, 15.5), (1.8, 6.4, 6.5), 1.6, body, "seat", blend=1.0, rotation=rot_y(-0.3)),
    # Steering column and wheel.
    RoundCone((22, 0, 9.5), (12.5, 0, 17.5), 0.8, 0.7, body, "frame", blend=0.6),
    Torus((12.0, 0, 18.0), (-1.0, 0, 1.3), 4.0, 0.75, steer, "plastic"),
    RoundCone((12.0, -3.8, 18.0), (12.0, 3.8, 18.0), 0.55, 0.55, steer, "plastic", blend=0.3),
    # Engine, exhausts and rear wing.
    RoundBox((-21, 0, 11), (5.0, 7.0, 4.5), 1.5, body, "metal", blend=1.0),
    RoundCone((-23, 4.5, 14), (-30, 5.5, 17.5), 1.1, 1.0, body, "metal", blend=0.6),
    RoundCone((-23, -4.5, 14), (-30, -5.5, 17.5), 1.1, 1.0, body, "metal", blend=0.6),
    RoundCone((-25, 7, 11), (-27, 9, 19), 0.7, 0.7, body, "frame", blend=0.6),
    RoundCone((-25, -7, 11), (-27, -9, 19), 0.7, 0.7, body, "frame", blend=0.6),
    RoundBox((-28, 0, 20), (3.2, 13.5, 0.8), 0.6, body, "paint", blend=0.6),
    # Axles stop short of the hubs so the wheels can spin freely.
    RoundCone((19, -13.5, FRONT_R), (19, 13.5, FRONT_R), 0.9, 0.9, body, "frame", blend=0.8),
    RoundCone((-19, -13.8, REAR_R), (-19, 13.8, REAR_R), 1.1, 1.1, body, "frame", blend=0.8),
]
for name, (pos, r, half) in wheels.items():
    b = m.bone_index(name)
    shapes.append(Cylinder(pos, (0, 1, 0), r, half, min(2.0, half * 0.6), b, "tire"))
    side = 1 if pos[1] > 0 else -1
    shapes.append(Cylinder((pos[0], pos[1] + side * 0.4, pos[2]), (0, 1, 0), r * 0.55, half, 0.6, b, "hub"))

verts, tris = m.build_mesh(shapes, (-33, -24, -0.5), (36, 24, 25), 0.75)
print("kart:", verts, "vertices", tris, "triangles")

size = 128
m.textures = {
    "paint": tx.paint(size, (206, 34, 28), 31, chips=(90, 80, 75), chip_amount=0.12),
    "frame": tx.metal(size, (64, 64, 68), 32),
    "plastic": tx.rubber(size, (34, 34, 36), 33),
    "seat": tx.leather(size, (36, 34, 34), 34),
    "metal": tx.metal(size, (160, 160, 165), 35),
    "tire": tx.tread(size, (30, 30, 30), 36),
    "hub": tx.metal(size, (188, 188, 192), 37),
}

m.sequence("idle", 10.0, True, [m.pose()])
FRAMES = 16
drive = []
for i in range(FRAMES):
    a = i / FRAMES * 2 * math.pi
    rot = {
        "wheel_rl": (0, a, 0), "wheel_rr": (0, a, 0),
        "wheel_fl": (0, a * REAR_R / FRONT_R, 0), "wheel_fr": (0, a * REAR_R / FRONT_R, 0),
    }
    drive.append(m.pose(rot))
m.sequence("drive", 16.0, True, drive, distance=2 * math.pi * REAR_R)
print("wrote", m.write(sys.argv[1]), "bytes")
