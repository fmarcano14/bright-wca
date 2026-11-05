#!/bin/bash
# Rebuilds libspake2.so for all ABIs into libs/spake2/jni/<abi>/ (NDK r27, API 30, 16 KB pages).
set -e
cd "$(dirname "$0")/.."
NDK=${NDK:-/home/box/tools/sdk/ndk/27.2.12479018}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
S=native/spake2
for pair in arm64-v8a:aarch64-linux-android armeabi-v7a:armv7a-linux-androideabi x86:i686-linux-android x86_64:x86_64-linux-android; do
  abi=${pair%%:*}; tri=${pair#*:}
  out=libs/spake2/jni/$abi; mkdir -p $out
  o=build/native/$abi; mkdir -p $o
  F="--target=${tri}30 -fPIC -O2 -fvisibility=hidden -ffunction-sections -fdata-sections -I$S/include"
  $TC/clang $F -c $S/sha512.c -o $o/sha512.o
  $TC/clang $F -c $S/spake2.c -o $o/spake2.o
  $TC/clang++ $F -std=c++17 -fno-exceptions -fno-rtti -c $S/spake2_jni.cpp -o $o/jni.o
  $TC/clang++ --target=${tri}30 -shared $o/*.o -Wl,--gc-sections -Wl,-z,max-page-size=16384 \
    -Wl,--hash-style=both -static-libstdc++ -o $out/libspake2.so
  $TC/llvm-strip --strip-unneeded $out/libspake2.so
  echo "built $out/libspake2.so"
done
