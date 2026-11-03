"""Fill the launcher's local configuration UI without exposing the access token."""
from pathlib import Path
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ADB = [str(ROOT/'work/android-tools/platform-tools/adb'),'-s','834b3988']
QA = ROOT/'work/minimal-launcher/qa'

def run(*args):
    try:
        return subprocess.check_output(ADB+list(args),text=True,stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        raise RuntimeError('ADB operation failed; arguments withheld') from None

def snapshot(name):
    run('shell','uiautomator','dump','/sdcard/minimal-home-ui.xml')
    path = QA/(name+'.xml')
    run('pull','/sdcard/minimal-home-ui.xml',str(path))
    nodes = list(ET.parse(path).iter('node'))
    return nodes

def tap(node):
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',node.get('bounds')))
    run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

def text_node(nodes,text):
    return next(n for n in nodes if n.get('text')==text)

if __name__ == '__main__':
    cfg = json.loads((Path(__file__).resolve().parent/'private/tablet-config.json').read_text())
    assert cfg['base_url']=='http://127.0.0.1:18123'
    assert re.fullmatch(r'[A-Za-z0-9._-]+',cfg['token'])
    assert re.fullmatch(r'switch\.[a-z0-9_]+',cfg['entity_id'])
    run('shell','input','keyevent','KEYCODE_HOME')
    idle = snapshot('socket-before-binding')
    if [n.get('text') for n in idle if n.get('text')] != ['搜索应用']:
        raise RuntimeError('Expected an unconfigured desktop; leaving existing controls untouched')
    run('shell','input','swipe','800','1800','800','1800','900')
    tap(text_node(snapshot('socket-bind-menu'),'插座控制设置'))
    nodes=snapshot('socket-bind-settings')
    edits=[n for n in nodes if n.get('class')=='android.widget.EditText']
    assert len(edits)==4 and edits[1].get('password')=='true'
    tap(edits[0])
    for i,value in enumerate([cfg['base_url'],cfg['token'],cfg['entity_id']]):
        if i: run('shell','input','keyevent','KEYCODE_TAB')
        run('shell','input','text',value)
    run('shell','input','keyevent','KEYCODE_BACK')
    nodes=snapshot('socket-bind-ready')
    # Password fields are masked by UIAutomator and are not printed.
    assert any(n.get('text')==cfg['base_url'] for n in nodes)
    assert any(n.get('text')==cfg['entity_id'] for n in nodes)
    tap(text_node(nodes,'保存'))
    print('Configuration saved through the app; token remains masked and encrypted by Android Keystore.')
    nodes=snapshot('socket-bound')
    print(json.dumps([{'text':n.get('text'),'enabled':n.get('enabled')} for n in nodes
                      if n.get('text') and n.get('password')!='true'],ensure_ascii=False))
