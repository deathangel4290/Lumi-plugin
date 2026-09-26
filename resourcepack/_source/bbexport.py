"""Write a Blockbench project (.bbmodel, Java Block/Item format) from an exported Java item model + its texture."""
import base64, io, json, uuid

def to_bbmodel(model_json, texture_img, name, out_path):
    buf = io.BytesIO(); texture_img.save(buf, 'PNG')
    res = texture_img.width
    k = res / 16.0                                  # Java uv is 0..16; Blockbench stores texture pixels
    elements, outliner = [], []
    for i, el in enumerate(model_json['elements']):
        eid = str(uuid.uuid4())
        rot = [0, 0, 0]; origin = [8, 8, 8]
        if 'rotation' in el:
            axis = 'xyz'.index(el['rotation']['axis']); rot[axis] = el['rotation']['angle']; origin = el['rotation']['origin']
        faces = {f: {'uv': [v * k for v in d['uv']], 'texture': 0} for f, d in el['faces'].items()}
        elements.append({'name': f'part_{i}', 'box_uv': False, 'rescale': False, 'locked': False, 'render_order': 'default',
                         'from': el['from'], 'to': el['to'], 'autouv': 0, 'color': i % 8, 'origin': origin, 'rotation': rot,
                         'faces': faces, 'type': 'cube', 'uuid': eid})
        outliner.append(eid)
    project = {
        'meta': {'format_version': '4.10', 'model_format': 'java_block', 'box_uv': False},
        'name': name, 'parent': '', 'ambientocclusion': True, 'front_gui_light': False,
        'visible_box': [1, 1, 0], 'variable_placeholders': '', 'variable_placeholder_buttons': [],
        'unhandled_root_fields': {}, 'resolution': {'width': res, 'height': res},
        'elements': elements, 'outliner': outliner,
        'textures': [{'path': '', 'name': name + '.png', 'folder': 'item', 'namespace': 'luminara', 'id': '0',
                      'width': res, 'height': res, 'uv_width': res, 'uv_height': res, 'particle': True,
                      'render_mode': 'default', 'render_sides': 'auto', 'frame_time': 1, 'frame_order_type': 'loop',
                      'frame_order': '', 'frame_interpolate': False, 'visible': True, 'internal': True, 'saved': True,
                      'uuid': str(uuid.uuid4()), 'relative_path': '',
                      'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()}],
        'display': model_json.get('display', {}),
    }
    json.dump(project, open(out_path, 'w'), indent=1)
