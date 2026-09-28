"""Third-person render: a blocky player holding a model in the right hand, following vanilla's
LivingEntityRenderer -> HumanoidModel (ITEM arm pose) -> ItemInHandLayer -> ItemTransform chain."""
import math, numpy as np
from PIL import Image
from toon import Model, _corners, _tri, rot_xyz

def T(x, y, z): M = np.eye(4); M[:3, 3] = (x, y, z); return M
def R(rx, ry, rz): M = np.eye(4); M[:3, :3] = rot_xyz(rx, ry, rz); return M
def S(x, y, z): return np.diag([x, y, z, 1.0])
def RZYX(z, y, x):                    # ModelPart.translateAndRotate: rotationZYX(zRot, yRot, xRot) = Rz*Ry*Rx
    return R(0, 0, math.degrees(z)) @ R(0, math.degrees(y), 0) @ R(math.degrees(x), 0, 0)

ENTITY = R(0, 180, 0) @ S(-1, -1, 1) @ T(0, -1.501, 0)
ARM = ENTITY @ T(-5 / 16, 2 / 16, 0) @ RZYX(0, 0, -math.pi / 10)          # right arm, ITEM pose (no bob)
HAND = ARM @ R(-90, 0, 0) @ R(0, 180, 0) @ T(1 / 16, 0.125, -0.625)

def player_parts():
    """(model_json, texture, matrix) for body, head, legs, arms, in model-part pixel space (y down)."""
    parts = []
    def part(frm, to, col, M):
        m = Model(); m.mat('c', [col] * 5)
        m.box(frm, to, 'c')
        out, tex = m.export('x', {})
        parts.append((out, tex, M @ S(1 / 16, 1 / 16, 1 / 16)))
    part((-4, 0, -2), (4, 12, 2), '#3a6ea5', ENTITY)                          # body (shirt)
    part((-4, -8, -4), (4, 0, 4), '#c8966e', ENTITY)                          # head
    part((-4, 12, -2), (0, 24, 2), '#2a2a5a', ENTITY @ T(0, 0, 0))            # legs
    part((0, 12, -2), (4, 24, 2), '#2a2a5a', ENTITY)
    part((-3, -2, -2), (1, 10, 2), '#c8966e', ARM)                            # right arm
    part((-1, -2, -2), (3, 10, 2), '#c8966e', ENTITY @ T(5 / 16, 2 / 16, 0))  # left arm
    return parts

def item_matrix(disp):
    t = disp['thirdperson_righthand']
    return HAND @ T(*(np.array(t.get('translation', [0, 0, 0])) / 16)) @ R(*t.get('rotation', [0, 0, 0])) @ S(*t.get('scale', [1, 1, 1])) @ T(-0.5, -0.5, -0.5) @ S(1 / 16, 1 / 16, 1 / 16)

def render(item_json, item_tex, W=300, H=300, eye=(-2.2, 1.5, 2.6), look=(-0.2, 1.0, 0.0), fov=45):
    items = player_parts() + [(item_json, item_tex, item_matrix(item_json['display']))]
    e = np.array(eye); f = np.array(look) - e; f /= np.linalg.norm(f)
    r = np.cross(f, [0, 1, 0]); r /= np.linalg.norm(r); u = np.cross(r, f)
    fl = (H / 2) / math.tan(math.radians(fov / 2))
    img = np.zeros((H, W, 3)); img[:] = (139, 139, 139); zb = np.full((H, W), np.inf)
    L = np.array((0.35, 0.8, 0.5)); L /= np.linalg.norm(L)
    for js, tex, M in items:
        Tx = np.asarray(tex.convert('RGBA'), dtype=np.float64); Sz = tex.width
        for el in js['elements']:
            for fname, fd in el['faces'].items():
                q = np.array(_corners(el)[fname], float)
                w = (np.c_[q, np.ones(4)] @ M.T)[:, :3] - e
                x, y, z = w @ r, w @ u, w @ f
                if np.any(z <= 0.05): continue
                sx, sy = W / 2 + x / z * fl, H / 2 - y / z * fl
                cross = (sx[1] - sx[0]) * (sy[3] - sy[0]) - (sy[1] - sy[0]) * (sx[3] - sx[0])
                if abs(cross) < 1e-9: continue
                # the mirror in ENTITY flips winding for the body; just draw both sides, depth sorts it
                wn = np.cross(w[1] - w[0], w[3] - w[0]); wn /= (np.linalg.norm(wn) or 1)
                shade = 0.6 + 0.4 * abs(float(wn @ L))
                u1, v1, u2, v2 = (v * Sz / 16 for v in fd['uv'])
                tuv = np.array([(u1, v1), (u2, v1), (u2, v2), (u1, v2)])
                for tri in ((0, 1, 2), (0, 2, 3)):
                    _tri(img, zb, Tx, sx[list(tri)], sy[list(tri)], z[list(tri)], tuv[list(tri)], shade, W, H)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))

# ---------------------------------------------------------------- solving third-person poses
def _euler(M):
    b = math.asin(max(-1, min(1, M[0, 2])))
    return [round(math.degrees(v), 2) for v in (math.atan2(-M[1, 2], M[2, 2]), b, math.atan2(-M[0, 1], M[0, 0]))]

def vanilla_grip_world():
    """Where a vanilla sword's grip sits in the right hand (world space, idle ITEM pose)."""
    M = HAND @ T(0, 4 / 16, 0.5 / 16) @ R(0, -90, 55) @ S(0.85, 0.85, 0.85)
    return (M @ np.array([-4.5 / 16, -4.5 / 16, 0, 1]))[:3]

def solve_tp(model_axes, world_axes, scale, grip, target=None):
    """Rotation so model_axes[i] -> world_axes[i] (two axes; third by cross product), translation so the
    model point `grip` (model units, 0..16 space) lands on `target` (default: the vanilla sword grip)."""
    def frame(a, b):
        a = np.array(a, float); a /= np.linalg.norm(a)
        b = np.array(b, float); b -= b.dot(a) * a; b /= np.linalg.norm(b)
        return np.c_[a, b, np.cross(a, b)]
    Rw = frame(*world_axes) @ frame(*model_axes).T
    Rh = HAND[:3, :3]
    Ri = np.linalg.solve(Rh, Rw)                       # HAND rotation * item rotation = wanted world rotation
    rot = _euler(Ri)
    Rm = rot_xyz(*rot)
    P = vanilla_grip_world() if target is None else np.array(target)
    local = np.linalg.solve(Rh, P - HAND[:3, 3])
    g = (np.array(grip, float) - 8) / 16
    t = 16 * (local - Rm @ (scale * g))
    return {'rotation': rot, 'translation': [round(float(v), 3) for v in t], 'scale': [scale] * 3}

def use_arm_pose(xrot, yrot):
    """Swap the right-arm pose (radians, as HumanoidModel sets them) for the solver and renderer."""
    global ARM, HAND
    ARM = ENTITY @ T(-5 / 16, 2 / 16, 0) @ RZYX(0, yrot, xrot)
    HAND = ARM @ R(-90, 0, 0) @ R(0, 180, 0) @ T(1 / 16, 0.125, -0.625)

ITEM_POSE = (-math.pi / 10, 0.0)
CROSSBOW_HOLD = (-math.pi / 2 + 0.1, -0.3)          # AnimationUtils.animateCrossbowHold, head level
CROSSBOW_CHARGE = (-0.97079635, -0.8)                 # AnimationUtils.animateCrossbowCharge (right arm)
