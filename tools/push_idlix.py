#!/usr/bin/env python3
import base64, hashlib
from tv import connect

dev = connect()
apk = r'C:\Users\user\idlix-androidtv\app-tv\build\outputs\apk\debug\app-tv-debug.apk'
remote = '/data/local/tmp/idlix.apk'
data = open(apk,'rb').read()
enc = base64.b64encode(data).decode()
tmp = remote + '.b64'
dev.shell(f'rm -f {tmp} {remote}')
for i in range(0, len(enc), 8000):
    dev.shell("echo -n '" + enc[i:i+8000] + "' >> " + tmp)
dev.shell(f'base64 -d {tmp} > {remote} && rm -f {tmp}')
rm = dev.shell(f'md5sum {remote}').split()[0]
lm = hashlib.md5(data).hexdigest()
print(rm, lm)
dev.close()
