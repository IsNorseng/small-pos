"""Host SQLite integrity checks using the app's actual schema; no packages needed."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

SOURCE=(Path(__file__).resolve().parents[1]/'app/src/main/java/ru/smallpos/app/RetailDb.java').read_text(encoding='utf-8')
SCHEMA=SOURCE.split('public void onCreate(SQLiteDatabase db) {',1)[1].split('@Override public void onUpgrade',1)[0]
DDL=[json.loads('"'+s+'"') for s in re.findall(r'db\.execSQL\("((?:[^"\\]|\\.)*)"\)',SCHEMA)]

class Integrity(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(':memory:');self.db.execute('PRAGMA foreign_keys=ON')
        for sql in DDL:self.db.execute(sql)
        self.db.execute("INSERT INTO products VALUES(1,'Чай','piece',10000,1)");self.db.commit()
    def tearDown(self):self.db.close()
    def order(self,key='key',state='pending',paid_by=None):
        c=self.db.execute("INSERT INTO orders(request_key,fingerprint,total,state,method,store_name,created,paid_at,paid_by) VALUES(?,'hash',10000,?,'cash','Точка',1,1,?)",(key,state,paid_by));id=c.lastrowid
        self.db.execute("INSERT INTO order_lines VALUES(?,1,'Чай','piece',10000,1,10000)",(id,));return id
    def test_price_snapshot(self):
        self.order();self.db.execute('UPDATE products SET price=50000,name=\'Кофе\' WHERE id=1')
        self.assertEqual(self.db.execute('SELECT price,name,amount FROM order_lines').fetchone(),(10000,'Чай',10000))
    def test_request_and_bank_unique(self):
        a=self.order();b=self.order('other')
        with self.assertRaises(sqlite3.IntegrityError):self.order()
        self.db.execute("INSERT INTO bank_payments VALUES(?, 'a','bank','https://ecomtest.sberbank.ru/',0)",(a,))
        with self.assertRaises(sqlite3.IntegrityError):self.db.execute("INSERT INTO bank_payments VALUES(?, 'b','bank','https://ecomtest.sberbank.ru/',0)",(b,))
    def test_broken_restore_rolls_back(self):
        self.order();self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute('DELETE FROM orders')
                self.db.execute("INSERT INTO products VALUES(2,'Bad','invalid',100,1)")
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM orders').fetchone()[0],1)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM order_lines').fetchone()[0],1)
    def test_statistics_exclude_pending_cancelled_and_sandbox(self):
        self.order('paid','paid','SELLER');self.order('unpaid');self.order('cancelled','cancelled');self.order('test','paid','BANK_TEST')
        queries=re.findall(r'rawQuery\("([^"]+)',SOURCE)
        stats=next(q for q in queries if q.startswith('SELECT COUNT(*),COALESCE(SUM(total)'))
        self.assertEqual(self.db.execute(stats,(0,)).fetchone(),(1,10000))
        top=next(q for q in queries if q.startswith('SELECT l.product_id,l.name'))
        row=self.db.execute(top,(0,)).fetchone();self.assertEqual(row[3:],(1,1,10000))
    def test_foreign_keys_cascade(self):
        id=self.order();self.db.execute("INSERT INTO bank_payments VALUES(?,'n','b','u',0)",(id,));self.db.execute('DELETE FROM orders')
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM order_lines').fetchone()[0],0)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM bank_payments').fetchone()[0],0)
    def test_paid_queue_is_paginated_without_pending_orders(self):
        for i in range(205):self.order(str(i),'paid' if i>=100 else 'pending')
        first=self.db.execute("SELECT id FROM orders WHERE state='paid' AND done=0 AND id>? ORDER BY id LIMIT 100",(0,)).fetchall()
        second=self.db.execute("SELECT id FROM orders WHERE state='paid' AND done=0 AND id>? ORDER BY id LIMIT 100",(first[-1][0],)).fetchall()
        self.assertEqual((len(first),len(second)),(100,5));self.assertEqual(len(set(first+second)),105)

if __name__=='__main__':unittest.main(verbosity=2)
