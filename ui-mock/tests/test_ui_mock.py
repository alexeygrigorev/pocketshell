"""No Android/Gradle dependencies. Real renders remain a separate acceptance check."""
from __future__ import annotations

import base64
import http.client
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from builder import Builder, Image, command, png_image, verify_test_report
from catalog import Case, code_mask, discover, parse_file
from engine import Engine, RepositoryLock, source_stamp
from serve import ThreadingHTTPServer, allowed_host, make_handler

FIXTURE = '''package com.pocketshell.next.render
class SampleRenders {
    @Test
    fun empty() = render("empty") { HostScreen() }
    @Test
    fun longText() = render(
      "long-text"
    ) { HostScreen() }
}
'''


def make_root(path):
    (path / "app2").mkdir(exist_ok=True)
    (path / "app2/build.gradle.kts").write_text("// fixture")
    source = path / "app2/src/test/java/com/pocketshell/next/render/SampleRenders.kt"
    source.parent.mkdir(parents=True)
    source.write_text(FIXTURE)
    (path / "gradlew").write_text("#!/bin/sh\nexit 0\n")
    (path / "gradlew.bat").write_text("@exit /b 0\n")
    return source


def png():
    def chunk(name, data):
        return struct.pack(">I", len(data)) + name + data + struct.pack(">I", zlib.crc32(name + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00\x20\x30\x40")) + chunk(b"IEND", b""))


def report(path, case, child=None, name=None):
    suite=ET.Element("testsuite", tests="1")
    test=ET.SubElement(suite,"testcase",name=name or case.method,classname=case.class_name)
    if child: ET.SubElement(test,child)
    path.parent.mkdir(parents=True,exist_ok=True)
    ET.ElementTree(suite).write(path,encoding="utf-8")


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.source=make_root(self.root)
        self.case=discover(self.root)[0][0]


class CatalogTests(Base):
    def test_two_literal_cases(self):
        cases,warnings=discover(self.root)
        self.assertEqual([c.label for c in cases],["empty","long-text"])
        self.assertFalse(warnings)
        self.assertEqual(cases[0].test_filter,"com.pocketshell.next.render.SampleRenders.empty")

    def test_comments_strings_and_nested_comments_do_not_make_cases(self):
        bogus='\n/* outer /* inner */ @Test fun bad() = render("bad") {} */\n'
        bogus+='val sample = """@Test fun bogus() = render("bogus") {}"""\n'
        self.source.write_text(bogus+FIXTURE)
        self.assertEqual(len(discover(self.root)[0]),2)

    def test_mask_preserves_offsets_and_newlines(self):
        text='a/* aa\n /*b*/ */b "// hi" c \'x\' d'
        masked=code_mask(text)
        self.assertEqual(len(text),len(masked));self.assertEqual(text.count('\n'),masked.count('\n'))
        self.assertNotIn('hi',masked);self.assertIn('b ',masked)

    def test_dynamic_labels_warn_and_are_not_executed(self):
        self.source.write_text(FIXTURE.replace('render("empty")','render(label)'))
        cases,notes=discover(self.root)
        self.assertEqual(len(cases),1);self.assertIn('unsupported',notes[0])

    def test_ids_stable_when_label_changes(self):
        old=self.case.id;self.source.write_text(FIXTURE.replace('"empty"','"renamed"'))
        self.assertEqual(discover(self.root)[0][0].id,old)

    def test_kit_is_opt_in_and_labelled(self):
        path=self.root/'shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt'
        path.parent.mkdir(parents=True);path.write_text(FIXTURE.replace('SampleRenders','DesignRenders'))
        self.assertEqual(len(discover(self.root)[0]),2)
        cases,_=discover(self.root,True);self.assertEqual(len(cases),4)
        self.assertEqual(cases[-1].kind,'ui-kit-example')

    def test_path_traversal_label_rejected(self):
        self.source.write_text(FIXTURE.replace('"empty"','"../../secret"'))
        cases,notes=discover(self.root);self.assertEqual(len(cases),1);self.assertTrue(notes)

    def test_removing_fixture_updates_catalog(self):
        self.source.unlink();self.assertEqual(discover(self.root)[0],[])


class BuilderTests(Base):
    def test_gradle_command_is_targeted_and_incremental(self):
        args=command(self.root,self.case)
        self.assertIn('--daemon',args);self.assertIn('--build-cache',args)
        self.assertIn(':app2:testDebugUnitTest',args);self.assertIn(self.case.test_filter,args)
        self.assertNotIn('--rerun-tasks',args);self.assertFalse(any('assemble' in a for a in args))

    def test_missing_wrapper_errors(self):
        (self.root/'gradlew').unlink();(self.root/'gradlew.bat').unlink()
        with self.assertRaisesRegex(RuntimeError,'wrapper'):command(self.root,self.case)

    def test_validate_png_and_dimensions(self):
        path=self.root/'sample.png';path.write_bytes(png())
        image=png_image(path);self.assertEqual((image.width,image.height),(1,1))
        path.write_bytes(png()[:-4])
        with self.assertRaises(RuntimeError):png_image(path)

    def test_missing_zero_skipped_failed_reports_rejected(self):
        path=self.root/'report.xml'
        with self.assertRaises(RuntimeError):verify_test_report(path,self.case)
        for status in ('skipped','failure','error'):
            report(path,self.case,child=status)
            with self.assertRaises(RuntimeError):verify_test_report(path,self.case)
        path.write_text('<testsuite tests="0"/>')
        with self.assertRaises(RuntimeError):verify_test_report(path,self.case)

    def test_parameterized_success_report(self):
        path=self.root/'report.xml';report(path,self.case,name=self.case.method+'[0]')
        self.assertEqual(verify_test_report(path,self.case),1)

    def test_closed_builder_cannot_spawn(self):
        b=Builder(self.root);b.close()
        with self.assertRaisesRegex(RuntimeError,'stopped'):b.build(self.case,lambda _:None)

    def test_success_requires_fresh_image_and_fresh_report(self):
        case=self.case;root=self.root
        class Process:
            def __init__(self,*args,**kwargs):
                self.stdout=io.StringIO('test passed\n')
                (root/'app2/build/renders/empty.png').write_bytes(png())
                report(root/f'app2/build/test-results/testDebugUnitTest/TEST-{case.class_name}.xml',case)
            def wait(self,timeout=None):return 0
            def poll(self):return 0
        with patch('builder.subprocess.Popen',Process):
            images=Builder(root).build(case,lambda _:None)
        self.assertEqual(images[0].name,'empty.png')
        class NoOutput:
            def __init__(self,*args,**kwargs):self.stdout=io.StringIO('UP-TO-DATE\n')
            def wait(self,timeout=None):return 0
            def poll(self):return 0
        with patch('builder.subprocess.Popen',NoOutput):
            with self.assertRaisesRegex(RuntimeError,'report'):Builder(root).build(case,lambda _:None)

    def test_nonzero_gradle_exit_fails_even_if_png_exists(self):
        class Failed:
            def __init__(self,*args,**kwargs):self.stdout=io.StringIO('compile error\n')
            def wait(self,timeout=None):return 7
            def poll(self):return 7
        with patch('builder.subprocess.Popen',Failed):
            with self.assertRaisesRegex(RuntimeError,'exit 7'):Builder(self.root).build(self.case,lambda _:None)


class FakeBuilder:
    def __init__(self):self.calls=[];self.fail=False
    def build(self,case,log):
        self.calls.append(case.id)
        if self.fail:raise RuntimeError('compile failed')
        return [Image(case.label+'.png',png(),1,1)]
    def close(self):pass


def wait_for(predicate):
    end=time.monotonic()+3
    while time.monotonic()<end:
        if predicate():return
        time.sleep(.01)
    raise AssertionError('Timed out waiting for worker')


class EngineTests(Base):
    def test_success_failure_then_recovery_marks_staleness(self):
        fake=FakeBuilder();engine=Engine(self.root,watch=False,builder=fake)
        self.addCleanup(engine.close);engine.start()
        wait_for(lambda:engine.status()['phase']=='ready')
        state=engine.status();self.assertFalse(state['stale'])
        fake.fail=True;engine.request()
        wait_for(lambda:engine.status()['phase']=='error')
        self.assertTrue(engine.status()['stale']);self.assertEqual(engine.status()['image_revision'],1)
        fake.fail=False;engine.request()
        wait_for(lambda:engine.status()['phase']=='ready')
        self.assertFalse(engine.status()['stale']);self.assertEqual(engine.status()['image_revision'],2)

    def test_no_outdated_inflight_result_published(self):
        entered=threading.Event();release=threading.Event()
        class Slow(FakeBuilder):
            def build(self,case,log):
                if not self.calls:entered.set();release.wait(3)
                return super().build(case,log)
        fake=Slow();engine=Engine(self.root,watch=False,builder=fake)
        self.addCleanup(engine.close);engine.start();self.assertTrue(entered.wait(2))
        second=engine.cases[1].id;engine.request(second);release.set()
        wait_for(lambda:engine.status()['phase']=='ready')
        state=engine.status();self.assertEqual(state['rendered_case'],second)
        self.assertEqual(state['image_revision'],1);self.assertEqual(fake.calls[-1],second)

    def test_unknown_case_rejected(self):
        engine=Engine(self.root,watch=False,builder=FakeBuilder());self.addCleanup(engine.close)
        with self.assertRaises(ValueError):engine.request('../../bad')

    def test_image_revision_checked(self):
        engine=Engine(self.root,watch=False,builder=FakeBuilder());self.addCleanup(engine.close);engine.start()
        wait_for(lambda:engine.status()['phase']=='ready')
        with self.assertRaises(ValueError):engine.image(0,999)
        self.assertEqual(engine.image(0,1),png())

    def test_watch_stamp_ignores_build_outputs(self):
        before=source_stamp(self.root);folder=self.root/'app2/build/renders';folder.mkdir(parents=True)
        (folder/'a.png').write_bytes(png());self.assertEqual(source_stamp(self.root),before)
        self.source.write_text(FIXTURE+'// saved');self.assertNotEqual(source_stamp(self.root),before)

    def test_repository_lock_excludes_second_server(self):
        lock=RepositoryLock(self.root)
        try:
            with self.assertRaises(RuntimeError):RepositoryLock(self.root)
        finally:lock.close()
        lock2=RepositoryLock(self.root);lock2.close()


class HttpTests(Base):
    def setUp(self):
        super().setUp();self.engine=Engine(self.root,watch=False,builder=FakeBuilder())
        self.server=ThreadingHTTPServer(('127.0.0.1',0),make_handler(self.engine,'test-token'))
        self.server.daemon_threads=True
        threading.Thread(target=self.server.serve_forever,daemon=True).start()
        self.addCleanup(self.engine.close);self.addCleanup(self.server.server_close);self.addCleanup(self.server.shutdown)

    def request(self,path,method='GET',body=None,headers=None):
        h={'X-UI-Mock-Token':'test-token'};h.update(headers or {})
        conn=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3)
        try:
            conn.request(method,path,body=body,headers=h);response=conn.getresponse()
            return response.status,response.read(),dict(response.getheaders())
        finally:conn.close()

    def test_static_and_catalog_work(self):
        status,body,headers=self.request('/');self.assertEqual(status,200);self.assertIn(b'UI MOCK',body)
        self.assertIn('Content-Security-Policy',headers)
        status,body,_=self.request('/api/catalog');self.assertEqual(len(json.loads(body)['cases']),2)

    def test_bad_or_missing_token_rejected(self):
        self.assertEqual(self.request('/api/state',headers={'X-UI-Mock-Token':''})[0],401)
        self.assertEqual(self.request('/api/state',headers={'X-UI-Mock-Token':'\xff'})[0],401)

    def test_cross_origin_and_dns_rebinding_rejected(self):
        self.assertEqual(self.request('/api/catalog',headers={'Origin':'https://evil.invalid'})[0],403)
        self.assertEqual(self.request('/api/catalog',headers={'Host':'evil.invalid'})[0],403)

    def test_traversal_and_arbitrary_methods_rejected(self):
        self.assertEqual(self.request('/../../AGENTS.md')[0],404)
        status,_,_=self.request('/api/select','POST',json.dumps({'case_id':'../../bad'}),{'Content-Type':'application/json'})
        self.assertEqual(status,400)

    def test_post_requires_json_and_known_schema(self):
        self.assertEqual(self.request('/api/rebuild','POST','{}')[0],400)
        self.assertEqual(self.request('/api/rebuild','POST','[]',{'Content-Type':'application/json'})[0],400)
        self.assertEqual(self.request('/api/rebuild','POST','{"command":"anything"}',{'Content-Type':'application/json'})[0],400)

    def test_valid_selection_queues_only_allowlisted_case(self):
        second=self.engine.cases[1]
        status,body,_=self.request('/api/select','POST',json.dumps({'case_id':second.id}),{'Content-Type':'application/json'})
        self.assertEqual(status,202);self.assertEqual(json.loads(body)['selected'],second.id)

    def test_host_validation(self):
        for host in ('localhost:4173','127.0.0.1:9000','[::1]:4173'):self.assertTrue(allowed_host(host))
        for host in ('localhost.evil','user@localhost:4173','localhost/secret','localhost:99999',''):
            self.assertFalse(allowed_host(host))


if __name__=='__main__':unittest.main()
