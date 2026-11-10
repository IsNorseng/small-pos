#!/usr/bin/env python3
"""Build an offline APK using only Python stdlib, JDK and official Android tools."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def run(args, env=None):
    args = [str(x) for x in args]
    if os.name == 'nt' and args[0].endswith(('.bat', '.cmd')):
        args = [os.environ.get('COMSPEC', 'cmd.exe'), '/d', '/s', '/c', subprocess.list2cmdline(args)]
    subprocess.run(args, check=True, env=env)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--sdk',default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    p.add_argument('--java',default=os.environ.get('JAVA_HOME'))
    p.add_argument('--output',type=Path,default=ROOT/'build'/'small-pos.apk')
    p.add_argument('--signing-dir',type=Path,default=ROOT/'build'/'signing')
    p.add_argument('--tests',action='store_true',help='Also build Android instrumentation APK')
    p.add_argument('--core-test',action='store_true',help='Run arithmetic and PIN tests before build')
    a=p.parse_args()
    if not a.sdk or not a.java:p.error('Set ANDROID_HOME and JAVA_HOME, or pass --sdk and --java.')
    sdk,java=Path(a.sdk),Path(a.java)/'bin'
    os.environ['JAVA_HOME']=str(java.parent)
    suffix='.exe' if os.name=='nt' else ''
    def j(tool):return java/(tool+suffix)
    versions=lambda v:tuple(int(n) for n in re.findall(r'\d+',v.name))
    platforms=sorted((sdk/'platforms').glob('android-*'),key=versions)
    tools=sorted((sdk/'build-tools').glob('*'),key=versions)
    if not platforms or not tools:p.error('Install Android platform 36+ and build-tools 36+ in the SDK.')
    platform,bt=platforms[-1],tools[-1]
    if versions(platform)[0]<36:p.error('Android compile platform 36+ is required.')
    android=platform/'android.jar'
    def b(tool):return bt/(tool+('.bat' if os.name=='nt' and tool in ('d8','apksigner') else suffix))
    work=ROOT/'build'/'intermediates';work.mkdir(parents=True,exist_ok=True)
    # Individual outputs are replaced; source, signing keys and user files are never removed.
    classes=work/'classes';generated=work/'generated';dex=work/'dex'
    for folder in (classes,generated,dex):
        if folder.exists():
            if not folder.resolve().is_relative_to((ROOT/'build').resolve()):raise RuntimeError('Unexpected build path')
            shutil.rmtree(folder)
        folder.mkdir()
    compiler=work/'compiler';compiler.mkdir(exist_ok=True)
    run([j('javac'),'-encoding','UTF-8','-d',compiler,ROOT/'scripts'/'Compile.java'])
    def compile_java(sources,dest,cp=None):
        opts=['-Xlint:-options','--release','8','-encoding','UTF-8','-d',str(dest)]
        if cp:opts+=['-classpath',str(cp)]
        run([j('java'),'-cp',compiler,'Compile',*opts,'--',*sources])
    if a.core_test:
        core=work/'core';core.mkdir(exist_ok=True)
        compile_java([ROOT/'app/src/main/java/ru/smallpos/app/Domain.java',ROOT/'tests/CoreTest.java'],core)
        run([j('java'),'-cp',core,'ru.smallpos.app.CoreTest'])
    resources=work/'resources.zip';unsigned=work/'resources.apk'
    run([b('aapt2'),'compile','--dir',ROOT/'app/src/main/res','-o',resources])
    run([b('aapt2'),'link','-o',unsigned,'-I',android,'--manifest',ROOT/'app/src/main/AndroidManifest.xml','--java',generated,'--min-sdk-version','26','--target-sdk-version','36',resources])
    sources=sorted((ROOT/'app/src/main/java').rglob('*.java'))+sorted(generated.rglob('*.java'))
    compile_java(sources,classes,android)
    # A jar avoids platform-dependent command line length limits for many .class files.
    classjar=work/'classes.jar'
    with zipfile.ZipFile(classjar,'w',zipfile.ZIP_DEFLATED) as z:
        for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
    run([b('d8'),'--release','--min-api','26','--lib',android,'--output',dex,classjar])
    payload=work/'unsigned.apk';shutil.copyfile(unsigned,payload)
    with zipfile.ZipFile(payload,'a',zipfile.ZIP_DEFLATED) as z:
        for f in dex.glob('*.dex'):z.write(f,f.name)
    signing=a.signing_dir.resolve();signing.mkdir(parents=True,exist_ok=True)
    key=signing/'release.p12';password=signing/'password.txt'
    if key.exists()!=password.exists():raise RuntimeError('Signing key/password pair is incomplete. Restore both files.')
    if not key.exists():
        password.write_text(secrets.token_urlsafe(32),encoding='utf-8')
        if os.name!='nt':password.chmod(0o600)
        env=os.environ.copy();env['SMALL_POS_KEY_PASS']=password.read_text().strip()
        run([j('keytool'),'-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass:env','SMALL_POS_KEY_PASS','-keypass:env','SMALL_POS_KEY_PASS','-alias','small-pos','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Small POS, O=Small POS, C=RU'],env)
    env=os.environ.copy();env['SMALL_POS_KEY_PASS']=password.read_text().strip()
    def sign(source,target):
        aligned=work/('aligned-'+target.name);target=target.resolve();target.parent.mkdir(parents=True,exist_ok=True)
        run([b('zipalign'),'-f','-p','4',source,aligned])
        run([b('apksigner'),'sign','--ks',key,'--ks-key-alias','small-pos','--ks-pass','env:SMALL_POS_KEY_PASS','--out',target,aligned],env)
        run([b('apksigner'),'verify','--verbose',target])
        print('APK:',target,'SHA256:',hashlib.sha256(target.read_bytes()).hexdigest())
    sign(payload,a.output)
    if a.tests:
        testclasses=work/'testclasses';testclasses.mkdir(exist_ok=True)
        testjar=work/'tests.jar';testdex=work/'testdex';testdex.mkdir(exist_ok=True)
        # Compile app sources again to avoid directory-classpath quirks on restricted Windows.
        compile_java(sources+[ROOT/'tests/RetailInstrumentation.java'],testclasses,android)
        with zipfile.ZipFile(testjar,'w',zipfile.ZIP_DEFLATED) as z:
            for f in testclasses.rglob('*.class'):
                if f.name.startswith('RetailInstrumentation'):z.write(f,f.relative_to(testclasses).as_posix())
        run([b('d8'),'--min-api','26','--lib',android,'--classpath',classjar,'--output',testdex,testjar])
        testapk=work/'tests-unsigned.apk'
        run([b('aapt2'),'link','-o',testapk,'-I',android,'--manifest',ROOT/'tests/AndroidManifest.xml','--min-sdk-version','26','--target-sdk-version','36'])
        with zipfile.ZipFile(testapk,'a',zipfile.ZIP_DEFLATED) as z:
            for f in testdex.glob('*.dex'):z.write(f,f.name)
        sign(testapk,a.output.with_name('small-pos-tests.apk'))

if __name__=='__main__':
    try:main()
    except (subprocess.CalledProcessError,RuntimeError) as e:
        print('Build failed:',e,file=sys.stderr);sys.exit(1)
