#!/usr/bin/env python3
"""Manage only this tablet-local shell bridge. Does not restart HA or Termux."""
import argparse
import json
from pathlib import Path
import re
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
BASE=Path(__file__).resolve().parent
REMOTE='/data/local/tmp/homepad-tmall-bridge'
sys.path.insert(0,str(ROOT/'work/tablet-ha'))
from verify_local_service import Adb, read_private_json


def status(adb):
    raw=adb.run('shell','cat',REMOTE+'/status.json')
    data=json.loads(raw)
    if not isinstance(data,dict) or type(data.get('pid')) is not int:
        raise RuntimeError('invalid_owned_status')
    return data


def owned_process(rows,pid):
    return any(len(row)>=4 and row[0]==str(pid) and row[1]=='2000'
               and 'TmallCurtainBridge' in row[3:] and REMOTE+'/runtime-config.json' in row[3:] for row in rows)


def stop(adb):
    try:
        data=status(adb)
    except Exception:
        return
    if not data.get('alive'):
        return
    pid=str(data['pid'])
    if not re.fullmatch(r'[1-9][0-9]{1,8}',pid):
        raise RuntimeError('invalid_owned_pid')
    # Android app_process sets argv[0] to its main class, and /proc may be hidden.
    process_columns = ('shell', 'ps', '-A', '-o', 'PID,UID,STAT,NAME,ARGS')
    rows = [row.split() for row in adb.run(*process_columns).splitlines()[1:]]
    if not any(len(row) >= 3 and row[0] == pid and not row[2].startswith('Z') for row in rows):
        return
    if not owned_process(rows,pid):
        raise RuntimeError('owned_process_identity_mismatch')
    adb.run('shell','touch',REMOTE+'/stop.request')
    for attempt in range(180):
        time.sleep(.2)
        if not status(adb).get('alive'):
            return
        if attempt % 5 == 4:
            rows = [row.split() for row in adb.run(*process_columns).splitlines()[1:]]
            if not any(len(row) >= 3 and row[0] == pid and not row[2].startswith('Z') for row in rows):
                return
    raise RuntimeError('bridge_cleanup_not_finished')


def start(adb):
    config=BASE/'private/runtime-config.json'
    read_private_json(config)
    stop(adb)
    adb.run('shell','mkdir','-p',REMOTE)
    adb.run('shell','chmod','700',REMOTE)
    adb.run('push',str(BASE/'build/dex/classes.dex'),REMOTE+'/runtime.dex')
    adb.run('push',str(config),REMOTE+'/runtime-config.json')
    adb.run('shell','chmod','600',REMOTE+'/runtime.dex',REMOTE+'/runtime-config.json')
    adb.run('shell','rm','-f',REMOTE+'/status.json',REMOTE+'/stop.request')
    started=int(adb.run('shell','date','+%s'))
    # Static reviewed shell syntax; private values never enter argv or this command.
    command=('nohup setsid env CLASSPATH='+REMOTE+'/runtime.dex app_process /system/bin TmallCurtainBridge '
             +REMOTE+'/runtime-config.json >'+REMOTE+'/runtime.log 2>&1 </dev/null &')
    adb.run('shell',command)
    for _ in range(40):
        time.sleep(.25)
        try:
            data=status(adb)
            if data.get('alive') and data.get('updated',0)>=started:
                print(json.dumps({'bridge_started':True,'status':data,'ha_restart_requested':False}))
                return
        except Exception:
            pass
    raise RuntimeError('bridge_did_not_start')


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('operation',choices=['start','stop','status'])
    args=parser.parse_args();adb=Adb()
    if args.operation=='start':start(adb)
    elif args.operation=='stop':stop(adb);print(json.dumps({'bridge_stopped':True}))
    else:
        data=status(adb)
        rows=[row.split() for row in adb.run('shell','ps','-A','-o','PID,UID,NAME,ARGS').splitlines()[1:]]
        data['process_present']=owned_process(rows,data['pid'])
        data['alive']=data.get('alive',False) and data['process_present']
        data['heartbeat_age_seconds']=round(int(adb.run('shell','date','+%s'))-data['updated'],2)
        print(json.dumps(data))


if __name__=='__main__':
    try:main()
    except Exception as error:
        message=str(error)
        print(json.dumps({'deploy_error':type(error).__name__,'reason':message if re.fullmatch(r'[a-z_]{1,100}',message) else 'details_withheld'}))
        raise SystemExit(1)
