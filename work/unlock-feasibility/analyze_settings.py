from pathlib import Path
from zipfile import ZipFile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
p=Path('work/unlock-feasibility/Settings.apk')
out=Path('work/unlock-feasibility/settings-cloud-disassembly.txt')
lines=[]
with ZipFile(p) as z:
 d=DEX(z.read('classes2.dex'))
 for c in d.get_classes():
  if any(x in c.get_name() for x in ['CloudDeviceStatus','bootloader']):
   lines.append('CLASS '+c.get_name())
   for m in c.get_methods():
    lines.append('METHOD '+m.get_name()+' '+m.get_descriptor())
    for inst in m.get_instructions():
     lines.append('  '+inst.get_name()+' '+inst.get_output())
out.write_text('\n'.join(lines))
print(str(out),len(lines),'lines')
for c in d.get_classes():
 if any(x in c.get_name() for x in ['CloudDeviceStatus','bootloader']): print(c.get_name())
