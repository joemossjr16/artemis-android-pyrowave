# Application.mk for Moonlight

# Our minimum version is Android 5.0
APP_PLATFORM := android-21

# We support 16KB pages
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

# The PyroWave renderer is C++; the other native modules are C.
APP_STL := c++_static

# Optimise native code in debug builds too: FEC recovery and depacketization run
# per packet, and unoptimised builds cannot keep up with high-bitrate streams.
APP_OPTIM := release
