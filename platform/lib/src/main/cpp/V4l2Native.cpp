#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <sstream>
#include <string>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

#define LOG_TAG "FabScreenUVC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

const int kMaximumWidth = 1920;
const int kMaximumHeight = 1080;
const size_t kMaximumFrameBytes = 8U * 1024U * 1024U;

thread_local std::string g_last_error;

struct Buffer {
    void* start = nullptr;
    size_t length = 0;
};

struct Handle {
    int fd = -1;
    uint32_t pixel_format = 0;
    int width = 0;
    int height = 0;
    int stride = 0;
    int fps = 0;
    std::vector<Buffer> buffers;
};

int xioctl(int fd, unsigned long request, void* argument) {
    int result;
    do {
        result = ioctl(fd, request, argument);
    } while (result == -1 && errno == EINTR);
    return result;
}

void set_error(const std::string& operation) {
    g_last_error = operation + ": " + strerror(errno);
}

std::string fourcc(uint32_t value) {
    char text[5] = {
        static_cast<char>(value & 0xff),
        static_cast<char>((value >> 8) & 0xff),
        static_cast<char>((value >> 16) & 0xff),
        static_cast<char>((value >> 24) & 0xff),
        0
    };
    return std::string(text);
}

bool is_jpeg(uint32_t value) {
    return value == V4L2_PIX_FMT_MJPEG || value == V4L2_PIX_FMT_JPEG;
}

bool is_supported(uint32_t value) {
    return is_jpeg(value) || value == V4L2_PIX_FMT_YUYV;
}

int format_rank(uint32_t value) {
    if (value == V4L2_PIX_FMT_MJPEG) return 3;
    if (value == V4L2_PIX_FMT_JPEG) return 2;
    if (value == V4L2_PIX_FMT_YUYV) return 1;
    return 0;
}

int clamp_positive(int value, int fallback, int maximum) {
    if (value <= 0) return fallback;
    return value > maximum ? maximum : value;
}

int align_step(int value, int minimum, int maximum, int step) {
    if (maximum < minimum) return 0;
    int clamped = value < minimum ? minimum : (value > maximum ? maximum : value);
    if (step <= 1) return clamped;
    return minimum + ((clamped - minimum) / step) * step;
}

bool query_best_format(
        int fd,
        int requested_width,
        int requested_height,
        uint32_t* pixel_format,
        int* width,
        int* height) {
    const int maximum_width = clamp_positive(requested_width, kMaximumWidth, kMaximumWidth);
    const int maximum_height = clamp_positive(requested_height, kMaximumHeight, kMaximumHeight);
    bool found = false;
    uint32_t best_format = 0;
    int best_width = 0;
    int best_height = 0;

    for (uint32_t format_index = 0;; ++format_index) {
        v4l2_fmtdesc description{};
        description.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        description.index = format_index;
        if (xioctl(fd, VIDIOC_ENUM_FMT, &description) == -1) break;
        if (!is_supported(description.pixelformat)) continue;

        bool enumerated_size = false;
        int candidate_width = 0;
        int candidate_height = 0;
        for (uint32_t size_index = 0;; ++size_index) {
            v4l2_frmsizeenum frame_size{};
            frame_size.index = size_index;
            frame_size.pixel_format = description.pixelformat;
            if (xioctl(fd, VIDIOC_ENUM_FRAMESIZES, &frame_size) == -1) break;
            enumerated_size = true;

            int frame_width = 0;
            int frame_height = 0;
            if (frame_size.type == V4L2_FRMSIZE_TYPE_DISCRETE) {
                frame_width = static_cast<int>(frame_size.discrete.width);
                frame_height = static_cast<int>(frame_size.discrete.height);
            } else if (frame_size.type == V4L2_FRMSIZE_TYPE_STEPWISE ||
                       frame_size.type == V4L2_FRMSIZE_TYPE_CONTINUOUS) {
                frame_width = align_step(
                    maximum_width,
                    static_cast<int>(frame_size.stepwise.min_width),
                    static_cast<int>(frame_size.stepwise.max_width),
                    static_cast<int>(frame_size.stepwise.step_width));
                frame_height = align_step(
                    maximum_height,
                    static_cast<int>(frame_size.stepwise.min_height),
                    static_cast<int>(frame_size.stepwise.max_height),
                    static_cast<int>(frame_size.stepwise.step_height));
            }

            if (frame_width <= 0 || frame_height <= 0 ||
                frame_width > maximum_width || frame_height > maximum_height) {
                continue;
            }
            if (static_cast<long long>(frame_width) * frame_height >
                static_cast<long long>(candidate_width) * candidate_height) {
                candidate_width = frame_width;
                candidate_height = frame_height;
            }
            if (frame_size.type != V4L2_FRMSIZE_TYPE_DISCRETE) break;
        }

        // Some compliant drivers do not implement ENUM_FRAMESIZES. In that
        // case, ask for the bounded requested size and validate S_FMT later.
        if (!enumerated_size) {
            candidate_width = maximum_width;
            candidate_height = maximum_height;
        }
        if (candidate_width <= 0 || candidate_height <= 0) continue;

        const int candidate_rank = format_rank(description.pixelformat);
        const int best_rank = format_rank(best_format);
        const long long candidate_area = static_cast<long long>(candidate_width) * candidate_height;
        const long long best_area = static_cast<long long>(best_width) * best_height;
        if (!found || candidate_rank > best_rank ||
            (candidate_rank == best_rank && candidate_area > best_area)) {
            found = true;
            best_format = description.pixelformat;
            best_width = candidate_width;
            best_height = candidate_height;
        }
    }

    if (!found) return false;
    *pixel_format = best_format;
    *width = best_width;
    *height = best_height;
    return true;
}

void close_handle(Handle* handle) {
    if (!handle) return;
    if (handle->fd >= 0) {
        v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        xioctl(handle->fd, VIDIOC_STREAMOFF, &type);
    }
    for (Buffer& buffer : handle->buffers) {
        if (buffer.start && buffer.length > 0) {
            munmap(buffer.start, buffer.length);
        }
    }
    if (handle->fd >= 0) close(handle->fd);
    delete handle;
}

std::string safe_card_name(const unsigned char* card) {
    std::string value(reinterpret_cast<const char*>(card));
    for (char& character : value) {
        if (character == '|' || character == '\r' || character == '\n') character = ' ';
    }
    return value;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_fabscreen_platform_lib_uvc_V4l2Native_probe(
        JNIEnv* environment,
        jclass,
        jstring path_value,
        jint maximum_width,
        jint maximum_height) {
    g_last_error.clear();
    const char* path = environment->GetStringUTFChars(path_value, nullptr);
    int fd = open(path, O_RDWR | O_NONBLOCK, 0);
    if (fd == -1) {
        set_error(std::string("open ") + path);
        environment->ReleaseStringUTFChars(path_value, path);
        return nullptr;
    }

    v4l2_capability capability{};
    if (xioctl(fd, VIDIOC_QUERYCAP, &capability) == -1) {
        set_error("VIDIOC_QUERYCAP");
        close(fd);
        environment->ReleaseStringUTFChars(path_value, path);
        return nullptr;
    }
    uint32_t capabilities = capability.capabilities;
    if ((capabilities & V4L2_CAP_DEVICE_CAPS) != 0) capabilities = capability.device_caps;
    if ((capabilities & V4L2_CAP_VIDEO_CAPTURE) == 0 ||
        (capabilities & V4L2_CAP_STREAMING) == 0) {
        close(fd);
        environment->ReleaseStringUTFChars(path_value, path);
        return nullptr;
    }

    uint32_t pixel_format = 0;
    int width = 0;
    int height = 0;
    if (!query_best_format(fd, maximum_width, maximum_height, &pixel_format, &width, &height)) {
        g_last_error = "No bounded MJPEG/JPEG/YUYV capture format on " + std::string(path);
        close(fd);
        environment->ReleaseStringUTFChars(path_value, path);
        return nullptr;
    }

    std::ostringstream result;
    result << path << '|' << safe_card_name(capability.card) << '|'
           << width << '|' << height << '|' << fourcc(pixel_format);
    close(fd);
    environment->ReleaseStringUTFChars(path_value, path);
    g_last_error.clear();
    return environment->NewStringUTF(result.str().c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_fabscreen_platform_lib_uvc_V4l2Native_open(
        JNIEnv* environment,
        jclass,
        jstring path_value,
        jint requested_width,
        jint requested_height,
        jint requested_fps) {
    g_last_error.clear();
    const char* path = environment->GetStringUTFChars(path_value, nullptr);
    int fd = open(path, O_RDWR | O_NONBLOCK, 0);
    if (fd == -1) {
        set_error(std::string("open ") + path);
        environment->ReleaseStringUTFChars(path_value, path);
        return 0;
    }

    const int bounded_width = clamp_positive(requested_width, 1280, kMaximumWidth);
    const int bounded_height = clamp_positive(requested_height, 720, kMaximumHeight);
    const int bounded_fps = requested_fps < 1 ? 1 : (requested_fps > 10 ? 10 : requested_fps);
    uint32_t pixel_format = 0;
    int width = 0;
    int height = 0;
    if (!query_best_format(fd, bounded_width, bounded_height, &pixel_format, &width, &height)) {
        g_last_error = "No bounded MJPEG/JPEG/YUYV capture format on " + std::string(path);
        close(fd);
        environment->ReleaseStringUTFChars(path_value, path);
        return 0;
    }

    v4l2_format format{};
    format.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    format.fmt.pix.width = width;
    format.fmt.pix.height = height;
    format.fmt.pix.pixelformat = pixel_format;
    format.fmt.pix.field = V4L2_FIELD_ANY;
    if (xioctl(fd, VIDIOC_S_FMT, &format) == -1) {
        set_error("VIDIOC_S_FMT");
        close(fd);
        environment->ReleaseStringUTFChars(path_value, path);
        return 0;
    }
    if (!is_supported(format.fmt.pix.pixelformat) ||
        static_cast<int>(format.fmt.pix.width) > kMaximumWidth ||
        static_cast<int>(format.fmt.pix.height) > kMaximumHeight) {
        g_last_error = "Driver selected an unsupported or oversized capture format";
        close(fd);
        environment->ReleaseStringUTFChars(path_value, path);
        return 0;
    }

    v4l2_streamparm parameters{};
    parameters.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    parameters.parm.capture.timeperframe.numerator = 1;
    parameters.parm.capture.timeperframe.denominator = bounded_fps;
    xioctl(fd, VIDIOC_S_PARM, &parameters);
    memset(&parameters, 0, sizeof(parameters));
    parameters.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    int actual_fps = bounded_fps;
    if (xioctl(fd, VIDIOC_G_PARM, &parameters) == 0 &&
        parameters.parm.capture.timeperframe.numerator > 0) {
        actual_fps = static_cast<int>(
            parameters.parm.capture.timeperframe.denominator /
            parameters.parm.capture.timeperframe.numerator);
        if (actual_fps <= 0) actual_fps = bounded_fps;
    }

    v4l2_requestbuffers request{};
    request.count = 4;
    request.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    request.memory = V4L2_MEMORY_MMAP;
    if (xioctl(fd, VIDIOC_REQBUFS, &request) == -1 || request.count < 2) {
        set_error("VIDIOC_REQBUFS");
        close(fd);
        environment->ReleaseStringUTFChars(path_value, path);
        return 0;
    }

    Handle* handle = new Handle();
    handle->fd = fd;
    handle->pixel_format = format.fmt.pix.pixelformat;
    handle->width = static_cast<int>(format.fmt.pix.width);
    handle->height = static_cast<int>(format.fmt.pix.height);
    handle->stride = static_cast<int>(format.fmt.pix.bytesperline);
    if (handle->pixel_format == V4L2_PIX_FMT_YUYV) {
        const int packed_stride = handle->width * 2;
        if (handle->stride < packed_stride) handle->stride = packed_stride;
    }
    // Report what the driver says it is producing. The Java manager separately
    // throttles publication to the configured maximum when a device ignores
    // VIDIOC_S_PARM.
    handle->fps = actual_fps;
    handle->buffers.resize(request.count);

    for (uint32_t index = 0; index < request.count; ++index) {
        v4l2_buffer buffer{};
        buffer.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buffer.memory = V4L2_MEMORY_MMAP;
        buffer.index = index;
        if (xioctl(fd, VIDIOC_QUERYBUF, &buffer) == -1) {
            set_error("VIDIOC_QUERYBUF");
            close_handle(handle);
            environment->ReleaseStringUTFChars(path_value, path);
            return 0;
        }
        void* mapped = mmap(nullptr, buffer.length, PROT_READ | PROT_WRITE,
                            MAP_SHARED, fd, buffer.m.offset);
        if (mapped == MAP_FAILED) {
            set_error("mmap");
            close_handle(handle);
            environment->ReleaseStringUTFChars(path_value, path);
            return 0;
        }
        handle->buffers[index].start = mapped;
        handle->buffers[index].length = buffer.length;
        if (xioctl(fd, VIDIOC_QBUF, &buffer) == -1) {
            set_error("VIDIOC_QBUF");
            close_handle(handle);
            environment->ReleaseStringUTFChars(path_value, path);
            return 0;
        }
    }

    v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(fd, VIDIOC_STREAMON, &type) == -1) {
        set_error("VIDIOC_STREAMON");
        close_handle(handle);
        environment->ReleaseStringUTFChars(path_value, path);
        return 0;
    }

    LOGI("V4L2 stream opened %s %dx%d %s @ %d FPS", path, handle->width,
         handle->height, fourcc(handle->pixel_format).c_str(), handle->fps);
    environment->ReleaseStringUTFChars(path_value, path);
    g_last_error.clear();
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_fabscreen_platform_lib_uvc_V4l2Native_format(JNIEnv* environment, jclass, jlong value) {
    Handle* handle = reinterpret_cast<Handle*>(value);
    return environment->NewStringUTF(handle ? fourcc(handle->pixel_format).c_str() : "");
}

extern "C" JNIEXPORT jint JNICALL
Java_fabscreen_platform_lib_uvc_V4l2Native_width(JNIEnv*, jclass, jlong value) {
    Handle* handle = reinterpret_cast<Handle*>(value);
    return handle ? handle->width : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_fabscreen_platform_lib_uvc_V4l2Native_height(JNIEnv*, jclass, jlong value) {
    Handle* handle = reinterpret_cast<Handle*>(value);
    return handle ? handle->height : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_fabscreen_platform_lib_uvc_V4l2Native_stride(JNIEnv*, jclass, jlong value) {
    Handle* handle = reinterpret_cast<Handle*>(value);
    return handle ? handle->stride : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_fabscreen_platform_lib_uvc_V4l2Native_fps(JNIEnv*, jclass, jlong value) {
    Handle* handle = reinterpret_cast<Handle*>(value);
    return handle ? handle->fps : 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_fabscreen_platform_lib_uvc_V4l2Native_readFrame(
        JNIEnv* environment,
        jclass,
        jlong value,
        jint timeout_ms) {
    g_last_error.clear();
    Handle* handle = reinterpret_cast<Handle*>(value);
    if (!handle || handle->fd < 0) {
        g_last_error = "Invalid V4L2 handle";
        return nullptr;
    }

    pollfd descriptor{};
    descriptor.fd = handle->fd;
    descriptor.events = POLLIN;
    int poll_result = poll(&descriptor, 1, timeout_ms > 0 ? timeout_ms : 1000);
    if (poll_result == 0) return nullptr;
    if (poll_result < 0) {
        set_error("poll");
        return nullptr;
    }
    if ((descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
        g_last_error = "V4L2 device disconnected or stopped";
        return nullptr;
    }

    v4l2_buffer buffer{};
    buffer.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    buffer.memory = V4L2_MEMORY_MMAP;
    if (xioctl(handle->fd, VIDIOC_DQBUF, &buffer) == -1) {
        if (errno != EAGAIN) set_error("VIDIOC_DQBUF");
        return nullptr;
    }

    jbyteArray result = nullptr;
    if (buffer.index >= handle->buffers.size()) {
        g_last_error = "V4L2 returned an invalid capture buffer index";
    } else if (buffer.bytesused == 0) {
        g_last_error = "V4L2 returned an empty capture frame";
    } else if (buffer.bytesused > kMaximumFrameBytes) {
        g_last_error = "V4L2 frame exceeds the 8 MB limit";
    } else if (buffer.bytesused > handle->buffers[buffer.index].length) {
        g_last_error = "V4L2 frame exceeds its mapped capture buffer";
    } else {
        result = environment->NewByteArray(static_cast<jsize>(buffer.bytesused));
        if (result) {
            environment->SetByteArrayRegion(
                result,
                0,
                static_cast<jsize>(buffer.bytesused),
                reinterpret_cast<const jbyte*>(handle->buffers[buffer.index].start));
        } else if (!environment->ExceptionCheck()) {
            g_last_error = "Could not allocate the Java camera frame";
        }
    }
    if (xioctl(handle->fd, VIDIOC_QBUF, &buffer) == -1) {
        set_error("VIDIOC_QBUF");
        if (result) environment->DeleteLocalRef(result);
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_fabscreen_platform_lib_uvc_V4l2Native_close(JNIEnv*, jclass, jlong value) {
    close_handle(reinterpret_cast<Handle*>(value));
}

extern "C" JNIEXPORT jstring JNICALL
Java_fabscreen_platform_lib_uvc_V4l2Native_lastError(JNIEnv* environment, jclass) {
    return environment->NewStringUTF(g_last_error.c_str());
}
