"""Tests use synthetic SQLite databases only; never the user's Messages database."""
import datetime as dt
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location("sms_collector",Path(__file__).with_name("sms_collector.py"))
collector=importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class CollectorTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.directory=Path(self.temp.name)
        self.database=self.directory/"synthetic.db"
        self.now=dt.datetime.now(dt.timezone.utc)
        stamp=int((self.now-collector.APPLE_EPOCH).total_seconds()*1_000_000_000)
        with sqlite3.connect(self.database) as db:
            db.executescript("CREATE TABLE handle(id TEXT); CREATE TABLE message(guid TEXT,text TEXT,date INTEGER,is_from_me INTEGER,handle_id INTEGER);")
            db.executemany("INSERT INTO handle(id) VALUES(?)",[("VM-AXISBK-S",),("FRIEND",)])
            db.executemany("INSERT INTO message VALUES(?,?,?,?,?)",[("bank-1","INR 20 debited",stamp,0,1),("personal","private",stamp,0,2),("outbound","my reply",stamp,1,1)])
        self.config={"senders":["AXISBK-S"],"messagesDb":str(self.database),"stateFile":str(self.directory/"state.json"),"lookbackDays":1}

    def tearDown(self): self.temp.cleanup()

    def test_queriesOnlyApprovedIncomingMessages(self):
        state={"lastRowId":0,"scanAfter":(self.now-dt.timedelta(days=1)).isoformat(),"pendingRows":[]}
        with sqlite3.connect(self.database) as db: rows=collector.read_rows(db,self.config,state)
        self.assertEqual([r[1] for r in rows],["bank-1"])

    def test_checkpointAdvancesOnlyAfterAcknowledgement(self):
        with patch.object(collector,"post",side_effect=OSError("offline")):
            with self.assertRaises(OSError): collector.run_once(self.config)
        self.assertEqual(json.loads(Path(self.config["stateFile"]).read_text())["lastRowId"],0)
        with patch.object(collector,"post",return_value={"status":"REVIEW"}) as post:
            self.assertEqual(collector.run_once(self.config),(1,0))
            self.assertEqual(post.call_args_list[0].args[2]["sourceId"],"bank-1")
            self.assertEqual(collector.run_once(self.config),(0,0))
        state=Path(self.config["stateFile"]).read_text()
        self.assertNotIn("INR",state);self.assertNotIn("private",state)

    def test_unavailableTextIsRetriedWithoutBlockingNewAlerts(self):
        with sqlite3.connect(self.database) as db: db.execute("UPDATE message SET text=NULL WHERE guid='bank-1'")
        with patch.object(collector,"post",return_value={}): self.assertEqual(collector.run_once(self.config),(0,1))
        with sqlite3.connect(self.database) as db: db.execute("UPDATE message SET text='INR 20 debited' WHERE guid='bank-1'")
        with patch.object(collector,"post",return_value={}): self.assertEqual(collector.run_once(self.config),(1,0))

    def test_captureRequiresExplicitApprovalAndLocalDestination(self):
        path=self.directory/"config.json"
        collector.atomic_json(path,{"enabled":False,"senders":["AXISBK-S"]})
        with self.assertRaises(ValueError):collector.load_config(path)
        collector.atomic_json(path,{"enabled":True,"approvedAt":"now","senders":["AXISBK-S"],"apiUrl":"https://example.com/api/capture"})
        with self.assertRaises(ValueError):collector.load_config(path)

    def test_macosAuthorizationDenialIsReportedWithoutCrashing(self):
        class Args:
            config="unused"
            watch=False
        with patch.object(collector,"load_config",return_value=self.config), patch.object(collector,"run_once",side_effect=sqlite3.DatabaseError("authorization denied")), patch.object(collector,"heartbeat") as report:
            self.assertEqual(collector.run(Args()),2)
            self.assertEqual(report.call_args.args[1],"PERMISSION_REQUIRED")


if __name__=="__main__":unittest.main()
