"""Builds MC:Source's own rigged models and writes them as Half-Life 1 style
.mdl files (version 10), the format the mod's model reader loads.

Shapes are sculpted from signed distance fields: round cones, ellipsoids and
rounded boxes blended with smooth unions, so joints and muscles flow into each
other like a modelled mesh instead of meeting at hard seams. The surface is
extracted with surface nets, each vertex is bound to the bone of the shape it
came from, and textures are painted procedurally and reduced to a 256 colour
palette like GoldSrc's.

Everything here is original to this mod; no Valve models, textures or code.
"""

import math
import struct

import numpy as np


# ---------------------------------------------------------------- shapes


def _len(v):
    return np.sqrt(np.sum(v * v, axis=-1))


class Shape:
    """A solid with a distance function, a bone and a material."""

    def __init__(self, bone, material, blend=0.0):
        self.bone = bone
        self.material = material
        self.blend = blend

    def dist(self, p):
        raise NotImplementedError


class RoundCone(Shape):
    """A capsule from a (radius ra) to b (radius rb)."""

    def __init__(self, a, b, ra, rb, bone, material, blend=0.0, squash=(1.0, 1.0, 1.0)):
        super().__init__(bone, material, blend)
        self.a = np.array(a, float)
        self.b = np.array(b, float)
        self.ra = ra
        self.rb = rb
        self.squash = np.array(squash, float)

    def dist(self, p):
        a, b = self.a, self.b
        ba = b - a
        pa = p - a
        t = np.clip(pa @ ba / max(ba @ ba, 1e-9), 0.0, 1.0)
        closest = a + t[:, None] * ba
        r = self.ra + (self.rb - self.ra) * t
        d = (p - closest) / self.squash
        return _len(d) * self.squash.min() - r


class Ellipsoid(Shape):
    def __init__(self, c, radii, bone, material, blend=0.0, rotation=None):
        super().__init__(bone, material, blend)
        self.c = np.array(c, float)
        self.r = np.array(radii, float)
        self.rotation = rotation

    def dist(self, p):
        q = p - self.c
        if self.rotation is not None:
            q = q @ self.rotation
        k0 = _len(q / self.r)
        k1 = _len(q / (self.r * self.r))
        return np.where(k1 > 1e-9, k0 * (k0 - 1.0) / np.maximum(k1, 1e-9), -self.r.min())


class RoundBox(Shape):
    def __init__(self, c, half, radius, bone, material, blend=0.0, rotation=None):
        super().__init__(bone, material, blend)
        self.c = np.array(c, float)
        self.half = np.array(half, float)
        self.radius = radius
        self.rotation = rotation

    def dist(self, p):
        q = p - self.c
        if self.rotation is not None:
            q = q @ self.rotation
        q = np.abs(q) - (self.half - self.radius)
        outside = _len(np.maximum(q, 0.0))
        inside = np.minimum(np.max(q, axis=-1), 0.0)
        return outside + inside - self.radius


class Cylinder(Shape):
    """A rounded cylinder around an axis through c (a unit vector)."""

    def __init__(self, c, axis, radius, half_length, rounding, bone, material, blend=0.0):
        super().__init__(bone, material, blend)
        self.c = np.array(c, float)
        self.axis = np.array(axis, float) / np.linalg.norm(axis)
        self.radius = radius
        self.half = half_length
        self.rounding = rounding

    def dist(self, p):
        q = p - self.c
        h = q @ self.axis
        radial = _len(q - h[:, None] * self.axis)
        dx = radial - (self.radius - self.rounding)
        dy = np.abs(h) - (self.half - self.rounding)
        outside = np.sqrt(np.maximum(dx, 0) ** 2 + np.maximum(dy, 0) ** 2)
        return outside + np.minimum(np.maximum(dx, dy), 0.0) - self.rounding


class Torus(Shape):
    """A ring of tube radius r around an axis through c, ring radius R."""

    def __init__(self, c, axis, R, r, bone, material, blend=0.0):
        super().__init__(bone, material, blend)
        self.c = np.array(c, float)
        self.axis = np.array(axis, float) / np.linalg.norm(axis)
        self.R = R
        self.r = r

    def dist(self, p):
        q = p - self.c
        h = q @ self.axis
        radial = _len(q - h[:, None] * self.axis)
        return np.sqrt((radial - self.R) ** 2 + h * h) - self.r


class Carve(Shape):
    """Wraps a shape so it is cut out of everything before it."""

    def __init__(self, shape, blend=0.0):
        super().__init__(None, None, blend)
        self.shape = shape

    def dist(self, p):
        return self.shape.dist(p)


class Displace(Shape):
    """Adds a function of position to a shape's surface (grooves, wrinkles)."""

    def __init__(self, shape, fn):
        super().__init__(shape.bone, shape.material, shape.blend)
        self.shape = shape
        self.fn = fn

    def dist(self, p):
        return self.shape.dist(p) + self.fn(p)


def smin(a, b, k):
    if k <= 0:
        return np.minimum(a, b)
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0.0, 1.0)
    return b + (a - b) * h - k * h * (1.0 - h)


def smax(a, b, k):
    return -smin(-a, -b, k)


def field(shapes, p):
    d = None
    for s in shapes:
        v = s.dist(p)
        if isinstance(s, Carve):
            d = smax(d, -v, s.blend)
        elif d is None:
            d = v
        else:
            d = smin(d, v, s.blend)
    return d


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


# ---------------------------------------------------------------- meshing


def surface_nets(shapes, lo, hi, step):
    """Extracts the surface of the shapes as triangles.

    Returns vertex positions, normals and triangles (index triples, wound
    counter-clockwise seen from outside).
    """
    lo = np.array(lo, float)
    hi = np.array(hi, float)
    n = np.ceil((hi - lo) / step).astype(int) + 1
    xs = [lo[i] + np.arange(n[i]) * step for i in range(3)]
    gx, gy, gz = np.meshgrid(*xs, indexing="ij")
    pts = np.stack([gx.ravel(), gy.ravel(), gz.ravel()], axis=1)
    f = np.empty(len(pts))
    for i in range(0, len(pts), 400000):
        f[i:i + 400000] = field(shapes, pts[i:i + 400000])
    f = f.reshape(n)
    inside = f < 0

    corners = [(a, b, c) for a in (0, 1) for b in (0, 1) for c in (0, 1)]
    edges = [(i, j) for i in range(8) for j in range(i + 1, 8)
             if sum(abs(corners[i][k] - corners[j][k]) for k in range(3)) == 1]
    cn = n - 1
    total = np.zeros(tuple(cn) + (3,))
    count = np.zeros(tuple(cn))
    for i, j in edges:
        ci, cj = corners[i], corners[j]
        fi = f[ci[0]:ci[0] + cn[0], ci[1]:ci[1] + cn[1], ci[2]:ci[2] + cn[2]]
        fj = f[cj[0]:cj[0] + cn[0], cj[1]:cj[1] + cn[1], cj[2]:cj[2] + cn[2]]
        cross = (fi < 0) != (fj < 0)
        t = np.where(cross, fi / np.where(cross, fi - fj, 1.0), 0.0)
        for k in range(3):
            total[..., k] += np.where(cross, ci[k] + t * (cj[k] - ci[k]), 0.0)
        count += cross
    has = count > 0
    index = -np.ones(tuple(cn), int)
    index[has] = np.arange(has.sum())
    cells = np.argwhere(has)
    verts = lo + (cells + total[has] / count[has][:, None]) * step

    quads = []
    for axis in range(3):
        u, v = [a for a in range(3) if a != axis]
        sl0 = [slice(None)] * 3
        sl1 = [slice(None)] * 3
        sl0[axis] = slice(0, n[axis] - 1)
        sl1[axis] = slice(1, n[axis])
        a = inside[tuple(sl0)]
        b = inside[tuple(sl1)]
        cross = a != b
        # Edges on the grid border have no full ring of cells around them.
        for k in (u, v):
            for edge in (slice(0, 1), slice(n[k] - 1, n[k])):
                cross[tuple(edge if a == k else slice(None) for a in range(3))] = False
        for e in np.argwhere(cross):
            ring = []
            for du, dv in ((-1, -1), (0, -1), (0, 0), (-1, 0)):
                c = e.copy()
                c[u] += du
                c[v] += dv
                ring.append(index[tuple(c)])
            if min(ring) < 0:
                continue
            quads.append(ring)
    quads = np.array(quads)

    normals = gradient(shapes, verts, step * 0.5)
    tris = []
    for q in quads:
        pa, pb, pc, pd = verts[q]
        if _len(pa - pc) < _len(pb - pd):
            pair = ((q[0], q[1], q[2]), (q[0], q[2], q[3]))
        else:
            pair = ((q[0], q[1], q[3]), (q[1], q[2], q[3]))
        tris.extend(pair)
    tris = np.array(tris)
    # Wind every triangle to face the way the field's gradient points.
    a, b, c = verts[tris[:, 0]], verts[tris[:, 1]], verts[tris[:, 2]]
    face = np.cross(b - a, c - a)
    flip = np.sum(face * (normals[tris[:, 0]] + normals[tris[:, 1]] + normals[tris[:, 2]]), axis=1) < 0
    tris[flip] = tris[flip][:, ::-1]
    return verts, normals, tris


def gradient(shapes, p, eps):
    g = np.zeros_like(p)
    for k in range(3):
        d = np.zeros(3)
        d[k] = eps
        g[:, k] = field(shapes, p + d) - field(shapes, p - d)
    return g / np.maximum(_len(g), 1e-9)[:, None]


def assign(shapes, verts):
    """The bone and material of the nearest shape for each vertex."""
    solid = [s for s in shapes if not isinstance(s, Carve)]
    dists = np.stack([s.dist(verts) for s in solid], axis=1)
    nearest = np.argmin(dists, axis=1)
    bones = np.array([solid[i].bone for i in nearest])
    materials = [solid[i].material for i in nearest]
    return bones, materials


# ---------------------------------------------------------------- textures


def noise(size, scale, seed, octaves=4):
    """Tileable value noise in 0..1."""
    rng = np.random.default_rng(seed)
    out = np.zeros((size, size))
    amp = 1.0
    norm = 0.0
    cells = max(1, int(size / scale))
    for _ in range(octaves):
        grid = rng.random((cells, cells))
        idx = np.arange(size) * cells / size
        i0 = np.floor(idx).astype(int)
        t = idx - i0
        t = t * t * (3 - 2 * t)
        i1 = (i0 + 1) % cells
        g00 = grid[np.ix_(i0, i0)]
        g10 = grid[np.ix_(i1, i0)]
        g01 = grid[np.ix_(i0, i1)]
        g11 = grid[np.ix_(i1, i1)]
        ty = t[:, None]
        tx = t[None, :]
        out += amp * ((g00 * (1 - tx) + g01 * tx) * (1 - ty) + (g10 * (1 - tx) + g11 * tx) * ty)
        norm += amp
        amp *= 0.5
        cells = min(cells * 2, size)
    return out / norm


def quantize(rgb, colors=256, seed=1):
    """Reduces an image to a palette with a few rounds of k-means."""
    px = rgb.reshape(-1, 3).astype(float)
    rng = np.random.default_rng(seed)
    unique = np.unique(px, axis=0)
    if len(unique) <= colors:
        palette = np.zeros((colors, 3))
        palette[:len(unique)] = unique
        lookup = {tuple(c): i for i, c in enumerate(unique)}
        idx = np.array([lookup[tuple(c)] for c in px])
        return idx.astype(np.uint8), palette.astype(np.uint8)
    centers = px[rng.choice(len(px), colors, replace=False)]
    for _ in range(8):
        best = np.empty(len(px), int)
        for i in range(0, len(px), 4096):
            chunk = px[i:i + 4096]
            d = ((chunk[:, None, :] - centers[None, :, :]) ** 2).sum(-1)
            best[i:i + 4096] = d.argmin(1)
        for c in range(colors):
            members = px[best == c]
            if len(members):
                centers[c] = members.mean(0)
    return best.astype(np.uint8), np.clip(np.round(centers), 0, 255).astype(np.uint8)


# ---------------------------------------------------------------- model


class Model:
    """A skeleton, a skinned mesh and animations, written as a .mdl file."""

    def __init__(self, name):
        self.name = name
        self.bones = []  # (name, parent, world position)
        self.sequences = []  # (name, fps, looping, frames array [F, bones, 6], distance)
        self.textures = {}  # material -> (rgb array H x W x 3)
        self.mesh = None

    def bone(self, name, parent, pos):
        self.bones.append((name, parent, np.array(pos, float)))
        return len(self.bones) - 1

    def bone_index(self, name):
        return [b[0] for b in self.bones].index(name)

    def bind_local(self):
        """Each bone's position relative to its parent in the bind pose."""
        out = []
        for name, parent, pos in self.bones:
            out.append(pos - (self.bones[parent][2] if parent >= 0 else 0))
        return np.array(out)

    def pose(self, rotations=None, offsets=None):
        """One frame: bind positions plus offsets, with Euler angles per bone."""
        frame = np.zeros((len(self.bones), 6))
        frame[:, 0:3] = self.bind_local()
        for name, value in (offsets or {}).items():
            frame[self.bone_index(name), 0:3] += value
        for name, value in (rotations or {}).items():
            frame[self.bone_index(name), 3:6] = value
        return frame

    def sequence(self, name, fps, looping, frames, distance=0.0):
        self.sequences.append((name, fps, looping, np.array(frames), distance))

    def build_mesh(self, shapes, lo, hi, step):
        return self.build_parts([(shapes, lo, hi, step)])

    def build_parts(self, parts):
        """Meshes several pieces, each at its own detail, into one mesh.

        Pieces may overlap; an open edge where a piece's box cuts through its
        shapes should be hidden inside another piece.
        """
        all_v, all_n, all_t, all_b, all_m = [], [], [], [], []
        base = 0
        for shapes, lo, hi, step in parts:
            verts, normals, tris = surface_nets(shapes, lo, hi, step)
            bones, materials = assign(shapes, verts)
            all_v.append(verts)
            all_n.append(normals)
            all_t.append(tris + base)
            all_b.append(bones)
            all_m.extend(materials)
            base += len(verts)
        self.mesh = (np.concatenate(all_v), np.concatenate(all_n), np.concatenate(all_t), np.concatenate(all_b), all_m)
        return base, sum(len(t) for t in all_t)

    # -- writing

    def write(self, path, texture_size=128):
        verts, normals, tris, vbones, vmaterials = self.mesh
        bone_world = np.array([b[2] for b in self.bones])
        local = verts - bone_world[vbones]
        lo = verts.min(0)
        hi = verts.max(0)

        # Group triangles by material, using the material most of its corners have.
        mats = sorted(self.textures)
        groups = {m: [] for m in mats}
        for t in tris:
            names = [vmaterials[i] for i in t]
            groups[max(set(names), key=names.count)].append(t)

        textures = []
        for m in mats:
            rgb = self.textures[m]
            idx, palette = quantize(rgb)
            textures.append((m, rgb.shape[1], rgb.shape[0], idx, palette))

        def uv(tri, size):
            a, b, c = verts[tri]
            n = np.abs(np.cross(b - a, c - a))
            axis = int(np.argmax(n))
            u, v = [k for k in range(3) if k != axis]
            span = max(hi[u] - lo[u], hi[v] - lo[v], 1e-6)
            out = []
            for i in tri:
                s = (verts[i][u] - lo[u]) / span * (size - 1)
                t = (hi[v] - verts[i][v]) / span * (size - 1)
                out.append((int(round(s)), int(round(t))))
            return out

        meshes = []
        for ti, m in enumerate(mats):
            cmds = bytearray()
            for t in groups[m]:
                cmds += struct.pack("<h", 3)
                for i, (s, tt) in zip(t, uv(t, textures[ti][1])):
                    cmds += struct.pack("<hhhh", int(i), int(i), s, tt)
            cmds += struct.pack("<h", 0)
            meshes.append((len(groups[m]), ti, bytes(cmds)))

        out = bytearray(244)
        put = lambda fmt, at, *v: struct.pack_into(fmt, out, at, *v)

        def align():
            while len(out) % 4:
                out.append(0)

        def name(s, size):
            b = s.encode("latin-1")[:size - 1]
            return b + bytes(size - len(b))

        put("<4si", 0, b"IDST", 10)
        out[8:72] = name(self.name, 64)
        put("<3f", 76, 0, 0, 0)
        put("<3f", 112, *lo)
        put("<3f", 124, *hi)

        # Bones: bind position as the default value; animation channels scaled to fit shorts.
        frames_all = [s[3] for s in self.sequences]
        base = np.zeros((len(self.bones), 6))
        base[:, 0:3] = self.bind_local()
        scale = np.full((len(self.bones), 6), 1.0)
        for b in range(len(self.bones)):
            for c in range(6):
                spread = max(float(np.abs(f[:, b, c] - base[b, c]).max()) for f in frames_all) if frames_all else 0.0
                scale[b, c] = spread / 32000.0 if spread > 1e-7 else 1.0
        align()
        put("<ii", 140, len(self.bones), len(out))
        for i, (bname, parent, _) in enumerate(self.bones):
            rec = bytearray(112)
            rec[0:32] = name(bname, 32)
            struct.pack_into("<ii", rec, 32, parent, 0)
            struct.pack_into("<6i", rec, 40, *([-1] * 6))
            struct.pack_into("<6f", rec, 64, *base[i])
            struct.pack_into("<6f", rec, 88, *scale[i])
            out += rec

        # Sequences, each followed by its animation data.
        align()
        seq_at = len(out)
        put("<ii", 164, len(self.sequences), seq_at)
        out += bytes(176 * len(self.sequences))
        for si, (sname, fps, looping, frames, distance) in enumerate(self.sequences):
            align()
            anim_at = len(out)
            nb = len(self.bones)
            structs = bytearray(nb * 12)
            data = bytearray()
            for b in range(nb):
                for c in range(6):
                    raw = np.round((frames[:, b, c] - base[b, c]) / scale[b, c]).astype(int)
                    if not raw.any():
                        continue
                    offset = nb * 12 + len(data) - b * 12
                    struct.pack_into("<H", structs, b * 12 + c * 2, offset)
                    count = len(raw)
                    pos = 0
                    while pos < count:
                        run = min(255, count - pos)
                        data += struct.pack("<BB", run, run)
                        data += struct.pack("<%dh" % run, *raw[pos:pos + run])
                        pos += run
            out += structs + data
            rec = bytearray(176)
            rec[0:32] = name(sname, 32)
            struct.pack_into("<fii", rec, 32, fps, 1 if looping else 0, 0)
            struct.pack_into("<i", rec, 56, len(frames))
            struct.pack_into("<ii", rec, 68, 1 if distance > 0 else 0, 0)
            struct.pack_into("<3f", rec, 76, distance, 0, 0)
            struct.pack_into("<i", rec, 120, 1)
            struct.pack_into("<i", rec, 124, anim_at)
            struct.pack_into("<i", rec, 156, 0)
            out[seq_at + si * 176:seq_at + (si + 1) * 176] = rec

        # One sequence group: everything is in this file.
        align()
        put("<ii", 172, 1, len(out))
        group = bytearray(104)
        group[0:32] = name("default", 32)
        out += group

        # Textures and the skin table.
        align()
        put("<iii", 180, len(textures), len(out), 0)
        tex_at = len(out)
        out += bytes(80 * len(textures))
        put("<i", 188, len(out))
        for i, (m, w, h, idx, palette) in enumerate(textures):
            rec = bytearray(80)
            rec[0:64] = name(m + ".bmp", 64)
            struct.pack_into("<iiii", rec, 64, 0, w, h, len(out))
            out[tex_at + i * 80:tex_at + (i + 1) * 80] = rec
            out += idx.tobytes() + palette.tobytes()
        align()
        put("<iii", 192, len(textures), 1, len(out))
        for i in range(len(textures)):
            out += struct.pack("<h", i)

        # One body part with one model.
        align()
        put("<ii", 204, 1, len(out))
        part_at = len(out)
        out += bytes(76)
        align()
        model_at = len(out)
        out += bytes(112)
        part = bytearray(76)
        part[0:64] = name("body", 64)
        struct.pack_into("<iii", part, 64, 1, 1, model_at)
        out[part_at:part_at + 76] = part

        align()
        vinfo_at = len(out)
        out += bytes(int(b) for b in vbones)
        align()
        vert_at = len(out)
        out += local.astype("<f4").tobytes()
        ninfo_at = len(out)
        out += bytes(int(b) for b in vbones)
        align()
        norm_at = len(out)
        out += normals.astype("<f4").tobytes()

        align()
        mesh_at = len(out)
        out += bytes(20 * len(meshes))
        for i, (count, ti, cmds) in enumerate(meshes):
            align()
            at = len(out)
            out += cmds
            struct.pack_into("<iiiii", out, mesh_at + i * 20, count, at, ti, len(verts), 0)

        rec = bytearray(112)
        rec[0:64] = name(self.name, 64)
        struct.pack_into("<if10i", rec, 64, 0, float(_len(hi - lo)), len(meshes), mesh_at,
                         len(verts), vinfo_at, vert_at, len(verts), ninfo_at, norm_at, 0, 0)
        out[model_at:model_at + 112] = rec
        put("<i", 72, len(out))
        with open(path, "wb") as fh:
            fh.write(out)
        return len(out)
