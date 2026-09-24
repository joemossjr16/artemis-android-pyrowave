# PyroWave prebuilt libraries

`libpyrowave-shared.so` (C API 0.6.0) built from the pinned `pyrowave` tree with NDK 29.0.14206865
and stripped:

```
cmake -S pyrowave -B build-android-<abi> -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=<abi> -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release
cmake --build build-android-<abi> --target pyrowave-shared
llvm-strip --strip-unneeded libpyrowave-shared.so
```

Only `arm64-v8a` and `x86_64` are provided. PyroWave needs a Vulkan 1.3 GPU, which 32-bit devices do not
have, and the renderer checks the device at runtime before the app offers PyroWave. The library loads
Vulkan itself, so it links only libc, libm, libdl and liblog.

`include/pyrowave/pyrowave.h` must match the library version; the renderer rejects any other API minor.
