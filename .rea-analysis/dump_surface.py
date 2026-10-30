import sys
sys.stdout.reconfigure(encoding='utf-8')
from zipfile import ZipFile
import struct

with ZipFile('.rea-analysis/com.vacuapps.photos3d.apk') as z:
    dex = z.read('classes.dex')

string_ids_size, string_ids_off = struct.unpack_from('<II', dex, 56)
type_ids_size, type_ids_off = struct.unpack_from('<II', dex, 64)
field_ids_size, field_ids_off = struct.unpack_from('<II', dex, 80)
method_ids_size, method_ids_off = struct.unpack_from('<II', dex, 88)

def get_string(idx):
    off = struct.unpack_from('<I', dex, string_ids_off + idx * 4)[0]
    p = off; l = 0; shift = 0
    while True:
        b = dex[p]; p += 1
        l |= (b & 0x7f) << shift
        if not (b & 0x80): break
        shift += 7
    return dex[p:p+l].decode('utf-8', errors='replace')

def get_type(idx):
    return get_string(struct.unpack_from('<I', dex, type_ids_off + idx * 4)[0])

def get_method(idx):
    c_idx, p_idx, n_idx = struct.unpack_from('<HHI', dex, method_ids_off + idx * 8)
    return f'{get_type(c_idx)}->{get_string(n_idx)}'

def get_field(idx):
    c_idx, t_idx, n_idx = struct.unpack_from('<HHI', dex, field_ids_off + idx * 8)
    return f'{get_type(c_idx)}->{get_string(n_idx)}'

code_off = 2477776 # onSurfaceChanged
registers_size, ins_size, outs_size, tries_size, debug_info_off, insns_size = struct.unpack_from('<HHHHII', dex, code_off)
insns = dex[code_off+16:code_off+16+insns_size*2]

p = 0
while p < len(insns):
    op = insns[p]
    pos = p // 2
    b1 = insns[p+1]
    if op == 0x00: print(f'{pos:04x}: nop'); p += 2
    elif op == 0x12:
        vx = b1 & 0xf; lit = b1 >> 4
        if lit & 8: lit -= 16
        print(f'{pos:04x}: const/4 v{vx}, #{lit}'); p += 2
    elif op == 0x13:
        vx = b1
        val = struct.unpack_from('<h', insns, p+2)[0]
        print(f'{pos:04x}: const/16 v{vx}, #{val}'); p += 4
    elif op == 0x14:
        vx = b1
        val = struct.unpack_from('<f', insns, p+2)[0]
        print(f'{pos:04x}: const v{vx}, #{val}f'); p += 6
    elif op == 0x1a:
        vx = b1
        sidx = struct.unpack_from('<H', insns, p+2)[0]
        print(f'{pos:04x}: const-string v{vx}, "{get_string(sidx)}"'); p += 4
    elif 0x52 <= op <= 0x5f:
        vx = b1 & 0xf; vy = b1 >> 4
        fidx = struct.unpack_from('<H', insns, p+2)[0]
        print(f'{pos:04x}: iget/iput v{vx}, v{vy}, {get_field(fidx)}'); p += 4
    elif 0x6e <= op <= 0x72:
        midx = struct.unpack_from('<H', insns, p+2)[0]
        print(f'{pos:04x}: invoke {get_method(midx)}'); p += 6
    elif 0x74 <= op <= 0x78:
        midx = struct.unpack_from('<H', insns, p+2)[0]
        print(f'{pos:04x}: invoke/range {get_method(midx)}'); p += 6
    elif op == 0x81:
        vx = b1 & 0xf; vy = b1 >> 4
        print(f'{pos:04x}: int-to-float v{vx}, v{vy}'); p += 2
    elif op in (0xa6, 0xa7, 0xa8, 0xa9, 0xaa):
        opnames = {0xa6: 'add-float', 0xa7: 'sub-float', 0xa8: 'mul-float', 0xa9: 'div-float', 0xaa: 'rem-float'}
        vx = b1; vy = insns[p+2]; vz = insns[p+3]
        print(f'{pos:04x}: {opnames[op]} v{vx}, v{vy}, v{vz}'); p += 4
    elif op in (0xb0, 0xb1, 0xb2, 0xb3, 0xb4):
        opnames = {0xb0: 'add-float/2addr', 0xb1: 'sub-float/2addr', 0xb2: 'mul-float/2addr', 0xb3: 'div-float/2addr'}
        vx = b1 & 0xf; vy = b1 >> 4
        print(f'{pos:04x}: {opnames.get(op, hex(op))} v{vx}, v{vy}'); p += 2
    elif op in (0x32, 0x33, 0x34, 0x35, 0x36, 0x37):
        print(f'{pos:04x}: if-test'); p += 4
    elif op == 0x28:
        print(f'{pos:04x}: goto'); p += 2
    elif op == 0x0e:
        print(f'{pos:04x}: return-void'); p += 2
    else:
        print(f'{pos:04x}: op {op:02x}'); p += 2

