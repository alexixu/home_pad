#!/usr/bin/env python3
"""Read only official OTA HTTP ranges; reconstruct only boot, standard library only."""
import base64, bz2, hashlib, json, lzma, pathlib, struct, sys, urllib.request, zlib

OUT = pathlib.Path(__file__).resolve().parent
P = json.loads((OUT / 'probe.json').read_text())
URL, SIZE = P['url'], P['size']
BUDGET = 200_000_000
downloaded = P['downloaded_bytes'] + P.get('failed_request_reserved_bytes', 0)
receipts = []
if (OUT/'range-receipts.json').exists():
    old_receipts = json.loads((OUT/'range-receipts.json').read_text())
    downloaded = max(downloaded, old_receipts['budgeted_requested_bytes'])
    receipts = old_receipts['ranges']
tail = (OUT / 'zip-tail.bin').read_bytes()

def get(start, length, label):
    global downloaded
    assert 0 <= start and 0 < length and start + length <= SIZE
    if P['tail_start'] <= start and start + length <= SIZE:
        a = start - P['tail_start']
        return tail[a:a + length]
    if downloaded + length > BUDGET:
        raise RuntimeError('200 MB cumulative request budget exceeded')
    downloaded += length  # Count full requested bytes even when a request fails.
    req = urllib.request.Request(URL, headers={'Range':f'bytes={start}-{start+length-1}', 'User-Agent':'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=30) as r:
        expected = f'bytes {start}-{start+length-1}/{SIZE}'
        if r.status != 206 or r.headers.get('Content-Range') != expected:
            raise RuntimeError(f'Server did not honor Range: {r.status} {r.headers.get("Content-Range")}')
        if int(r.headers.get('Content-Length', '-1')) != length:
            raise RuntimeError('Content-Length mismatch')
        data = r.read(length)
        if len(data) != length:
            raise RuntimeError('Truncated range')
    receipts.append({'label':label, 'offset':start, 'length':length, 'sha256':hashlib.sha256(data).hexdigest()})
    (OUT/'range-receipts.json').write_text(json.dumps({'url':URL,'budgeted_requested_bytes':downloaded,'ranges':receipts},indent=2))
    return data

def varint(buf, pos):
    value = 0
    for shift in range(0, 70, 7):
        n = buf[pos]; pos += 1
        value |= (n & 127) << shift
        if not n & 128: return value, pos
    raise ValueError('Oversized protobuf varint')

def proto(buf):
    fields = {}; pos = 0
    while pos < len(buf):
        tag, pos = varint(buf, pos); field, wire = tag >> 3, tag & 7
        if field == 0: raise ValueError('Invalid protobuf field')
        if wire == 0: value, pos = varint(buf, pos)
        elif wire == 2:
            n, pos = varint(buf, pos); value = buf[pos:pos+n]; pos += n
            if len(value) != n: raise ValueError('Truncated protobuf field')
        elif wire in (1, 5):
            n = 8 if wire == 1 else 4
            value = buf[pos:pos+n]; pos += n
            if len(value) != n: raise ValueError('Truncated fixed field')
        else: raise ValueError(f'Unsupported protobuf wire {wire}')
        fields.setdefault(field, []).append(value)
    return fields

def one(p, field, default=None):
    values = p.get(field, [])
    if not values: return default
    if len(values) != 1: raise ValueError(f'Nonunique field {field}')
    return values[0]

z64 = struct.unpack_from('<4sQ2H2I4Q', tail, P['zip64_eocd_tail_offset'])
count, cd_size, cd_offset = z64[7], z64[8], z64[9]
cd = get(cd_offset, cd_size, 'ZIP central directory')
entries = {}; pos = 0
for _ in range(count):
    h = struct.unpack_from('<4s6H3I5H2I', cd, pos)
    if h[0] != b'PK\x01\x02': raise ValueError('Invalid central-directory header')
    name_len, extra_len, comment_len = h[10:13]
    name = cd[pos+46:pos+46+name_len].decode('utf-8')
    extra = cd[pos+46+name_len:pos+46+name_len+extra_len]
    e = {'flags':h[3], 'compression':h[4], 'crc32':h[7], 'compressed_size':h[8], 'size':h[9], 'header_offset':h[16]}
    ep = 0
    while ep < len(extra):
        tag, length = struct.unpack_from('<HH', extra, ep); ep += 4
        value = extra[ep:ep+length]; ep += length
        if tag == 1:
            vp = 0
            for key in ('size','compressed_size','header_offset'):
                if e[key] == 0xffffffff:
                    e[key] = struct.unpack_from('<Q', value, vp)[0]; vp += 8
    entries[name] = e
    pos += 46+name_len+extra_len+comment_len
assert pos == len(cd)
(OUT/'zip-entries.json').write_text(json.dumps(entries, indent=2))

def entry_offset(name):
    e = entries[name]
    if 'data_offset' not in e:
        b = get(e['header_offset'], 30, name+' local header')
        h = struct.unpack('<4s5H3I2H', b)
        if h[0] != b'PK\x03\x04' or h[3] != e['compression']: raise ValueError('Local header mismatch')
        e['data_offset'] = e['header_offset'] + 30 + h[-2] + h[-1]
    return e['data_offset']

def small_entry(name):
    e = entries[name]
    if e['size'] > 1_000_000 or e['compressed_size'] > 1_000_000: raise ValueError('Not a small entry')
    b = get(entry_offset(name), e['compressed_size'], name)
    if e['compression'] == 8: b = zlib.decompress(b, -15)
    elif e['compression'] != 0: raise ValueError('Unsupported ZIP compression')
    if len(b) != e['size'] or zlib.crc32(b) != e['crc32']: raise ValueError('ZIP entry size/CRC mismatch')
    return b

metadata = small_entry('META-INF/com/android/metadata')
(OUT/'ota-metadata.txt').write_bytes(metadata)
if b'post-build-incremental=V816.0.2.0.TKYCNXM' not in metadata or b'pre-device=elish' not in metadata:
    raise ValueError('Wrong device or build metadata')
properties_bytes = small_entry('payload_properties.txt')
(OUT/'payload_properties.txt').write_bytes(properties_bytes)
properties = dict(line.split('=',1) for line in properties_bytes.decode().splitlines() if '=' in line)
payload = entries['payload.bin']
if payload['compression'] != 0: raise ValueError('payload.bin is ZIP-compressed; refusing full download')
payload_start = entry_offset('payload.bin')
header = get(payload_start, 24, 'payload header')
magic, major, manifest_size, sig_size = struct.unpack('>4sQQI', header)
if magic != b'CrAU' or major != 2 or manifest_size > 20_000_000: raise ValueError('Unsupported payload')
manifest = get(payload_start+24, manifest_size, 'payload manifest')
(OUT/'payload-manifest.pb').write_bytes(manifest)
metadata_blob = header + manifest
if len(metadata_blob) != int(properties['METADATA_SIZE']): raise ValueError('Metadata size mismatch')
metadata_hash = base64.b64encode(hashlib.sha256(metadata_blob).digest()).decode()
if metadata_hash != properties['METADATA_HASH']: raise ValueError('Metadata SHA256 mismatch')
m = proto(manifest); block_size = one(m,3,4096)
if one(m,12,0) != 0: raise ValueError('Incremental payload not supported')
partitions = [proto(b) for b in m[13]]
partition_names = [one(p,1).decode() for p in partitions]
boot = next(p for p in partitions if one(p,1) == b'boot')
if 6 in boot: raise ValueError('Boot requires old partition')
info = proto(one(boot,7)); boot_size = one(info,1); expected_hash = one(info,2)
ops = [proto(b) for b in boot[8]]
summary = []
for op in ops:
    typ = one(op,1)
    if typ not in (0,1,6,8): raise ValueError(f'Unsupported operation type {typ}')
    extents = [proto(b) for b in op.get(6,[])]
    summary.append({'type':typ,'data_offset':one(op,2,0),'data_length':one(op,3,0),'data_sha256':one(op,8,b'').hex(),'dst_extents':[[one(e,1),one(e,2)] for e in extents]})
network_needed = sum(s['data_length'] for s in summary)
if downloaded + network_needed > BUDGET: raise ValueError('Boot exceeds download budget')
if not 0 < boot_size <= 200_000_000: raise ValueError('Unexpected boot size')
plan = {'source_url':URL,'zip_size':SIZE,'zip_entries':entries,'payload_start':payload_start,'payload_size':payload['size'],'manifest_size':manifest_size,'signature_size':sig_size,'block_size':block_size,'partition_names':partition_names,'metadata_sha256_verified':True,'boot_size':boot_size,'boot_expected_sha256':expected_hash.hex(),'boot_operations':summary,'operation_download_bytes':network_needed,'total_budgeted_requested_bytes_before_ops':downloaded}
(OUT/'extraction-plan.json').write_text(json.dumps(plan,indent=2))
print(json.dumps({k:v for k,v in plan.items() if k not in ('zip_entries','boot_operations')}),flush=True)
if '--extract' not in sys.argv: sys.exit(0)

image = bytearray(boot_size)
coverage = bytearray((boot_size+block_size-1)//block_size)
verified_ops = 0
data_base = payload_start + 24 + manifest_size + sig_size
for index, s in enumerate(summary):
    length = sum(n for _,n in s['dst_extents']) * block_size
    if s['type'] == 6: data = bytes(length)
    else:
        blob = get(data_base+s['data_offset'],s['data_length'],f'boot operation {index}')
        if not s['data_sha256'] or hashlib.sha256(blob).hexdigest() != s['data_sha256']: raise ValueError('Operation blob hash mismatch')
        verified_ops += 1
        if s['type'] == 0: data = blob
        elif s['type'] == 1: data = bz2.decompress(blob)
        elif s['type'] == 8: data = lzma.decompress(blob)
        if len(data) > length: raise ValueError('Operation expands past extents')
        data = data.ljust(length,b'\0')
    dp = 0
    for start, count in s['dst_extents']:
        a,b = start*block_size,(start+count)*block_size
        if a < 0 or b > boot_size or any(coverage[start:start+count]): raise ValueError('Bad/overlapping extents')
        image[a:b] = data[dp:dp+b-a]; dp += b-a
        coverage[start:start+count] = bytes([1])*count
    print(f'Completed boot operation {index+1}/{len(summary)}',flush=True)
if not all(coverage): raise ValueError('Incomplete boot partition reconstruction')
actual_hash = hashlib.sha256(image).digest()
if actual_hash != expected_hash: raise ValueError('Final boot partition SHA256 mismatch')
if image[:8] != b'ANDROID!': raise ValueError('Unexpected boot image magic')
boot_header_version = struct.unpack_from('<I',image,40)[0]
boot_header = {'magic':'ANDROID!', 'kernel_size':struct.unpack_from('<I',image,8)[0], 'header_version':boot_header_version}
if boot_header_version in (3,4):
    boot_header['ramdisk_size'] = struct.unpack_from('<I',image,12)[0]
    boot_header['header_size'] = struct.unpack_from('<I',image,20)[0]
path = OUT/'boot.img'
path.write_bytes(image)
report = {'source_url':URL,'device':'elish','build':'OS1.0.2.0.TKYCNXM','boot_file':str(path),'boot_size':len(image),'boot_sha256':actual_hash.hex(),'boot_header':boot_header,'payload_metadata_sha256_matches_properties':True,'operation_data_sha256_verified_count':verified_ops,'boot_partition_sha256_matches_manifest':True,'budgeted_requested_bytes':downloaded,'whole_zip_md5_verified':False,'whole_zip_signature_verified':False,'whole_payload_file_hash_verified':False,'payload_metadata_signature_verified':False,'verification_boundary':'Range extraction verifies consistency against hashes embedded in the same official-CDN OTA. No independent signing-key trust verification and no full-archive/full-payload verification. No device read/write.'}
(OUT/'verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
