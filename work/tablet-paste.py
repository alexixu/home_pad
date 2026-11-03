from pathlib import Path
import subprocess,socket,struct,sys,time
adb=str(Path('work/android-tools/platform-tools/adb').resolve())
base=[adb,'-s','834b3988']
body=Path(sys.argv[1]).read_bytes()
assert len(body)<262130
remote='/data/local/tmp/codex-clipboard-server.jar'
subprocess.run(base+['push','work/android-tools/scrcpy-macos-aarch64-v5.0.1/scrcpy-server',remote],check=True,stdout=subprocess.DEVNULL)
port=int(subprocess.check_output(base+['forward','tcp:0','localabstract:scrcpy_6a31c0de'],text=True).strip())
cmd='CLASSPATH='+remote+' app_process / com.genymobile.scrcpy.Server 5.0.1 scid=6a31c0de log_level=info video=false audio=false control=true tunnel_forward=true cleanup=false power_on=false clipboard_autosync=false send_device_meta=false send_dummy_byte=true display_id=0'
log=Path('work/tablet-paste-server.log').open('wb')
proc=subprocess.Popen(base+['shell',cmd],stdout=log,stderr=subprocess.STDOUT)
sock=None
try:
 for _ in range(30):
  try:
   sock=socket.create_connection(('127.0.0.1',port),timeout=.5)
   sock.settimeout(.5)
   hello=sock.recv(1)
   if hello==b'\0': break
   sock.close(); sock=None
  except (OSError,TimeoutError):
   if sock: sock.close(); sock=None
  time.sleep(.1)
 else: raise RuntimeError('No scrcpy control handshake')
 sock.settimeout(5)
 sock.sendall(struct.pack('>BQBI',9,1,1,len(body))+body)
 ack=b''
 while len(ack)<9:
  part=sock.recv(9-len(ack))
  if not part: raise RuntimeError('Socket closed before ACK')
  ack+=part
 assert ack==struct.pack('>BQ',1,1), repr(ack)
 print('Clipboard paste acknowledged; verify field in UI before sending.')
 time.sleep(.2)
finally:
 if sock: sock.close()
 try: proc.wait(timeout=3)
 except subprocess.TimeoutExpired:
  proc.terminate(); proc.wait(timeout=3)
 log.close()
 subprocess.run(base+['forward','--remove','tcp:'+str(port)],check=False)
 subprocess.run(base+['shell','rm',remote],check=False)
