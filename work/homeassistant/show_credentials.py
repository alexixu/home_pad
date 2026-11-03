"""User-invoked local credential display. Do not run in automated diagnostics."""
import json
from pathlib import Path
owner = json.loads((Path(__file__).resolve().parent / 'private/owner.json').read_text())
print('Home Assistant 本机管理账号（不是小米账号）')
print('地址：http://127.0.0.1:8123')
print('用户名：' + owner['username'])
print('密码：' + owner['password'])
