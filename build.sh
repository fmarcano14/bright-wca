#!/bin/bash
set -e
cd "$(dirname "$0")"
T=${TOOLS:-/home/box/tools}; BT=$T/sdk/build-tools/34.0.0; AJ=$T/sdk/platforms/android-34/android.jar
export JAVA_HOME=$T/jdk-17.0.20.1+1; export PATH=$JAVA_HOME/bin:$PATH
L=libs; JARS="$L/bcprov-jdk15to18-1.77.jar:$L/bcpkix-jdk15to18-1.77.jar:$L/bcutil-jdk15to18-1.77.jar:$L/conscrypt/classes.jar:$L/annotation-jvm-1.9.1.jar"
rm -rf build; mkdir -p build/gen build/cls build/dex build/apk/lib
$BT/aapt2 compile --dir app/res -o build/res.zip
$BT/aapt2 link -I $AJ --manifest app/AndroidManifest.xml -o build/base.apk --java build/gen build/res.zip
javac -nowarn -source 8 -target 8 -cp $AJ:$JARS -d build/cls $(find app/src build/gen src-libadb/libadb/src/main/java spake2-java/android/src/main/java -name '*.java') 
(cd build/cls && jar cf ../app.jar .)
$BT/d8 --release --lib $AJ --min-api 30 --output build/dex build/app.jar $L/bcprov-jdk15to18-1.77.jar $L/bcpkix-jdk15to18-1.77.jar $L/bcutil-jdk15to18-1.77.jar $L/conscrypt/classes.jar 2>&1 | grep -v -i warning | head -20
cp build/base.apk build/u.apk
for a in arm64-v8a armeabi-v7a x86 x86_64; do mkdir -p build/apk/lib/$a; cp $L/conscrypt/jni/$a/libconscrypt_jni.so $L/spake2/jni/$a/libspake2.so build/apk/lib/$a/; done
cp build/dex/*.dex build/apk/
python3 -c "
import zipfile,os
z=zipfile.ZipFile('build/u.apk','a',zipfile.ZIP_DEFLATED)
for r,d,f in os.walk('build/apk'):
  for n in f:
    p=os.path.join(r,n); z.write(p,os.path.relpath(p,'build/apk'))
z.close()"
$BT/zipalign -f -p 4 build/u.apk build/a.apk
KS=${KEYSTORE:-/workspace/focusapp/debug.keystore}; KSPASS=${KEYSTORE_PASS:-android}
mkdir -p out
$BT/apksigner sign --ks "$KS" --ks-pass pass:"$KSPASS" --out out/bright-wca.apk build/a.apk
rm -f out/*.idsig
ls -la out/bright-wca.apk
cp -f out/bright-wca.apk bright-wca.apk
