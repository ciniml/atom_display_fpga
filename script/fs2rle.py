#!/usr/bin/env python
# SPDX-License-Identifier: BSL-1.0
# Author: Kenta Ida (fuga@fugafuga.org)
# add RLE Modified by lovyan03

import sys

input_path = sys.argv[1]
output_path = sys.argv[2]

buf_direct = bytearray(0)
val_prev = -1
val_rlelen = 0
limit_max = 255

with open(output_path, 'wb') as output_file:
    def add_data(val_now):
        global val_prev
        global val_rlelen
        global buf_direct
        buffer = bytearray(1)
        if (len(buf_direct) >= limit_max or (len(buf_direct) > 0 and val_now == val_prev)):
            while len(buf_direct):
                val_directlen = len(buf_direct)
                if (val_directlen == 1):
                    buffer[0] = 1
                    output_file.write(buffer)
                    output_file.write(buf_direct)
                    buf_direct.clear()
                else:
                    if (val_directlen > limit_max):
                        val_directlen = limit_max
                    buffer[0] = 0
                    output_file.write(buffer)
                    buffer[0] = val_directlen
                    output_file.write(buffer)
                    output_file.write(buf_direct[0:val_directlen])
                    del buf_direct[0:val_directlen]
        if (val_rlelen > 0 and val_prev != val_now):
            val_rlelen += 1
            while val_rlelen > 0:
                val_l = limit_max if val_rlelen > limit_max else val_rlelen
                buffer[0] = val_l
                output_file.write(buffer)
                buffer[0] = val_prev
                output_file.write(buffer)
                val_rlelen -= val_l
            val_rlelen = 0
            if (val_prev != val_now):
                val_prev = -1
        if (val_prev == val_now):
            val_rlelen += 1
        else:
            if (val_prev >= 0):
                buf_direct.append(val_prev)
            val_prev = val_now

    with open(input_path, 'r') as input_file:
        for line in iter(input_file.readline, ''): #type: str
            line = line.strip()
            if line.startswith('//'):
                continue
            for byte_index in range(0, len(line), 8):
                value = 0
                for bit_index in range(8):
                    bit = line[byte_index + bit_index]
                    value = (value << 1) | (1 if bit == '1' else 0)
                add_data(value)
        add_data(-1)
        add_data(-1)