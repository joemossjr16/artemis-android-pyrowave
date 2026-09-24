// PyroWave video renderer for Moonlight Android.
//
// PyroWave is an intra-only wavelet codec decoded with Vulkan compute, so it cannot
// go through MediaCodec. This renderer owns a Vulkan device and a swapchain on the
// stream's Surface, lends the device to PyroWave, decodes each frame into three
// Y/Cb/Cr plane images and converts them to RGB in a fragment shader.
//
// Vulkan is loaded at runtime (libvulkan.so is not available on every Android
// version this app supports), so this library links neither libvulkan nor any
// Vulkan prototypes.

#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <pyrowave/pyrowave.h>

#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <time.h>
#include <unistd.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <iterator>
#include <memory>
#include <mutex>
#include <vector>

#include "shaders_spv.h"

#define LOG_TAG "PyroWave"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    // Results returned to Java.
    constexpr int SUBMIT_OK = 0;
    constexpr int SUBMIT_SKIPPED = 1;
    constexpr int SUBMIT_ERROR = -1;

    constexpr uint64_t ACQUIRE_TIMEOUT_NS = 250'000'000;
    constexpr uint64_t FENCE_TIMEOUT_NS = 2'000'000'000;

    // Moonlight frame container: "PYRW", version, big-endian u16 packet count,
    // reserved byte, then per packet a big-endian u32 length and the packet bytes.
    constexpr size_t FRAME_HEADER_SIZE = 8;
    constexpr uint8_t FRAME_VERSION = 1;

    const char *const INSTANCE_EXTENSIONS[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };
    const char *const DEVICE_EXTENSIONS[] = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
    };

#define VK_GLOBAL_FUNCTIONS(X) \
    X(CreateInstance) \
    X(EnumerateInstanceVersion)

#define VK_INSTANCE_FUNCTIONS(X) \
    X(DestroyInstance) \
    X(EnumeratePhysicalDevices) \
    X(GetPhysicalDeviceProperties) \
    X(GetPhysicalDeviceFeatures2) \
    X(GetPhysicalDeviceQueueFamilyProperties) \
    X(GetPhysicalDeviceMemoryProperties) \
    X(CreateDevice) \
    X(GetDeviceProcAddr) \
    X(CreateAndroidSurfaceKHR) \
    X(DestroySurfaceKHR) \
    X(GetPhysicalDeviceSurfaceSupportKHR) \
    X(GetPhysicalDeviceSurfaceCapabilitiesKHR) \
    X(GetPhysicalDeviceSurfaceFormatsKHR) \
    X(GetPhysicalDeviceSurfacePresentModesKHR)

#define VK_DEVICE_FUNCTIONS(X) \
    X(DestroyDevice) \
    X(GetDeviceQueue) \
    X(DeviceWaitIdle) \
    X(CreateImage) \
    X(DestroyImage) \
    X(GetImageMemoryRequirements) \
    X(AllocateMemory) \
    X(FreeMemory) \
    X(BindImageMemory) \
    X(CreateImageView) \
    X(DestroyImageView) \
    X(CreateSampler) \
    X(DestroySampler) \
    X(CreateDescriptorSetLayout) \
    X(DestroyDescriptorSetLayout) \
    X(CreatePipelineLayout) \
    X(DestroyPipelineLayout) \
    X(CreateDescriptorPool) \
    X(DestroyDescriptorPool) \
    X(AllocateDescriptorSets) \
    X(UpdateDescriptorSets) \
    X(CreateShaderModule) \
    X(DestroyShaderModule) \
    X(CreateRenderPass) \
    X(DestroyRenderPass) \
    X(CreateGraphicsPipelines) \
    X(DestroyPipeline) \
    X(CreateFramebuffer) \
    X(DestroyFramebuffer) \
    X(CreateCommandPool) \
    X(DestroyCommandPool) \
    X(AllocateCommandBuffers) \
    X(BeginCommandBuffer) \
    X(EndCommandBuffer) \
    X(ResetCommandBuffer) \
    X(CmdPipelineBarrier) \
    X(CmdBeginRenderPass) \
    X(CmdEndRenderPass) \
    X(CmdBindPipeline) \
    X(CmdBindDescriptorSets) \
    X(CmdSetViewport) \
    X(CmdSetScissor) \
    X(CmdDraw) \
    X(CreateFence) \
    X(DestroyFence) \
    X(WaitForFences) \
    X(ResetFences) \
    X(CreateSemaphore) \
    X(DestroySemaphore) \
    X(QueueSubmit) \
    X(CreateSwapchainKHR) \
    X(DestroySwapchainKHR) \
    X(GetSwapchainImagesKHR) \
    X(AcquireNextImageKHR) \
    X(QueuePresentKHR) \
    X(CreateQueryPool) \
    X(DestroyQueryPool) \
    X(CmdResetQueryPool) \
    X(CmdWriteTimestamp) \
    X(GetQueryPoolResults)

    struct VulkanLoader {
        void *library = nullptr;
        PFN_vkGetInstanceProcAddr GetInstanceProcAddr = nullptr;
#define X(name) PFN_vk##name name = nullptr;
        VK_GLOBAL_FUNCTIONS(X)
        VK_INSTANCE_FUNCTIONS(X)
        VK_DEVICE_FUNCTIONS(X)
#undef X

        ~VulkanLoader() {
            if (library != nullptr) {
                dlclose(library);
            }
        }

        bool loadGlobal() {
            library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
            if (library == nullptr) {
                LOGI("No Vulkan loader on this device");
                return false;
            }
            GetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
            if (GetInstanceProcAddr == nullptr) {
                return false;
            }
#define X(name) name = reinterpret_cast<PFN_vk##name>(GetInstanceProcAddr(VK_NULL_HANDLE, "vk" #name));
            VK_GLOBAL_FUNCTIONS(X)
#undef X
            // vkEnumerateInstanceVersion is missing on Vulkan 1.0 loaders, which is fine: 1.0 is not enough.
            return CreateInstance != nullptr && EnumerateInstanceVersion != nullptr;
        }

        bool loadInstance(VkInstance instance) {
            bool ok = true;
#define X(name) \
            name = reinterpret_cast<PFN_vk##name>(GetInstanceProcAddr(instance, "vk" #name)); \
            ok = ok && name != nullptr;
            VK_INSTANCE_FUNCTIONS(X)
#undef X
            return ok;
        }

        bool loadDevice(VkDevice device) {
            bool ok = true;
#define X(name) \
            name = reinterpret_cast<PFN_vk##name>(GetDeviceProcAddr(device, "vk" #name)); \
            ok = ok && name != nullptr;
            VK_DEVICE_FUNCTIONS(X)
#undef X
            return ok;
        }
    };

    // Features PyroWave's decode kernels use unconditionally. shaderFloat16 is optional.
    struct FeatureProbe {
        bool ok = false;
        bool float16 = false;
        uint32_t apiVersion = 0;
        char name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE] = {};
    };

    FeatureProbe probeFeatures(const VulkanLoader &vk, VkPhysicalDevice device) {
        FeatureProbe probe;
        VkPhysicalDeviceProperties props;
        vk.GetPhysicalDeviceProperties(device, &props);
        probe.apiVersion = props.apiVersion;
        std::memcpy(probe.name, props.deviceName, sizeof(probe.name));
        if (props.apiVersion < VK_API_VERSION_1_3) {
            return probe;
        }

        VkPhysicalDeviceVulkan13Features f13 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
        VkPhysicalDeviceVulkan12Features f12 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
        f12.pNext = &f13;
        VkPhysicalDeviceFeatures2 f2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        f2.pNext = &f12;
        vk.GetPhysicalDeviceFeatures2(device, &f2);

        probe.ok = f2.features.shaderInt16 && f12.storageBuffer8BitAccess && f12.timelineSemaphore &&
                   f13.subgroupSizeControl && f13.computeFullSubgroups && f13.synchronization2;
        probe.float16 = f12.shaderFloat16;
        return probe;
    }

    bool apiVersionSupported() {
        uint32_t major = 0, minor = 0, patch = 0;
        pyrowave_get_api_version(&major, &minor, &patch);
        if (major != PYROWAVE_API_VERSION_MAJOR || minor != PYROWAVE_API_VERSION_MINOR) {
            LOGE("PyroWave runtime %u.%u.%u does not match %u.%u", major, minor, patch,
                 PYROWAVE_API_VERSION_MAJOR, PYROWAVE_API_VERSION_MINOR);
            return false;
        }
        return true;
    }

    uint64_t nowUs() {
        timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return uint64_t(ts.tv_sec) * 1000000u + uint64_t(ts.tv_nsec) / 1000u;
    }

    const char *presentModeName(VkPresentModeKHR mode) {
        switch (mode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR: return "IMMEDIATE";
            case VK_PRESENT_MODE_MAILBOX_KHR: return "MAILBOX";
            case VK_PRESENT_MODE_FIFO_KHR: return "FIFO";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR: return "FIFO_RELAXED";
            default: return "other";
        }
    }

    // Android performance hints (ADPF). Telling the platform the per-frame deadline and
    // the actual CPU and GPU time keeps the decode thread and GPU clocked for the
    // stream instead of letting the governors down-clock between frames.
    class PerformanceHint {
    public:
        ~PerformanceHint() {
            if (session != nullptr && closeSession != nullptr) {
                closeSession(session);
            }
        }

        void start(int64_t targetNs) {
            void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
            if (lib == nullptr) {
                return;
            }
            auto getManager = reinterpret_cast<void *(*)()>(dlsym(lib, "APerformanceHint_getManager"));
            auto createSession = reinterpret_cast<void *(*)(void *, const int32_t *, size_t, int64_t)>(
                dlsym(lib, "APerformanceHint_createSession"));
            closeSession = reinterpret_cast<void (*)(void *)>(dlsym(lib, "APerformanceHint_closeSession"));
            report = reinterpret_cast<int (*)(void *, int64_t)>(dlsym(lib, "APerformanceHint_reportActualWorkDuration"));
            report2 = reinterpret_cast<int (*)(void *, void *)>(dlsym(lib, "APerformanceHint_reportActualWorkDuration2"));
            createWork = reinterpret_cast<void *(*)()>(dlsym(lib, "AWorkDuration_create"));
            setStart = reinterpret_cast<void (*)(void *, int64_t)>(dlsym(lib, "AWorkDuration_setWorkPeriodStartTimestampNanos"));
            setTotal = reinterpret_cast<void (*)(void *, int64_t)>(dlsym(lib, "AWorkDuration_setActualTotalDurationNanos"));
            setCpu = reinterpret_cast<void (*)(void *, int64_t)>(dlsym(lib, "AWorkDuration_setActualCpuDurationNanos"));
            setGpu = reinterpret_cast<void (*)(void *, int64_t)>(dlsym(lib, "AWorkDuration_setActualGpuDurationNanos"));
            if (getManager == nullptr || createSession == nullptr || closeSession == nullptr || report == nullptr) {
                LOGI("Performance hints unavailable on this Android version");
                return;
            }
            void *manager = getManager();
            const int32_t tid = gettid();
            session = manager != nullptr ? createSession(manager, &tid, 1, targetNs) : nullptr;
            gpuHints = session != nullptr && report2 != nullptr && createWork != nullptr && setStart != nullptr &&
                       setTotal != nullptr && setCpu != nullptr && setGpu != nullptr;
            if (gpuHints) {
                work = createWork();
                gpuHints = work != nullptr;
            }
            LOGI("Performance hint session %s (target %.2f ms, GPU hints %s)", session != nullptr ? "on" : "unavailable",
                 targetNs / 1e6, gpuHints ? "on" : "off");
        }

        void reportFrame(int64_t startNs, int64_t cpuNs, int64_t gpuNs) {
            if (session == nullptr || cpuNs <= 0) {
                return;
            }
            if (gpuHints && gpuNs > 0) {
                setStart(work, startNs);
                setCpu(work, cpuNs);
                setGpu(work, gpuNs);
                setTotal(work, cpuNs + gpuNs);
                report2(session, work);
            } else {
                report(session, cpuNs + gpuNs);
            }
        }

    private:
        void *session = nullptr;
        void *work = nullptr;  // Released with the process; AWorkDuration objects are tiny.
        bool gpuHints = false;
        void (*closeSession)(void *) = nullptr;
        int (*report)(void *, int64_t) = nullptr;
        int (*report2)(void *, void *) = nullptr;
        void *(*createWork)() = nullptr;
        void (*setStart)(void *, int64_t) = nullptr;
        void (*setTotal)(void *, int64_t) = nullptr;
        void (*setCpu)(void *, int64_t) = nullptr;
        void (*setGpu)(void *, int64_t) = nullptr;
    };

    uint32_t readBe32(const uint8_t *p) {
        return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | uint32_t(p[3]);
    }

    struct Plane {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        uint32_t width = 0;
        uint32_t height = 0;
    };

    class Renderer {
    public:
        ~Renderer() {
            destroy();
        }

        bool create(ANativeWindow *nativeWindow, int streamWidth, int streamHeight, int frameRate) {
            window = nativeWindow;
            frameRateHz = frameRate > 0 ? frameRate : 60;
            width = uint32_t(streamWidth);
            height = uint32_t(streamHeight);
            return apiVersionSupported() && createInstanceAndSurface() && createDevice() &&
                   createDecoder() && createPlanes() && createSwapchain() && createPipeline() &&
                   createFrameResources();
        }

        int submit(const uint8_t *data, size_t length) {
            if (!pushFrame(data, length)) {
                pyrowave_decoder_clear(decoder);
                return SUBMIT_SKIPPED;
            }
            // Intra-only: a frame missing packets still decodes, with lost detail.
            if (!pyrowave_decoder_decode_is_ready(decoder, true)) {
                return SUBMIT_SKIPPED;
            }
            return present() ? SUBMIT_OK : SUBMIT_ERROR;
        }

    private:
        bool check(VkResult result, const char *what) {
            if (result != VK_SUCCESS) {
                LOGE("%s failed: %d", what, result);
                return false;
            }
            return true;
        }

        bool createInstanceAndSurface() {
            if (!vk.loadGlobal()) {
                return false;
            }
            uint32_t loaderVersion = 0;
            vk.EnumerateInstanceVersion(&loaderVersion);
            if (loaderVersion < VK_API_VERSION_1_3) {
                LOGE("Vulkan loader %u.%u is older than 1.3", VK_API_VERSION_MAJOR(loaderVersion), VK_API_VERSION_MINOR(loaderVersion));
                return false;
            }

            // These create infos stay alive for the device's lifetime: PyroWave reads them.
            appInfo = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
            appInfo.pApplicationName = "Moonlight";
            appInfo.apiVersion = VK_API_VERSION_1_3;
            instanceInfo = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
            instanceInfo.pApplicationInfo = &appInfo;
            instanceInfo.enabledExtensionCount = uint32_t(std::size(INSTANCE_EXTENSIONS));
            instanceInfo.ppEnabledExtensionNames = INSTANCE_EXTENSIONS;
            if (!check(vk.CreateInstance(&instanceInfo, nullptr, &instance), "vkCreateInstance")) {
                return false;
            }
            if (!vk.loadInstance(instance)) {
                LOGE("Vulkan instance is missing required functions");
                // destroy() must not call through missing pointers.
                auto destroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(vk.GetInstanceProcAddr(instance, "vkDestroyInstance"));
                if (destroyInstance != nullptr) {
                    destroyInstance(instance, nullptr);
                }
                instance = VK_NULL_HANDLE;
                return false;
            }

            VkAndroidSurfaceCreateInfoKHR surfaceInfo = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
            surfaceInfo.window = window;
            return check(vk.CreateAndroidSurfaceKHR(instance, &surfaceInfo, nullptr, &surface), "vkCreateAndroidSurfaceKHR");
        }

        bool createDevice() {
            uint32_t count = 0;
            vk.EnumeratePhysicalDevices(instance, &count, nullptr);
            std::vector<VkPhysicalDevice> devices(count);
            vk.EnumeratePhysicalDevices(instance, &count, devices.data());

            FeatureProbe chosenProbe;
            for (auto device : devices) {
                auto probe = probeFeatures(vk, device);
                if (!probe.ok) {
                    LOGI("Skipping %s: missing the PyroWave feature set", probe.name);
                    continue;
                }
                uint32_t familyCount = 0;
                vk.GetPhysicalDeviceQueueFamilyProperties(device, &familyCount, nullptr);
                std::vector<VkQueueFamilyProperties> families(familyCount);
                vk.GetPhysicalDeviceQueueFamilyProperties(device, &familyCount, families.data());
                for (uint32_t i = 0; i < familyCount; ++i) {
                    VkBool32 presentable = VK_FALSE;
                    vk.GetPhysicalDeviceSurfaceSupportKHR(device, i, surface, &presentable);
                    if ((families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && presentable) {
                        physicalDevice = device;
                        queueFamily = i;
                        timestampsSupported = families[i].timestampValidBits > 0;
                        chosenProbe = probe;
                        break;
                    }
                }
                if (physicalDevice != VK_NULL_HANDLE) {
                    break;
                }
            }
            if (physicalDevice == VK_NULL_HANDLE) {
                LOGE("No Vulkan 1.3 device can decode PyroWave and present to this surface");
                return false;
            }

            queuePriority = 1.0f;
            queueInfo = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
            queueInfo.queueFamilyIndex = queueFamily;
            queueInfo.queueCount = 1;
            queueInfo.pQueuePriorities = &queuePriority;

            features13 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
            features13.synchronization2 = VK_TRUE;
            features13.subgroupSizeControl = VK_TRUE;
            features13.computeFullSubgroups = VK_TRUE;
            features12 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
            features12.pNext = &features13;
            features12.timelineSemaphore = VK_TRUE;
            features12.storageBuffer8BitAccess = VK_TRUE;
            features12.shaderFloat16 = chosenProbe.float16 ? VK_TRUE : VK_FALSE;
            features2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
            features2.pNext = &features12;
            features2.features.shaderInt16 = VK_TRUE;

            deviceInfo = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
            deviceInfo.pNext = &features2;
            deviceInfo.queueCreateInfoCount = 1;
            deviceInfo.pQueueCreateInfos = &queueInfo;
            deviceInfo.enabledExtensionCount = uint32_t(std::size(DEVICE_EXTENSIONS));
            deviceInfo.ppEnabledExtensionNames = DEVICE_EXTENSIONS;
            if (!check(vk.CreateDevice(physicalDevice, &deviceInfo, nullptr, &device), "vkCreateDevice")) {
                return false;
            }
            if (!vk.loadDevice(device)) {
                LOGE("Vulkan device is missing required functions");
                auto destroyDevice = reinterpret_cast<PFN_vkDestroyDevice>(vk.GetDeviceProcAddr(device, "vkDestroyDevice"));
                if (destroyDevice != nullptr) {
                    destroyDevice(device, nullptr);
                }
                device = VK_NULL_HANDLE;
                return false;
            }
            vk.GetDeviceQueue(device, queueFamily, 0, &queue);
            VkPhysicalDeviceProperties props;
            vk.GetPhysicalDeviceProperties(physicalDevice, &props);
            timestampPeriodNs = props.limits.timestampPeriod;

            LOGI("%s on Vulkan %u.%u (float16: %d)", chosenProbe.name, VK_API_VERSION_MAJOR(chosenProbe.apiVersion),
                 VK_API_VERSION_MINOR(chosenProbe.apiVersion), chosenProbe.float16);
            return true;
        }

        bool createDecoder() {
            pyrowave_device_create_queue_info pyroQueue = {queue, queueFamily, 0};
            pyrowave_device_create_info info = {};
            info.GetInstanceProcAddr = vk.GetInstanceProcAddr;
            info.instance = instance;
            info.physical_device = physicalDevice;
            info.device = device;
            info.instance_create_info = &instanceInfo;
            info.device_create_info = &deviceInfo;
            info.queue_info = &pyroQueue;
            info.queue_info_count = 1;
            // All submissions happen on the decode thread, so no queue lock is needed.
            auto result = pyrowave_create_device(&info, &pyroDevice);
            if (result != PYROWAVE_SUCCESS) {
                LOGE("pyrowave_create_device failed: %d", result);
                return false;
            }
            // Mobile GPUs (Adreno, Mali) decode much faster with PyroWave's fragment path,
            // which runs the inverse DWT in render passes instead of compute shaders.
            fragmentPath = pyrowave_decoder_device_prefers_fragment_path(pyroDevice);
            // Command buffers are recorded for the graphics queue, which also does compute.
            pyrowave_device_set_queue_type(pyroDevice, fragmentPath ? VK_QUEUE_GRAPHICS_BIT : VK_QUEUE_COMPUTE_BIT);

            pyrowave_decoder_create_info decoderInfo = {};
            decoderInfo.device = pyroDevice;
            decoderInfo.width = int(width);
            decoderInfo.height = int(height);
            decoderInfo.chroma = PYROWAVE_CHROMA_SUBSAMPLING_420;
            // The decoder writes the planes (as storage images or render targets) and the
            // CSC pass samples them directly.
            decoderInfo.fragment_path = fragmentPath;
            LOGI("Using the PyroWave %s decode path", fragmentPath ? "fragment" : "compute");
            result = pyrowave_decoder_create(&decoderInfo, &decoder);
            if (result != PYROWAVE_SUCCESS) {
                LOGE("pyrowave_decoder_create failed: %d", result);
                return false;
            }
            return true;
        }

        bool createPlane(Plane &plane, uint32_t planeWidth, uint32_t planeHeight) {
            plane.width = planeWidth;
            plane.height = planeHeight;

            VkImageCreateInfo imageInfo = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            imageInfo.imageType = VK_IMAGE_TYPE_2D;
            imageInfo.format = VK_FORMAT_R8_UNORM;
            imageInfo.extent = {planeWidth, planeHeight, 1};
            imageInfo.mipLevels = 1;
            imageInfo.arrayLayers = 1;
            imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
            imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
            imageInfo.usage = (fragmentPath ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : VK_IMAGE_USAGE_STORAGE_BIT) |
                              VK_IMAGE_USAGE_SAMPLED_BIT;
            imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            if (!check(vk.CreateImage(device, &imageInfo, nullptr, &plane.image), "vkCreateImage")) {
                return false;
            }

            VkMemoryRequirements requirements;
            vk.GetImageMemoryRequirements(device, plane.image, &requirements);
            VkPhysicalDeviceMemoryProperties memoryProps;
            vk.GetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProps);
            uint32_t typeIndex = UINT32_MAX;
            for (uint32_t i = 0; i < memoryProps.memoryTypeCount; ++i) {
                if ((requirements.memoryTypeBits & (1u << i)) &&
                    (memoryProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
                    typeIndex = i;
                    break;
                }
            }
            if (typeIndex == UINT32_MAX) {
                LOGE("No device-local memory for a plane image");
                return false;
            }
            VkMemoryAllocateInfo allocInfo = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            allocInfo.allocationSize = requirements.size;
            allocInfo.memoryTypeIndex = typeIndex;
            if (!check(vk.AllocateMemory(device, &allocInfo, nullptr, &plane.memory), "vkAllocateMemory") ||
                !check(vk.BindImageMemory(device, plane.image, plane.memory, 0), "vkBindImageMemory")) {
                return false;
            }

            VkImageViewCreateInfo viewInfo = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            viewInfo.image = plane.image;
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = VK_FORMAT_R8_UNORM;
            viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            return check(vk.CreateImageView(device, &viewInfo, nullptr, &plane.view), "vkCreateImageView");
        }

        bool createPlanes() {
            return createPlane(planes[0], width, height) &&
                   createPlane(planes[1], width / 2, height / 2) &&
                   createPlane(planes[2], width / 2, height / 2);
        }

        bool createSwapchain() {
            VkSurfaceCapabilitiesKHR caps;
            if (!check(vk.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, &caps), "surface capabilities")) {
                return false;
            }
            if (caps.currentExtent.width == 0 || caps.currentExtent.height == 0) {
                LOGW("Surface has no extent yet");
                return false;
            }

            if (swapchainFormat == VK_FORMAT_UNDEFINED) {
                uint32_t formatCount = 0;
                vk.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, &formatCount, nullptr);
                std::vector<VkSurfaceFormatKHR> formats(formatCount);
                vk.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, &formatCount, formats.data());
                if (formats.empty()) {
                    LOGE("Surface reports no formats");
                    return false;
                }
                // UNORM, not sRGB: the shader already outputs gamma-encoded BT.709 values.
                auto chosen = formats[0];
                for (const auto &format : formats) {
                    if (format.format == VK_FORMAT_R8G8B8A8_UNORM || format.format == VK_FORMAT_B8G8R8A8_UNORM) {
                        chosen = format;
                        break;
                    }
                }
                swapchainFormat = chosen.format;
                swapchainColorSpace = chosen.colorSpace;

                uint32_t modeCount = 0;
                vk.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, &modeCount, nullptr);
                std::vector<VkPresentModeKHR> modes(modeCount);
                vk.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, &modeCount, modes.data());
                // MAILBOX shows the newest frame without tearing where available.
                presentMode = std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_MAILBOX_KHR) != modes.end() ?
                    VK_PRESENT_MODE_MAILBOX_KHR : VK_PRESENT_MODE_FIFO_KHR;
            }

            uint32_t imageCount = caps.minImageCount + 1;
            if (caps.maxImageCount > 0) {
                imageCount = std::min(imageCount, caps.maxImageCount);
            }

            VkSwapchainCreateInfoKHR info = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
            info.surface = surface;
            info.minImageCount = imageCount;
            info.imageFormat = swapchainFormat;
            info.imageColorSpace = swapchainColorSpace;
            info.imageExtent = caps.currentExtent;
            info.imageArrayLayers = 1;
            info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
            // The shader draws unrotated; let the compositor rotate like it does for MediaCodec.
            info.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) ?
                VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
            info.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) ?
                VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
            info.presentMode = presentMode;
            info.clipped = VK_TRUE;
            info.oldSwapchain = swapchain;

            VkSwapchainKHR newSwapchain = VK_NULL_HANDLE;
            if (!check(vk.CreateSwapchainKHR(device, &info, nullptr, &newSwapchain), "vkCreateSwapchainKHR")) {
                return false;
            }
            destroySwapchainResources();
            if (swapchain != VK_NULL_HANDLE) {
                vk.DestroySwapchainKHR(device, swapchain, nullptr);
            }
            swapchain = newSwapchain;
            swapchainExtent = caps.currentExtent;

            uint32_t count = 0;
            vk.GetSwapchainImagesKHR(device, swapchain, &count, nullptr);
            swapchainImages.resize(count);
            vk.GetSwapchainImagesKHR(device, swapchain, &count, swapchainImages.data());
            return renderPass == VK_NULL_HANDLE || createSwapchainResources();
        }

        bool createSwapchainResources() {
            for (auto image : swapchainImages) {
                VkImageViewCreateInfo viewInfo = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
                viewInfo.image = image;
                viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
                viewInfo.format = swapchainFormat;
                viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                VkImageView view;
                if (!check(vk.CreateImageView(device, &viewInfo, nullptr, &view), "swapchain view")) {
                    return false;
                }
                swapchainViews.push_back(view);

                VkFramebufferCreateInfo fbInfo = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
                fbInfo.renderPass = renderPass;
                fbInfo.attachmentCount = 1;
                fbInfo.pAttachments = &view;
                fbInfo.width = swapchainExtent.width;
                fbInfo.height = swapchainExtent.height;
                fbInfo.layers = 1;
                VkFramebuffer framebuffer;
                if (!check(vk.CreateFramebuffer(device, &fbInfo, nullptr, &framebuffer), "vkCreateFramebuffer")) {
                    return false;
                }
                framebuffers.push_back(framebuffer);

                // Per image, so a present never waits on a semaphore a later submit re-signals.
                VkSemaphoreCreateInfo semInfo = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
                VkSemaphore semaphore;
                if (!check(vk.CreateSemaphore(device, &semInfo, nullptr, &semaphore), "vkCreateSemaphore")) {
                    return false;
                }
                renderDone.push_back(semaphore);
            }
            return true;
        }

        void destroySwapchainResources() {
            for (auto framebuffer : framebuffers) {
                vk.DestroyFramebuffer(device, framebuffer, nullptr);
            }
            for (auto view : swapchainViews) {
                vk.DestroyImageView(device, view, nullptr);
            }
            for (auto semaphore : renderDone) {
                vk.DestroySemaphore(device, semaphore, nullptr);
            }
            framebuffers.clear();
            swapchainViews.clear();
            renderDone.clear();
        }

        bool recreateSwapchain() {
            vk.DeviceWaitIdle(device);
            return createSwapchain();
        }

        VkShaderModule createShader(const uint32_t *code, size_t size) {
            VkShaderModuleCreateInfo info = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
            info.codeSize = size;
            info.pCode = code;
            VkShaderModule module = VK_NULL_HANDLE;
            check(vk.CreateShaderModule(device, &info, nullptr, &module), "vkCreateShaderModule");
            return module;
        }

        bool createPipeline() {
            VkAttachmentDescription attachment = {};
            attachment.format = swapchainFormat;
            attachment.samples = VK_SAMPLE_COUNT_1_BIT;
            attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;  // Black letterbox bars.
            attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
            attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            VkAttachmentReference colorRef = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
            VkSubpassDescription subpass = {};
            subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
            subpass.colorAttachmentCount = 1;
            subpass.pColorAttachments = &colorRef;
            // Order the layout transition after the acquire semaphore wait.
            VkSubpassDependency dependency = {};
            dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
            dependency.dstSubpass = 0;
            dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            VkRenderPassCreateInfo rpInfo = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
            rpInfo.attachmentCount = 1;
            rpInfo.pAttachments = &attachment;
            rpInfo.subpassCount = 1;
            rpInfo.pSubpasses = &subpass;
            rpInfo.dependencyCount = 1;
            rpInfo.pDependencies = &dependency;
            if (!check(vk.CreateRenderPass(device, &rpInfo, nullptr, &renderPass), "vkCreateRenderPass")) {
                return false;
            }

            VkSamplerCreateInfo samplerInfo = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
            samplerInfo.magFilter = VK_FILTER_LINEAR;
            samplerInfo.minFilter = VK_FILTER_LINEAR;
            samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
            samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            samplerInfo.maxLod = 0.0f;
            if (!check(vk.CreateSampler(device, &samplerInfo, nullptr, &sampler), "vkCreateSampler")) {
                return false;
            }

            VkDescriptorSetLayoutBinding bindings[3] = {};
            for (uint32_t i = 0; i < 3; ++i) {
                bindings[i].binding = i;
                bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                bindings[i].descriptorCount = 1;
                bindings[i].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
            }
            VkDescriptorSetLayoutCreateInfo layoutInfo = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            layoutInfo.bindingCount = 3;
            layoutInfo.pBindings = bindings;
            if (!check(vk.CreateDescriptorSetLayout(device, &layoutInfo, nullptr, &setLayout), "vkCreateDescriptorSetLayout")) {
                return false;
            }
            VkPipelineLayoutCreateInfo plInfo = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            plInfo.setLayoutCount = 1;
            plInfo.pSetLayouts = &setLayout;
            if (!check(vk.CreatePipelineLayout(device, &plInfo, nullptr, &pipelineLayout), "vkCreatePipelineLayout")) {
                return false;
            }

            VkDescriptorPoolSize poolSize = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 3};
            VkDescriptorPoolCreateInfo poolInfo = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = 1;
            poolInfo.pPoolSizes = &poolSize;
            if (!check(vk.CreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool), "vkCreateDescriptorPool")) {
                return false;
            }
            VkDescriptorSetAllocateInfo setInfo = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            setInfo.descriptorPool = descriptorPool;
            setInfo.descriptorSetCount = 1;
            setInfo.pSetLayouts = &setLayout;
            if (!check(vk.AllocateDescriptorSets(device, &setInfo, &descriptorSet), "vkAllocateDescriptorSets")) {
                return false;
            }
            // The plane images never change, so the descriptors are written once.
            VkDescriptorImageInfo imageInfos[3];
            VkWriteDescriptorSet writes[3] = {};
            for (uint32_t i = 0; i < 3; ++i) {
                imageInfos[i] = {sampler, planes[i].view, VK_IMAGE_LAYOUT_GENERAL};
                writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                writes[i].dstSet = descriptorSet;
                writes[i].dstBinding = i;
                writes[i].descriptorCount = 1;
                writes[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                writes[i].pImageInfo = &imageInfos[i];
            }
            vk.UpdateDescriptorSets(device, 3, writes, 0, nullptr);

            VkShaderModule vert = createShader(fullscreen_vert_spv, sizeof(fullscreen_vert_spv));
            VkShaderModule frag = createShader(planar_csc_frag_spv, sizeof(planar_csc_frag_spv));
            if (vert == VK_NULL_HANDLE || frag == VK_NULL_HANDLE) {
                if (vert != VK_NULL_HANDLE) vk.DestroyShaderModule(device, vert, nullptr);
                if (frag != VK_NULL_HANDLE) vk.DestroyShaderModule(device, frag, nullptr);
                return false;
            }
            VkPipelineShaderStageCreateInfo stages[2] = {};
            stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
            stages[0].module = vert;
            stages[0].pName = "main";
            stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
            stages[1].module = frag;
            stages[1].pName = "main";

            VkPipelineVertexInputStateCreateInfo vertexInput = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
            inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            VkPipelineViewportStateCreateInfo viewportState = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
            viewportState.viewportCount = 1;
            viewportState.scissorCount = 1;
            VkPipelineRasterizationStateCreateInfo raster = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
            raster.polygonMode = VK_POLYGON_MODE_FILL;
            raster.cullMode = VK_CULL_MODE_NONE;
            raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
            raster.lineWidth = 1.0f;
            VkPipelineMultisampleStateCreateInfo multisample = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
            multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
            VkPipelineColorBlendAttachmentState blendAttachment = {};
            blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                             VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
            VkPipelineColorBlendStateCreateInfo blend = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
            blend.attachmentCount = 1;
            blend.pAttachments = &blendAttachment;
            const VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
            VkPipelineDynamicStateCreateInfo dynamic = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
            dynamic.dynamicStateCount = 2;
            dynamic.pDynamicStates = dynamicStates;

            VkGraphicsPipelineCreateInfo pipelineInfo = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
            pipelineInfo.stageCount = 2;
            pipelineInfo.pStages = stages;
            pipelineInfo.pVertexInputState = &vertexInput;
            pipelineInfo.pInputAssemblyState = &inputAssembly;
            pipelineInfo.pViewportState = &viewportState;
            pipelineInfo.pRasterizationState = &raster;
            pipelineInfo.pMultisampleState = &multisample;
            pipelineInfo.pColorBlendState = &blend;
            pipelineInfo.pDynamicState = &dynamic;
            pipelineInfo.layout = pipelineLayout;
            pipelineInfo.renderPass = renderPass;
            const bool ok = check(vk.CreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline),
                                  "vkCreateGraphicsPipelines");
            vk.DestroyShaderModule(device, vert, nullptr);
            vk.DestroyShaderModule(device, frag, nullptr);
            return ok && createSwapchainResources();
        }

        bool createFrameResources() {
            VkCommandPoolCreateInfo poolInfo = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
            poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
            poolInfo.queueFamilyIndex = queueFamily;
            if (!check(vk.CreateCommandPool(device, &poolInfo, nullptr, &commandPool), "vkCreateCommandPool")) {
                return false;
            }
            VkCommandBufferAllocateInfo allocInfo = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            allocInfo.commandPool = commandPool;
            allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            allocInfo.commandBufferCount = 2;
            VkCommandBuffer buffers[2];
            if (!check(vk.AllocateCommandBuffers(device, &allocInfo, buffers), "vkAllocateCommandBuffers")) {
                return false;
            }
            decodeCommandBuffer = buffers[0];
            commandBuffer = buffers[1];
            if (timestampsSupported) {
                VkQueryPoolCreateInfo queryInfo = {VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
                queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
                queryInfo.queryCount = QUERY_COUNT;
                if (vk.CreateQueryPool(device, &queryInfo, nullptr, &queryPool) != VK_SUCCESS) {
                    queryPool = VK_NULL_HANDLE;
                }
            }
            LOGI("Present mode %s, GPU timestamps %s", presentModeName(presentMode),
                 queryPool != VK_NULL_HANDLE ? "on" : "off");
            VkFenceCreateInfo fenceInfo = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
            VkSemaphoreCreateInfo semInfo = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            return check(vk.CreateFence(device, &fenceInfo, nullptr, &frameFence), "vkCreateFence") &&
                   check(vk.CreateSemaphore(device, &semInfo, nullptr, &acquireSemaphore), "vkCreateSemaphore");
        }

        bool pushFrame(const uint8_t *data, size_t length) {
            if (length < FRAME_HEADER_SIZE || std::memcmp(data, "PYRW", 4) != 0 ||
                data[4] != FRAME_VERSION || data[7] != 0) {
                LOGW("Dropping frame without a valid PYRW header");
                return false;
            }
            const size_t packetCount = (size_t(data[5]) << 8) | data[6];
            size_t offset = FRAME_HEADER_SIZE;
            for (size_t i = 0; i < packetCount; ++i) {
                if (length - offset < 4) {
                    return false;
                }
                const uint32_t packetSize = readBe32(data + offset);
                offset += 4;
                if (packetSize == 0 || packetSize > length - offset ||
                    pyrowave_decoder_push_packet(decoder, data + offset, packetSize) != PYROWAVE_SUCCESS) {
                    return false;
                }
                offset += packetSize;
            }
            return packetCount > 0 && offset == length;
        }

        // Where the decoder writes the planes: compute storage writes, or colour
        // attachment writes for the fragment path.
        VkPipelineStageFlags decodeWriteStage() const {
            return fragmentPath ? VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        }

        VkAccessFlags decodeWriteAccess() const {
            return fragmentPath ? VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT : VK_ACCESS_SHADER_WRITE_BIT;
        }

        void planeBarrier(VkCommandBuffer cmd, VkPipelineStageFlags srcStage, VkAccessFlags srcAccess, VkPipelineStageFlags dstStage,
                          VkAccessFlags dstAccess, VkImageLayout oldLayout) {
            VkImageMemoryBarrier barriers[3] = {};
            for (int i = 0; i < 3; ++i) {
                barriers[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                barriers[i].srcAccessMask = srcAccess;
                barriers[i].dstAccessMask = dstAccess;
                barriers[i].oldLayout = oldLayout;
                barriers[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
                barriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barriers[i].image = planes[i].image;
                barriers[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            }
            vk.CmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 3, barriers);
        }

        bool present() {
            const uint64_t frameStart = nowUs();
            if (!hintStarted) {
                // Created on the decode thread, which is the thread being hinted.
                hintStarted = true;
                hint.start(int64_t(1'000'000'000LL / frameRateHz));
            }

            // One frame in flight: after this wait the previous frame's sampling of the
            // planes is finished, so decoding may overwrite them.
            if (!check(vk.WaitForFences(device, 1, &frameFence, VK_TRUE, FENCE_TIMEOUT_NS), "frame fence")) {
                return false;
            }
            const uint64_t afterFence = nowUs();
            readTimestamps();

            // Decode first, without waiting for the display, so the GPU starts on the
            // frame immediately. The draw below is ordered after it on the same queue.
            vk.ResetCommandBuffer(decodeCommandBuffer, 0);
            VkCommandBufferBeginInfo beginInfo = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
            beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            vk.BeginCommandBuffer(decodeCommandBuffer, &beginInfo);
            if (queryPool != VK_NULL_HANDLE) {
                vk.CmdResetQueryPool(decodeCommandBuffer, queryPool, 0, QUERY_COUNT);
                vk.CmdWriteTimestamp(decodeCommandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0);
            }
            planeBarrier(decodeCommandBuffer, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, decodeWriteStage(),
                         decodeWriteAccess(), planesInitialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED);

            pyrowave_gpu_buffers buffers = {};
            for (int i = 0; i < 3; ++i) {
                auto &view = buffers.planes[i];
                view.image = planes[i].image;
                view.width = planes[i].width;
                view.height = planes[i].height;
                view.image_format = VK_FORMAT_R8_UNORM;
                view.view_format = VK_FORMAT_R8_UNORM;
                view.aspect = VK_IMAGE_ASPECT_COLOR_BIT;
                view.swizzle = VK_COMPONENT_SWIZZLE_IDENTITY;
                view.layout = VK_IMAGE_LAYOUT_GENERAL;
            }
            pyrowave_device_set_command_buffer(pyroDevice, decodeCommandBuffer);
            const auto decoded = pyrowave_decoder_decode_gpu_buffer(decoder, nullptr, nullptr, &buffers);
            pyrowave_device_set_command_buffer(pyroDevice, VK_NULL_HANDLE);
            if (decoded != PYROWAVE_SUCCESS) {
                LOGE("pyrowave_decoder_decode_gpu_buffer failed: %d", decoded);
                vk.EndCommandBuffer(decodeCommandBuffer);
                return false;
            }
            planesInitialized = true;
            if (queryPool != VK_NULL_HANDLE) {
                vk.CmdWriteTimestamp(decodeCommandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 1);
            }
            if (!check(vk.EndCommandBuffer(decodeCommandBuffer), "vkEndCommandBuffer(decode)")) {
                return false;
            }
            VkSubmitInfo decodeSubmit = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
            decodeSubmit.commandBufferCount = 1;
            decodeSubmit.pCommandBuffers = &decodeCommandBuffer;
            if (!check(vk.QueueSubmit(queue, 1, &decodeSubmit, VK_NULL_HANDLE), "vkQueueSubmit(decode)")) {
                return false;
            }

            uint32_t imageIndex = 0;
            const uint64_t beforeAcquire = nowUs();
            auto acquired = vk.AcquireNextImageKHR(device, swapchain, ACQUIRE_TIMEOUT_NS, acquireSemaphore,
                                                   VK_NULL_HANDLE, &imageIndex);
            const uint64_t afterAcquire = nowUs();
            if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
                return recreateSwapchain();
            }
            if (acquired == VK_TIMEOUT || acquired == VK_NOT_READY) {
                return true;  // Skip presenting this frame; the next one replaces it anyway.
            }
            if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
                return check(acquired, "vkAcquireNextImageKHR");
            }

            vk.ResetCommandBuffer(commandBuffer, 0);
            vk.BeginCommandBuffer(commandBuffer, &beginInfo);
            // Barriers order against all earlier work on the queue, which covers the
            // decode submitted above.
            planeBarrier(commandBuffer, decodeWriteStage(), decodeWriteAccess(),
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_GENERAL);

            VkClearValue clear = {};
            clear.color = {{0.0f, 0.0f, 0.0f, 1.0f}};
            VkRenderPassBeginInfo rpBegin = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
            rpBegin.renderPass = renderPass;
            rpBegin.framebuffer = framebuffers[imageIndex];
            rpBegin.renderArea = {{0, 0}, swapchainExtent};
            rpBegin.clearValueCount = 1;
            rpBegin.pClearValues = &clear;
            vk.CmdBeginRenderPass(commandBuffer, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);

            // Fit the stream into the surface, preserving its aspect ratio.
            const float scale = std::min(float(swapchainExtent.width) / float(width),
                                         float(swapchainExtent.height) / float(height));
            VkViewport viewport = {};
            viewport.width = float(width) * scale;
            viewport.height = float(height) * scale;
            viewport.x = (float(swapchainExtent.width) - viewport.width) / 2.0f;
            viewport.y = (float(swapchainExtent.height) - viewport.height) / 2.0f;
            viewport.maxDepth = 1.0f;
            VkRect2D scissor = {{0, 0}, swapchainExtent};
            vk.CmdSetViewport(commandBuffer, 0, 1, &viewport);
            vk.CmdSetScissor(commandBuffer, 0, 1, &scissor);
            vk.CmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            vk.CmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, 1,
                                     &descriptorSet, 0, nullptr);
            vk.CmdDraw(commandBuffer, 3, 1, 0, 0);
            vk.CmdEndRenderPass(commandBuffer);
            if (queryPool != VK_NULL_HANDLE) {
                vk.CmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 2);
            }
            if (!check(vk.EndCommandBuffer(commandBuffer), "vkEndCommandBuffer")) {
                return false;
            }

            const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            VkSubmitInfo submitInfo = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submitInfo.waitSemaphoreCount = 1;
            submitInfo.pWaitSemaphores = &acquireSemaphore;
            submitInfo.pWaitDstStageMask = &waitStage;
            submitInfo.commandBufferCount = 1;
            submitInfo.pCommandBuffers = &commandBuffer;
            submitInfo.signalSemaphoreCount = 1;
            submitInfo.pSignalSemaphores = &renderDone[imageIndex];
            vk.ResetFences(device, 1, &frameFence);
            if (!check(vk.QueueSubmit(queue, 1, &submitInfo, frameFence), "vkQueueSubmit")) {
                return false;
            }
            queriesPending = queryPool != VK_NULL_HANDLE;

            VkPresentInfoKHR presentInfo = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
            presentInfo.waitSemaphoreCount = 1;
            presentInfo.pWaitSemaphores = &renderDone[imageIndex];
            presentInfo.swapchainCount = 1;
            presentInfo.pSwapchains = &swapchain;
            presentInfo.pImageIndices = &imageIndex;
            const auto presented = vk.QueuePresentKHR(queue, &presentInfo);
            const uint64_t frameEnd = nowUs();

            // CPU time spent on this frame, excluding waits on the GPU and the display.
            lastFrameStartUs = frameStart;
            lastFrameCpuUs = (frameEnd - frameStart) - (afterFence - frameStart) - (afterAcquire - beforeAcquire);

            stats.frames++;
            stats.fenceWaitUs += afterFence - frameStart;
            stats.acquireWaitUs += afterAcquire - beforeAcquire;
            stats.presentUs += frameEnd - afterAcquire;
            logStatsIfDue(frameEnd);

            if (presented == VK_ERROR_OUT_OF_DATE_KHR || presented == VK_SUBOPTIMAL_KHR || acquired == VK_SUBOPTIMAL_KHR) {
                return recreateSwapchain();
            }
            return check(presented, "vkQueuePresentKHR");
        }

        void destroy() {
            if (device != VK_NULL_HANDLE) {
                vk.DeviceWaitIdle(device);
            }
            // PyroWave objects before the VkDevice they borrow.
            if (decoder != nullptr) {
                pyrowave_decoder_destroy(decoder);
                decoder = nullptr;
            }
            if (pyroDevice != nullptr) {
                pyrowave_device_destroy(pyroDevice);
                pyroDevice = nullptr;
            }
            if (device != VK_NULL_HANDLE) {
                destroySwapchainResources();
                if (swapchain != VK_NULL_HANDLE) vk.DestroySwapchainKHR(device, swapchain, nullptr);
                if (acquireSemaphore != VK_NULL_HANDLE) vk.DestroySemaphore(device, acquireSemaphore, nullptr);
                if (frameFence != VK_NULL_HANDLE) vk.DestroyFence(device, frameFence, nullptr);
                if (queryPool != VK_NULL_HANDLE) vk.DestroyQueryPool(device, queryPool, nullptr);
                if (commandPool != VK_NULL_HANDLE) vk.DestroyCommandPool(device, commandPool, nullptr);
                if (pipeline != VK_NULL_HANDLE) vk.DestroyPipeline(device, pipeline, nullptr);
                if (descriptorPool != VK_NULL_HANDLE) vk.DestroyDescriptorPool(device, descriptorPool, nullptr);
                if (pipelineLayout != VK_NULL_HANDLE) vk.DestroyPipelineLayout(device, pipelineLayout, nullptr);
                if (setLayout != VK_NULL_HANDLE) vk.DestroyDescriptorSetLayout(device, setLayout, nullptr);
                if (sampler != VK_NULL_HANDLE) vk.DestroySampler(device, sampler, nullptr);
                if (renderPass != VK_NULL_HANDLE) vk.DestroyRenderPass(device, renderPass, nullptr);
                for (auto &plane : planes) {
                    if (plane.view != VK_NULL_HANDLE) vk.DestroyImageView(device, plane.view, nullptr);
                    if (plane.image != VK_NULL_HANDLE) vk.DestroyImage(device, plane.image, nullptr);
                    if (plane.memory != VK_NULL_HANDLE) vk.FreeMemory(device, plane.memory, nullptr);
                }
                vk.DestroyDevice(device, nullptr);
                device = VK_NULL_HANDLE;
            }
            if (instance != VK_NULL_HANDLE) {
                if (surface != VK_NULL_HANDLE) vk.DestroySurfaceKHR(instance, surface, nullptr);
                vk.DestroyInstance(instance, nullptr);
                instance = VK_NULL_HANDLE;
            }
            if (window != nullptr) {
                ANativeWindow_release(window);
                window = nullptr;
            }
        }

        VulkanLoader vk;
        ANativeWindow *window = nullptr;
        uint32_t width = 0;
        uint32_t height = 0;

        // Kept alive for PyroWave, which reads the create infos after device creation.
        VkApplicationInfo appInfo = {};
        VkInstanceCreateInfo instanceInfo = {};
        float queuePriority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo = {};
        VkPhysicalDeviceVulkan13Features features13 = {};
        VkPhysicalDeviceVulkan12Features features12 = {};
        VkPhysicalDeviceFeatures2 features2 = {};
        VkDeviceCreateInfo deviceInfo = {};

        VkInstance instance = VK_NULL_HANDLE;
        VkSurfaceKHR surface = VK_NULL_HANDLE;
        VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
        uint32_t queueFamily = 0;
        VkDevice device = VK_NULL_HANDLE;
        VkQueue queue = VK_NULL_HANDLE;

        pyrowave_device pyroDevice = nullptr;
        pyrowave_decoder decoder = nullptr;
        Plane planes[3];
        bool planesInitialized = false;
        bool fragmentPath = false;

        VkFormat swapchainFormat = VK_FORMAT_UNDEFINED;
        VkColorSpaceKHR swapchainColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
        VkSwapchainKHR swapchain = VK_NULL_HANDLE;
        VkExtent2D swapchainExtent = {};
        std::vector<VkImage> swapchainImages;
        std::vector<VkImageView> swapchainViews;
        std::vector<VkFramebuffer> framebuffers;
        std::vector<VkSemaphore> renderDone;

        VkRenderPass renderPass = VK_NULL_HANDLE;
        VkSampler sampler = VK_NULL_HANDLE;
        VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
        VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
        VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        VkPipeline pipeline = VK_NULL_HANDLE;

        VkCommandPool commandPool = VK_NULL_HANDLE;
        VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
        VkFence frameFence = VK_NULL_HANDLE;
        VkSemaphore acquireSemaphore = VK_NULL_HANDLE;

        // Timing. Queries 0/1 bracket the decode, 1/2 the colour conversion draw.
        static constexpr uint32_t QUERY_COUNT = 3;
        static constexpr uint64_t STATS_INTERVAL_US = 5'000'000;
        VkCommandBuffer decodeCommandBuffer = VK_NULL_HANDLE;
        VkQueryPool queryPool = VK_NULL_HANDLE;
        bool timestampsSupported = false;
        int frameRateHz = 60;
        PerformanceHint hint;
        bool hintStarted = false;
        uint64_t lastFrameStartUs = 0;
        uint64_t lastFrameCpuUs = 0;
        bool queriesPending = false;
        float timestampPeriodNs = 1.0f;

    public:
        // GPU decode time of the most recently completed frame, or 0 when unknown.
        uint32_t lastGpuDecodeUs = 0;

    private:
        struct {
            uint64_t startUs = 0;
            uint32_t frames = 0;
            uint64_t gpuDecodeUs = 0;
            uint64_t gpuDrawUs = 0;
            uint32_t gpuSamples = 0;
            uint64_t fenceWaitUs = 0;
            uint64_t acquireWaitUs = 0;
            uint64_t presentUs = 0;
        } stats;

        void readTimestamps() {
            if (!queriesPending) {
                return;
            }
            queriesPending = false;
            uint64_t ticks[QUERY_COUNT] = {};
            if (vk.GetQueryPoolResults(device, queryPool, 0, QUERY_COUNT, sizeof(ticks), ticks, sizeof(uint64_t),
                                       VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) {
                return;
            }
            const auto toUs = [this](uint64_t delta) { return uint64_t(double(delta) * timestampPeriodNs / 1000.0); };
            lastGpuDecodeUs = uint32_t(toUs(ticks[1] - ticks[0]));
            hint.reportFrame(int64_t(lastFrameStartUs) * 1000, int64_t(lastFrameCpuUs) * 1000,
                             int64_t(toUs(ticks[2] - ticks[0])) * 1000);
            stats.gpuDecodeUs += lastGpuDecodeUs;
            stats.gpuDrawUs += toUs(ticks[2] - ticks[1]);
            stats.gpuSamples++;
        }

        void logStatsIfDue(uint64_t now) {
            if (stats.startUs == 0) {
                stats.startUs = now;
                return;
            }
            if (now - stats.startUs < STATS_INTERVAL_US || stats.frames == 0) {
                return;
            }
            const double seconds = double(now - stats.startUs) / 1e6;
            const double gpuFrames = stats.gpuSamples ? double(stats.gpuSamples) : 1.0;
            LOGI("%.1f fps: GPU decode %.2f ms, GPU draw %.2f ms, wait previous frame %.2f ms, "
                 "wait swapchain image %.2f ms, submit+present %.2f ms (%s)",
                 stats.frames / seconds, stats.gpuDecodeUs / gpuFrames / 1000.0, stats.gpuDrawUs / gpuFrames / 1000.0,
                 stats.fenceWaitUs / double(stats.frames) / 1000.0, stats.acquireWaitUs / double(stats.frames) / 1000.0,
                 stats.presentUs / double(stats.frames) / 1000.0, presentModeName(presentMode));
            stats = {};
            stats.startUs = now;
        }
    };

    bool probeAvailable() {
        if (!apiVersionSupported()) {
            return false;
        }
        VulkanLoader vk;
        if (!vk.loadGlobal()) {
            return false;
        }
        uint32_t loaderVersion = 0;
        vk.EnumerateInstanceVersion(&loaderVersion);
        if (loaderVersion < VK_API_VERSION_1_3) {
            LOGI("Vulkan loader is older than 1.3; PyroWave unavailable");
            return false;
        }

        VkApplicationInfo appInfo = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
        appInfo.apiVersion = VK_API_VERSION_1_3;
        VkInstanceCreateInfo instanceInfo = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        instanceInfo.pApplicationInfo = &appInfo;
        VkInstance instance = VK_NULL_HANDLE;
        if (vk.CreateInstance(&instanceInfo, nullptr, &instance) != VK_SUCCESS) {
            return false;
        }
        // Only the functions the probe needs; surface functions may be absent here.
        vk.EnumeratePhysicalDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
            vk.GetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
        vk.GetPhysicalDeviceProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
            vk.GetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
        vk.GetPhysicalDeviceFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            vk.GetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2"));
        vk.DestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(
            vk.GetInstanceProcAddr(instance, "vkDestroyInstance"));

        bool capable = false;
        uint32_t count = 0;
        vk.EnumeratePhysicalDevices(instance, &count, nullptr);
        std::vector<VkPhysicalDevice> devices(count);
        vk.EnumeratePhysicalDevices(instance, &count, devices.data());
        for (auto device : devices) {
            auto probe = probeFeatures(vk, device);
            LOGI("%s (Vulkan %u.%u) PyroWave-capable: %d", probe.name, VK_API_VERSION_MAJOR(probe.apiVersion),
                 VK_API_VERSION_MINOR(probe.apiVersion), probe.ok);
            capable = capable || probe.ok;
        }
        vk.DestroyInstance(instance, nullptr);
        return capable;
    }
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_video_PyroWaveDecoderRenderer_nativeIsAvailable(JNIEnv *, jclass) {
    // The capability is a property of the driver, so probe once per process.
    static std::once_flag once;
    static bool available = false;
    std::call_once(once, [] { available = probeAvailable(); });
    return available ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_limelight_binding_video_PyroWaveDecoderRenderer_nativeCreate(JNIEnv *env, jclass, jobject surface,
                                                                      jint width, jint height, jint frameRate) {
    if (surface == nullptr || width <= 0 || height <= 0 || (width & 1) || (height & 1)) {
        LOGE("PyroWave needs a surface and positive, even dimensions (%dx%d)", width, height);
        return 0;
    }
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        return 0;
    }
    auto renderer = std::make_unique<Renderer>();
    if (!renderer->create(window, width, height, frameRate)) {
        return 0;  // The renderer releases the window.
    }
    LOGI("PyroWave renderer ready for %dx%d", width, height);
    return reinterpret_cast<jlong>(renderer.release());
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_video_PyroWaveDecoderRenderer_nativeSubmitFrame(JNIEnv *env, jclass, jlong handle,
                                                                           jbyteArray data, jint length) {
    auto *renderer = reinterpret_cast<Renderer *>(handle);
    if (renderer == nullptr || data == nullptr || length <= 0) {
        return SUBMIT_ERROR;
    }
    // Copy out of the Java array so decode and present do not run inside a JNI
    // critical section. Frames arrive on one thread, so the buffer is reused.
    static thread_local std::vector<uint8_t> frame;
    if (length > env->GetArrayLength(data)) {
        return SUBMIT_ERROR;
    }
    frame.resize(size_t(length));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte *>(frame.data()));
    return renderer->submit(frame.data(), frame.size());
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_video_PyroWaveDecoderRenderer_nativeGetLastGpuDecodeUs(JNIEnv *, jclass, jlong handle) {
    auto *renderer = reinterpret_cast<Renderer *>(handle);
    return renderer != nullptr ? jint(renderer->lastGpuDecodeUs) : 0;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyroWaveDecoderRenderer_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Renderer *>(handle);
}

}  // extern "C"
