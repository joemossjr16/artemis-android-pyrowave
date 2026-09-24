# Android.mk for the PyroWave Vulkan renderer.
#
# PyroWave is built only for 64-bit ABIs (see prebuilt/README.md). On other ABIs
# the renderer library is absent and the app never offers PyroWave.
LOCAL_PATH := $(call my-dir)

ifneq ($(filter arm64-v8a x86_64,$(TARGET_ARCH_ABI)),)

include $(CLEAR_VARS)
LOCAL_MODULE := pyrowave-shared
LOCAL_SRC_FILES := prebuilt/$(TARGET_ARCH_ABI)/libpyrowave-shared.so
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/prebuilt/include
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := pyrowave-renderer
LOCAL_SRC_FILES := pyrowave_renderer.cpp
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Wno-missing-field-initializers -fno-exceptions -fno-rtti
LOCAL_SHARED_LIBRARIES := pyrowave-shared
# Vulkan is loaded with dlopen at runtime; libvulkan is not linked.
LOCAL_LDLIBS := -llog -landroid -ldl
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)

endif
