import math, json, numpy as np
from PIL import Image
from toon import raster, rot_xyz

def T(x, y, z): M = np.eye(4); M[:3, 3] = (x, y, z); return M
def R(rx, ry, rz): M = np.eye(4); M[:3, :3] = rot_xyz(rx, ry, rz); return M
def S(x, y, z): return np.diag([x, y, z, 1.0])

def view(model_json, tex, disp, charging=None, W=480, H=270, fov=70):
    M = T(0.56, -0.52, -0.72)
    if charging is not None:                       # ItemInHandRenderer SPEAR branch, right hand
        l = charging
        M = M @ T(-0.5, 0.7, 0.1) @ R(-55, 0, 0) @ R(0, 35.3, 0) @ R(0, 0, -9.785) @ T(0, 0, l * 0.2) @ S(1, 1, 1 + l * 0.2) @ R(0, -45, 0)
    M = M @ T(*(np.array(disp['translation']) / 16)) @ R(*disp['rotation']) @ S(*disp['scale']) @ T(-0.5, -0.5, -0.5)
    f = (H / 2) / math.tan(math.radians(fov / 2))
    def proj(q):
        p = (np.c_[q / 16, np.ones(len(q))] @ M.T)[:, :3]
        return W / 2 + p[:, 0] / -p[:, 2] * f, H / 2 - p[:, 1] / -p[:, 2] * f, -p[:, 2]
    img = raster(model_json, tex, proj, W, H, bg=(120, 160, 210))
    px = img.load()
    for k in range(-6, 7): px[W // 2 + k, H // 2] = (255, 255, 255); px[W // 2, H // 2 + k] = (255, 255, 255)
    return img
